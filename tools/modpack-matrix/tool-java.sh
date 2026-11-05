#!/bin/bash
# Starts the server inside a run directory with the installed Forgia loader.
set -uo pipefail
RUN=$1
cd "$RUN"
exec /home/yc/.local/opt/jdk-21.0.13+9/bin/java -Xms1G -Xmx3G -Dfile.encoding=UTF-8 \
    @libraries/net/neoforged/neoforge/21.1.250.0-beta/unix_args.txt nogui
