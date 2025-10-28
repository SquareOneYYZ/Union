#!/usr/bin/env python3
"""
Quick DC600 Test - Single alarm test for rapid development iteration
Usage: python3 quick_test.py [server_ip] [port]
"""

import sys
import time
from test_dc600_protocol import DC600Device, ServerConfig, DeviceConfig, logger, logging

# Set logging to show more detail
logging.getLogger().setLevel(logging.INFO)


def quick_test(host="165.22.228.97", port=5999):
    """
    Quick single-alarm test
    Returns True if alarm flag is properly populated, False otherwise
    """
    print("\n" + "="*60)
    print("DC600 QUICK TEST - Alarm Flag Verification")
    print("="*60 + "\n")
    print(f"Server: {host}:{port}")
    print()

    server = ServerConfig(host=host, port=port, timeout=30)
    device_config = DeviceConfig()
    device = DC600Device(server, device_config)

    try:
        # Connect
        print("Step 1: Connecting...")
        if not device.connect():
            print("✗ FAILED: Could not connect to server")
            return False
        print("✓ Connected\n")

        time.sleep(1)

        # Authenticate
        print("Step 2: Authenticating...")
        if not device.authenticate():
            print("✗ FAILED: Authentication failed")
            device.disconnect()
            return False
        print("✓ Authenticated\n")

        time.sleep(1)

        # Send heartbeat
        print("Step 3: Sending heartbeat...")
        device.send_heartbeat()
        print()

        time.sleep(1)

        # Send alarm
        print("Step 4: Sending ALARM with 0x70 multi-media event...")
        device.send_location_report(with_alarm=True)
        print()

        time.sleep(2)

        # Listen for 0x9208
        print("Step 5: Listening for 0x9208 alarm attachment request...")
        result = device.listen_for_9208(timeout=15)
        print()

        if not result:
            print("\n" + "="*60)
            print("✗ TEST FAILED: No 0x9208 received")
            print("="*60)
            print("\nPossible causes:")
            print("1. Server didn't detect alarm (check for WORKAROUND log)")
            print("2. Server didn't send 0x9208 (check SENDING ALARM log)")
            print("3. Network issue (use tcpdump/Wireshark)")
            print("\nCheck server logs for:")
            print("  - 'WORKAROUND - Multi-media event detected'")
            print("  - 'SENDING ALARM ATTACHMENT REQUEST (0x9208)'")
            device.disconnect()
            return False

        alarm_id, alarm_type, alarm_flag = result

        # Check alarm flag
        if alarm_flag == b'\x00' * 16:
            print("\n" + "="*60)
            print("✗ TEST FAILED: Alarm flag is ALL ZEROS")
            print("="*60)
            print("\nBUG NOT FIXED!")
            print("\nRequired fix in DC600ProtocolDecoder.java:")
            print("  Line 759: position.set(\"adasAlarmId\", mediaId);")
            print("\nThis line must be added in the 0x70 workaround handler")
            print("to ensure sendAlarmAttachmentRequest() populates the alarm flag.")
            device.disconnect()
            return False

        # Success!
        print("\n" + "="*60)
        print("✓ TEST PASSED: Alarm flag is properly populated!")
        print("="*60)
        print(f"\nAlarm flag: {alarm_flag.hex()}")
        print(f"  Device ID: {alarm_flag[0:7].decode('ascii', errors='ignore')}")
        print(f"  Alarm time: {alarm_flag[7:13].hex()}")
        print(f"  Alarm ID: {alarm_flag[13]}")
        print(f"  Alarm type: {alarm_flag[14]}")
        print("\n✓ Bug fix verified - device will accept this 0x9208 request")

        # Send 0x1210 response
        print("\nStep 6: Sending 0x1210 response...")
        if device.send_1210_response(alarm_id, alarm_type):
            print("✓ 0x1210 sent successfully")
            print("\n✓ COMPLETE FLOW SUCCESSFUL!")
        else:
            print("⚠ 0x1210 sent but not acknowledged")

        device.disconnect()
        return True

    except KeyboardInterrupt:
        print("\n\nTest interrupted by user")
        device.disconnect()
        return False
    except Exception as e:
        print(f"\n✗ TEST ERROR: {e}")
        import traceback
        traceback.print_exc()
        device.disconnect()
        return False


if __name__ == "__main__":
    host = sys.argv[1] if len(sys.argv) > 1 else "165.22.228.97"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 5999

    success = quick_test(host, port)

    print("\n" + "="*60)
    print("RESULT:", "✓ PASSED" if success else "✗ FAILED")
    print("="*60 + "\n")

    sys.exit(0 if success else 1)
