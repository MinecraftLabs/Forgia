# Upstream drift: what this fork is missing from NeoForge 21.1.249

Measured against `refs/remotes/upstream/1.21.1` (upstream HEAD `624c929f4`, the 1.21.1 line that produces
21.1.2xx), compared with this fork at `cfc89106c`.

## Numbers

| Item | Count |
|---|---|
| Upstream patch files | 747 |
| Patch files this fork has that upstream does not | 11 (all region-threading additions) |
| Upstream patch files this fork lacks | 4 (enchantment loot functions and predicates) |
| Patch files whose content differs | 30 |
| Of those, carrying a region-threading hook | 16 |
| Of those, untouched since the fork | 10 |

## Why a partial sync does not work

The 4 missing patches and the 10 untouched ones were applied verbatim as an experiment. They apply with zero
rejects, but the fork then **fails to compile**: the upstream patch bodies reference NeoForge APIs that exist in
upstream's own sources (`src/main/java/net/neoforged/**`) and not in this fork's, for example

```
PlayerList.java:847            cannot find symbol
EnchantmentHelper.java:388     cannot find symbol
EnchantedCountIncreaseFunction.java:71   cannot find symbol
```

So the vanilla patch set cannot be moved ahead of the NeoForge sources it depends on. The fork is roughly 200
NeoForge releases behind on both halves, and the two halves have to move together.

That experiment was reverted; the tree is back at `cfc89106c` with 761 JUnit tests green.

## The real fix: a rebase, not a backport

1. Take upstream's `patches/` **and** `src/main/java/net/neoforged/**` at the 21.1.x state the pack needs.
2. Re-apply this fork's 21 hook patch files on top of upstream's versions of the same vanilla classes. Our
   hooks are localised (`RegionThreading*`, `chunkVersion`, `beginChunkMutation`, `refuseForeignChunkMutation`,
   `ServerEntityStorageOwner`, the deferred-update seams), so the merge is mechanical but has to be done file by
   file, and each one needs the equivalence tests re-run.
3. Rebuild and re-run the modpack matrix, because the API surface a mod compiles against changes with the
   rebase.

Until that happens the fork identifies as `21.1.250.x-beta` only through a local git tag. That satisfies the
pack's dependency *ranges*, and it is why 170 jars load, but it does **not** mean the API surface equals
21.1.249: a mod that calls a method added upstream after this fork's snapshot fails with `NoSuchMethodError`,
which is exactly what ByePregen did with `ChunkStatus.getChunkSaveHeightmaps()` (that one was backported
separately and does compile because its dependencies were also added).

## Practical consequence for the pack

Loading is proven for all 170 jars, but any mod whose code path touches a post-snapshot API can still throw at
runtime. The matrix harness classifies that as `LOAD` with the missing member in the reason, so a rebase can be
scoped by evidence rather than by guessing.
