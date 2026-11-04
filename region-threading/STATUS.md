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
| 289 chunks of natural spawning | 4.13–4.62 ms slice | 2.79–3.93 | 1.15–1.65× |
| 2048 hoppers | 3.0 mspt | 3.6–4.0 | 0.85× (slower) |
| 4096 furnaces | 2.48 mspt | 3.09 | 0.80× (slower) |

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

| Profile | mspt mean | p50 | p95 | p99 |
|---|---|---|---|---|
| on (3 runs) | 7.35–8.28 | 6.60–7.39 | 11.0–13.0 | 14.0–18.4 |
| off (3 runs) | 6.48–7.60 | 6.00–6.38 | 9.4–15.9 | 14.6–21.4 |

**No speedup yet at this scale** (0.97–1.23×, median 1.14× slower). The counters say why: region work totals
5.31 ms per tick across 57 regions (≈2.7 % worker utilisation) while the server thread still runs ~6.9 ms,
because with 3-chunk regions only the centre chunk of a region is interior — 155 k chunk ticks on workers
against 1.24 M boundary fallbacks, 18.6 k block-entity ticks against 285 k fallbacks. The barrier is paid in
full for a small parallel win.

## Roadmap

| # | Milestone | State |
|---|---|---|
| A1 | Deferred cross-region block writes, then relax the interior-chunk rule | in progress |
| A2 | Persistent workers + multi-region group leases (light-load overhead) | planned |
| A3 | Continuous region ticking (tick clock instead of a per-tick barrier) | planned |
| A4 | Dynamic region size (split/merge by load) | planned |
| B1 | Cross-region game events (sculk vibration) | planned |
| B2 | Structure leases (piston multi-block moves, Create contraptions) | planned |
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
- A worker write outside its lease is refused rather than deferred (this is what A1 changes).
- Cross-region effects are ordered per target region, not in one global vanilla order.
- Modded callbacks that reach beyond the admitted radius are refused, not analysed.
