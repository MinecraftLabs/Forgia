#!/usr/bin/env python3
"""Classify a mod pack by adaptive bisection.

Runs the installed server on a set of jars; if the set reaches "Done" every jar in it is compatible, if it fails
the set is split and both halves are tested, so the incompatible jars are found in O(k log n) runs instead of n.

  bisect.py <pack-dir> [--parallel N] [--timeout S] [--out results.csv]

The pack directory must contain the installed loader (libraries/net/neoforged/neoforge/<version>/unix_args.txt)
and the mod jars in mods/ (or mods-disabled/ for jars that must be disabled for the baseline).
"""
import argparse
import concurrent.futures
import os
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent


def run_group(pack: Path, jars, name, timeout):
    listfile = Path(tempfile.gettempdir()) / f"group-{name}.txt"
    listfile.write_text("\n".join(jars) + "\n")
    env = dict(os.environ, GROUP_TIMEOUT=str(timeout))
    out = subprocess.run([str(HERE / "run_group.sh"), str(pack), str(listfile), name],
                         capture_output=True, text=True, env=env).stdout
    line = next((l for l in out.splitlines() if l.startswith("RESULT ")), out.strip())
    fields = dict(part.split("=", 1) for part in line[len("RESULT "):].split(" ") if "=" in part)
    return fields


def load_dependencies(pack):
    """Map every mod id to the jar that provides it, including ids inside jar-in-jar bundles."""
    import json
    cache = Path(tempfile.gettempdir()) / "forgia-mods.json"
    if not cache.exists():
        subprocess.run([sys.executable, str(HERE / "scan.py"), str(pack / "mods"), str(cache)],
                       check=True, capture_output=True)
    mods = json.loads(cache.read_text())
    provider = {}
    for m in mods:
        provider.setdefault(m["id"], m["jar"])
    return provider, mods


def close_over(jars, provider, by_jar):
    """Adds the jars that provide a mandatory dependency, recursively, so a group is always loadable."""
    result = set(jars)
    pending = list(jars)
    while pending:
        jar = pending.pop()
        for dep in by_jar.get(jar, ()):
            other = provider.get(dep)
            if other is None or other in result:
                continue
            result.add(other)
            pending.append(other)
    return sorted(result)


def split(jars):
    half = len(jars) // 2
    return jars[:half], jars[half:]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pack")
    ap.add_argument("--parallel", type=int, default=2)
    ap.add_argument("--timeout", type=int, default=180)
    ap.add_argument("--out", default="/home/yc/Minecraft/modpack-matrix-runs/results.csv")
    args = ap.parse_args()

    pack = Path(args.pack).resolve()
    jars = sorted(p.name for d in ("mods", "mods-disabled") if (pack / d).is_dir()
                  for p in (pack / d).iterdir() if p.suffix == ".jar")
    provider, mods = load_dependencies(pack)
    by_jar = {}
    for m in mods:
        by_jar.setdefault(m["jar"], set()).update(m.get("deps", []))
    print(f"{len(jars)} jars, {len(provider)} mod ids", flush=True)

    def expand(group):
        # connector and the forgified fabric api provide the minecraft / fabricloader pseudo-mods that every
        # Fabric mod loaded through Connector depends on, so they are infrastructure for any group.
        infra = [jar for jar in jars if jar.startswith(("connector-", "[信雅互联]")) or "forgified-fabric-api" in jar]
        return close_over(sorted(set(group) | set(infra)), provider, by_jar)

    good, bad = [], []
    queue = [(jars, "root")]
    results = []
    counter = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.parallel) as pool:
        futures = {}
        while queue or futures:
            while queue and len(futures) < args.parallel:
                group, name = queue.pop()
                counter += 1
                run = expand(group)
                futures[pool.submit(run_group, pack, run, f"{name}-{counter}", args.timeout)] = group
            done, _ = concurrent.futures.wait(futures, return_when=concurrent.futures.FIRST_COMPLETED)
            for future in done:
                group = futures.pop(future)
                fields = future.result()
                status = fields.get("status", "?")
                results.append((fields.get("name", "?"), len(group), status, fields.get("seconds", "?"), fields.get("reason", "")))
                print(f"{fields.get('name','?'):24s} n={len(group):3d} {status:6s} {fields.get('seconds','?'):>4s}s {fields.get('reason','')[:90]}", flush=True)
                if status == "OK":
                    good.extend(group)
                elif len(group) == 1:
                    bad.append((group[0], fields.get("reason", status)))
                else:
                    left, right = split(group)
                    queue.append((left, fields.get("name", "g") + "L"))
                    queue.append((right, fields.get("name", "g") + "R"))

    with open(args.out, "w") as f:
        f.write("group,mods,status,seconds,reason\n")
        for r in sorted(results):
            f.write(",".join(str(x).replace(",", ";") for x in r) + "\n")
    print(f"\ncompatible jars: {len(good)}")
    print(f"incompatible jars: {len(bad)}")
    for jar, reason in bad:
        print(f"  {jar}: {reason[:120]}")
    print(f"results: {args.out}")


if __name__ == "__main__":
    main()
