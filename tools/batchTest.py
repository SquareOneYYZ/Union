#!/usr/bin/env python3
"""
Batch writing test script.
Sends positions from tollRouteSample.py points array rapidly
using multiple fake device IDs so batches fill up.
Each device sends different coordinates (offset slightly) so
duplicate/static filters don't trigger.
"""

import threading
import urllib
import http.client as httplib
import time

server = 'localhost:5055'

DEVICE_IDS = [
    '354876543210123',
    '490154203237518',
    '861075030123456',
    '356938035643809',
    '869470028364715',
    '352044090876543',
    '359112078654321',
    '862549036547890',
    '358967054321098',
    '864209037654321',
    '357924056789012',
    '865432098765432',
    '351987065432109',
    '867890123456789',
    '356789012345678',
    '869012345678901',
    '354321098765432',
    '862109876543210',
    '358765432109876',
    '864567890123456',
]


BASE_POINTS = [
    (43.59919, -79.79541, 61.0, 246),
    (43.59796, -79.79859, 56.7, 236),
    (43.59628, -79.80152, 61.0, 227),
    (43.59480, -79.80472, 59.4, 257),
    (43.59407, -79.80822, 58.9, 235),
    (43.59257, -79.80982, 62.1, 201),
    (43.59169, -79.81017, 63.2, 196),
    (43.58882, -79.81071, 62.1, 177),
    (43.58598, -79.80973, 63.7, 153),
    (43.58363, -79.80713, 67.0, 134),
    (43.58142, -79.80408, 66.4, 135),
    (43.57924, -79.80110, 66.4, 135),
    (43.57704, -79.79808, 68.0, 134),
    (43.57475, -79.79495, 69.7, 134),
    (43.57256, -79.79166, 70.2, 131),
    (43.57026, -79.78844, 71.3, 137),
    (43.56776, -79.78544, 72.4, 137),
    (43.56551, -79.78243, 62.6, 135),
    (43.56347, -79.77957, 64.3, 134),
    (43.56128, -79.77651, 69.1, 134),
    (43.55897, -79.77333, 70.2, 135),
    (43.55667, -79.77014, 70.2, 132),
    (43.55462, -79.76672, 68.6, 128),
    (43.55256, -79.76339, 69.1, 133),
    (43.55015, -79.76036, 70.7, 140),
    (43.54756, -79.75752, 71.8, 141),
    (43.54509, -79.75443, 70.7, 135),
    (43.54278, -79.75126, 70.2, 135),
    (43.54046, -79.74807, 71.3, 135),
    (43.53815, -79.74476, 71.8, 131),
]

def send_for_device(device_id, offset):
    """Send all points for one device with a small lat/lon offset so each device
    has unique coordinates — avoids duplicate/static filter."""
    conn = httplib.HTTPConnection(server)
    for i, (lat, lon, speed, heading) in enumerate(BASE_POINTS):
        lat_offset = offset * 0.0001
        lon_offset = offset * 0.0001
        params = (
            ('id', device_id),
            ('timestamp', int(time.time())),
            ('lat', round(lat + lat_offset, 6)),
            ('lon', round(lon + lon_offset, 6)),
            ('speed', speed),
            ('heading', heading),
        )
        try:
            conn.request('POST', '?' + urllib.parse.urlencode(params))
            conn.getresponse().read()
        except Exception as e:
            print(f"Error sending for {device_id}: {e}")
            try:
                conn = httplib.HTTPConnection(server)
            except Exception:
                pass
        time.sleep(0.1)
    conn.close()
    print(f"Done: {device_id}")

print(f"Starting batch test with {len(DEVICE_IDS)} devices...")
print(f"Expected: ~{len(DEVICE_IDS) * len(BASE_POINTS)} total positions")
print(f"With batching ON (interval=100ms): should see batches of {len(DEVICE_IDS)} positions per flush")
print()

threads = []
for i, device_id in enumerate(DEVICE_IDS):
    t = threading.Thread(target=send_for_device, args=(device_id, i))
    t.start()
    threads.append(t)

for t in threads:
    t.join()

print()
print("All done. Check DB:")
print("SELECT COUNT(*) FROM tc_positions WHERE serverTime > DATE_SUB(NOW(), INTERVAL 5 MINUTE);")
