#!/usr/bin/env bash
# CPI Groovy Tester - Weboberflaeche auf http://localhost:8899
set -e
cd "$(dirname "$0")"
JAR="target/cpi-groovy-tester.jar"
[ -f "$JAR" ] || { echo "JAR fehlt - baue es zuerst mit ./build.sh"; exit 2; }
exec java -jar "$JAR" ui "$@"
