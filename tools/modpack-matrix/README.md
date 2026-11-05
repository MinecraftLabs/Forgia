# Mod pack compatibility matrix

Runs the installed server on a **selected set of mods** so compatibility can be classified without booting the
whole pack every time. This is a test harness, not part of the loader.

## Why not a loader switch

FML has no "load only these mods" option. The safe equivalent is an overlay run directory: everything in the
pack except `mods/` is a symlink, and `mods/` holds hardlinks to the jars a group names. Nothing is copied,
nothing in the pack is modified, no loader code is patched, and each run is independent.

## Pieces

| File | What it does |
|---|---|
| `scan.py` | Reads every jar's `neoforge.mods.toml` (including jar-in-jar) and writes `mods.json` with each mod id, version, jar and mandatory dependencies. Use it to reason about dependency closures. |
| `run_group.sh` | Builds the overlay run directory for one jar list, writes a tiny flat-world `server.properties`, starts the server, and classifies the outcome: `OK` (reached `Done (`), `MIXIN` (critical injection failure, with the mod), `DEPS` (missing mandatory dependency), `LOAD` (mod failed to load, `NoSuchMethodError`, `NoClassDefFoundError`), `CRASH` otherwise. |
| `bisect.py` | Adaptive bisection: run the whole set; if it reaches `Done` every jar is compatible, otherwise split and test both halves, recursively. Finds k incompatible jars in about k·log₂(n) runs instead of n. Results in `results.csv`. |

## Usage

```bash
# classify the whole pack (2 concurrent runs, 3 minutes per run)
python3 tools/modpack-matrix/bisect.py /home/yc/Minecraft/SkyExplorer-forgia --parallel 2 --timeout 180

# one specific group
printf 'Quark-4.1-482.jar\n' > /tmp/group.txt
tools/modpack-matrix/run_group.sh /home/yc/Minecraft/SkyExplorer-forgia /tmp/group.txt quark-alone
```

Each run keeps its log at `/tmp/modpack-matrix/runs/<name>.log` and its overlay directory at
`/tmp/modpack-matrix/runs/<name>/`, so a failure can be inspected (or re-run with one more jar) without
rebuilding anything.

## Reading the results

* `OK` - the group reached `Done`, so every jar in it starts under Forgia. Bisection does not re-test them.
* `MIXIN` - a mod's mixin could not be applied, usually because Forgia changed the shape of a vanilla call.
  The reason names the callback and the mod.
* `DEPS` - the group is missing a mandatory dependency; run it again with the provider included.
* `LOAD` - a mod threw while loading (missing method, missing class). The reason names it.

A group that fails for `DEPS` is not evidence about compatibility, so the driver reports it as a failure and
bisection will usually end up at single jars; read the reason column before drawing conclusions.

## Caveats

* Runs share the CPU and the heap budget; keep `--parallel` at 2 on a 4-core/9 GiB host.
* A run that reaches `Done` proves loading and the first tick, not gameplay. Deeper behaviour is what the
  region threading GameTests and the production soak cover.
* The harness needs the installer to have been applied to the pack with the Forgia loader, and the version
  directory in `tool-java.sh` (`libraries/net/neoforged/neoforge/<version>/unix_args.txt`) has to match.
