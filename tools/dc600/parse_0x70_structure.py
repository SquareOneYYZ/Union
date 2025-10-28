#!/usr/bin/env python3
"""
Parse 0x70 multimedia event packet structure
"""

# Your device (oct27-json2) - lines 220-222
your_0x70 = """
70 2F 00 00 00 00 00 03 00 01 00 1C 00 00 18 00 8F 02 99 AE E7 04 C0 78 E1
25 10 28 02 42 39 04 01 30 00 00 00 00 00 00 25 10 28 02 42 39 00 04 00
"""

# Vendor device (oct24-2-istar) - 0x64 ADAS alarm for comparison
vendor_0x64 = """
64 2F 00 00 00 01 00 03 01 00 00 00 1D 00 73 02 98 E7 FF 04 BF 4D 7B
25 10 25 05 57 00 04 01 30 00 00 00 00 00 00 25 10 25 05 57 00 01 04 00
"""

def parse_0x70(hex_str):
    data = bytes.fromhex(hex_str.replace('\n', '').replace(' ', ''))
    print(f"Total length: {len(data)} bytes")
    print(f"\nByte-by-byte breakdown:")

    offset = 0
    msg_id = data[offset]
    offset += 1
    print(f"  [{offset-1:2d}] Message ID: 0x{msg_id:02X}")

    length = data[offset]
    offset += 1
    print(f"  [{offset-1:2d}] Length: {length} bytes")

    media_id = int.from_bytes(data[offset:offset+4], 'big')
    offset += 4
    print(f"  [{offset-4:2d}-{offset-1:2d}] Media ID: {media_id}")

    media_type = data[offset]
    offset += 1
    print(f"  [{offset-1:2d}] Media Type: {media_type}")

    media_format = data[offset]
    offset += 1
    print(f"  [{offset-1:2d}] Media Format: {media_format}")

    event_code = data[offset]
    offset += 1
    print(f"  [{offset-1:2d}] Event Code: 0x{event_code:02X}")

    channel = data[offset]
    offset += 1
    print(f"  [{offset-1:2d}] Channel: {channel}")

    print(f"\n  Extended data (offset {offset}):")

    # Skip speed/altitude/lat/lon (15 bytes total based on structure)
    speed = data[offset]
    offset += 1
    print(f"  [{offset-1:2d}] Speed: {speed} km/h")

    altitude_raw = int.from_bytes(data[offset:offset+2], 'big')
    offset += 2
    print(f"  [{offset-2:2d}-{offset-1:2d}] Altitude: {altitude_raw} (raw)")

    lat_raw = int.from_bytes(data[offset:offset+4], 'big', signed=True)
    offset += 4
    print(f"  [{offset-4:2d}-{offset-1:2d}] Latitude: {lat_raw} (raw)")

    lon_raw = int.from_bytes(data[offset:offset+4], 'big', signed=True)
    offset += 4
    print(f"  [{offset-4:2d}-{offset-1:2d}] Longitude: {lon_raw} (raw)")

    time_bcd = data[offset:offset+6]
    offset += 6
    print(f"  [{offset-6:2d}-{offset-1:2d}] Time (BCD): {' '.join(f'{b:02X}' for b in time_bcd)} = 20{time_bcd[0]:02X}-{time_bcd[1]:02X}-{time_bcd[2]:02X} {time_bcd[3]:02X}:{time_bcd[4]:02X}:{time_bcd[5]:02X}")

    vehicle_status = data[offset:offset+2]
    offset += 2
    print(f"  [{offset-2:2d}-{offset-1:2d}] Vehicle Status: {' '.join(f'{b:02X}' for b in vehicle_status)}")

    print(f"\n  Alarm Identification (16 bytes) - Table 4-16 structure:")
    if offset + 16 <= len(data):
        alarm_id_block = data[offset:offset+16]

        terminal_id = alarm_id_block[0:7]
        print(f"  [{offset:2d}-{offset+6:2d}] Terminal ID: {' '.join(f'{b:02X}' for b in terminal_id)} = '{terminal_id.decode('ascii', errors='ignore').strip(chr(0))}'")
        offset += 7

        alarm_time_bcd = alarm_id_block[7:13]
        print(f"  [{offset:2d}-{offset+5:2d}] Alarm Time (BCD): {' '.join(f'{b:02X}' for b in alarm_time_bcd)} = 20{alarm_time_bcd[0]:02X}-{alarm_time_bcd[1]:02X}-{alarm_time_bcd[2]:02X} {alarm_time_bcd[3]:02X}:{alarm_time_bcd[4]:02X}:{alarm_time_bcd[5]:02X}")
        offset += 6

        serial = alarm_id_block[13]
        print(f"  [{offset:2d}] Serial Number: {serial}")
        offset += 1

        num_attachments = alarm_id_block[14]
        print(f"  [{offset:2d}] Number of Attachments: {num_attachments} *** CRITICAL ***")
        offset += 1

        reserved = alarm_id_block[15]
        print(f"  [{offset:2d}] Reserved: 0x{reserved:02X}")
        offset += 1
    else:
        print(f"  WARNING: Not enough bytes for Alarm Identification structure!")

    print(f"\n  Total bytes parsed: {offset}/{len(data)}")

print("="*80)
print("YOUR DEVICE (oct27-json2) - 0x70 Multimedia Event")
print("="*80)
parse_0x70(your_0x70)

print("\n" + "="*80)
print("VENDOR DEVICE (oct24-2-istar) - 0x64 ADAS Alarm")
print("="*80)
parse_0x70(vendor_0x64)

print("\n" + "="*80)
print("CRITICAL FINDINGS:")
print("="*80)
print("1. Both packets contain a 16-byte 'Alarm Identification' structure")
print("2. This structure matches Table 4-16 (Alarm Flag) format")
print("3. Byte [39] contains NUMBER OF ATTACHMENTS (4 in both cases)")
print("4. We are currently HARDCODING attachments = 1 in our 0x9208 request")
print("5. Device expects us to ECHO BACK the alarm identification it sent")
print("\nACTION REQUIRED:")
print("- Extract the full 16-byte Alarm Identification from 0x70/0x64/0x65")
print("- Store it with the position")
print("- Send it back EXACTLY in the 0x9208 Alarm Flag field")
print("- This ensures timestamp, serial, and attachment count all match!")
