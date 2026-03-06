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
mv /opt/traccar/traccar-archive.service /etc/systemd/system
chmod 664 /etc/systemd/system/traccar-archive.service

systemctl daemon-reload
systemctl enable traccar.service
systemctl enable traccar-archive.service

rm /opt/traccar/setup.sh
rm -r ../out
