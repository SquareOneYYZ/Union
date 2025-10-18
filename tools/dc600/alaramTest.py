#!/usr/bin/env python3
"""
JT/T 808 ADAS/DSM Alarm Packet Generator and Sender
Creates test packets with ADAS and DSM alarms
"""

import socket
import struct
import time
from datetime import datetime
from typing import Optional, List

class JT808PacketBuilder:
    """Build JT/T 808 location report packets with ADAS/DSM alarms"""

    def __init__(self, device_id: str = "496076898991"):
        """
        Initialize packet builder

        Args:
            device_id: 12-digit device ID (BCD encoded phone number)
        """
        self.device_id = device_id
        self.sequence = 1

    def _bcd_encode(self, number_string: str) -> bytes:
        """Convert string of digits to BCD bytes"""
        # Pad to even length
        if len(number_string) % 2 != 0:
            number_string = '0' + number_string

        result = bytearray()
        for i in range(0, len(number_string), 2):
            high = int(number_string[i])
            low = int(number_string[i+1])
            result.append((high << 4) | low)
        return bytes(result)

    def _calculate_checksum(self, data: bytes) -> int:
        """Calculate XOR checksum"""
        checksum = 0
        for byte in data:
            checksum ^= byte
        return checksum

    def _escape_packet(self, data: bytes) -> bytes:
        """Escape 0x7e and 0x7d in packet (not needed for this protocol)"""
        # JT808 doesn't require escaping between start/end markers
        return data

    def build_location_packet(
        self,
        lat: float = 43.556454,
        lon: float = 80.000940,
        speed: int = 913,  # 0.1 km/h units
        course: int = 59,
        altitude: int = 150,
        alarm: int = 0x00000000,
        status: int = 0x004C000B,
        extensions: Optional[List[tuple]] = None
    ) -> bytes:
        """
        Build a 0x0200 location report packet

        Args:
            lat: Latitude in degrees
            lon: Longitude in degrees
            speed: Speed in 0.1 km/h
            course: Course in degrees
            altitude: Altitude in meters
            alarm: Alarm flags (4 bytes)
            status: Status flags (4 bytes)
            extensions: List of (id, data) tuples for extensions

        Returns:
            Complete packet with 0x7e markers
        """

        # Build message body
        body = bytearray()

        # Alarm flags (4 bytes)
        body.extend(struct.pack('>I', alarm))

        # Status (4 bytes)
        body.extend(struct.pack('>I', status))

        # Latitude (4 bytes) - in 1/1000000 degrees
        body.extend(struct.pack('>I', int(lat * 1000000)))

        # Longitude (4 bytes)
        body.extend(struct.pack('>I', int(lon * 1000000)))

        # Altitude (2 bytes)
        body.extend(struct.pack('>H', altitude))

        # Speed (2 bytes) - 0.1 km/h
        body.extend(struct.pack('>H', speed))

        # Course (2 bytes)
        body.extend(struct.pack('>H', course))

        # Time (6 bytes BCD) - YY-MM-DD-HH-MM-SS
        now = datetime.now()
        time_bcd = self._bcd_encode(
            f"{now.year % 100:02d}{now.month:02d}{now.day:02d}"
            f"{now.hour:02d}{now.minute:02d}{now.second:02d}"
        )
        body.extend(time_bcd)

        # Add extensions
        if extensions:
            for ext_id, ext_data in extensions:
                body.append(ext_id)
                body.append(len(ext_data))
                body.extend(ext_data)

        # Build header
        header = bytearray()

        # Message ID: 0x0200 (location report)
        header.extend(struct.pack('>H', 0x0200))

        # Message properties (body length in lower 10 bits)
        props = len(body) & 0x3FF
        header.extend(struct.pack('>H', props))

        # Device ID (6 bytes BCD)
        header.extend(self._bcd_encode(self.device_id))

        # Message sequence number (2 bytes)
        header.extend(struct.pack('>H', self.sequence))
        self.sequence += 1

        # Combine header + body
        packet_data = header + body

        # Calculate checksum
        checksum = self._calculate_checksum(packet_data)

        # Build final packet: 7e + data + checksum + 7e
        packet = bytearray([0x7e])
        packet.extend(packet_data)
        packet.append(checksum)
        packet.append(0x7e)

        return bytes(packet)

    def build_adas_alarm_extension(
        self,
        alarm_type: int = 0x01,  # Forward collision
        alarm_level: int = 2,     # High risk
        vehicle_speed: int = 80,
        preceding_distance: int = 10
    ) -> tuple:
        """
        Build ADAS alarm extension (0x64)

        Returns:
            (extension_id, extension_data) tuple
        """
        data = bytearray()

        # Alarm ID (4 bytes)
        data.extend(struct.pack('>I', int(time.time()) & 0xFFFFFFFF))

        # Flag status (1 byte)
        data.append(0x00)

        # Alarm type (1 byte)
        data.append(alarm_type)

        # Alarm level (1 byte)
        data.append(alarm_level)

        # Preceding vehicle speed (1 byte)
        data.append(vehicle_speed)

        # Preceding vehicle distance (1 byte)
        data.append(preceding_distance)

        # Deviation type (1 byte)
        data.append(0x00)

        # Road sign type (1 byte)
        data.append(0x00)

        # Road sign data (1 byte)
        data.append(0x00)

        # Vehicle speed (1 byte)
        data.append(vehicle_speed)

        # Altitude (2 bytes)
        data.extend(struct.pack('>H', 150))

        # Latitude (4 bytes)
        data.extend(struct.pack('>I', int(43.556454 * 1000000)))

        # Longitude (4 bytes)
        data.extend(struct.pack('>I', int(80.000940 * 1000000)))

        # Time (6 bytes BCD)
        now = datetime.now()
        time_bcd = self._bcd_encode(
            f"{now.year % 100:02d}{now.month:02d}{now.day:02d}"
            f"{now.hour:02d}{now.minute:02d}{now.second:02d}"
        )
        data.extend(time_bcd)

        # Status (2 bytes)
        data.extend(struct.pack('>H', 0x000B))

        # Alarm identification (16 bytes)
        alarm_sign = bytes([0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF,
                           0xFE, 0xDC, 0xBA, 0x98, 0x76, 0x54, 0x32, 0x10])
        data.extend(alarm_sign)

        return (0x64, bytes(data))

    def build_dsm_alarm_extension(
        self,
        alarm_type: int = 0x01,  # Fatigue driving
        alarm_level: int = 3,     # Critical
        fatigue_level: int = 85
    ) -> tuple:
        """
        Build DSM alarm extension (0x65)

        Returns:
            (extension_id, extension_data) tuple
        """
        data = bytearray()

        # Alarm ID (4 bytes)
        data.extend(struct.pack('>I', int(time.time()) & 0xFFFFFFFF))

        # Flag status (1 byte)
        data.append(0x00)

        # Alarm type (1 byte)
        data.append(alarm_type)

        # Alarm level (1 byte)
        data.append(alarm_level)

        # Fatigue level or reserved (1 byte)
        data.append(fatigue_level if alarm_type == 0x01 else 0x00)

        # Reserved (4 bytes)
        data.extend(bytes(4))

        # Vehicle speed (1 byte)
        data.append(80)

        # Altitude (2 bytes)
        data.extend(struct.pack('>H', 150))

        # Latitude (4 bytes)
        data.extend(struct.pack('>I', int(43.556454 * 1000000)))

        # Longitude (4 bytes)
        data.extend(struct.pack('>I', int(80.000940 * 1000000)))

        # Time (6 bytes BCD)
        now = datetime.now()
        time_bcd = self._bcd_encode(
            f"{now.year % 100:02d}{now.month:02d}{now.day:02d}"
            f"{now.hour:02d}{now.minute:02d}{now.second:02d}"
        )
        data.extend(time_bcd)

        # Status (2 bytes)
        data.extend(struct.pack('>H', 0x000B))

        # Alarm identification (16 bytes)
        alarm_sign = bytes([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x11, 0x22,
                           0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0x00])
        data.extend(alarm_sign)

        return (0x65, bytes(data))


def send_packet(host: str, port: int, packet: bytes) -> bool:
    """Send packet to server"""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(10)
        sock.connect((host, port))

        print(f"📤 Sending {len(packet)} bytes...")
        print(f"   Hex: {packet.hex()}")

        sock.sendall(packet)

        # Wait for response
        response = sock.recv(1024)
        if response:
            print(f"📥 Response: {response.hex()}")

        sock.close()
        return True

    except Exception as e:
        print(f"✗ Error: {e}")
        return False


def main():
    # Configuration
    SERVER_HOST = "localhost"  # Change to your Traccar server IP
    SERVER_PORT = 5999
    DEVICE_ID = "496076898991"

    builder = JT808PacketBuilder(DEVICE_ID)

    print("="*70)
    print("JT/T 808 ADAS/DSM Alarm Packet Tester")
    print("="*70)

    tests = [
        {
            "name": "Normal Location (No Alarm)",
            "extensions": []
        },
        {
            "name": "ADAS Forward Collision (Level 2)",
            "extensions": [
                builder.build_adas_alarm_extension(
                    alarm_type=0x01,  # Forward collision
                    alarm_level=2,
                    vehicle_speed=80,
                    preceding_distance=10
                )
            ]
        },
        {
            "name": "ADAS Lane Departure (Level 1)",
            "extensions": [
                builder.build_adas_alarm_extension(
                    alarm_type=0x02,  # Lane departure
                    alarm_level=1
                )
            ]
        },
        {
            "name": "DSM Fatigue Driving (Level 3)",
            "extensions": [
                builder.build_dsm_alarm_extension(
                    alarm_type=0x01,  # Fatigue
                    alarm_level=3,
                    fatigue_level=90
                )
            ]
        },
        {
            "name": "DSM Phone Call (Level 2)",
            "extensions": [
                builder.build_dsm_alarm_extension(
                    alarm_type=0x02,  # Phone call
                    alarm_level=2
                )
            ]
        },
        {
            "name": "DSM Smoking (Level 1)",
            "extensions": [
                builder.build_dsm_alarm_extension(
                    alarm_type=0x03,  # Smoking
                    alarm_level=1
                )
            ]
        }
    ]

    for i, test in enumerate(tests, 1):
        print(f"\n{'='*70}")
        print(f"Test {i}/{len(tests)}: {test['name']}")
        print('='*70)

        packet = builder.build_location_packet(
            extensions=test['extensions']
        )

        success = send_packet(SERVER_HOST, SERVER_PORT, packet)

        if success:
            print("✓ Test passed")
        else:
            print("✗ Test failed")

        if i < len(tests):
            print("\nWaiting 2 seconds...")
            time.sleep(2)

    print("\n" + "="*70)
    print("Testing complete!")
    print("="*70)


if __name__ == "__main__":
    main()