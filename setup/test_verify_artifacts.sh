#!/usr/bin/env bash
#
# CI self-test for verify_artifacts.sh — against REAL package.sh artifacts.
#
# The original requirement is "extract from the built artifact, not out/";
# an earlier version of this test fabricated its own payloads, which is
# exactly why CI could not catch a package.sh bug (out/scripts leaking into
# the "other" zip). This version stubs only the heavyweight inputs (server
# jar, web build, JDK tarballs built from the runner's own $JAVA_HOME jmods)
# and then runs the actual ./package.sh for ALL platforms, asserting the
# verifier against the artifacts it really produced:
#   1. faithful build PASSES (including: other-zip carries NO script — the
#      leak regression)
#   2. a repo-side script change makes the built artifacts FAIL (tamper)
#   3. a carrier artifact missing the script FAILS (fabricated case — the
#      only way to produce that state)
#   4. the script sneaking into the absent-by-design zip FAILS (fabricated)
#
# Needs: makeself, zip, unzip, jlink on PATH, JAVA_HOME set (setup-java).

set -eu

cd "$(dirname "$0")"
REPO_ROOT="$(cd .. && pwd)"
V="selftest"

cleanup() {
    # Restore anything we stubbed or tampered.
    if [ -f "$REPO_ROOT/scripts/archive_cold_storage.py.orig" ]; then
        mv -f "$REPO_ROOT/scripts/archive_cold_storage.py.orig" \
              "$REPO_ROOT/scripts/archive_cold_storage.py"
    fi
    rm -f traccar-linux-64-$V.zip traccar-linux-arm-$V.zip traccar-other-$V.zip
    rm -f OpenJDK-selftest_x64_linux.tar.gz OpenJDK-selftest_aarch64_linux.tar.gz
    rm -rf jdk-selftest out
}
trap cleanup EXIT

# --- stub the heavyweight prerequisites package.sh expects ----------------
mkdir -p "$REPO_ROOT/target/lib"
[ -f "$REPO_ROOT/target/tracker-server.jar" ] || echo dummy > "$REPO_ROOT/target/tracker-server.jar"
[ -n "$(ls -A "$REPO_ROOT/target/lib" 2>/dev/null)" ] || echo dummy > "$REPO_ROOT/target/lib/dummy.jar"
mkdir -p "$REPO_ROOT/traccar-web/build"
[ -n "$(ls -A "$REPO_ROOT/traccar-web/build" 2>/dev/null)" ] || echo '<html></html>' > "$REPO_ROOT/traccar-web/build/index.html"

# JDK tarballs: real jmods from the runner's JDK, top dir named jdk-* as
# package.sh expects. Same jmods serve both platform names — jlink output
# content is irrelevant to what this test asserts.
rm -rf jdk-selftest
mkdir jdk-selftest
cp -r "$JAVA_HOME/jmods" jdk-selftest/
tar czf OpenJDK-selftest_x64_linux.tar.gz jdk-selftest
cp OpenJDK-selftest_x64_linux.tar.gz OpenJDK-selftest_aarch64_linux.tar.gz
rm -rf jdk-selftest

# --- build ALL platforms with the real packaging script -------------------
./package.sh "$V"

verify() {
    bash "$REPO_ROOT/setup/verify_artifacts.sh" "$V" "$(pwd)"
}

# --- 1. faithful real artifacts PASS (includes the other-zip leak check) --
if verify; then
    echo "PASS 1: real package.sh artifacts verify (and 'other' carries no script)"
else
    echo "FAIL 1: faithful real artifacts were rejected"; exit 1
fi

# --- 2. tamper: repo copy changes AFTER the build -> built artifacts FAIL -
cp "$REPO_ROOT/scripts/archive_cold_storage.py" "$REPO_ROOT/scripts/archive_cold_storage.py.orig"
echo "# tampered" >> "$REPO_ROOT/scripts/archive_cold_storage.py"
if verify; then
    echo "FAIL 2: artifact/repo mismatch passed"; exit 1
else
    echo "PASS 2: artifact/repo mismatch rejected"
fi
mv -f "$REPO_ROOT/scripts/archive_cold_storage.py.orig" "$REPO_ROOT/scripts/archive_cold_storage.py"

# --- 3. carrier missing the script (fabricated: only way to produce it) ---
WORK=$(mktemp -d)
mkdir -p "$WORK/payload"
printf '#!/bin/sh\ntrue\n' > "$WORK/payload/setup.sh"
chmod +x "$WORK/payload/setup.sh"
( cd "$WORK" && makeself --needroot --quiet --notemp payload traccar.run "traccar" ./setup.sh )
cp "traccar-linux-64-$V.zip" "traccar-linux-64-$V.zip.orig"
zip -qj "traccar-linux-64-$V.zip" "$WORK/traccar.run"
if verify; then
    echo "FAIL 3: carrier without the script passed"; exit 1
else
    echo "PASS 3: carrier without the script rejected"
fi
mv -f "traccar-linux-64-$V.zip.orig" "traccar-linux-64-$V.zip"
rm -rf "$WORK"

# --- 4. script sneaking into the absent-by-design zip (fabricated) --------
SNEAK=$(mktemp -d)
mkdir -p "$SNEAK/scripts"
cp "$REPO_ROOT/scripts/archive_cold_storage.py" "$SNEAK/scripts/"
( cd "$SNEAK" && zip -qr "$OLDPWD/traccar-other-$V.zip" scripts )
if verify; then
    echo "FAIL 4: script sneaking into the 'other' artifact passed"; exit 1
else
    echo "PASS 4: sneak into the absent-by-design artifact rejected"
fi
rm -rf "$SNEAK"

echo "verify_artifacts.sh self-test: all four behaviors confirmed against real artifacts."
