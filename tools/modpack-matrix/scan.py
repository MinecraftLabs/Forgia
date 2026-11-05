#!/usr/bin/env python3
"""Scan a mods directory and write mods.json: id, version, jar, required dependencies.

Reads every jar's META-INF/neoforge.mods.toml (and nested jar-in-jar), collects the mod id
and the mandatory dependencies, and prints a summary. The dependency graph is what the
planner needs so a group never trips over a missing mandatory dependency.
"""
import json
import os
import re
import sys
import zipfile

BLOCK = re.compile(r"\[\[mods\]\](.*?)(?=\[\[|\Z)", re.S)
DEP = re.compile(r"\[\[dependencies\.([^\]]+)\]\](.*?)(?=\[\[|\Z)", re.S)
KV = re.compile(r'^\s*(\w+)\s*=\s*"([^"]*)"', re.M)


def parse_toml(text):
    mods = []
    for block in BLOCK.findall(text):
        kv = dict(KV.findall(block))
        if "modId" in kv:
            mods.append({"id": kv["modId"], "version": kv.get("version", "0"), "name": kv.get("displayName", kv["modId"])})
    deps = []
    for owner, body in DEP.findall(text):
        kv = dict(KV.findall(body))
        if kv.get("type", "required") != "required":
            continue
        if kv.get("modId") in (None, "neoforge", "minecraft", "forge"):
            continue
        deps.append(kv["modId"])
    return mods, deps


def scan_jar(path, entry, out, depth=0):
    try:
        z = zipfile.ZipFile(path if depth == 0 else entry)
    except Exception:
        return
    names = z.namelist()
    for n in names:
        if n.lower().endswith("neoforge.mods.toml") or n.lower().endswith("mods.toml"):
            try:
                text = z.read(n).decode("utf-8", "replace")
            except Exception:
                continue
            mods, deps = parse_toml(text)
            for m in mods:
                m["jar"] = os.path.basename(path) if depth == 0 else os.path.basename(path)
                m.setdefault("deps", [])
                m["deps"] = sorted(set(deps))
                out.append(m)
    if depth == 0:
        for n in names:
            if n.lower().endswith(".jar"):
                import io
                try:
                    nested = zipfile.ZipFile(io.BytesIO(z.read(n)))
                except Exception:
                    continue
                scan_jar(path, nested, out, depth + 1)


def main(mods_dir, out_path):
    mods = []
    for name in sorted(os.listdir(mods_dir)):
        if name.endswith(".jar"):
            scan_jar(os.path.join(mods_dir, name), None, mods)
    by_id = {}
    for m in mods:
        by_id.setdefault(m["id"], m)
    mods = sorted(by_id.values(), key=lambda m: m["id"])
    with open(out_path, "w") as f:
        json.dump(mods, f, indent=1)
    print(f"{len(mods)} mods from {mods_dir} -> {out_path}")
    jars = {m["jar"] for m in mods}
    print(f"{len(jars)} jars")
    missing = 0
    for m in mods:
        for d in m["deps"]:
            if d not in by_id:
                missing += 1
                print(f"  missing dep: {m['id']} requires {d}")
    print(f"{missing} unresolved mandatory dependencies")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "mods.json")
