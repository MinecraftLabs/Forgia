# Forgia

**A regionized, multithreaded Minecraft server for NeoForge 1.21.1.**

Forgia ports [Folia](https://github.com/PaperMC/Folia)'s region model into NeoForge natively: the world is
partitioned into regions, each region owns its chunks, entities, block entities and points of interest, and
the regions tick in parallel on worker threads. It is a NeoForge fork, not a plugin platform — scheduling and
ownership are exposed through native NeoForge API, and mods keep running as mods.

> **Project status: independent.** Forgia started as a fork of an earlier Forgia port. That upstream stopped
> updating, so this repository is now the continuation: it tracks NeoForge 21.1.x as its base, but the
> regionized runtime is developed here. See [region-threading/STATUS.md](region-threading/STATUS.md) for what works, what is
> measured, and what is still missing.

---

## What works today

Everything below is implemented, tested and measured on this repository. All region threading is **off by
default** and every slice fails closed: work that cannot be proven safe runs on the server thread and is
counted.

| Area | What it does | Measured |
|---|---|---|
| **Entity ticks** | Regions tick their own entities; the server thread no longer walks the entity list | **2.5–4.5×** on 1024 mobs (4 workers) |
| **Chunk ticks** | Random ticks, ice/snow, thunder on the region that owns the chunk | 1.15–1.27× with 9-chunk regions |
| **Natural spawning** | Per-region spawn bodies against the vanilla level-wide mob cap | spawn slice 4.6 → 2.8–3.9 ms |
| **Points of interest** | Region-partitioned storage with published snapshots; queries identical to vanilla | villagers **2.1–2.4×**, zombies **3.4×** |
| **Block entities** | Furnace/hopper family admitted on workers with a per-chunk admission cache | at parity |
| **Redstone** | Cross-region updates converge in the **same tick** through a fixpoint | 98 layouts, 5 880 tick comparisons, identical to vanilla |
| **Cross-region reads** | Seqlock chunk versions; a stale read re-runs the region that made it | 0 torn reads, 0 stale takes |
| **Global state** | Explicit global phase + bounded cross-region mailbox | weather/time writes from a region fail closed |
| **Failures** | A worker tick failure is reported by the server thread, never built on the worker | barrier wait 200 ms → **1.25 ms** |

Correctness is the acceptance criterion throughout: the POI manager, the redstone components and the chunk
random tick are compared against **real vanilla implementations**, tick by tick, not against expectations.

## What is not done yet

Forgia is not a finished Folia replacement. The honest list:

- **Light loads do not win yet.** A 2048-hopper line is at parity, 4096 furnaces are slower. The per-tick
  barrier costs 0.4–0.8 ms while the work is 1–2 ms.
- **The production soak shows no speedup at 3-chunk regions.** Only the centre chunk of a 3×3 region has all
  eight neighbours inside it, so 89 % of chunk ticks and 94 % of block-entity ticks still fall back. The fix
  (deferred cross-region block writes, then relaxing the interior rule) is the milestone in progress.
- **Structures are not region-aware.** A piston that moves blocks across a border, and Create-style
  contraptions, still run on the server thread.
- **Cross-region game events (sculk) are not regionized.**
- **Players are not regionized.** Teleport, portals and player entities stay on the server thread.
- **No long-run or multiplayer validation yet**, and **no mod-pack validation yet** — a 170-mod pack is
  prepared for that stage.
- **Region size is static.** Dynamic regions (split/merge by load) and continuous region ticking (a tick clock
  instead of a per-tick barrier) are designed but not built.

The full remaining plan is in [region-threading/STATUS.md](region-threading/STATUS.md#roadmap).

## Configuration

All switches live in `config/neoforge-server.toml` and are **`false` by default**. A server that wants the
full stack sets:

```toml
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingEntityTicks = true            # selects region-owned entity storage
enableRegionThreadingBlockEntityTicks = true
enableRegionThreadingRandomTicks = true
enableRegionThreadingSpawning = true
enableRegionThreadingPoi = true                    # implied by entity ticks
enableRegionThreadingBorderUpdates = true
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 3
regionThreadingWorkerThreads = 4
```

`regionThreadingWorkerThreads = 0` means "one less than the CPU count". Entity ticks, spawning and POI
require the fixed layout; everything else degrades to the vanilla path when its requirement is missing.

Diagnostics: `/neoforge regionthreading status` prints the per-region MSPT/TPS, every slice's worker and
fallback counters, the border-update and foreign-read counters, and the reason each refusal happened. Nothing
is silently dropped.

## Building

```
./gradlew --offline :neoforge:setup          # apply patches into projects/
./gradlew --offline build                    # build the loader
./gradlew --offline :tests:junitTest         # 676 unit tests
./gradlew --offline :tests:runGameTestServer # 166 in-game tests
```

JDK 21 is required. The repository is a patched-source NeoForge tree: vanilla sources live in
`projects/neoforge/src/main/java` (generated), the patch set in `patches/`, and the Forgia runtime in
`src/main/java/net/neoforged/neoforge/server/threading`.

Benchmarks and soaks:

```
./gradlew --offline :tests:runGameTestServer -PserverBenchmark=true \
    -PgameTestFilter=benchmarkEntity -PserverBenchmarkZombies=1024
./gradlew --offline :tests:runSoakGameTestServer        # 6000-tick production soak
./gradlew --offline :tests:runSoakGameTestServerReference
```

Measure with a low-pause collector (`-XX:+UseZGC -XX:+ZGenerational`): the default G1 on a 2 GiB heap spends
seconds in pauses on this workload and hides every effect.

## Architecture in one page

- **Regions** are a fixed, non-overlapping grid of N×N chunks (`regionThreadingFixedRegionChunks`). Each
  region has a lease; only the lease holder may tick its chunks, entities, block entities, spawns and POIs.
- **The server thread** builds the plan, leases every region, submits one task per region, waits at a barrier
  and runs the handoff: deferred section updates, removals, admissions, block updates, portal transitions,
  border updates and the propagation fixpoint.
- **Workers** own their region for the duration of a task and enumerate their own entities, tickers, chunks
  and POIs instead of receiving a per-unit list.
- **Everything cross-region is deferred**, never raced: neighbour updates, block writes, reads, POI queries,
  game events. Each has a bounded queue, a counter and a fallback.
- **Fail closed**: any work that cannot be proven safe — an unknown modded callback, a level without
  region-owned storage, a region that cannot be leased — runs on the server thread.

Deeper notes live next to the code (`src/main/java/net/neoforged/neoforge/server/threading/region/*.java`)
and in the task records under `.pi/tasks/`, which document the design and the evidence for each milestone.

## Licence and lineage

Forgia is a fork of NeoForge (LGPL-2.1) and of the earlier Forgia port; the regionized runtime added here is
under the same licence. Upstream NeoForge attribution is kept in the source headers and in
[NOTICE](NOTICE) where present. This repository no longer tracks the upstream Forgia project, which stopped
updating.
