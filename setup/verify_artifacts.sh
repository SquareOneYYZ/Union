#!/usr/bin/env bash
#
# verify_artifacts.sh VERSION [DIR]
#
# Verifies the BUILT installer artifacts against the repo copy — not the
# staging out/ directory, which proves nothing about what makeself actually
# shipped. For each artifact:
#
#   EXPECTED TO CARRY scripts/archive_cold_storage.py (per package.sh,
#   package_linux only):
#       traccar-linux-64-$VERSION.zip
#       traccar-linux-arm-$VERSION.zip
#   -> open the zip, extract the makeself payload from traccar.run
#      (--noexec: setup.sh is never executed), REQUIRE the file present,
#      and REQUIRE its sha256 to equal the repo copy's.
#
#   ABSENT BY DESIGN (package_other ships no scripts/):
#       traccar-other-$VERSION.zip
#   -> REQUIRE the file absent; a script sneaking in would deploy through an
#      unvetted path.
#
# scripts/requirements.txt is checked with the same mechanism the moment it
# exists in the repo (C8): carriers must ship it byte-identical, the other
# artifact must not ship it at all. A dependency file that exists in the repo
# but is missing from a carrier fails the build — that is the "deployed
# script whose dependency file didn't ship" failure class.
#
# Exit 0 only if every check passes. Any missing expected artifact fails.

set -u

VERSION="${1:?usage: verify_artifacts.sh VERSION [DIR]}"
DIR="${2:-.}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

CARRY=("traccar-linux-64-$VERSION.zip" "traccar-linux-arm-$VERSION.zip")
NOCARRY=("traccar-other-$VERSION.zip")

FILES_TO_CHECK=("scripts/archive_cold_storage.py")
if [ -f "$REPO_ROOT/scripts/requirements.txt" ]; then
    FILES_TO_CHECK+=("scripts/requirements.txt")
fi

fail=0

extract_payload() {
    # $1 = artifact zip; echoes the payload dir, or returns 1.
    local zip="$1" workdir target
    workdir=$(mktemp -d) || return 1
    unzip -q "$zip" traccar.run -d "$workdir" || return 1
    target="$workdir/payload"
    # --noexec extracts without running setup.sh. The archive was built with
    # --needroot, whose check runs before extraction, so retry under sudo.
    if ! sh "$workdir/traccar.run" --noexec --keep --target "$target" >/dev/null 2>&1; then
        if command -v sudo >/dev/null 2>&1; then
            sudo sh "$workdir/traccar.run" --noexec --keep --target "$target" >/dev/null 2>&1 || return 1
            sudo chown -R "$(id -u)" "$target" 2>/dev/null || true
        else
            return 1
        fi
    fi
    echo "$target"
}

for z in "${CARRY[@]}"; do
    path="$DIR/$z"
    if [ ! -f "$path" ]; then
        echo "FAIL: expected artifact missing: $z"
        fail=1
        continue
    fi
    payload=$(extract_payload "$path") || {
        echo "FAIL: could not extract makeself payload from $z"
        fail=1
        continue
    }
    for f in "${FILES_TO_CHECK[@]}"; do
        packaged="$payload/$f"
        if [ ! -f "$packaged" ]; then
            echo "FAIL: $z payload does not contain $f (this artifact must carry it)"
            fail=1
            continue
        fi
        sum_pkg=$(sha256sum "$packaged" | cut -d' ' -f1)
        sum_repo=$(sha256sum "$REPO_ROOT/$f" | cut -d' ' -f1)
        if [ "$sum_pkg" != "$sum_repo" ]; then
            echo "FAIL: $z carries $f but sha256 differs (packaged $sum_pkg != repo $sum_repo)"
            fail=1
        else
            echo "OK:   $z carries $f, sha256 $sum_pkg (matches repo)"
        fi
    done
done

for z in "${NOCARRY[@]}"; do
    path="$DIR/$z"
    if [ ! -f "$path" ]; then
        echo "FAIL: expected artifact missing: $z"
        fail=1
        continue
    fi
    for f in "${FILES_TO_CHECK[@]}"; do
        if unzip -l "$path" | grep -q "$f"; then
            echo "FAIL: $z must NOT carry $f (absent by design) but does"
            fail=1
        else
            echo "OK:   $z does not carry $f (absent by design)"
        fi
    done
done

if [ "$fail" -ne 0 ]; then
    echo "ARTIFACT VERIFICATION FAILED"
    exit 1
fi
echo "All artifacts verified against the repo."
