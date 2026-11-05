#!/bin/bash
# Runs the installed server on a selected set of mods with the region engine forced on, generates no load but
# lets it tick, asks for the region threading status through the console, stops it and classifies the outcome.
#
#   run_soak.sh <pack-dir> <jar-list-file> <run-name> [seconds] [status-command]
#
# Outcome classes:
#   OK            reached Done, ticked for the window, answered the status command, stopped within the grace
#   DISABLED      the compatibility policy kept the engine off
#   BOOT_FAIL     did not reach Done
#   CRASH         the server died during the window
#   STOP_HANG     reached Done but did not stop within the grace period (a hang, not a slow save)
set -uo pipefail

PACK=${1:?pack dir}
LIST=${2:?jar list}
NAME=${3:?run name}
SECONDS_TO_RUN=${4:-120}
STATUS_CMD=${5:-neoforge regionthreading status}
RUNS=${RUNS:-/home/yc/Minecraft/modpack-matrix-runs}
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

RUNDIR="$RUNS/$NAME"
rm -rf "$RUNDIR"
mkdir -p "$RUNDIR/mods" "$RUNDIR/config"
for entry in "$PACK"/*; do
    base=$(basename "$entry")
    case "$base" in
        mods|logs|logs-out|world|run-forgia.sh|mods-disabled|config) continue ;;
    esac
    ln -s "$entry" "$RUNDIR/$base"
done
cp -a "$PACK/config/." "$RUNDIR/config/" 2>/dev/null
cat > "$RUNDIR/config/neoforge-server.toml" <<'CFG'
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingEntityTicks = true
enableRegionThreadingBlockEntityTicks = true
enableRegionThreadingScheduledTicks = false
enableRegionThreadingRandomTicks = true
enableRegionThreadingSpawning = true
enableRegionThreadingPoi = true
enableRegionThreadingBorderUpdates = true
enableRegionThreadingGameEvents = false
regionThreadingForceWithKnownConflicts = true
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 3
regionThreadingWorkerThreads = 4
CFG
count=0
while IFS= read -r jar; do
    [ -z "$jar" ] && continue
    src="$PACK/mods/$jar"
    [ -f "$src" ] || src="$PACK/mods-disabled/$jar"
    [ -f "$src" ] || { echo "MISSING_JAR $jar" >&2; continue; }
    ln "$src" "$RUNDIR/mods/$jar" 2>/dev/null || cp "$src" "$RUNDIR/mods/$jar"
    count=$((count + 1))
done < "$LIST"

cat > "$RUNDIR/server.properties" <<'PROPS'
server-ip=
server-port=0
online-mode=false
level-name=world
level-type=minecraft\:flat
view-distance=4
simulation-distance=4
max-players=2
allow-flight=true
spawn-protection=0
max-tick-time=0
PROPS
printf 'eula=true\n' > "$RUNDIR/eula.txt"

LOG="$RUNS/$NAME.log"
STATUS_OUT="$RUNS/$NAME.status.txt"
FIFO="$RUNS/$NAME.stdin"
rm -f "$FIFO"; mkfifo "$FIFO"
START=$(date +%s)
( "$SCRIPT_DIR/tool-java.sh" "$RUNDIR" < "$FIFO" > "$LOG" 2>&1 ) &
PID=$!
exec 3> "$FIFO"

# wait for Done
DONE=""
while [ $(( $(date +%s) - START )) -lt "${BOOT_TIMEOUT:-240}" ]; do
    kill -0 "$PID" 2>/dev/null || break
    if grep -q 'Done (' "$LOG" 2>/dev/null; then DONE=$(date +%s); break; fi
    sleep 1
done

OUTCOME=BOOT_FAIL
if [ -n "$DONE" ]; then
    if grep -q 'Region threading engine disabled' "$LOG"; then
        OUTCOME=DISABLED
    else
        # optional load: one console command per line in $LOAD_CMDS
        if [ -n "${LOAD_CMDS:-}" ]; then
            printf '%s\n' "$LOAD_CMDS" | while IFS= read -r cmd; do
                [ -n "$cmd" ] && printf '%s\n' "$cmd" >&3
                sleep 0.3
            done
        fi
        sleep "$SECONDS_TO_RUN"
        if ! kill -0 "$PID" 2>/dev/null; then
            OUTCOME=CRASH
        else
            printf '%s\n' "$STATUS_CMD" >&3
            sleep 6
            printf 'stop\n' >&3
            GRACE=${STOP_GRACE:-90}
            STOPPED=""
            for _ in $(seq 1 "$GRACE"); do
                kill -0 "$PID" 2>/dev/null || { STOPPED=yes; break; }
                sleep 1
            done
            OUTCOME=${STOPPED:+OK}
            OUTCOME=${OUTCOME:-STOP_HANG}
        fi
    fi
fi

exec 3>&-
kill -KILL "$PID" 2>/dev/null
wait "$PID" 2>/dev/null
grep -a 'regionthreading\|Region threading\|ownership\|tick failure' "$LOG" > "$STATUS_OUT" 2>/dev/null
ELAPSED=$(( $(date +%s) - START ))
counters=$(rg -o 'entityTicksOnWorkers=[0-9]+|chunkTicksOnWorkers=[0-9]+|blockEntityTicksOnWorkers=[0-9]+|spawnChunksOnWorkers=[0-9]+|ownershipViolationCount=[0-9]+|tickFailures=[0-9]+|borderUpdates=[^ ]*' "$STATUS_OUT" 2>/dev/null | tr '\n' ' ')
echo "RESULT name=$NAME mods=$count outcome=$OUTCOME seconds=$ELAPSED $counters"
