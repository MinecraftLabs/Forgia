# Mod pack status: SkyExplorer v0.1.5 (170 mods, NeoForge 21.1.249)

The pack is the compatibility target: Minecraft 1.21.1, NeoForge 21.1.249, 170 mod jars including c2me,
Connector, FTB Chunks, Quark and Create. This file records what works, what does not, and why - measured with
`tools/modpack-matrix`, which boots the installed server on a selected set of mods and classifies the outcome.

## Result

| Configuration | Outcome |
|---|---|
| **168 jars (everything except Connector + Forgified Fabric API)** | **reaches `Done` in 69 s** |
| 170 jars (with Connector) | fails in `fabric_lifecycle_events_v1: server.WorldChunkMixin` |

So region threading, the patched vanilla classes and 168 mods coexist. The two excluded jars are the
Fabric-on-NeoForge translation layer, and the reason they fail is a different class of incompatibility from the
rest (below).

## How the pack is run

The loader identifies itself as `21.1.250.x-beta`, which satisfies every dependency range in the pack
(`[21.1.219,)` through `[21.1.247,)`); the version comes from a git tag, so a compatibility build is
`git tag 21.1.<n>-beta` before `:neoforge:installerJar`. Install with the produced installer into a copy of the
pack, then start with the pack's own `unix_args.txt`.

## Incompatibilities found, in the order they were hit

### 1. Moonlight `ServerLevelMixin` - fixed

`@WrapOperation` on `new PersistentEntitySectionManager<>(Entity.class, callbacks, storage)`.
Our patch had changed that call to five arguments (storage guard + section layout), so the injection point did
not exist. Moonlight is mandatory for Supplementaries, so it could not be dropped.

Fixed by restoring the vanilla call byte for byte and taking the guard and the layout from the level instead:
`ServerLevel.EntityCallbacks` receives a NeoForge **injected interface** (`ServerEntityStorageOwner`) that
exposes the level, and the manager installs both from it. No constructor parameter, no access transformer, no
signature change.

### 2. Forgified Fabric API `server.WorldChunkMixin` - fixed

`@Inject` into `LevelChunk.setBlockEntity` and `removeBlockEntity`, capturing the block entity that gets
replaced or removed. Our patches had moved those method bodies into `setBlockEntityUnderMutation` and friends so
a seqlock could wrap them, which removed the injectable code.

Fixed by keeping every body where vanilla puts it and wrapping it in place: `beginChunkMutation()` before a
`try`, `endChunkMutation()` in the `finally`. Adding statements and an exception table does not move the code a
mixin anchors on; moving it into a helper does.

### 3. Connector / Forgified Fabric API - open

Still fails, and it is a harder kind of problem: these mixins assume the **exact instruction sequence** around
vanilla calls.

```
@Inject(method = "setBlockEntity",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;put(...)", shift = At.Shift.BY, by = 3))
```

`shift = BY 3` means "three instructions after the `Map.put`", which is brittle against any patch - ours or
NeoForge's - that inserts something nearby. `onRemoveBlockEntity` uses a `@Redirect` inside a `@Slice` anchored
on a field access in `getBlockEntity`, which our foreign-read branch perturbs.

Two ways out, neither free:

* **A. Move the hooks out of the hot vanilla methods.** The foreign-read and foreign-write guards in
  `LevelChunk.getBlockEntity` / `setBlockEntity` could live in `Level`'s callers instead, leaving `LevelChunk`
  close to vanilla. This is a real refactor of the core patches and is not done.
* **B. Declare Connector unsupported.** The pack loses the Fabric mods loaded through it (a small subset), and
  the rest runs. This is the current state.

The pack's own `netherexp` and `modernfix` mixins also log optional injection failures for the same reason
(instruction-level assumptions) but are non-fatal.

## Reproducing

```bash
git tag 21.1.250-beta
./gradlew --offline :neoforge:installerJar
java -jar projects/neoforge/build/libs/neoforge-21.1.250.*-installer.jar --installServer /path/to/pack-copy

# one group
printf '%s\n' "Quark-4.1-482.jar" > /tmp/group.txt
tools/modpack-matrix/run_group.sh /path/to/pack-copy /tmp/group.txt quark

# the whole pack, bisecting to the failing jar
python3 tools/modpack-matrix/bisect_mods.py /path/to/pack-copy --parallel 2
```

A run takes about 25 seconds because the runner stops the server as soon as `Done` or an error appears.

## What this does and does not prove

Reaching `Done` proves that the mods load and that the server ticks its first ticks with Forgia's patched
classes; it does not prove gameplay equivalence under load. Region threading is **off by default** in the pack's
configuration, so this is a drop-in compatibility result first. Turning the slices on with 168 mods is the next
step, and the conflict table (`/neoforge regionthreading compat`) already names c2me, modernfix, moonlight,
alltheleaks, voxy, byepregen, sable, spawn and mobspawncontroller as mods that fight the region model.
