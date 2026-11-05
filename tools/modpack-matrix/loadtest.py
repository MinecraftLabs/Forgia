#!/usr/bin/env python3
"""Run one isolated, acknowledged modpack load test. A boot alone never means PASS."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time

from rcon import Rcon, RconError

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
WORKER_KEYS = ('entityTicksOnWorkers', 'blockEntityTicksOnWorkers', 'chunkTicksOnWorkers', 'spawnChunksOnWorkers')
COUNTER_KEYS = WORKER_KEYS + ('ownershipViolationCount', 'fallbackEntityTicks', 'fallbackBlockEntityTicks',
    'unloadedNeighborhoodChunkTicks', 'truncatedChunkTicks', 'truncatedSpawnChunks', 'gateRefusedSpawnChunks',
    'droppedDeferredEntityAdditions', 'droppedDeferredEntityRemovals', 'droppedDeferredEntitySectionUpdates',
    'droppedDeferredEntitySectionUpdatesForRemovedEntity')


def read_list(pack, filename):
    names = []
    for line in filename.read_text().splitlines():
        name = line.strip()
        if not name or name.startswith('#'):
            continue
        if Path(name).name != name or not name.endswith('.jar') or name in names:
            raise ValueError(f'Invalid/duplicate jar name: {name}')
        src = next((pack / d / name for d in ('mods', 'mods-disabled') if (pack / d / name).is_file()), None)
        if src is None:
            raise FileNotFoundError(name)
        names.append(name)
    if not any('kubejs' in name.lower() for name in names):
        raise ValueError('This fixture requires the pack KubeJS mod for server-side probes')
    return names


def patch_config(path, changes):
    lines = path.read_text().splitlines() if path.exists() else []
    # All region options belong to the TOML root; never append them inside a later [table].
    for key, value in changes.items():
        lines = [line for line in lines if not re.match(r'^' + re.escape(key) + r'\s*=', line)]
        lines.insert(0, f'{key} = {value}')
    path.write_text('\n'.join(lines) + '\n')


def parse_diagnostics(text):
    if 'Region threading diagnostics: enabled=true' not in text:
        raise ValueError('Region engine not installed/effective; see diagnostics.raw.txt')
    if 'entityStorageMode=' not in text or 'Region players:' not in text:
        raise ValueError('Incomplete region diagnostics response')
    result = {}
    for key in COUNTER_KEYS:
        match = re.search(r'(?<![\w])' + key + r'=(\d+)', text)
        if not match:
            raise ValueError(f'Missing diagnostic {key}; not substituting zero')
        result[key] = int(match[1])
    failure = re.search(r'tickFailures=(.*?), deferredUpdateBudgetChunkTicks=', text, re.S)
    if not failure:
        raise ValueError('Missing structured tick failure counts')
    result['tickFailures'] = sum(int(n) for n in re.findall(r'\b\w+=(\d+)', failure[1]))
    for section in ('borderUpdates', 'gameEvents'):
        match = re.search(section + r'=([^,\r\n]+)', text)
        if not match:
            raise ValueError(f'Missing {section}')
        for key, value in re.findall(r'([A-Za-z]+)=(\d+)', match[1]):
            result[section + '.' + key] = int(value)
    return result


def unsafe_counts(sample):
    fields = ('ownershipViolationCount', 'tickFailures', 'droppedDeferredEntityAdditions',
        'droppedDeferredEntityRemovals', 'borderUpdates.dropped', 'borderUpdates.blockWritesDropped',
        'borderUpdates.blockWritesOverflowed', 'gameEvents.dropped', 'gameEvents.refused')
    errors = {key: sample[key] for key in fields if sample.get(key, 0) > 0}
    live_drops = sample['droppedDeferredEntitySectionUpdates'] - sample['droppedDeferredEntitySectionUpdatesForRemovedEntity']
    if live_drops > 0:
        errors['liveEntitySectionDrops'] = live_drops
    return errors


def fixture_commands():
    """Four pads, one in the centre of each of four adjacent fixed regions.

    The regions are 3 chunks wide, so their centres are chunks 62 and 65 (blocks 1000 and 1048) and the four pads
    span a 2x2 region area around the border corner at block 1024. A fake player stands on that corner, so every
    pad is inside the simulation distance and every slice has work in more than one region.
    """
    for cmd in ('difficulty normal', 'gamerule doMobSpawning true', 'gamerule doDaylightCycle false',
                'gamerule randomTickSpeed 3', 'time set noon', 'weather clear'):
        yield cmd
    for x, z in ((1000, 1000), (1048, 1000), (1000, 1048), (1048, 1048)):
        yield f'fill {x-4} 280 {z-4} {x+7} 280 {z+7} minecraft:stone'
        yield f'fill {x-4} 281 {z-4} {x+7} 284 {z+7} minecraft:air'
        # Cobblestone path: every slot is tracked, including the hopper in flight.
        yield f'setblock {x} 281 {z} minecraft:chest'
        yield f'setblock {x} 282 {z} minecraft:hopper[facing=down]'
        yield f'setblock {x} 283 {z} minecraft:chest'
        for slot in range(8):
            yield f'item replace block {x} 283 {z} container.{slot} with minecraft:cobblestone 64'
        # Furnace with an input hopper and an output hopper.
        yield f'setblock {x+2} 281 {z} minecraft:chest'
        yield f'setblock {x+2} 282 {z} minecraft:hopper[facing=down]'
        yield f'setblock {x+2} 283 {z} minecraft:furnace'
        yield f'setblock {x+2} 284 {z} minecraft:hopper[facing=down]'
        yield f'item replace block {x+2} 283 {z} container.0 with minecraft:sand 64'
        yield f'item replace block {x+2} 283 {z} container.1 with minecraft:coal 64'
        # Crops for the random tick slice, with a light source above the pad.
        yield f'fill {x-4} 280 {z+2} {x-1} 280 {z+5} minecraft:farmland[moisture=7]'
        yield f'fill {x-4} 281 {z+2} {x-1} 281 {z+5} minecraft:wheat[age=0]'
        yield f'setblock {x-2} 284 {z+4} minecraft:glowstone'
        for mob in ('cow', 'zombie'):
            yield (f'summon minecraft:{mob} {x+4}.5 281 {z+4}.5 '
                   '{Tags:["forgia_load"],PersistenceRequired:1b,Invulnerable:1b}')
        yield f'setblock {x+4} 281 {z+6} minecraft:red_bed[part=foot,facing=south]'
        yield f'setblock {x+4} 281 {z+7} minecraft:red_bed[part=head,facing=south]'
        yield f'setblock {x+6} 281 {z+6} minecraft:cartography_table'
        yield (f'summon minecraft:villager {x+5}.5 281 {z+6}.5 '
               '{Tags:["forgia_load"],PersistenceRequired:1b,Invulnerable:1b}')
    # Region border at block 1024: the comparator reads a chest on the other side of it.
    yield 'fill 1022 280 1022 1025 280 1026 minecraft:stone'
    yield 'setblock 1023 281 1024 minecraft:chest'
    yield 'setblock 1024 281 1024 minecraft:comparator[facing=west]'
    yield 'setblock 1025 281 1024 minecraft:redstone_wire'
    yield 'setblock 1026 281 1024 minecraft:redstone_lamp'
    yield 'item replace block 1023 281 1024 container.0 with minecraft:stone 64'


class Run:
    def __init__(self, args):
        self.args = args
        self.root = args.runs.resolve() / args.name
        self.proc = None
        self.client = None
        self.log = None
        self.samples = []
        self.stop_result = None
        self.summary = {'outcome': 'STARTING', 'run': str(self.root), 'window_seconds': args.minutes * 60,
                        'coverage': 'headless fixture with a fake player, not real multiplayer or mod gameplay'}

    def prepare(self):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,60}', self.args.name):
            raise ValueError('Use a simple run name')
        self.root.mkdir(parents=True, exist_ok=False)  # never delete an existing world
        pack = self.args.pack.resolve()
        names = read_list(pack, self.args.mods_list)
        excluded = {'mods','mods-disabled','world','logs','logs-out','crash-reports','libraries','runtime',
                    'server.properties','eula.txt','usercache.json','usernamecache.json','ops.json','whitelist.json',
                    'banned-ips.json','banned-players.json','server-icon.png'}
        for entry in pack.iterdir():
            if entry.name in excluded or entry.name.startswith('.'):
                continue
            if entry.is_dir():
                shutil.copytree(entry, self.root / entry.name, symlinks=False)
        # Mutable scripts, resources, caches and config are private copies. Only installed libraries are shared.
        (self.root / 'libraries').symlink_to(pack / 'libraries', target_is_directory=True)
        shutil.copy2(pack / 'eula.txt', self.root / 'eula.txt')
        (self.root / 'mods').mkdir()
        for name in names:
            src = next(pack / d / name for d in ('mods', 'mods-disabled') if (pack / d / name).is_file())
            shutil.copy2(src, self.root / 'mods' / name)
        self.summary['mods'] = len(names)
        (self.root / 'mods-list.txt').write_text('\n'.join(names) + '\n')
        cfg = self.root / 'config/neoforge-server.toml'
        cfg.parent.mkdir(exist_ok=True)
        enabled = 'false' if self.args.no_regions else 'true'
        switches = {key: enabled for key in ('enableRegionThreadingBridge','enableRegionThreadingWorkers',
            'enableRegionThreadingEntityTicks','enableRegionThreadingBlockEntityTicks','enableRegionThreadingRandomTicks',
            'enableRegionThreadingSpawning','enableRegionThreadingPoi','enableRegionThreadingBorderUpdates',
            'enableRegionThreadingGameEvents')}
        switches.update({'enableRegionThreadingScheduledTicks': 'false', 'enableGlobalRegionThreadingWorker': 'false',
            'enableRegionThreadingPersistentWorkers': 'false', 'enableRegionThreadingPackedRegionLeases': 'false',
            'regionThreadingForceWithKnownConflicts':'false',
            'regionThreadingMode':'"FIXED_3X3"', 'regionThreadingFixedRegionChunks':'3','regionThreadingWorkerThreads':'4'})
        patch_config(cfg, switches)
        target = self.root / 'kubejs/server_scripts/forgia_load_probe.js'
        shutil.copy2(HERE / 'load_probe.js', target)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            self.port = sock.getsockname()[1]
        self.password = secrets.token_hex(24)
        properties = ('server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-name=world\n'
            'level-type=minecraft\\:normal\nlevel-seed=4242424242\nview-distance=6\nsimulation-distance=4\n'
            'max-players=2\nallow-flight=true\nspawn-protection=0\nmax-tick-time=120000\n'
            'sync-chunk-writes=true\nenable-rcon=true\nbroadcast-rcon-to-ops=false\n'
            f'rcon.port={self.port}\nrcon.password={self.password}\n')
        (self.root / 'server.properties').write_text(properties)
        (self.root / 'server.properties').chmod(0o600)
        options = sorted((pack / 'libraries/net/neoforged/neoforge').glob('*-beta/unix_args.txt'),
                         key=lambda p: [int(n) for n in re.findall(r'\d+', p.parent.name)])
        if not options:
            raise FileNotFoundError('No installed Forgia loader')
        self.loader_args = options[-1].relative_to(pack)
        self.summary.update(loader=str(self.loader_args),
            loader_args_sha256=hashlib.sha256(options[-1].read_bytes()).hexdigest(),
            installed_jars={p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                            for p in options[-1].parent.glob('*.jar')},
            repository_commit_at_run=subprocess.check_output(['git','rev-parse','HEAD'], cwd=REPO, text=True).strip())
        self.save_summary()

    def save_summary(self):
        if self.root.is_dir():
            (self.root / 'result.json').write_text(json.dumps(self.summary, indent=2) + '\n')

    def command(self, text, expected=None, retries=3):
        for attempt in range(retries + 1):
            response = self.client.command(text)
            with (self.root / 'commands.jsonl').open('a') as stream:
                stream.write(json.dumps({'command':text, 'response':response}, ensure_ascii=False) + '\n')
            if expected is None or re.search(expected, response):
                return response
            # Chunks can be loaded but not yet at the level a fill/setblock needs; wait and retry rather than
            # accepting a partial fixture.
            if attempt < retries and 'not loaded' in response.lower():
                time.sleep(5)
                continue
            raise ValueError(f'Command did not acknowledge success: {text}: {response[:500]}')
        raise AssertionError('unreachable')

    def probe(self, kind):
        reply = self.command('forgia_probe ' + kind, r'FORGIA_PROBE ')
        data = json.JSONDecoder().raw_decode(reply.split('FORGIA_PROBE ', 1)[1])[0]
        if 'error' in data:
            raise ValueError('Probe: ' + data['error'])
        return data

    def start(self):
        self.log = (self.root / 'server.log').open('w')
        java = self.args.java.expanduser().resolve()
        java_version = subprocess.run([str(java), '-version'], capture_output=True, text=True, check=True)
        self.summary['java'] = str(java)
        self.summary['java_version'] = java_version.stderr.strip()
        self.proc = subprocess.Popen([str(java), '-Xms1G', '-Xmx4G', '-Dfile.encoding=UTF-8',
                                      '@' + str(self.loader_args), 'nogui'], cwd=self.root,
                                     stdin=subprocess.PIPE, stdout=self.log, stderr=subprocess.STDOUT,
                                     text=True, start_new_session=True)
        (self.root / 'pid').write_text(str(self.proc.pid))
        deadline = time.monotonic() + self.args.boot_timeout
        while time.monotonic() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError(f'Server exited during boot: {self.proc.returncode}')
            if 'Done (' in (self.root / 'server.log').read_text(errors='replace'):
                self.client = Rcon('127.0.0.1', self.port, self.password, timeout=180)
                self.command('list', r'There are')
                print('BOOT_READY ' + str(self.root), flush=True)
                return
            time.sleep(2)
        raise TimeoutError('Boot deadline exceeded')

    def setup(self):
        self.probe('tickets')
        for x, z in ((1000, 1000), (1048, 1000), (1000, 1048), (1048, 1048)):
            self.command(f'forceload add {x} {z}', r'Added|already|No chunks were marked')
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            status = self.probe('status')
            if status['loadedChunks'] == status['expectedChunks']:
                break
            time.sleep(5)
        else:
            raise TimeoutError(f"{status['expectedChunks']} fixture chunks did not reach FULL status")
        self.probe('player')
        for cmd in fixture_commands():
            if cmd.startswith('fill '):
                # Clearing an already-empty volume is a no-op, not a failure; a solid fill must place blocks.
                expected = r'Successfully filled|No blocks were filled' if ' minecraft:air' in cmd else r'Successfully filled'
            elif cmd.startswith('setblock '):
                expected = r'Changed the block'
            elif cmd.startswith('item replace '):
                expected = r'Replaced'
            elif cmd.startswith('summon '):
                expected = r'Summoned new'
            else:
                expected = r'set|Set'
            self.command(cmd, expected)
        tracked = self.probe('track')
        if tracked['fixtureMobs'] < 12 or tracked['containers'] != 37 or tracked['cobblestone'] != 2048:
            raise ValueError(f'Fixture verification failed: {tracked}')
        self.summary['fixture'] = tracked
        (self.root / 'compat.raw.txt').write_text(self.command('neoforge regionthreading compat'))
        time.sleep(self.args.warmup)

    def sample(self):
        if self.proc.poll() is not None:
            raise RuntimeError('Server exited during measurement')
        raw = self.command('neoforge regionthreading status')
        probe = self.probe('status')
        data = {**parse_diagnostics(raw), **probe}
        tps = self.command('neoforge tps')
        timing = re.search(r'Overall: ([\d.]+) TPS \(([\d.]+) ms/tick\)', tps)
        if not timing:
            raise ValueError('Server TPS command did not report overall tick time: ' + tps)
        data['tps'], data['mspt'] = map(float, timing.groups())
        process_status = Path(f'/proc/{self.proc.pid}/status').read_text()
        rss = re.search(r'^VmRSS:\s+(\d+) kB', process_status, re.M)
        data['rssMiB'] = int(rss[1]) / 1024 if rss else None
        data['elapsed_s'] = round(time.monotonic() - self.window_start, 3)
        with (self.root / 'samples.jsonl').open('a') as stream:
            stream.write(json.dumps(data, ensure_ascii=False) + '\n')
        self.samples.append(data)
        # Store the entire response, including all RCON continuation packets.
        (self.root / 'diagnostics.raw.txt').write_text(raw)
        violations = unsafe_counts(data)
        if violations:
            raise ValueError(f'Runtime safety counters are nonzero: {violations}')
        if data['cobblestone'] != 2048:
            raise ValueError(f'Cobblestone conservation failed: {data["cobblestone"]} != 2048')
        return data

    def measure(self):
        self.window_start = time.monotonic()
        baseline = self.sample()
        deadline = self.window_start + self.args.minutes * 60
        next_sample = min(deadline, self.window_start + self.args.sample_seconds)
        while time.monotonic() < deadline:
            time.sleep(max(0, next_sample - time.monotonic()))
            self.sample()
            next_sample = min(deadline, next_sample + self.args.sample_seconds)
        final = self.samples[-1]
        deltas = {k: final[k] - baseline[k] for k in COUNTER_KEYS}
        if any(value < 0 for value in deltas.values()):
            raise ValueError('Engine counters reset during the measurement window')
        if final['fixtureMobs'] != 12 or not final['playerPresent']:
            raise ValueError('Fixture population/player did not survive the window')
        self.summary.update(deltas=deltas, first=baseline, last=final, samples=len(self.samples),
                            measured_seconds=final['elapsed_s'])
        missing = [key for key in WORKER_KEYS if deltas[key] <= 0]
        if missing:
            raise ValueError('Required worker coverage did not advance: ' + ', '.join(missing))
        self.summary['outcome'] = 'PASS'
        self.summary['unverified'] = ['network players', 'contraptions', 'individual mod mechanics',
                                      'performance versus stock NeoForge', 'heap growth (RSS only)']

    def stop(self):
        if self.client:
            self.client.close()
        if self.proc is None:
            return
        if self.proc.poll() is None:
            try:
                self.proc.stdin.write('stop\n')
                self.proc.stdin.flush()
                self.proc.wait(timeout=90)
                self.stop_result = 'clean'
            except (BrokenPipeError, subprocess.TimeoutExpired):
                self.stop_result = 'forced'
                os.killpg(self.proc.pid, signal.SIGTERM)
                try:
                    self.proc.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(self.proc.pid, signal.SIGKILL)
                    self.proc.wait(timeout=15)
        else:
            self.stop_result = 'already_exited'
        self.summary['shutdown'] = self.stop_result
        self.summary['exit_code'] = self.proc.returncode
        if self.stop_result != 'clean' or self.proc.returncode != 0:
            if self.summary['outcome'] == 'PASS':
                self.summary['outcome'] = 'SHUTDOWN_FAILED'
        if self.log:
            self.log.close()

    def execute(self):
        try:
            self.prepare()
            self.start()
            self.setup()
            self.measure()
        except Exception as exc:
            self.summary['outcome'] = 'FAILED'
            self.summary['error'] = str(exc)
        finally:
            self.stop()
            self.save_summary()
        if self.samples:
            with (self.root / 'samples.csv').open('w') as stream:
                columns = ['elapsed_s','mspt','rssMiB','fixtureMobs','cobblestone','glass','grownCrops'] + list(COUNTER_KEYS)
                writer = csv.DictWriter(stream, fieldnames=columns, extrasaction='ignore')
                writer.writeheader()
                writer.writerows(self.samples)
        print(json.dumps(self.summary, ensure_ascii=False), flush=True)
        return 0 if self.summary['outcome'] == 'PASS' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pack', type=Path)
    parser.add_argument('mods_list', type=Path)
    parser.add_argument('name')
    parser.add_argument('minutes', type=float, nargs='?', default=30)
    parser.add_argument('sample_seconds', type=float, nargs='?', default=300)
    parser.add_argument('--warmup', type=float, default=30)
    parser.add_argument('--no-regions', action='store_true', help='Control run: the same fixture with the engine off')
    parser.add_argument('--boot-timeout', type=float, default=360)
    parser.add_argument('--runs', type=Path, default=REPO.parent / 'modpack-matrix-runs')
    parser.add_argument('--java', type=Path, default=Path('/home/yc/.local/opt/jdk-21.0.13+9/bin/java'))
    args = parser.parse_args()
    if args.minutes <= 0 or args.sample_seconds <= 0 or args.warmup < 0:
        parser.error('Duration/sample interval must be positive, warmup nonnegative')
    def cancel(signum, frame):
        raise RuntimeError(f'Cancelled by signal {signum}')
    signal.signal(signal.SIGTERM, cancel)
    signal.signal(signal.SIGINT, cancel)
    return Run(args).execute()


if __name__ == '__main__':
    sys.exit(main())
