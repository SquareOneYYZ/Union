#!/usr/bin/env bash
#
# Runs ON the target host (piped over ssh by .github/workflows/deploy.yml).
#
#   deploy-remote.sh WORKDIR HEALTHCHECK_URL ARCHIVER_SHA256 REQUIREMENTS_SHA256 ENV
#
# WORKDIR already holds traccar-linux-64-*.zip, check-config.py,
# config-rules.txt and, optionally, traccar.xml (written from the TRACCAR_XML
# environment secret). Steps:
#   0. check the config this deploy will run with; stop before touching
#      anything if it fails
#   1. back up the current install's code + config to /opt/traccar-backups/
#   2. save root's crontab, run the makeself installer (setup.sh preserves the
#      existing traccar.xml), then put the saved crontab back
#   3. prove the deploy: installed archiver files must match the commit's hashes
#   4. if WORKDIR/traccar.xml exists, install it as conf/traccar.xml
#   5. restart traccar and poll HEALTHCHECK_URL until it answers 200
#
# Step 2's crontab restore: setup.sh re-arms the archive cron to monthly on
# every install wherever archive.spaces.bucket is configured (runbook Phase 1
# item 0). A deploy must never change the archive schedule, so whatever the
# crontab was before the install is exactly what it is after.
#
# No automatic rollback: a new build may have run Liquibase migrations, and
# putting the old jar back over a migrated schema is a decision for a human.
# On failure the restore command for the backup is printed instead.

set -euo pipefail

WORKDIR="${1:?usage: deploy-remote.sh WORKDIR HEALTHCHECK_URL}"
HEALTHCHECK_URL="${2:?usage: deploy-remote.sh WORKDIR HEALTHCHECK_URL}"
EXPECTED_ARCHIVER_SHA="${3:?missing expected archive_cold_storage.py sha256}"
EXPECTED_REQUIREMENTS_SHA="${4:?missing expected requirements.txt sha256}"
DEPLOY_ENV="${5:?missing environment name}"
INSTALL_DIR=/opt/traccar
BACKUP_ROOT=/opt/traccar-backups
KEEP_BACKUPS=5
HEALTH_TIMEOUT=300

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
    SUDO="sudo -n"
fi

cd "$WORKDIR"
# The secret-derived config must not outlive this run in /tmp, pass or fail
trap 'rm -f "$WORKDIR/traccar.xml"' EXIT

# 0. Config check, before anything on the host changes
if [ -f traccar.xml ]; then
    CONFIG_TO_CHECK="$WORKDIR/traccar.xml"
else
    CONFIG_TO_CHECK="$INSTALL_DIR/conf/traccar.xml"
fi
echo "Checking $CONFIG_TO_CHECK against config-rules.txt ($DEPLOY_ENV)"
if ! $SUDO /usr/bin/python3 check-config.py "$CONFIG_TO_CHECK" config-rules.txt "$DEPLOY_ENV"; then
    echo "!! Config check FAILED, nothing was changed on this host"
    exit 1
fi

# 1. Backup (code + config only; data/ and logs/ are left in place)
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP="$BACKUP_ROOT/$STAMP"
if [ -f "$INSTALL_DIR/tracker-server.jar" ]; then
    echo "Backing up current install to $BACKUP"
    $SUDO mkdir -p "$BACKUP"
    for item in tracker-server.jar lib web templates schema conf scripts; do
        if [ -e "$INSTALL_DIR/$item" ]; then
            $SUDO cp -a "$INSTALL_DIR/$item" "$BACKUP/"
        fi
    done
    # Prune old backups, newest KEEP_BACKUPS survive
    $SUDO ls -1d "$BACKUP_ROOT"/*/ 2>/dev/null | sort | head -n -"$KEEP_BACKUPS" | xargs -r $SUDO rm -rf
else
    echo "No existing install at $INSTALL_DIR, skipping backup"
    BACKUP=""
fi

# 2. Install (makeself --notemp extracts into ./out under the current dir),
#    with root's crontab saved before and restored after
HAD_CRONTAB=0
if $SUDO crontab -l > crontab.before 2>/dev/null; then
    HAD_CRONTAB=1
fi
unzip -o -q traccar-linux-64-*.zip traccar.run
$SUDO sh ./traccar.run
# setup.sh comments out the archive line when the archiver selfcheck fails.
# That disarm must survive the restore; only a re-arm is undone.
SELFCHECK_DISARMED=0
if $SUDO crontab -l 2>/dev/null | grep -q '^#.*archive_cold_storage'; then
    SELFCHECK_DISARMED=1
fi
if [ "$HAD_CRONTAB" -eq 1 ]; then
    if [ "$SELFCHECK_DISARMED" -eq 1 ]; then
        sed 's|^\([^#].*archive_cold_storage.*\)$|#\1|' crontab.before | $SUDO crontab -
    else
        $SUDO crontab crontab.before
    fi
else
    $SUDO crontab -r 2>/dev/null || true
fi
if [ "$SELFCHECK_DISARMED" -eq 1 ]; then
    echo "::warning::Archiver selfcheck failed during install; the archive cron line stays commented out (see installer output above)"
fi
echo "Root crontab after install (archive line):"
$SUDO crontab -l 2>/dev/null | grep archive_cold_storage || echo "  (no archive cron line)"

# 3. Prove the deploy (runbook Phase 4 step 6)
check_sha () {
    local file="$1" expected="$2" actual
    actual=$($SUDO sha256sum "$file" | cut -d' ' -f1)
    if [ "$actual" != "$expected" ]; then
        echo "!! $file sha256 $actual does not match the commit's $expected"
        exit 1
    fi
    echo "$file matches the commit"
}
check_sha "$INSTALL_DIR/scripts/archive_cold_storage.py" "$EXPECTED_ARCHIVER_SHA"
check_sha "$INSTALL_DIR/scripts/requirements.txt" "$EXPECTED_REQUIREMENTS_SHA"

# 4. Config from the environment secret, if one was provided
if [ -f traccar.xml ]; then
    echo "Installing conf/traccar.xml from the TRACCAR_XML secret"
    $SUDO install -m 640 -o root -g root traccar.xml "$INSTALL_DIR/conf/traccar.xml"
else
    echo "No TRACCAR_XML secret set, keeping the host's existing conf/traccar.xml"
fi

# 5. Restart + health check
$SUDO systemctl restart traccar.service
echo "Waiting up to ${HEALTH_TIMEOUT}s for $HEALTHCHECK_URL"
deadline=$((SECONDS + HEALTH_TIMEOUT))
while [ "$SECONDS" -lt "$deadline" ]; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$HEALTHCHECK_URL" || true)
    if [ "$code" = "200" ]; then
        echo "Healthy (HTTP 200)"
        rm -rf "$WORKDIR"
        exit 0
    fi
    sleep 5
done

echo "!! Health check FAILED: $HEALTHCHECK_URL did not return 200 within ${HEALTH_TIMEOUT}s"
$SUDO systemctl status traccar.service --no-pager || true
$SUDO journalctl -u traccar.service -n 100 --no-pager || true
$SUDO tail -n 100 "$INSTALL_DIR/logs/tracker-server.log" 2>/dev/null || true
if [ -n "$BACKUP" ]; then
    echo "Previous code + config are in $BACKUP. To roll back (check DB migrations first):"
    echo "  sudo systemctl stop traccar && sudo cp -a $BACKUP/. $INSTALL_DIR/ && sudo systemctl start traccar"
fi
exit 1
