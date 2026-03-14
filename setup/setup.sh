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

systemctl daemon-reload
systemctl enable traccar.service

ARCHIVE_BUCKET=$(grep -o 'archive\.spaces\.bucket[^<]*</entry>' /opt/traccar/conf/traccar.xml 2>/dev/null | grep -o '>.*<' | tr -d '><')
if [ -n "$ARCHIVE_BUCKET" ]; then
    (crontab -l 2>/dev/null | grep -v "archive_cold_storage.py"; echo "0 4 1 * * /usr/bin/python3 /opt/traccar/scripts/archive_cold_storage.py --config /opt/traccar/conf/traccar.xml >> /opt/traccar/logs/archive.log 2>&1") | crontab -
    echo "Archive cron job installed."
else
    echo "archive.spaces.bucket not configured — skipping cron install."
fi

rm /opt/traccar/setup.sh
rm -r ../out
