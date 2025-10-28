#!/usr/bin/env python3
"""
Simple connection test for DC600 server
Tests basic TCP connectivity before running the full simulator
"""

import socket
import sys

def test_connection(host, port):
    """Test basic TCP connection"""
    print(f"Testing connection to {host}:{port}...")

    try:
        # Create socket
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(10)

        # Try to connect
        print(f"Attempting to connect...")
        sock.connect((host, port))

        print(f"✓ TCP connection successful!")
        print(f"  Local address: {sock.getsockname()}")
        print(f"  Remote address: {sock.getpeername()}")

        # Try to receive any data the server might send
        sock.settimeout(5)
        try:
            data = sock.recv(1024)
            if data:
                print(f"✓ Server sent data: {data.hex()}")
            else:
                print("  Server didn't send any initial data (normal for JT808)")
        except socket.timeout:
            print("  No data received in 5 seconds (normal for JT808)")

        sock.close()
        print("\n✓ Connection test PASSED")
        return True

    except socket.timeout:
        print(f"✗ Connection timed out after 10 seconds")
        print(f"  The server may be down or not listening on port {port}")
        return False

    except ConnectionRefusedError:
        print(f"✗ Connection refused")
        print(f"  The server is not accepting connections on port {port}")
        return False

    except socket.error as e:
        print(f"✗ Socket error: {e}")
        return False

    except Exception as e:
        print(f"✗ Unexpected error: {e}")
        return False

if __name__ == '__main__':
    if len(sys.argv) == 3:
        host = sys.argv[1]
        port = int(sys.argv[2])
    else:
        # Default from config
        host = "device.istarmap.com"
        port = 9092

    success = test_connection(host, port)
    sys.exit(0 if success else 1)
