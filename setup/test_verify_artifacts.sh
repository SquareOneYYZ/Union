#!/usr/bin/env bash
#
# CI self-test for verify_artifacts.sh. release.yml only runs on manual
# dispatch, so this fabricates minimal artifacts exactly the way package.sh
# does (makeself --needroot --notemp of an out/ dir, zipped with -j; the
# "other" artifact as a plain zip of out/*) and asserts four behaviors:
#   1. faithful artifacts PASS
#   2. a tampered script inside the makeself payload FAILS
#   3. a carrier artifact missing the script entirely FAILS
#   4. the script sneaking into the absent-by-design artifact FAILS
# Runs on ubuntu (needs makeself, zip, unzip, sudo).

set -eu

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
V="selftest"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

verify() {
    bash "$REPO_ROOT/setup/verify_artifacts.sh" "$V" "$WORK"
}

mk_payload() {  # $1 = dir; faithful payload mirroring package.sh's out/
    mkdir -p "$1/scripts"
    cp "$REPO_ROOT/scripts/archive_cold_storage.py" "$1/scripts/"
    if [ -f "$REPO_ROOT/scripts/requirements.txt" ]; then
        cp "$REPO_ROOT/scripts/requirements.txt" "$1/scripts/"
    fi
    printf '#!/bin/sh\ntrue\n' > "$1/setup.sh"
    chmod +x "$1/setup.sh"
}

build_carrier_zip() {  # $1 = payload dir, $2 = zip name
    rm -f traccar.run "$2"
    makeself --needroot --quiet --notemp "$1" traccar.run "traccar" ./setup.sh
    zip -qj "$2" traccar.run
}

# --- faithful set ----------------------------------------------------------
mk_payload out
build_carrier_zip out "traccar-linux-64-$V.zip"
cp "traccar-linux-64-$V.zip" "traccar-linux-arm-$V.zip"
mkdir -p other/web && echo x > other/web/index.html
( cd other && zip -qr "../traccar-other-$V.zip" . )

if verify; then
    echo "PASS 1: faithful artifacts verify"
else
    echo "FAIL 1: faithful artifacts were rejected"; exit 1
fi

# --- tampered script in the payload ---------------------------------------
mk_payload out_tampered
echo "# tampered" >> out_tampered/scripts/archive_cold_storage.py
build_carrier_zip out_tampered "traccar-linux-64-$V.zip"

if verify; then
    echo "FAIL 2: tampered artifact passed"; exit 1
else
    echo "PASS 2: tampered artifact rejected"
fi
build_carrier_zip out "traccar-linux-64-$V.zip"   # restore faithful

# --- carrier missing the script entirely ----------------------------------
mkdir -p out_missing
printf '#!/bin/sh\ntrue\n' > out_missing/setup.sh
chmod +x out_missing/setup.sh
build_carrier_zip out_missing "traccar-linux-64-$V.zip"

if verify; then
    echo "FAIL 3: carrier without the script passed"; exit 1
else
    echo "PASS 3: carrier without the script rejected"
fi
build_carrier_zip out "traccar-linux-64-$V.zip"   # restore faithful

# --- script sneaking into the absent-by-design artifact --------------------
mkdir -p other/scripts
cp "$REPO_ROOT/scripts/archive_cold_storage.py" other/scripts/
rm -f "traccar-other-$V.zip"
( cd other && zip -qr "../traccar-other-$V.zip" . )

if verify; then
    echo "FAIL 4: script sneaking into the 'other' artifact passed"; exit 1
else
    echo "PASS 4: sneak into the absent-by-design artifact rejected"
fi

echo "verify_artifacts.sh self-test: all four behaviors confirmed."
