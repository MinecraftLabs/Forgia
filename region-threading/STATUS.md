# Forgia status

Measured on a 4-core/8-thread machine, JDK 21, ZGC generational, fixed 3×3-chunk regions unless stated,
4 workers, 200 warmup + 600 measured ticks, two repeats. "Off" means the same build with region threading
disabled, which is the vanilla code path.

## Slices

| Workload | Off (server thread) | Regionized | Speedup |
|---|---|---|---|
| 1024 cows | 27.3 / 19.3 mspt | 6.0 / 5.8 mspt | 4.5× / 3.4× |
| 1024 zombies | 22.8 / 22.2 | 6.6 / 8.8 | 3.4× / 2.5× |
| 1024 villagers (bed + workstation) | 54.5 / 52.0 | 25.7 / 22.0 | 2.1× / 2.4× |
| 1024 chunks of random ticks (9×9 regions) | 5.59 / 5.87 ms slice | 4.80 / 4.64 | 1.15–1.27× |
| 1024 chunks of random ticks (3×3 regions, interior rule lifted) | 6.88–6.92 ms slice | 5.54 | 1.24–1.25× |
| 289 chunks of natural spawning | 4.13–4.62 ms slice | 2.79–3.93 | 1.15–1.65× |
| 2048 hoppers | 3.27 mspt | 3.74–3.97 | 0.82–0.87× (slower) |
| 4096 furnaces | 2.60 mspt | 2.70–3.34 | 0.78–0.96× (slower, −19% with the light-load changes) |

## Correctness evidence

| Area | How it is proven |
|---|---|
| POI | Randomized world compared against a real single-threaded `PoiManager`: every query type, every region perspective, region sizes 3/4/8 — same records, same order, same free tickets |
| Redstone | 49 layouts from real blocks across borders at offsets 1..15, run twice (on/off) for 60 ticks: 5 880 tick comparisons of block states, block-entity states, first-appearance ticks and the block event stream position by position |
| Chunk random ticks | Per-chunk derivation proven independent of region layout, worker count, tick order and thread |
| Spawning | Region states partition the level counts; the level-wide cap is enforced with an atomic check-and-reserve |
| Cross-region reads | Seqlock: never torn; a stale read re-runs the region that made it (the test fails with the check disabled) |
| Production soak | 6 000 ticks on generated terrain with villagers, mobs, a hopper/furnace line, a farm, a border-crossing redstone clock and a moving fake player: 0 ownership violations, 0 tick failures, item conservation, redstone identical in 6 200/6 200 ticks |
| Game events | Six sculk tests compare the full tick-by-tick trace against the inline run (power, comparator, calibrated filtering, two-region ties, a two-border radius, a shrieker summoning its warden); all fail with the replay short-circuited |
| Save and crash | Save/reload compares 114 blocks, 13 block entities, 9 entity UUIDs, 10 POI records with tickets and the rebuilt region mapping; a hard kill mid-tick recovers in three directions (on to on, on to off, off to on) |
| Players | Walking, a 1000-block teleport, a teleport during a batch, a nether round trip and two players swapping regions all keep a player in exactly one region |
| Save and reload | A whole fixture world saved, unloaded and reloaded from disk: every block, block entity, entity UUID and position, POI record with its free-ticket count and region mapping equal, with region threading on and off and across the two configurations |
| Crash recovery | Six runs in three pairs kill the JVM mid-tick and restart on the same world: the save point is intact, no duplicate UUID, no leaked ticket, no dangling deferral, 600 further ticks green |

## Production soak (everything on, 3-chunk regions)

| Milestone | on (mspt mean) | off (mspt mean) | ratio |
|---|---|---|---|
| before deferred cross-region writes | 7.35–8.28 | 6.48–7.60 | 0.97–1.23× (median 1.14× slower) |
| after deferred cross-region writes + interior rule lifted | 6.13–6.72 | 6.25–6.70 | **median 0.983, mean 0.976, range 0.941–1.014** |
| after persistent workers + packed leases (off by default) | 6.69 | 7.35 (baseline regionized) | −9% |

The first row was measured before the cross-region write path existed: with 3-chunk regions only the centre
chunk of a region had all eight neighbours inside it, so 89 % of chunk ticks and 94 % of block-entity ticks
stayed on the server thread while the barrier was paid in full. Lifting that rule — a worker write outside
its lease is now recorded and applied by the target region in the same tick — moved **100 % of the dispatched
chunk ticks** onto workers (1.31 M ticks, zero boundary fallbacks) and the soak now lands at or below inline,
with the median p50 at 5.60 ms against 6.22 ms.

Block entities remain at ~8 % on workers on purpose: a hopper or furnace reads the container one block away,
and a foreign block-entity read answers null — a wrong value, not an approximation — so relaxing that rule
needs a deferred foreign block-entity read first.

## Structure claims: measured, not built

A piston move runs inside the server-thread block event phase, and every block event of a level tick is
serialized there: `PistonBaseBlock.triggerEvent` resolves the structure and writes the blocks on the server
thread. The mechanism a claim would add — claim the chunks a move touches, run the move on the region worker,
make the target region skip the claimed chunks — was measured before it was built, with the `benchmarkPiston`
GameTest: 128 sticky pistons on a 20-tick farm clock, columns of 1..12 blocks, half of them pushing across a
3-chunk region border, 200 warmup + 600 measured ticks. The table below is the measurement that rejected the
claim (f58c3a883); the phase it reports has since been made a quiescent scope, see the next section.

| profile | moves/tick | block event phase ms/tick | share of a 50 ms tick | mspt mean | p95 | p99 |
|---|---|---|---|---|---|---|
| off (vanilla) | 13.0 | 0.82 | 1.6 % | 2.78 | 10.4 | 13.2 |
| on, border updates off | 13.0 | 0.76 | 1.5 % | 4.66 | 10.8 | 16.5 |
| on, every move inside its region | 13.0 | 0.76 | 1.5 % | 4.61 | 12.7 | 17.5 |
| on (everything) | 14.4 | 2.18 | 4.4 % | 5.92 | 22.8 | 38.9 |
| off, 512 pistons | 51.9 | 1.66 | 3.3 % | 3.95 | 16.3 | 27.0 |

The move itself costs 30–60 µs, so the phase is 1.5 % of a tick for a workload that already moves a column
every tick; the regionized cross-border path added 1.4 ms per tick of capture and fixpoint work for the half
of the moves that cross a border, which is the cost a claim would have to remove and does not. The phase also
sits between the chunk tick batch and the entity and block-entity slices of the same level tick, so no region
slice runs while a move runs and a claim has nothing to exclude today; running the move on a worker would
first require moving the phase into the batch, which changes the tick order. The mechanism was therefore not
built; the numbers and the reasoning are in `.pi/tasks/structure-claims.md`.

## Block event phase: quiescent scope, no capture

The block event phase is the one part of the level tick that is not a region slice. It drains the level's
ordered block event set on the server thread after every region batch of the tick returned
(`ServerLevel.tick`: block ticks, raids, chunk source, then block events), so no worker runs and no region is
leased by another thread: the whole level is quiescent for the phase. It therefore runs as a scope
(`RegionThreadingEngine.beginBlockEventPhase`) that takes the lease of each region its events run in once,
keeps it until the phase ends and captures no border update. A write of an event body to another region is
the vanilla inline call: nothing is queued for a fixpoint, so the phase runs none, and the body reads back
what it wrote. Every other server-thread region lease (a scheduled-tick or block-entity fallback) still
captures and flushes exactly as before, and so does every worker.

Two measurements motivated it. First, the per-event cost: the phase took a lease, ran one event and flushed a
border fixpoint after each of them, which cost 2.68 ms/tick at 128 pistons and 7.88 ms/tick at 512 while the
same workload inside one region cost 0.67/1.53 ms. Second, the event stream: the phase produced 12.5 % more
block events than the inline run. A west sticky piston whose column crosses the border wrote the moving
piston one block across the border; that write was captured, so the piston's own neighbour callback read the
head position back as the block that was there *before* the move, resolved the structure against it and
queued a second extend event for the same piston in the same tick. Inline the same callback reads the moving
piston, the resolver fails and no second event is queued. With the phase applying its writes inline the
stream matches inline position by position.

Paired A/B of the same build and config (`benchmarkPiston`, 200 warmup + 600 measured ticks, half the moves
across a border, 4 workers, 3×3 regions):

| profile | block events | events/tick | phase ms/tick | phase max ms | mspt mean | p50 | p95 | p99 |
|---|---|---|---|---|---|---|---|---|
| 128, phase captures (before) | 8 640 | 14.35 | 2.68 | 78.4 | 4.84 | 1.22 | 26.5 | 38.7 |
| 128, quiescent scope (after) | 7 680 | 12.76 | 0.53 | 10.4 | 3.85 | 1.40 | 19.2 | 26.2 |
| 128, inline (off) | 7 680 | 12.76 | 0.82 | 12.6 | 2.30 | 0.77 | 9.9 | 13.0 |
| 128, regionized, no border update | 7 680 | 12.76 | 0.67 | 11.4 | 2.36 | 1.19 | 8.0 | 12.9 |
| 512, phase captures (before) | 34 560 | 57.41 | 7.88 | 113.7 | 12.01 | 1.20 | 80.0 | 107.8 |
| 512, quiescent scope (after) | 30 720 | 51.03 | 1.36 | 26.2 | 8.11 | 1.02 | 53.8 | 71.7 |
| 512, inline (off) | 30 720 | 51.03 | 1.40 | 38.4 | 3.32 | 0.73 | 14.4 | 22.6 |
| 512, regionized, no border update | 30 720 | 51.03 | 1.53 | 31.4 | 3.82 | 0.91 | 16.1 | 26.8 |

The phase cost is now at the interior-only and inline cost and the event count equals the inline count at both
sizes; a final confirmation run of the same build at 128 pistons measured 0.47 ms/tick and 7 680 events. The
regionized mspt stays above inline because the piston workload is dominated by the serialized server-thread
moves themselves (the same rows show 19–54 ms of p95 for a workload that moves a column every tick on a
4-worker engine), not by the phase. The remaining block-event work is the move: it runs on the server thread
because the phase sits outside every batch, which is the tick-order constraint the structure claim would have
to remove.

Regression checks of the same build: hopper 2048 (block-entity slice, paired before/after with the scope
disabled at runtime) 4.35/4.92 vs 4.83/4.57 mspt mean, i.e. no difference beyond the run-to-run spread;
furnace 4096 and the entity workload fire no block events at all, so the phase scope cannot enter them. The
production soak reports `phases=19875/leases=0/events=0/fixpoints=0` for its window - the phase opens as a
scope every level tick, its redstone clock fires no block event, and no phase enters a fixpoint - and is green
with 0 ownership violations, 0 tick failures, item conservation and redstone identical in 6 200/6 200 ticks,
at 6.53 mspt mean (p50 5.84, p95 10.1, p99 13.8) against 6.35 (5.93/9.2/12.0) for the threading-off reference.
The one class of work the phase still serializes is the move itself, which runs on the server thread because
the phase sits outside every region batch.

## Save, reload and crash recovery

A world saved with region threading enabled reloads to the same state, and a hard kill leaves a world that
loads with the state of its last save point. Both are proven, not argued: one JUnit test drives the regionized
POI storage through a save and a reload and compares it with a vanilla manager over the same directory, one
GameTest saves a whole fixture world, unloads every chunk and loads it back from disk, and three pairs of
runs kill the JVM in the middle of the activity and check what the next process loads.

### What is authoritative and what is derived

| State | Kind | Where it lives | Across a save/load |
|---|---|---|---|
| Chunk block states, block entities, entities | authoritative | region files + entity files, written by the vanilla save | written at the save point, read back by the chunk load |
| POI records and their free-ticket counts | authoritative | the level's POI region files, written by `RegionPoiStorage.saveColumn` (`RegionPoiStorage.java:684`) in the vanilla `Sections`/`DataVersion` format | written by the level tick, by the save of their chunk and by `flushAll`; read by `loadColumn` (`:530`) |
| Region mapping (chunk -> region coordinate) | derived | `RegionLayout` (`RegionLayout.java`) | a pure function of the position; nothing to persist |
| Section partitions, admission caches, ticker indexes | derived | `FixedEntitySectionPartitions`, `RegionBlockEntityTickChunkCache`, `RegionEntityTickIndex` | rebuilt as the chunks load and the entities are ticked |
| Entity identity ledger (`EntityIdentityLedger`) | derived | in memory, one per level | rebuilt on load from the vanilla UUID set; the process that died leaves nothing behind |
| Deferred block updates, entity additions, removals, section updates | per tick batch | `RegionTickBatch` and its deferred queues | drained by the barrier of the batch, inside the level tick that published it; empty at a save point |
| Border update fixpoint queue, mailbox, pending entity transfers | cross tick | `RegionThreadingEngine.borderUpdates`, `RegionMailbox`, `RegionEntityTransferJournal` | deferred work by design; a crash loses it, exactly like a block update of a tick that never ran |
| Region entity storage mode, worker pool, leases | derived | `RegionWorkerPool`, `RegionState` | rebuilt on load |

### What a save can observe

`ServerLevel.save` brackets the save with `RegionThreadingHooks.beginLevelSave` / `endLevelSave`
(`patches/net/minecraft/server/level/ServerLevel.java.patch`). `beginLevelSave`
(`RegionThreadingEngine.java:6457`) asserts that the save observes a completed level tick:

* a batch in flight is a violation and is counted in `savesWithBatchInFlight`. It cannot happen on a server
  thread save: the server thread publishes and awaits a batch itself, so it can only save between two level
  ticks, and the vanilla autosave runs after `tickChildren` returned.
* deferred effects a barrier has not applied are counted in `savesWithTickDeferredWork`. Every per-tick queue
  is drained by the barrier of its own batch, so both counters are zero in production; the mechanics seam that
  could break that is counted instead of silently producing a half-applied world.
* cross-tick work (border updates delayed to the next tick, mailbox messages, pending transfer decisions and
  POI columns the tick has not written) is counted in `savesWithCrossTickDeferredWork`,
  `crossTickDeferredEntries` and `dirtyPoiColumns`. It is not a violation - it is work the design defers
  across a tick boundary - but a crash loses it, so it is counted instead of being invisible.

The counters are in `RegionThreadingDiagnostics.SaveCounters` (`RegionThreadingDiagnostics.java:390`) and are
asserted by the save/reload GameTest and by the hard-kill test.

### One repair, and why it was needed

The vanilla `SectionStorage` schedules its column writes on its own `IOWorker` and its `close()` drops the
writes that worker has not picked up yet, and `ServerLevel.save(flush=true)` only waits for the chunk and
entity IO. A POI write of the last tick could therefore be lost by a clean shutdown or by a save that reported
flushing. `RegionPoiStorage.flushAll()` (`RegionPoiStorage.java:663`) writes the remaining columns and waits
for its IO worker; it runs at every flushing save (`endLevelSave`, `RegionThreadingEngine.java:6508`) and at
`close()` (`:674`). A world that never crashes sees one extra IO wait at the save point (10-20 ms in the
fixture run) and nothing else: no behaviour changes, no data changes, and the level's data is the same as
before because the wait only orders writes that were already scheduled. The vanilla POI path is untouched
when the switch is off.

### Evidence

| What | Test | Result |
|---|---|---|
| POI save/reload equivalence | `RegionPoiSaveReloadTest` (JUnit): the same script of adds, removes and ticket changes into a vanilla `PoiManager` and into the regionized storage, both closed and reopened, then compared record by record and ticket by ticket | 4 tests: reloaded records, types, free tickets and village distance equal the vanilla manager's; the stored `Sections` compound is byte-equal to the vanilla one; every reloaded section answers from the region that owns its chunk with zero server-thread fallbacks; a close without a tick keeps every column the storage holds |
| Save/reload of a whole world | `savesAndReloadsRegionThreadingState` (GameTest, batch `saveReload`): 5x5 chunks across two region borders with beds and workstations, containers with item stacks, a sign, a bee nest, a bell, a 5x5 farm, a lever-fed circuit that crosses the border, 3 villagers and 6 cows; save, release every ticket, wait until the chunk source reports every chunk absent, force them again, compare | 114 blocks, 13 block entities, 9 entities, 10 POI records and 2 taken tickets equal before and after the reload; 0 save-point violations |
| Same state with region threading on and off | `runSaveReloadGameTestServer` and `runSaveReloadGameTestServerReference` | the reference run's reloaded state equals the on-run's reloaded state, position by position (114/13/9/10) |
| Hard kill, same configuration twice | `runCrashPrepareGameTestServer` then `runCrashRecoverGameTestServer` on one world directory | the world loads, the save point is intact, 9 unique entity UUIDs, no leaked ticket, nothing deferred left, 600 further ticks green |
| Hard kill, on saved and off loaded | `runCrashSaveOnGameTestServer` then `runCrashLoadOffGameTestServer` | same |
| Hard kill, off saved and on loaded | `runCrashSaveOffGameTestServer` then `runCrashLoadOnGameTestServer` | same |

The kill is a real hard kill: the prepare phase saves, records the state of that save point, keeps ticking and
then calls `Runtime.halt(0)` from another thread while the server thread is in the middle of a tick. No
shutdown hook, no chunk/entity/POI write and no flush of anything runs after the save point. What the next
process loads is the save point, which is exactly vanilla's guarantee for the same situation: the block
update, item transfer, entity move or POI write of the tick that never returned is lost, and nothing that was
applied before the save point is. No entity is admitted twice (the durable identity of an entity is its UUID
in one chunk's entity list, written once from a quiescent state), no identity reservation survives (the ledger
is derived and rebuilt per process), and no POI ticket leaks (a ticket is the `free_tickets` field of a record
that is written with its column, and the records are reconciled with the block states at chunk load by
`checkConsistencyWithBlocks`, the vanilla mechanism).

### Save and load wall time

`saveReload` fixture, 5x5 chunks, 3-chunk regions, 25 chunks force-loaded, one flushing save plus a full
unload and reload of the area:

| profile | save (ms) | reload (ms) | blocks | block entities | entities | POI records | taken tickets |
|---|---|---|---|---|---|---|---|
| on (4 workers) | 765 | 8 | 114 | 13 | 9 | 10 | 2 |
| off (vanilla) | 754 | 243 | 114 | 13 | 9 | 10 | 2 |

The save time is dominated by the vanilla chunk save of 25 chunks and is the same on both sides. The reload is
the force-load of the same chunks: with region threading on the chunk tasks are dispatched to the region
workers, with it off they stay on the server thread, which is the 30x difference of this small fixture and not
a claim about a large world. The hard-kill recovery of the same world (load, compare, 600 ticks) takes 20-30 s
per pair, of which 600 ticks at 20 TPS dominate.

## Roadmap

| # | Milestone | State |
|---|---|---|
| A1 | Deferred cross-region block writes, then relax the interior-chunk rule | **done** (e82a76c47) |
| A2 | Persistent workers + multi-region group leases (light-load overhead) | **done, off by default** (af9a44429): furnace −19 %, hopper −6 %, but the entity slice loses 2.3–2.7 ms because the per-worker slot loses the executor's dynamic balancing |
| A2b | Work-stealing dispatch (per-worker deque, steal half) and per-ticker wrapper attribution | planned — this is what makes A2 a win for every slice instead of a trade-off |
| A3 | Continuous region ticking (tick clock instead of a per-tick barrier) | planned |
| A4 | Dynamic region size (split/merge by load) | planned |
| B1 | Cross-region game events (sculk vibration) | **done** (dbebf9803): per-column capture, same-tick replay on the target worker, 1650 events / 5657 deferred columns in the soak, none lost. Found and fixed that worker-posted events were silently dropped before |
| B2 | Structure leases (piston multi-block moves, Create contraptions) | measured, not worth it (see above) |
| B3 | Player regionization (teleport, portals, cross-dimension) | **done** (f2f529272): explicit transitions by kind, exactly-one-region audit, ledger handover across dimensions, 6356 assertions in the soak with 0 failures |
| B4 | Large randomized redstone soak | planned |
| C1 | Save/load and crash recovery of regionized state | **done** |
| C2 | Long-run soak (6+ hours, drift and leak detection) | planned - the 6000-tick soak is green but six hours is a different regime |
| C3 | Multiplayer concurrency (players, view distance, tickets) | planned |
| D1 | Mod compatibility declarations (policy registry, conflict detection) | planned |
| D2 | 170-mod pack bring-up (bare → all mods off → all mods on → one at a time) | planned |
| D3 | Automated performance regression baselines | planned |
| D4 | Documentation and production configuration guide | planned |

Dependency spine: A1 → A2 → A3 → A4, then B1 → B2 → B3 → B4, then C1 → C2 → C3, then D1 → D2 → D3 → D4.

## Known limitations

- Region size is static; a region that becomes hot cannot be split.
- The per-tick barrier bounds light-load performance.
- Raw internal chunk mutation (the block-entity map, the section array, the raw heightmap array) is outside
  the seqlock contract; only the normal entry points are covered.
- A worker write outside its lease is deferred to the target region's same-tick fixpoint; a write that
  cannot be expressed as (position, state, flags) — a block entity with data, a block-entity removal, a
  heightmap-only write, a multi-position move — is still refused.
- A callback that writes outside its lease and then reads the same position in the same tick sees the
  pre-write state. Counted, never silent. The block event phase is exempt because it captures nothing: its
  writes are inline and an event body reads back what it wrote.
- The persistent worker pool and packed leases are off by default: they help the light slices and cost the
  entity slice, and the next step is a work-stealing dispatcher rather than a global switch.
- Cross-region effects are ordered per target region, not in one global vanilla order.
- Modded callbacks that reach beyond the admitted radius are refused, not analysed.
