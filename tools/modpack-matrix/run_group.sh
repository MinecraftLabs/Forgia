#!/bin/bash
# Runs the installed server on a selected set of mods and classifies the outcome.
#
#   run_group.sh <pack-dir> <jar-list-file> <run-name>
#
# The run directory is an overlay of the pack: everything except `mods` is a symlink, and `mods` holds hardlinks
# to the jars named in the list. That is the safe equivalent of a "load only these mods" switch - the loader is not
# patched, the pack is not modified, and each run needs no copying.
set -uo pipefail

PACK=${1:?pack dir}
LIST=${2:?jar list}
NAME=${3:?run name}
RUNS=${RUNS:-/tmp/modpack-matrix/runs}
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
BASE=${BASE_CPU:-}

RUNDIR="$RUNS/$NAME"
rm -rf "$RUNDIR"
mkdir -p "$RUNDIR/mods"

# overlay: link everything the server needs, except the mods directory and any world of a previous run
for entry in "$PACK"/*; do
    base=$(basename "$entry")
    case "$base" in
        mods|logs|logs-out|world|run-forgia.sh|mods-disabled) continue ;;
    esac
    ln -s "$entry" "$RUNDIR/$base"
done

count=0
while IFS= read -r jar; do
    [ -z "$jar" ] && continue
    if [ ! -f "$PACK/mods/$jar" ] && [ ! -f "$PACK/mods-disabled/$jar" ]; then
        echo "MISSING_JAR $jar" >&2
        continue
    fi
    src="$PACK/mods/$jar"
    [ -f "$src" ] || src="$PACK/mods-disabled/$jar"
    ln "$src" "$RUNDIR/mods/$jar" 2>/dev/null || cp "$src" "$RUNDIR/mods/$jar"
    count=$((count + 1))
done < "$LIST"

# a tiny flat world keeps world generation out of the measurement; the run only has to reach "Done"
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
sync-chunk-writes=false
PROPS
printf 'eula=true\n' > "$RUNDIR/eula.txt"

LOG="$RUNS/$NAME.log"
START=$(date +%s)
timeout "${GROUP_TIMEOUT:-180}" "$SCRIPT_DIR/tool-java.sh" "$RUNDIR" > "$LOG" 2>&1
rc=$?
ELAPSED=$(( $(date +%s) - START ))

STATUS=CRASH
REASON="exit $rc"
if grep -q 'Done (' "$LOG"; then
    STATUS=OK
    REASON="reached Done"
elif grep -q 'InjectionError\|Critical injection failure' "$LOG"; then
    STATUS=MIXIN
    REASON=$(grep -m1 -o 'Critical injection failure: Callback method [^ ]* in [^ ]* from mod [a-z0-9_]*' "$LOG" | head -1)
elif grep -q 'Missing or unsupported mandatory dependencies' "$LOG"; then
    STATUS=DEPS
    REASON=$(grep -A2 -m1 'Missing or unsupported mandatory dependencies' "$LOG" | tr '\n' ' ' | cut -c1-160)
elif grep -q 'has failed to load correctly\|Failed to load mods\|NoClassDefFoundError\|NoSuchMethodError' "$LOG"; then
    STATUS=LOAD
    REASON=$(grep -m1 -o 'Mod [a-zA-Z0-9_.-]* .*\|NoSuchMethodError[^\n]*\|NoClassDefFoundError[^\n]*' "$LOG" | head -1 | cut -c1-160)
fi

echo "RESULT name=$NAME mods=$count status=$STATUS seconds=$ELAPSED reason=$REASON"
