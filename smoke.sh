#!/usr/bin/env bash
# CPI Groovy Tester - Rauchtest ueber alle mitgelieferten Beispiele.
#
# Faehrt jedes Beispielscript gegen jede Testeingabe und vergleicht die
# erzeugten Dateien mit den Sollstaenden unter testdata/expected/. Damit faellt
# beides auf: wenn ein Beispiel gar nicht mehr laeuft, und wenn sich seine
# Ausgabe still veraendert hat.
#
#   ./smoke.sh              prueft gegen die eingecheckten Sollstaende
#   ./smoke.sh --update     schreibt die Sollstaende neu (nach beabsichtigten
#                           Aenderungen - das Ergebnis vorher mit git diff ansehen)
#   ./smoke.sh --profiles   baut vorher groovy4 und groovy3 und prueft beide
#
# Verglichen werden output.*, properties.json und headers.json. Bewusst NICHT
# die Attachments: deren Dateinamen enthalten einen Zeitstempel aus dem
# Laufzeitpunkt ("DEBUG Log " + dateLog in PerPersonToXml.groovy), sie waeren
# also nie zweimal gleich. Und nicht console.log, weil dort Pfade des
# ausfuehrenden Rechners landen koennen.
#
# Die Ausgaben sind ueber beide Groovy-Profile hinweg identisch, deshalb genuegt
# ein Satz Sollstaende fuer beide.

set -u
cd "$(dirname "$0")"

UPDATE=0
PROFILES=0
for argument in "$@"; do
    case "$argument" in
        --update)   UPDATE=1 ;;
        --profiles) PROFILES=1 ;;
        -h|--help)  sed -n '2,22p' "$0" | sed 's/^#\{1,\} \{0,1\}//'; exit 0 ;;
        *) echo "Unbekannte Option: $argument (--help zeigt die Optionen)"; exit 2 ;;
    esac
done

EXPECTED_ROOT="testdata/expected"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0
comparisons=0

# $1 = Bezeichnung des Falls, danach die Argumente fuer run.sh
run_case() {
    local label="$1"; shift
    local actual="$WORK/$label"

    if ! ./run.sh run "$@" --outdir "$actual" --quiet >"$WORK/$label.log" 2>&1; then
        printf '  FEHLER   %-32s Lauf abgebrochen\n' "$label"
        sed -n '1,2p' "$WORK/$label.log" | sed 's/^/             /'
        failures=$((failures + 1))
        return
    fi

    local expected="$EXPECTED_ROOT/$label"

    if [ "$UPDATE" -eq 1 ]; then
        rm -rf "$expected"
        mkdir -p "$expected"
        local file
        for file in "$actual"/output.* "$actual"/properties.json "$actual"/headers.json; do
            [ -f "$file" ] && cp "$file" "$expected/"
        done
        printf '  NEU      %-32s Sollstand geschrieben\n' "$label"
        return
    fi

    if [ ! -d "$expected" ]; then
        printf '  FEHLER   %-32s kein Sollstand - einmal mit --update erzeugen\n' "$label"
        failures=$((failures + 1))
        return
    fi

    local differing=""
    local reference name
    for reference in "$expected"/*; do
        name="$(basename "$reference")"
        comparisons=$((comparisons + 1))
        if [ ! -f "$actual/$name" ]; then
            differing="$differing $name(fehlt)"
        elif ! diff -q "$reference" "$actual/$name" >/dev/null; then
            differing="$differing $name"
        fi
    done

    if [ -n "$differing" ]; then
        printf '  ABWEICH  %-32s%s\n' "$label" "$differing"
        for name in $differing; do
            case "$name" in *"(fehlt)") continue ;; esac
            diff -u "$expected/$name" "$actual/$name" | sed -n '3,14p' | sed 's/^/             /'
        done
        failures=$((failures + 1))
    else
        printf '  OK       %-32s\n' "$label"
    fi
}

all_cases() {
    run_case order-v1 --config testdata/config.json
    run_case order-v2 --config testdata/config.json --script scripts/OrderToXml_v2.groovy
    local fixture
    for fixture in sample kein_treffer ahv_zeitscheiben hiring_not_completed; do
        run_case "perperson-v1-$fixture" --config examples/config.json \
                 --script examples/PerPersonToXml.groovy \
                 --body "examples/PerPerson_$fixture.json"
        run_case "perperson-v2-$fixture" --config examples/config.json \
                 --script examples/PerPersonToXml_v2.groovy \
                 --body "examples/PerPerson_$fixture.json"
    done
}

if [ "$PROFILES" -eq 1 ]; then
    for profile in groovy4 groovy3; do
        echo "== Profil $profile =="
        if ! ./build.sh -P"$profile" >/dev/null; then
            echo "  Build fehlgeschlagen"
            exit 2
        fi
        all_cases
    done
else
    if [ ! -f target/cpi-groovy-tester.jar ]; then
        echo "JAR fehlt - baue es zuerst mit ./build.sh"
        exit 2
    fi
    all_cases
fi

echo
if [ "$UPDATE" -eq 1 ]; then
    echo "Sollstaende neu geschrieben. Vor dem Einchecken mit 'git diff' ansehen -"
    echo "eine unbeabsichtigte Aenderung sieht hier genauso aus wie eine gewollte."
    exit 0
fi

echo "$comparisons Vergleiche, $failures Faelle mit Abweichung."
[ "$failures" -eq 0 ] || exit 1
