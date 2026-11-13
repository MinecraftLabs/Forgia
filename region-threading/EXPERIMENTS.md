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
the principled fix is to make each burst longer. It also means all light-slice measurements carry a
platform-specific penalty that a server with a fixed performance governor would not have.

A3 was proposed as that fix - a bounded tick window in which a region runs one tick ahead - and §5 measures it
and refutes it: the bursts are already as long as the work of their slice, and the interval between them is the
server thread building the next one. Making the burst longer needs a region to own its whole tick.

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

## 5. A bounded tick window (a region one tick ahead) - refuted

**Hypothesis:** the server thread parks at a barrier after every slice of a level tick, so letting a region
tick ahead while the server thread does its own work would recover the stop-and-go part of the tick (A3).

**Measurement:** a new opt-in attribution (`RegionBarrierAttribution`, off by default) splits the wait of every
batch of every slice into dispatch (submit to first group start), span (first to last group end, i.e. the worker
makespan) and tail (last group end to the end of the wait), and records the handoff after the barrier and the
server-thread interval between two barriers. Production soak (3-chunk regions, 4 workers, all switches on) and
the four slice workloads, 200 warmup + 600 measured ticks, two repeats, `-XX:+UseZGC`.

**Result:** barrier wait is 46-56 % of a tick in the soak and the light workloads and 87 % in the 1024-zombie
workload, but 75-99 % of that wait is the worker makespan of the slice - the interval in which the workers are
running the only work the tick has. The part of a tick that is the barrier's own cost (dispatch + completion
detection) is **1.1-11.7 %**: 1.143/1.224 ms of an 11.774/10.470 ms soak tick, 0.224/0.248 of a 6.512/5.904 ms
9x9 chunk tick, 0.293 of a 5.103 ms furnace tick, 0.311 of a 6.203 ms hopper tick, 0.317/0.310 of a 26.769/27.793
ms zombie tick. That is what a cheaper dispatch and a faster completion detection remove - which is A2's
submission path (furnace await 1.88 -> 1.62 ms with persistent workers and packed leases), not a window.

The naive "both lanes overlap" bound is large (max(span, tick - span) would save 37-48 % of a soak or chunk-tick
tick), and it is unreachable: the server-thread lane **produces** the region lane's input. The plan of slice
k+1 is computed from the state the handoff of slice k writes, the handoff must be applied before slice k+1's
bodies run because that is where the vanilla inline write would have landed, the vanilla statements between two
slices read the state the previous slice wrote (block events read block states), and the interval across the
tick boundary contains the collection of the next tick's first batch. A window would spend the same
dispatch + tail and gain nothing, because there is no region work available to put in it.

**What was done instead:** nothing is scheduled. The attribution is kept (off by default) because it is the
number that decides this question, and the milestone is recorded as refuted in `region-threading/STATUS.md`.
The remaining lever the numbers do support is the worker makespan (34.5-86.3 % of a tick): a cheaper body or a
better intra-slice balance (A2b work stealing), or longer bursts, which needs a region to own its whole tick -
regionized world ticking, a different milestone with its own correctness argument, not a bounded window.
Full tables, the ordering table and the two-tick observability list are in `tmp/continuous-tick/SUMMARY.md`.

## 6. Parallel dispatch of the collected scheduled ticks - open blocker, not enabled

**Hypothesis:** the collected block and fluid ticks of one level tick can be split by target region and run on
region workers in parallel. `LevelTicks` collects, budgets and de-duplicates on the server thread and hands the
snapshot to a `TickDispatcher`; the engine runs one lease per region and executes that region's ticks in their
collected order.

**Measurement:** the randomized redstone soak (`RegionRandomizedRedstoneSoakTest`), 20 seeds x 1000 ticks x
24 circuits and 5 seeds x 4000 ticks x 12 circuits, half of the circuits straddling a region border, comparing
the region run against the single-threaded inline run at every tick (block states, block entities, scheduled-tick
counts, block-event queue, item conservation). Two configurations are measured against the same inline
reference: the shipped one (scheduled ticks on the server thread, every other slice on) and the internal seam
(`enableUnclassifiedWorkerCallbacksForTesting`, scheduled ticks dispatched to region workers).

**Result:** the shipped configuration is equivalent on the whole matrix (0 unexplained state differences). The
parallel seam is not, and the divergence is not the `willTickThisTick` defect that the soak found first (that one
is fixed: the collected ticks now stay in the predicate set until their own callback is about to run, reported
per tick by the dispatcher on the worker that runs it; seeds `0xB4_5000..02` at 24 circuits x 200 ticks are the
regression cases and fail without the fix).

The remaining divergence is an ordering property of the seam, traced on seed `0xB4_5003` (24 circuits, 500
ticks, first state difference at tick 46): at tick 25 the repeater at `47,64,72` (region 0) notifies the dust at
`48,64,72` (region 1) across the `x=48` border. A cross-region update is applied by the *target* region in the
fixpoint, i.e. after that region's own collected ticks of the tick, so the comparator at `49,64,72` schedules the
follow-up that the single-threaded collected order suppresses (its tick was still pending when the notification
arrived inline). The seam schedules 4 extra comparator ticks at trigger 48 and the comparator is `powered=true`
at tick 46 where the inline run is `powered=false`. Delivering the border write before the target region's own
collected ticks instead would trade this divergence for the opposite one: when the writer's tick *follows* the
reader's in the collected order, vanilla applies the write after the reader's tick ran. Neither policy
converges to the single-threaded order, because one ordered tick list is being run on parallel groups.

**What was done instead:** the slice stays disabled. `beginScheduledTickDispatcher` returns `null` unless the
internal mechanics seam is enabled, so production runs the vanilla inline loop for scheduled ticks - that was
already gated on unclassified-callback admission. This is the **second, independent** blocker for enabling the
slice for real: enabling it needs an ordering design that does not exist yet (reproducing the collected order
across parallel groups when a write crosses a border, e.g. a per-region tick ordering derived from the collected
order, or a serialization of exactly the border-crossing ticks). The soak keeps the seam table
(`randomizedCircuitsOfTheParallelScheduledTickSeamAreMeasured`) to measure the distance, and it counts exactly
one shape: a differing circuit that straddles a border, whose region run holds more pending evaluations than the
inline run, in a tick that recorded a cross-region update. Every other state difference fails the table.
