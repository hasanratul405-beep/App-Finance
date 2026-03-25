#!/bin/sh
GRADLE_OPTS="${GRADLE_OPTS:-"-Xmx512m"}"
exec gradle "$@"
