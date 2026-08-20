#!/bin/sh

PRESERVECONFIG=0
if [ -f /opt/traccar/conf/traccar.xml ]
then
    cp /opt/traccar/conf/traccar.xml /opt/traccar/conf/traccar.xml.saved
    PRESERVECONFIG=1
fi

mkdir -p /opt/traccar
cp -r * /opt/traccar
chmod -R go+rX /opt/traccar

if [ ${PRESERVECONFIG} -eq 1 ] && [ -f /opt/traccar/conf/traccar.xml.saved ]
then
    mv -f /opt/traccar/conf/traccar.xml.saved /opt/traccar/conf/traccar.xml
fi

mv /opt/traccar/traccar.service /etc/systemd/system
chmod 664 /etc/systemd/system/traccar.service

mkdir -p /opt/traccar/scripts
chmod +x /opt/traccar/scripts/archive_cold_storage.py
mkdir -p /opt/traccar/parquet

# Archiver Python dependencies: pinned set, installed for the SAME interpreter
# the cron line invokes (/usr/bin/python3). System pip with
# --break-system-packages matches how the existing working host was set up
# (PEP 668 environment, packages in /usr/local). Idempotent: already-satisfied
# pins are skipped. Non-fatal but loud: a failed install must not abort the
# whole traccar upgrade -- --selfcheck below is the real gate.
if [ -f /opt/traccar/scripts/requirements.txt ]; then
    if /usr/bin/python3 -m pip --version >/dev/null 2>&1; then
        echo "Installing archiver Python dependencies (pinned set)..."
        if ! /usr/bin/python3 -m pip install --break-system-packages -r /opt/traccar/scripts/requirements.txt; then
            echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
            echo "!! WARNING: archiver dependency install FAILED.                 !!"
            echo "!! The archive script will NOT run until this is fixed.         !!"
            echo "!! Verify with the --selfcheck command printed below.           !!"
            echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
        fi
    else
        echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
        echo "!! WARNING: pip is not available for /usr/bin/python3.          !!"
        echo "!! Archiver dependencies NOT installed; the archive script      !!"
        echo "!! will NOT run until they are. See scripts/requirements.txt.   !!"
        echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
    fi
fi

systemctl daemon-reload
systemctl enable traccar.service

ARCHIVE_BUCKET=$(grep -o 'archive\.spaces\.bucket[^<]*</entry>' /opt/traccar/conf/traccar.xml 2>/dev/null | grep -o '>.*<' | tr -d '><')
if [ -n "$ARCHIVE_BUCKET" ]; then
    (crontab -l 2>/dev/null | grep -v "archive_cold_storage.py"; echo "0 4 1 * * /usr/bin/python3 /opt/traccar/scripts/archive_cold_storage.py --config /opt/traccar/conf/traccar.xml >> /opt/traccar/logs/archive.log 2>&1") | crontab -
    echo "Archive cron job installed."
else
    echo "archive.spaces.bucket not configured — skipping cron install."
fi

echo "Post-install verification of the archiver (read-only, run it now):"
echo "  sudo /usr/bin/python3 /opt/traccar/scripts/archive_cold_storage.py --config /opt/traccar/conf/traccar.xml --selfcheck"

rm /opt/traccar/setup.sh
rm -r ../out
