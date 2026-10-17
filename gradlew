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
else
    exec gradle "$@"
fi
