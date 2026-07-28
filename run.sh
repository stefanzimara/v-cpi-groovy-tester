#!/usr/bin/env bash
# CPI Groovy Tester - CLI-Modus
# Beispiel: ./run.sh run --config testdata/config.json --outdir out
set -e
cd "$(dirname "$0")"
JAR="target/cpi-groovy-tester.jar"
[ -f "$JAR" ] || { echo "JAR fehlt - baue es zuerst mit ./build.sh"; exit 2; }
exec java -jar "$JAR" "$@"
