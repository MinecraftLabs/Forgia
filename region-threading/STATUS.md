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
| Redstone | 49 layouts from real blocks across borders at offsets 1..15, run twice (on/off) for 60 ticks: 5 880 tick comparisons of block states, block-entity states and first-appearance ticks |
| Chunk random ticks | Per-chunk derivation proven independent of region layout, worker count, tick order and thread |
| Spawning | Region states partition the level counts; the level-wide cap is enforced with an atomic check-and-reserve |
| Cross-region reads | Seqlock: never torn; a stale read re-runs the region that made it (the test fails with the check disabled) |
| Production soak | 6 000 ticks on generated terrain with villagers, mobs, a hopper/furnace line, a farm, a border-crossing redstone clock and a moving fake player: 0 ownership violations, 0 tick failures, item conservation, redstone identical in 6 200/6 200 ticks |

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
thread, under a region lease the server thread takes for that one event. The mechanism a claim would add —
claim the chunks a move touches, run the move on the region worker, make the target region skip the claimed
chunks — was measured before it was built, with the `benchmarkPiston` GameTest: 128 sticky pistons on a
20-tick farm clock, columns of 1..12 blocks, half of them pushing across a 3-chunk region border, 200 warmup
+ 600 measured ticks.

| profile | moves/tick | block event phase ms/tick | share of a 50 ms tick | mspt mean | p95 | p99 |
|---|---|---|---|---|---|---|
| off (vanilla) | 13.0 | 0.82 | 1.6 % | 2.78 | 10.4 | 13.2 |
| on, border updates off | 13.0 | 0.76 | 1.5 % | 4.66 | 10.8 | 16.5 |
| on, every move inside its region | 13.0 | 0.76 | 1.5 % | 4.61 | 12.7 | 17.5 |
| on (everything) | 14.4 | 2.18 | 4.4 % | 5.92 | 22.8 | 38.9 |
| off, 512 pistons | 51.9 | 1.66 | 3.3 % | 3.95 | 16.3 | 27.0 |

The move itself costs 30–60 µs, so the phase is 1.5 % of a tick for a workload that already moves a column
every tick; the regionized cross-border path adds 1.4 ms per tick of capture and fixpoint work for the half
of the moves that cross a border, which is the cost a claim would have to remove and does not. The phase also
sits between the chunk tick batch and the entity and block-entity slices of the same level tick, so no region
slice runs while a move runs and a claim has nothing to exclude today; running the move on a worker would
first require moving the phase into the batch, which changes the tick order. The mechanism was therefore not
built; the numbers and the reasoning are in `.pi/tasks/structure-claims.md`.

## Roadmap

| # | Milestone | State |
|---|---|---|
| A1 | Deferred cross-region block writes, then relax the interior-chunk rule | **done** (e82a76c47) |
| A2 | Persistent workers + multi-region group leases (light-load overhead) | **done, off by default** (af9a44429): furnace −19 %, hopper −6 %, but the entity slice loses 2.3–2.7 ms because the per-worker slot loses the executor's dynamic balancing |
| A2b | Work-stealing dispatch (per-worker deque, steal half) and per-ticker wrapper attribution | planned — this is what makes A2 a win for every slice instead of a trade-off |
| A3 | Continuous region ticking (tick clock instead of a per-tick barrier) | planned |
| A4 | Dynamic region size (split/merge by load) | planned |
| B1 | Cross-region game events (sculk vibration) | planned |
| B2 | Structure leases (piston multi-block moves, Create contraptions) | measured, not worth it (see above) |
| B3 | Player regionization (teleport, portals, cross-dimension) | planned |
| B4 | Large randomized redstone soak | planned |
| C1 | Save/load and crash recovery of regionized state | planned |
| C2 | Long-run soak (6+ hours, drift and leak detection) | planned |
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
  pre-write state. Counted, never silent.
- The persistent worker pool and packed leases are off by default: they help the light slices and cost the
  entity slice, and the next step is a work-stealing dispatcher rather than a global switch.
- Cross-region effects are ordered per target region, not in one global vanilla order.
- Modded callbacks that reach beyond the admitted radius are refused, not analysed.
