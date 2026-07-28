#!/usr/bin/env bash
# Baut das JAR. Zusaetzliche Argumente gehen an Maven durch, z.B.:
#   ./build.sh -Pgroovy3
set -e
cd "$(dirname "$0")"
mvn -q -B clean package "$@"
echo "Fertig: target/cpi-groovy-tester.jar"
