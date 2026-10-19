#!/usr/bin/env bash
#
# Copyright 2026 OpenFlow Contributors
# Licensed under the Apache License, Version 2.0
#
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$SCRIPT_DIR/jdk-17.0.2" ]; then
    export JAVA_HOME="$SCRIPT_DIR/jdk-17.0.2"
fi
export GRADLE_OPTS="${GRADLE_OPTS:-} -Djava.net.preferIPv4Stack=true"
if [ -x "$SCRIPT_DIR/gradle-8.9/bin/gradle" ]; then
    exec "$SCRIPT_DIR/gradle-8.9/bin/gradle" "$@"
elif [ -n "$(find "${HOME}/.gradle/wrapper/dists/gradle-8.9-bin" -maxdepth 4 -name gradle -type f 2>/dev/null | head -n 1)" ]; then
    CACHED_GRADLE="$(find "${HOME}/.gradle/wrapper/dists/gradle-8.9-bin" -maxdepth 4 -name gradle -type f 2>/dev/null | head -n 1)"
    exec "$CACHED_GRADLE" "$@"
else
    exec gradle "$@"
fi
