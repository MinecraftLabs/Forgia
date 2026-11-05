# Modpack test tools

These tools select jars in an installed server copy. They do not add a loader `mods-list` option.
A successful boot is evidence only for that selected group and build, not for individual mods in all combinations.
Missing dependencies, timeouts and disabled worker slices are not compatibility passes.

## Load testing

`run_loadtest.sh` delegates to `loadtest.py` and runs **one server at a time**. Use JDK 21 explicitly;
changing the Java version or the installed loader changes the experiment. The result records both.

```bash
# A list contains exact jar basenames, including spaces and Unicode, one per line.
# Use a prepared list with all dependencies. Unknown and duplicate filenames are fatal.
tools/modpack-matrix/run_loadtest.sh /path/to/installed-pack /path/to/mods-list.txt smoke-001 1 30

# After the smoke fixture is valid, measure 30 minutes, sampling automatically every five minutes.
tools/modpack-matrix/run_loadtest.sh /path/to/installed-pack /path/to/mods-list.txt load-001 30 300
```

Options after the positional arguments include `--java /path/to/java`, `--runs /disk/runs`,
`--boot-timeout 360`, and `--warmup 30`. The default run root is the repository's sibling
`modpack-matrix-runs` directory, on disk rather than a small `/tmp` tmpfs. Existing run names are refused.
Do not install a new loader or modify shared library files during a run.

Each instance has private copies of its mutable resources: configuration, KubeJS scripts, mods, world,
logs and caches. Only the installed libraries are shared. The test never writes `server.properties` or
world/config files through links into the source pack. The copied EULA file must already permit startup.
Both game and RCON ports bind to loopback; RCON uses an ephemeral port and a per-run password.

The fixture uses 81 generated chunks, nine fenced platforms above the terrain, 45 tagged mobs including
villagers, crops, container/hopper/furnace lines, and a comparator reading a chest across a region border.
A test-only KubeJS command installs force-ticking tickets and a fake player to exercise player-dependent
chunk/spawn gates. This is not a network client or a multiplayer test. Only the isolated test directory
receives this script.

Commands have to acknowledge success. The runner waits for all fixture chunks before construction, verifies
mob/container counts and total cobblestone, then starts the measured window. It reads all RCON response
packets, including nested per-slice failure counters. Missing values are errors, not zeros. A PASS needs
advancing entity, block-entity, chunk and spawn worker counters, no safety counter failures, item conservation
and a clean stop. Other engine fallbacks are retained in the report. Failure paths stop the exact child
process, record a nonzero outcome and preserve the world and logs. Setup and shutdown are outside the
measurement duration.

Artifacts in each run directory:

- `result.json`: verdict, error, build identity, coverage and shutdown outcome.
- `commands.jsonl`: complete command responses; `diagnostics.raw.txt`: complete latest status response.
- `samples.jsonl` / `samples.csv`: actual MSPT, process RSS (not JVM heap), fixture checks and worker counters.
- `server.log`, the original `mods-list.txt`, and the retained world.

Tests for RCON framing, missing input, TOML placement and diagnostic parsing:

```bash
python3 -m unittest discover -s tools/modpack-matrix -p test_loadtest.py
```

## Startup groups and bisection

`run_group.sh`, `scan.py` and `bisect_mods.py` are the earlier startup-only tools. They retain logs and
classify `Done`, dependency and injection errors, but the scanner's dependency closure and group isolation
need further work before broad automated classification. In particular, forcing Connector/FFAPI into every
group makes a common infrastructure failure appear in many unrelated tests. A failing group does not prove
that each contained jar is incompatible, and passing halves do not rule out an interaction in their union.

Use the load runner above for measurements. Do not interpret the older `OK` startup result as a gameplay
compatibility verdict, a worker-execution check or a performance comparison.
