#!/usr/bin/env python3
"""
Analyze 0x9208 messages from server logs
Verifies that the alarm flag is properly populated after the bug fix
"""

import re
import sys


def analyze_9208_hex(hex_string):
    """
    Parse and analyze a 0x9208 message hex string
    """
    # Remove spaces and convert to bytes
    hex_clean = hex_string.replace(' ', '').replace('7e', '').replace('7E', '')
    if not hex_clean:
        return None

    try:
        # Skip first delimiter and last delimiter+checksum
        data = bytes.fromhex(hex_clean)
    except:
        return None

    # Parse message structure
    if len(data) < 65:
        return {"error": "Message too short", "length": len(data)}

    result = {}

    # Message ID (2 bytes)
    msg_id = (data[0] << 8) | data[1]
    result['msg_id'] = f"0x{msg_id:04X}"

    # Properties (2 bytes)
    properties = (data[2] << 8) | data[3]
    body_len = properties & 0x3FF
    result['body_length'] = body_len

    # Device ID (6 bytes)
    device_id_bytes = data[4:10]
    device_id = ''.join([f'{b:02X}' for b in device_id_bytes])
    result['device_id'] = device_id

    # Sequence (2 bytes)
    seq = (data[10] << 8) | data[11]
    result['sequence'] = seq

    # Body starts at offset 12
    body_offset = 12

    # Server IP length (1 byte)
    ip_len = data[body_offset]
    result['server_ip_length'] = ip_len

    # Server IP
    server_ip = data[body_offset+1:body_offset+1+ip_len].decode('ascii')
    result['server_ip'] = server_ip

    # Server port (2 bytes)
    port_offset = body_offset + 1 + ip_len
    server_port = (data[port_offset] << 8) | data[port_offset + 1]
    result['server_port'] = server_port

    # Skip reserved (2 bytes)
    alarm_flag_offset = port_offset + 4

    # Alarm flag (16 bytes) - CRITICAL!
    alarm_flag = data[alarm_flag_offset:alarm_flag_offset+16]
    result['alarm_flag_hex'] = alarm_flag.hex()

    # Check if all zeros
    if alarm_flag == b'\x00' * 16:
        result['alarm_flag_status'] = '✗ ALL ZEROS (BUG!)'
        result['bug_present'] = True
    else:
        result['alarm_flag_status'] = '✓ POPULATED (GOOD)'
        result['bug_present'] = False

        # Parse alarm flag components
        try:
            result['device_id_from_flag'] = alarm_flag[0:7].decode('ascii', errors='ignore')
            result['alarm_time_bcd'] = alarm_flag[7:13].hex()
            result['alarm_id'] = alarm_flag[13]
            result['alarm_type'] = alarm_flag[14]
            result['reserved'] = alarm_flag[15]
        except:
            pass

    # Alarm number (32 bytes)
    alarm_num_offset = alarm_flag_offset + 16
    alarm_number = data[alarm_num_offset:alarm_num_offset+32].rstrip(b'\x00').decode('ascii', errors='ignore')
    result['alarm_number'] = alarm_number

    return result


def analyze_log_file(log_path):
    """
    Analyze all 0x9208 messages in a log file
    """
    print(f"Analyzing log file: {log_path}\n")

    with open(log_path, 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()

    # Find all 0x9208 hex dumps
    # Pattern: lines with "9208" or "0x9208" followed by hex data
    pattern = r'(7[eE]9208[0-9a-fA-F\s]+7[eE])'

    matches = re.findall(pattern, content)

    if not matches:
        print("No 0x9208 messages found in log file")
        return []

    print(f"Found {len(matches)} 0x9208 message(s)\n")

    results = []
    for i, hex_str in enumerate(matches, 1):
        print(f"{'='*60}")
        print(f"0x9208 Message #{i}")
        print(f"{'='*60}")

        result = analyze_9208_hex(hex_str)
        if result and 'error' not in result:
            print(f"Message ID:     {result['msg_id']}")
            print(f"Device ID:      {result['device_id']}")
            print(f"Sequence:       {result['sequence']}")
            print(f"Server:         {result['server_ip']}:{result['server_port']}")
            print(f"\nAlarm Flag:     {result['alarm_flag_hex']}")
            print(f"Status:         {result['alarm_flag_status']}")

            if not result['bug_present']:
                print(f"\n  Device ID:    {result.get('device_id_from_flag', 'N/A')}")
                print(f"  Alarm Time:   {result.get('alarm_time_bcd', 'N/A')}")
                print(f"  Alarm ID:     {result.get('alarm_id', 'N/A')}")
                print(f"  Alarm Type:   {result.get('alarm_type', 'N/A')}")

            print(f"\nAlarm Number:   {result['alarm_number']}")
            results.append(result)
        else:
            print(f"Error parsing message: {result.get('error', 'Unknown error')}")

        print()

    return results


def print_summary(results):
    """Print summary of analysis"""
    if not results:
        return

    print(f"{'='*60}")
    print(f"SUMMARY")
    print(f"{'='*60}")

    total = len(results)
    bugged = sum(1 for r in results if r.get('bug_present', False))
    fixed = total - bugged

    print(f"Total 0x9208 messages:     {total}")
    print(f"  ✓ With populated flag:   {fixed}")
    print(f"  ✗ With empty flag (bug): {bugged}")
    print()

    if bugged > 0:
        print("✗ BUG STILL PRESENT!")
        print("  Fix required: DC600ProtocolDecoder.java:759")
        print("  Add: position.set(\"adasAlarmId\", mediaId);")
    else:
        print("✓ ALL 0x9208 MESSAGES HAVE POPULATED ALARM FLAGS")
        print("  Bug fix verified successfully!")

    print(f"{'='*60}\n")


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 analyze_9208.py <log_file>")
        print("\nExample:")
        print("  python3 analyze_9208.py logs/logs-2.log")
        print("\nOr analyze from stdin:")
        print("  grep '9208' logs/logs-2.log | python3 analyze_9208.py -")
        sys.exit(1)

    log_path = sys.argv[1]

    if log_path == '-':
        # Read from stdin
        print("Reading from stdin...")
        content = sys.stdin.read()
        pattern = r'(7[eE]9208[0-9a-fA-F\s]+7[eE])'
        matches = re.findall(pattern, content)

        if not matches:
            print("No 0x9208 messages found in input")
            sys.exit(1)

        results = []
        for i, hex_str in enumerate(matches, 1):
            result = analyze_9208_hex(hex_str)
            if result and 'error' not in result:
                results.append(result)
                print(f"Message #{i}: {result['alarm_flag_status']}")

        print()
        print_summary(results)
    else:
        # Analyze log file
        results = analyze_log_file(log_path)
        print_summary(results)


if __name__ == "__main__":
    main()
