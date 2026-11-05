# Experiments: hypotheses measured and refuted

Forgia's roadmap has been driven by measurements, and several plausible ideas were **refuted** before they
became code. This file records them so nobody has to rediscover them, with the numbers that decided it.

Method for all of them: production benchmark GameTests, `FIXED_3X3`, ZGC generational, 200 warmup + 600
measured ticks, config patched per cell and restored against the sha256 of the untouched backup, world
directory cleared per run.

## 1. Structure claims for piston moves - refuted

**Hypothesis:** moving a piston's blocks across a region border is expensive because it is serialized on the
server thread, so a chunk-set claim would let the move run on the moving region's worker.

**Measurement:** a piston benchmark (sticky-piston clocks pushing 1..12-block columns, half on a border, 128
to 512 pistons, up to a 4-tick clock) with per-phase attribution.

**Result:** the serialized move is **0.82 ms/tick for 128 pistons (1.6% of a 50ms tick)**, 1.66 ms for 512
(3.3%) and 1.81 ms for an overdriven clock (3.6%). The cost is per event, not per block: 30-60us per move is
flat from size 1 to size 12. The large regionized number (2.18 ms/tick) was **not** the move but the per-event
region lease plus the border fixpoint, which a claim would not remove.

**What was done instead:** the block-event phase runs on the server thread with every batch returned, so it is
globally quiescent; it now opens a lease-pooled scope in which a cross-region write is the vanilla inline call
(no capture, no fixpoint). That took the phase from **2.68 to 0.53 ms/tick** and removed an 11% event
duplication whose root cause was the source reading its own deferred write.

## 2. Region-to-worker affinity (cache/TLB locality) - refuted

**Hypothesis:** the vanilla tick body costs about twice as much on a worker as inline (1.77 vs 0.83 us per
hopper, 0.88 vs 0.40 per furnace) because a region is ticked by a different core every tick and is always
cold, so pinning a region to one worker would recover it.

**Measurement:** a temporary pool hook that pins submission *n* of a tick to worker *n % 4* with stealing
disabled, so every region is ticked by the same core on every tick (0.00 steals/tick), across 1, 2 and 4
workers, plus an external control benchmark of identical compute-only code.

**Result:** pinning moved the hopper body by **-6%** and the furnace body by **+1%**, both inside the cell
spread. A region that is always ticked by the same core is not faster.

**What it actually is - per-core duty cycle.** The body cost is monotone in the number of concurrently ticking
workers (hopper 0.82 -> 1.27 -> 1.65 us for 1 -> 2 -> 4 workers) even though each worker's share of the data
shrinks, and eight times *less* data makes it worse (512 hoppers, ~150 KB, L1/L2 resident: 2.37-2.80 us/ticker).
A control benchmark with the same instructions on 16 KB of resident data runs **1.5-1.7x slower** when
executed in short bursts separated by idle gaps than when one thread runs it continuously (p50 33.4 us
continuous against 55.6 us with 4 ms gaps). That is a platform effect - frequency scaling and C-states on a
laptop, inside WSL2 - and it explains the residual: the light slices are bursty by nature.

**Consequence for the roadmap:** light-load micro-optimisation on this host cannot close the remaining gap;
the principled fix is to make each burst longer, which is what continuous region ticking (A3) does. It also
means all light-slice measurements carry a platform-specific penalty that a server with a fixed performance
governor would not have.

## 3. Per-region mob cap for spawning - replaced

**Hypothesis:** a region can apply the vanilla mob cap formula to its own chunks and entities.

**Result:** populations ran **58% above vanilla** (73/77 inline against 103-126 regionized) because the cap
truncates per region and a mob that walks into a neighbour frees capacity where it left. Replaced with a
level-wide per-category counter that the server thread publishes once per tick and regions reserve against
atomically; populations came back inside the band (-7.6%, 0.0%, +2.5%, +1.3%).

## 4. Pinning POI users to the server thread - replaced

**Hypothesis (initial):** villages and zombies reach the level-wide POI storage, so they should not tick on
workers.

**Result:** correct but it cost the entity win for those types (zombies 22.6-23.2 mspt against 20.2-20.6
inline). Replaced by region-partitioned POI storage with published snapshots and vanilla-identical queries,
after which zombies run at 6.6-8.8 mspt (3.4x) and villagers at 21.9-25.7 (2.1-2.4x).
