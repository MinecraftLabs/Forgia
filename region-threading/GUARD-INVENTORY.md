# Guard inventory: every operation a region worker may reach

A native region worker ticks entities, block entities and chunks of one region. Everything it touches is either
its own region's storage, a published snapshot, or a refusal. This file is the checklist: **every new guard, and
every new vanilla entry point a worker can reach, must be added here with its class and its test before the
change is committed.** An entry point that is not in this file is an untested crash waiting for a mod to find it.

Classification:

| Class | Meaning |
|---|---|
| **LOCAL** | answered from the leased region's own storage; out-of-region parts are clamped or truncated and counted |
| **PUBLISHED** | answered from an immutable snapshot the server thread publishes once per tick; at most one tick stale, counted |
| **DEFERRED** | the worker records the intent, the server thread applies it at the batch barrier (mutations only) |
| **REFUSED** | no answer exists; the guard names the operation and throws, and the counter records it |

## Entity queries

| Entry point | Class | Implementation | Test |
|---|---|---|---|
| `LevelEntityGetter.get(int)` / `ServerLevel.getEntity(int)`, `getEntityOrPart(int)` | LOCAL | `RegionAwareEntityGetter.regionScopedLookup` + `RegionState.entityById` | `RegionEntityQueryPathsTest.byIdLookupOnAWorkerAnswersFromItsRegion`, `byIdLookupOfAnEntityWhoseRemovalIsDeferredIsRefused` |
| `LevelEntityGetter.get(UUID)` / `ServerLevel.getEntity(UUID)` | LOCAL | same, `RegionState.entityByUuid` | `byUuidLookupOnAWorkerAnswersFromItsRegion`, `byUuidLookupOfAForeignEntityIsRefused` |
| `LevelEntityGetter.getAll()` / `ServerLevel.getAllEntities()` | LOCAL, truncated | `RegionScopedEntityQuery.leasedEntityIndex` | `getAllOnAWorkerAnswersOnlyItsRegion`, `getAllOnACompleteRegionIsNotTruncated`, `getAllOnAWorkerOfALevelWithoutAPublishedIndexKeepsTheLegacyRejection` |
| `LevelEntityGetter.get(EntityTypeTest, consumer)` / `ServerLevel.getEntities(EntityTypeTest, ...)` / `getDragons()` | LOCAL, truncated | same | `typedLookupOnAWorkerAnswersOnlyItsRegion`, `typedLookupOnTheServerThreadKeepsVanillaSemantics` |
| `LevelEntityGetter.get(AABB, Consumer)` | LOCAL, clamped | `RegionScopedEntityQuery.tryGetInBox` | `boxConsumerOnAWorkerAnswersTheClampedLease` |
| `LevelEntityGetter.get(EntityTypeTest, AABB, AbortableIterationConsumer)` | LOCAL, clamped, player union | `tryGetByTypeInBox` | `typedBoxConsumerOnAWorkerAnswersAPlayerOfTheLeaseOnce`, `typedBoxQueryOnAWorkerStopsAtMaxWhileItAppendsThePlayerUnion` |
| `Level.getEntities(Entity, AABB, Predicate)` | LOCAL, clamped | `tryQuery` (no player union: physics stays clamped) | `boxQueryOnAWorkerAppendsThePartsOfTheOwnersItAnswers`, existing range tests |
| `Level.getEntities(EntityTypeTest, AABB, Predicate[, List, int])` | LOCAL, clamped, player union | `tryQuery` | `playerTypedBoxQueryUsesTheBoundingBoxOfTheSnapshot`, `playerTypedBoxQueryOnAWorkerAddsAPlayerOfAnotherRegion` |
| `players()`, `getPlayers(Predicate, int)`, `getNearestPlayer` ×5, `getPlayerByUUID`, `getNearbyPlayers`, `hasNearbyAlivePlayer`, `getRandomPlayer` | PUBLISHED | `RegionPlayerSnapshots` + `RegionThreadingHooks.playersOfLevel` + the `ServerLevel.players()` patch | `workerReadsThePlayerSnapshotInsteadOfTheLiveList`, `playerSnapshotIsReplacedOncePerTick`, `playerSnapshotIsDetachedWithTheLevel` |
| query from a worker **without a lease**, level with **no published region index**, **another region's** entity, `EntityStorageGuardedGetter` | REFUSED | `RegionThreadingHooks.rejectLegacyEntityQuery(operation, path)` | the `*IsRefused` tests above |

## Entity mutations

| Entry point | Class | Implementation | Test |
|---|---|---|---|
| `Entity.discard`, `remove`, `setRemoved`, `LivingEntity.remove`, `Slime.remove`, `Boat/ChestBoat/AbstractMinecartContainer.remove`, `FishingHook.remove` | DEFERRED (subclass removals refused) | deferred removal set, applied at the barrier | `RegionEntityRemoval*`, `RegionEntityTickPlanTest` |
| `Entity.move`, `moveTo`, `absMoveTo`, `setPosRaw`, `BlockAttachedEntity.setPos/move` | LOCAL when the entity is admitted; grantable while it is not yet admitted | `RegionEntityOwnershipScope` | `RegionEntityOwnershipLeaseTest` (incl. `aHangingEntityThatIsNotAddedYetMayBePositionedWhileItIsConstructed`) |
| entity admission (`addFreshEntity`, spawn) | DEFERRED | deferred admission, applied at the barrier | `RegionEntityAdmissionDeferralTest` |
| region transfer (`record/commit/abortEntityRegionTransfer`), `EntityIdentityLedger`, `FixedEntitySectionPartitions` | REFUSED | they mutate identity and section storage; the caller defers to the barrier | `RegionEntityTransferTest` |
| `ServerPlayer.teleportTo/dismountTo/teleportRelative`, `ServerGamePacketListenerImpl.teleport`, `ServerLevel.addPlayer` | REFUSED | a player is ticked by the server thread only; the transition is a recorded event | `PlayerRegionTransitionTest` |

## Block, chunk and block-entity storage

| Entry point | Class | Implementation | Test |
|---|---|---|---|
| `Level.setBlock` inside the lease | LOCAL | vanilla write on the region's own chunk | redstone equivalence suite |
| `Level.setBlock` outside the lease | DEFERRED | recorded write, applied by the target region's worker in the same tick's fixpoint | `deferred-cross-region-writes` tests |
| block entity with data, block-entity removal, heightmap-only write, multi-position move, non-representable write | REFUSED (no-replay) | `RegionWorkerChunkUnavailableException` reason | same |
| `LevelChunk.getBlockState` / `getBlockEntity` of a foreign chunk | LOCAL read via seqlock, or foreign block-entity read answers `null` (counted) | `chunkVersion` seqlock | `versioned-foreign-chunk-reads` tests |
| `LevelChunk.setBlockState`, `setBlockEntity`, `removeBlockEntity`, `Heightmap.set` on a foreign chunk | REFUSED before mutation | `refuseForeignChunkMutation` | `RegionChunkTickSharedStateTest` |
| worker chunk load / unload | REFUSED | `RegionWorkerChunkUnavailableException` | `worker-chunk-guard` tests |
| block entity tick | LOCAL for admitted types, server thread otherwise | `RegionBlockEntityTickPlan` + admission cache | `RegionBlockEntityTick*` |

## Level state

| Entry point | Class | Implementation | Test |
|---|---|---|---|
| weather, day time, `ServerLevel` global fields | REFUSED from a region phase | `LevelTickPhaseState` | `RegionGlobalPhaseTest` |
| cross-region messages | DEFERRED | `RegionMailbox`, bounded, at-most-once | `RegionMailboxTest` |
| cross-region game events (sculk) | DEFERRED, replayed in the same tick | `GameEventDispatcher` capture + replay | sculk equivalence tests |
| POI queries | LOCAL + PUBLISHED snapshots | `RegionPoiStorage` | `RegionPoiStorage*` |
| lighting, block updates, section updates, spawns, scheduled ticks | DEFERRED | batch queues, applied in a fixed order at the barrier | `RegionTickBatch*` |
