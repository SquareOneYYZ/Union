#!/usr/bin/env python3
"""
DC600 Protocol Test Script
Simulates a real DC600 device based on actual device logs from 7pm session.
Tests alarm event handling and video store request flow.
"""

import socket
import time
import struct
import logging
from datetime import datetime
from typing import List, Tuple, Optional
from dataclasses import dataclass

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


@dataclass
class ServerConfig:
    """Server connection configuration"""
    host: str = "localhost"  # Change to your server IP
    port: int = 5999
    timeout: int = 30


@dataclass
class DeviceConfig:
    """Device configuration"""
    device_id: str = "496076898991"
    device_id_bytes: bytes = bytes.fromhex("496076898991")
    auth_code: str = "496076898991"


class JT808Message:
    """JT808 protocol message builder and parser"""

    DELIMITER = 0x7E

    # Message IDs
    MSG_TERMINAL_AUTH = 0x0102
    MSG_HEARTBEAT = 0x0002
    MSG_LOCATION_REPORT = 0x0200
    MSG_LOCATION_BATCH = 0x0704
    MSG_ALARM_ATTACHMENT_INFO = 0x1210

    # Server message IDs
    MSG_PLATFORM_RESPONSE = 0x8001
    MSG_ALARM_ATTACHMENT_REQUEST = 0x9208

    @staticmethod
    def calculate_checksum(data: bytes) -> int:
        """Calculate XOR checksum"""
        checksum = 0
        for byte in data:
            checksum ^= byte
        return checksum

    @staticmethod
    def unescape(data: bytes) -> bytes:
        """Unescape 0x7D sequences in received data"""
        result = bytearray()
        i = 0
        while i < len(data):
            if data[i] == 0x7D:
                if i + 1 < len(data):
                    if data[i + 1] == 0x01:
                        result.append(0x7D)
                        i += 2
                        continue
                    elif data[i + 1] == 0x02:
                        result.append(0x7E)
                        i += 2
                        continue
            result.append(data[i])
            i += 1
        return bytes(result)

    @staticmethod
    def escape(data: bytes) -> bytes:
        """Escape 0x7D and 0x7E in data to be sent"""
        result = bytearray()
        for byte in data:
            if byte == 0x7E:
                result.extend([0x7D, 0x02])
            elif byte == 0x7D:
                result.extend([0x7D, 0x01])
            else:
                result.append(byte)
        return bytes(result)

    @staticmethod
    def build_message(msg_id: int, device_id: bytes, seq: int, body: bytes = b'') -> bytes:
        """Build a complete JT808 message with checksum and escaping"""
        # Message header
        header = bytearray()
        header.extend(struct.pack('>H', msg_id))  # Message ID (2 bytes)

        # Message properties
        body_len = len(body)
        properties = body_len & 0x3FF  # Body length (bits 0-9)
        header.extend(struct.pack('>H', properties))  # Properties (2 bytes)

        # Device ID (6 bytes BCD)
        header.extend(device_id)

        # Message sequence number (2 bytes)
        header.extend(struct.pack('>H', seq))

        # Combine header and body
        data = header + body

        # Calculate checksum
        checksum = JT808Message.calculate_checksum(data)

        # Escape the data
        escaped_data = JT808Message.escape(data)

        # Build final message: delimiter + escaped_data + checksum + delimiter
        message = bytearray()
        message.append(JT808Message.DELIMITER)
        message.extend(escaped_data)
        message.append(checksum)
        message.append(JT808Message.DELIMITER)

        return bytes(message)

    @staticmethod
    def parse_message(data: bytes) -> Optional[Tuple[int, int, bytes]]:
        """
        Parse a received JT808 message
        Returns: (msg_id, seq, body) or None if invalid
        """
        if len(data) < 13:  # Minimum message length
            return None

        # Check delimiters
        if data[0] != JT808Message.DELIMITER or data[-1] != JT808Message.DELIMITER:
            logger.warning(f"Invalid delimiters: start={hex(data[0])}, end={hex(data[-1])}")
            return None

        # Extract and unescape the payload (without delimiters and checksum)
        escaped_payload = data[1:-2]
        received_checksum = data[-2]

        # Unescape
        payload = JT808Message.unescape(escaped_payload)

        # Verify checksum
        calculated_checksum = JT808Message.calculate_checksum(payload)
        if calculated_checksum != received_checksum:
            logger.warning(f"Checksum mismatch: expected {hex(calculated_checksum)}, got {hex(received_checksum)}")
            return None

        # Parse header
        if len(payload) < 12:
            return None

        msg_id = struct.unpack('>H', payload[0:2])[0]
        properties = struct.unpack('>H', payload[2:4])[0]
        body_len = properties & 0x3FF
        device_id = payload[4:10]
        seq = struct.unpack('>H', payload[10:12])[0]

        # Extract body
        body = payload[12:12+body_len] if len(payload) >= 12 + body_len else b''

        return (msg_id, seq, body)


class DC600Device:
    """Simulates a DC600 device based on actual device logs"""

    def __init__(self, server: ServerConfig, device: DeviceConfig):
        self.server = server
        self.device = device
        self.socket: Optional[socket.socket] = None
        self.seq_num = 0
        self.connected = False
        self.authenticated = False

    def connect(self) -> bool:
        """Connect to the server"""
        try:
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.socket.settimeout(self.server.timeout)
            self.socket.connect((self.server.host, self.server.port))
            self.connected = True
            logger.info(f"✓ Connected to {self.server.host}:{self.server.port}")
            return True
        except Exception as e:
            logger.error(f"✗ Connection failed: {e}")
            return False

    def disconnect(self):
        """Disconnect from server"""
        if self.socket:
            try:
                self.socket.close()
            except:
                pass
            self.socket = None
            self.connected = False
            self.authenticated = False
            logger.info("Disconnected from server")

    def get_next_seq(self) -> int:
        """Get next sequence number"""
        self.seq_num += 1
        return self.seq_num

    def send_message(self, msg_id: int, body: bytes = b'', expect_response: bool = True) -> Optional[bytes]:
        """Send a message and optionally wait for response"""
        if not self.connected:
            logger.error("Not connected to server")
            return None

        seq = self.get_next_seq()
        message = JT808Message.build_message(msg_id, self.device.device_id_bytes, seq, body)

        logger.info(f"→ Sending message 0x{msg_id:04X} (seq={seq}, len={len(message)})")
        logger.debug(f"  Hex: {message.hex()}")

        try:
            self.socket.send(message)

            if expect_response:
                return self.receive_message(timeout=5)
            return None
        except Exception as e:
            logger.error(f"✗ Send failed: {e}")
            return None

    def receive_message(self, timeout: Optional[int] = None) -> Optional[bytes]:
        """Receive a message from server"""
        if not self.connected:
            return None

        if timeout:
            self.socket.settimeout(timeout)

        try:
            # Read until we find a complete message
            buffer = bytearray()
            while True:
                chunk = self.socket.recv(1024)
                if not chunk:
                    break
                buffer.extend(chunk)

                # Check if we have a complete message
                if len(buffer) >= 13:
                    # Find delimiters
                    start_idx = buffer.find(JT808Message.DELIMITER)
                    if start_idx >= 0:
                        # Look for end delimiter
                        end_idx = buffer.find(JT808Message.DELIMITER, start_idx + 1)
                        if end_idx > start_idx:
                            # Extract message
                            message = bytes(buffer[start_idx:end_idx+1])
                            # Remove processed data from buffer
                            del buffer[start_idx:end_idx+1]

                            # Parse and log
                            parsed = JT808Message.parse_message(message)
                            if parsed:
                                msg_id, seq, body = parsed
                                logger.info(f"← Received message 0x{msg_id:04X} (seq={seq}, body_len={len(body)})")
                                logger.debug(f"  Hex: {message.hex()}")
                            else:
                                logger.warning(f"← Received invalid message")

                            return message

            return None
        except socket.timeout:
            logger.debug("Receive timeout")
            return None
        except Exception as e:
            logger.error(f"✗ Receive failed: {e}")
            return None

    def authenticate(self) -> bool:
        """Send terminal authentication message (0x0102)"""
        logger.info("=== Terminal Authentication ===")

        # Real authentication message from device log (line 89)
        # 7E 01 02 00 0C 49 60 76 89 89 91 00 01 34 39 36 30 37 36 38 39 38 39 39 31 C2 7E
        # Body: "496076898991" (auth code)
        auth_code = self.device.auth_code.encode('ascii')

        response = self.send_message(JT808Message.MSG_TERMINAL_AUTH, auth_code, expect_response=True)

        if response:
            parsed = JT808Message.parse_message(response)
            if parsed:
                msg_id, seq, body = parsed
                if msg_id == JT808Message.MSG_PLATFORM_RESPONSE:
                    if len(body) >= 3:
                        result = body[2]
                        if result == 0:
                            logger.info("✓ Authentication successful")
                            self.authenticated = True
                            return True
                        else:
                            logger.error(f"✗ Authentication failed with result code: {result}")
                            return False

        logger.error("✗ No authentication response received")
        return False

    def send_heartbeat(self) -> bool:
        """Send heartbeat message (0x0002)"""
        logger.debug("→ Sending heartbeat")

        # Real heartbeat from device log (line 97)
        # 7E 00 02 00 00 49 60 76 89 89 91 00 02 CE 7E
        # No body for heartbeat
        response = self.send_message(JT808Message.MSG_HEARTBEAT, b'', expect_response=True)

        if response:
            parsed = JT808Message.parse_message(response)
            if parsed:
                msg_id, seq, body = parsed
                if msg_id == JT808Message.MSG_PLATFORM_RESPONSE:
                    logger.debug("✓ Heartbeat acknowledged")
                    return True

        logger.warning("✗ No heartbeat response")
        return False

    def send_location_report(self, with_alarm: bool = False) -> bool:
        """Send location information report (0x0200)"""
        if with_alarm:
            logger.info("=== Sending Location Report with ALARM (0x70 multi-media event) ===")
            # Real alarm message from device log (line 652-655) with 0x70 multi-media event
            # This is a Swerve alarm with 3 photos + 1 video
            body_hex = (
                "000000000000004C000B0299B02C04C0758C0095012A007A25102407573601040000000025040000000030011531011E"
                "702F0000000000030001001C000014009602"  # 0x70 starts at byte 47 (702F = 0x70 at offset 47)
                "99B12104C0764225102407573004013000000000000025102407573000040062"
            )
            body = bytes.fromhex(body_hex)
        else:
            logger.debug("→ Sending normal location report")
            # Real normal location report from device log (line 102-103)
            body_hex = (
                "000000000000000C000B0299B25304C074CC0098000000002510240754380104000000002504000000003001163101"
                "1E"
            )
            body = bytes.fromhex(body_hex)

        response = self.send_message(JT808Message.MSG_LOCATION_REPORT, body, expect_response=True)

        if response:
            parsed = JT808Message.parse_message(response)
            if parsed:
                msg_id, seq, body = parsed
                if msg_id == JT808Message.MSG_PLATFORM_RESPONSE:
                    logger.info("✓ Location report acknowledged by server")
                    return True

        logger.warning("✗ No location report response")
        return False

    def listen_for_9208(self, timeout: int = 10) -> Optional[Tuple[int, int, bytes]]:
        """
        Listen for 0x9208 alarm attachment request from server
        Returns: (alarm_id, alarm_type, alarm_flag) or None
        """
        logger.info(f"=== Listening for 0x9208 alarm attachment request (timeout={timeout}s) ===")

        start_time = time.time()
        while time.time() - start_time < timeout:
            message = self.receive_message(timeout=2)
            if message:
                parsed = JT808Message.parse_message(message)
                if parsed:
                    msg_id, seq, body = parsed
                    if msg_id == JT808Message.MSG_ALARM_ATTACHMENT_REQUEST:
                        logger.info("✓ Received 0x9208 alarm attachment request!")
                        logger.info(f"  Body length: {len(body)} bytes")
                        logger.debug(f"  Body hex: {body.hex()}")

                        # Parse 0x9208 body according to T/JSATL12-2017
                        if len(body) >= 65:
                            # Server IP length (1 byte)
                            ip_len = body[0]
                            logger.info(f"  Server IP length: {ip_len}")

                            # Server IP string
                            server_ip = body[1:1+ip_len].decode('ascii')
                            logger.info(f"  Server IP: {server_ip}")

                            # Server port (2 bytes)
                            offset = 1 + ip_len
                            server_port = struct.unpack('>H', body[offset:offset+2])[0]
                            logger.info(f"  Server port: {server_port}")

                            # Reserved (2 bytes)
                            offset += 2

                            # Alarm flag (16 bytes) - THIS IS KEY!
                            offset += 2
                            alarm_flag = body[offset:offset+16]
                            logger.info(f"  Alarm flag (16 bytes): {alarm_flag.hex()}")

                            # Check if alarm flag is all zeros (BUG!)
                            if alarm_flag == b'\x00' * 16:
                                logger.error("  ✗ ALARM FLAG IS ALL ZEROS - Device will ignore this request!")
                                logger.error("  ✗ Server bug: adasAlarmId/dsmAlarmId attribute not set")
                                return None
                            else:
                                # Parse alarm flag
                                device_id_str = alarm_flag[0:7].decode('ascii', errors='ignore')
                                alarm_time_bcd = alarm_flag[7:13]
                                alarm_id = alarm_flag[13]
                                alarm_type = alarm_flag[14]
                                logger.info(f"  Device ID: {device_id_str}")
                                logger.info(f"  Alarm time (BCD): {alarm_time_bcd.hex()}")
                                logger.info(f"  Alarm ID: {alarm_id}")
                                logger.info(f"  Alarm type: {alarm_type}")

                                # Alarm number (32 bytes)
                                offset += 16
                                alarm_number = body[offset:offset+32].rstrip(b'\x00').decode('ascii', errors='ignore')
                                logger.info(f"  Alarm number: {alarm_number}")

                                return (alarm_id, alarm_type, alarm_flag)
                        else:
                            logger.error(f"  ✗ Invalid 0x9208 body length: {len(body)} < 65")
                            return None

        logger.error("✗ TIMEOUT: No 0x9208 received within timeout period")
        return None

    def send_1210_response(self, alarm_id: int, alarm_type: int) -> bool:
        """
        Send 0x1210 alarm attachment information message
        This tells the server what media files are available for upload
        """
        logger.info("=== Sending 0x1210 Alarm Attachment Information ===")

        # Build 0x1210 body according to T/JSATL12-2017 Table 4-21
        body = bytearray()

        # Terminal ID (7 bytes BCD) - device ID
        body.extend(self.device.device_id.ljust(7, '0').encode('ascii')[:7])

        # Alarm flag (16 bytes) - same as received in 0x9208
        body.extend(b'\x00' * 16)  # Simplified for testing

        # Alarm serial number (32 bytes)
        alarm_num = f"ALM-{self.device.device_id}-{alarm_id}".encode('ascii')
        body.extend(alarm_num.ljust(32, b'\x00')[:32])

        # Reserved (8 bytes)
        body.extend(b'\x00' * 8)

        # File info list
        # Count (1 byte) - 4 files (3 photos + 1 video)
        body.append(4)

        # File 1: CH1IMG20251024-075730-0.jpg
        body.extend(self._build_file_info(
            file_name="CH1IMG20251024-075730-0.jpg",
            file_size=50000,
            media_type=0,  # Image
            channel_id=1
        ))

        # File 2: CH1IMG20251024-075730-1.jpg
        body.extend(self._build_file_info(
            file_name="CH1IMG20251024-075730-1.jpg",
            file_size=50000,
            media_type=0,
            channel_id=1
        ))

        # File 3: CH1IMG20251024-075730-2.jpg
        body.extend(self._build_file_info(
            file_name="CH1IMG20251024-075730-2.jpg",
            file_size=50000,
            media_type=0,
            channel_id=1
        ))

        # File 4: CH1EVT20251024-075730-0.mp4
        body.extend(self._build_file_info(
            file_name="CH1EVT20251024-075730-0.mp4",
            file_size=500000,
            media_type=1,  # Video
            channel_id=1
        ))

        response = self.send_message(JT808Message.MSG_ALARM_ATTACHMENT_INFO, bytes(body), expect_response=True)

        if response:
            parsed = JT808Message.parse_message(response)
            if parsed:
                msg_id, seq, resp_body = parsed
                if msg_id == JT808Message.MSG_PLATFORM_RESPONSE:
                    logger.info("✓ 0x1210 acknowledged - Video upload flow initiated!")
                    return True

        logger.warning("✗ No 0x1210 response from server")
        return False

    def _build_file_info(self, file_name: str, file_size: int, media_type: int, channel_id: int) -> bytes:
        """Build file info structure for 0x1210"""
        info = bytearray()

        # File name length (1 byte)
        info.append(len(file_name))

        # File name
        info.extend(file_name.encode('ascii'))

        # File size (4 bytes)
        info.extend(struct.pack('>I', file_size))

        # Media type (1 byte): 0=Image, 1=Audio, 2=Video
        info.append(media_type)

        # Channel ID (1 byte)
        info.append(channel_id)

        # Event code (1 byte) - alarm type
        info.append(0x00)

        return bytes(info)


def run_test_scenario_1(device: DC600Device) -> bool:
    """
    Test Scenario 1: Normal flow with alarm
    1. Connect and authenticate
    2. Send normal location reports
    3. Send alarm with 0x70 multi-media event
    4. Listen for 0x9208 request
    5. Send 0x1210 response if 0x9208 received
    """
    logger.info("=" * 60)
    logger.info("TEST SCENARIO 1: Normal Alarm Flow with 0x70 Multi-media Event")
    logger.info("=" * 60)

    # Step 1: Connect
    if not device.connect():
        return False

    time.sleep(1)

    # Step 2: Authenticate
    if not device.authenticate():
        device.disconnect()
        return False

    time.sleep(1)

    # Step 3: Send heartbeat
    device.send_heartbeat()
    time.sleep(1)

    # Step 4: Send normal location reports
    logger.info("Sending normal location reports...")
    for i in range(2):
        device.send_location_report(with_alarm=False)
        time.sleep(2)

    # Step 5: Send alarm location report with 0x70 multi-media event
    logger.info("Triggering ALARM (Swerve with 0x70 multi-media event)...")
    device.send_location_report(with_alarm=True)

    # Step 6: Listen for 0x9208 alarm attachment request
    time.sleep(1)
    result = device.listen_for_9208(timeout=15)

    if result:
        alarm_id, alarm_type, alarm_flag = result
        logger.info("✓ TEST PASSED: Received valid 0x9208 with populated alarm flag")

        # Step 7: Send 0x1210 response
        time.sleep(1)
        if device.send_1210_response(alarm_id, alarm_type):
            logger.info("✓ TEST PASSED: Complete flow successful")
            device.disconnect()
            return True
        else:
            logger.error("✗ TEST FAILED: 0x1210 not acknowledged")
            device.disconnect()
            return False
    else:
        logger.error("✗ TEST FAILED: No valid 0x9208 received")
        logger.error("   Possible causes:")
        logger.error("   1. Server did not send 0x9208 (bug in alarm detection)")
        logger.error("   2. 0x9208 alarm flag was all zeros (bug in sendAlarmAttachmentRequest)")
        logger.error("   3. Network issue")
        device.disconnect()
        return False


def run_test_scenario_2(device: DC600Device) -> bool:
    """
    Test Scenario 2: Multiple alarms
    Tests handling of multiple concurrent alarms
    """
    logger.info("=" * 60)
    logger.info("TEST SCENARIO 2: Multiple Concurrent Alarms")
    logger.info("=" * 60)

    if not device.connect():
        return False

    if not device.authenticate():
        device.disconnect()
        return False

    time.sleep(1)

    # Send multiple alarms in quick succession
    logger.info("Sending 3 alarms in quick succession...")
    for i in range(3):
        logger.info(f"Alarm {i+1}/3")
        device.send_location_report(with_alarm=True)
        time.sleep(2)

    # Listen for all 0x9208 requests
    logger.info("Listening for 0x9208 requests (should receive 3)...")
    requests_received = 0
    for i in range(3):
        result = device.listen_for_9208(timeout=10)
        if result:
            requests_received += 1
            alarm_id, alarm_type, alarm_flag = result
            logger.info(f"✓ Received 0x9208 #{requests_received} for alarm ID {alarm_id}")
        else:
            break

    logger.info(f"Total 0x9208 requests received: {requests_received}/3")

    device.disconnect()
    return requests_received == 3


def run_test_scenario_3(device: DC600Device) -> bool:
    """
    Test Scenario 3: Verify alarm flag population bug fix
    Specifically tests that the alarm flag is NOT all zeros
    """
    logger.info("=" * 60)
    logger.info("TEST SCENARIO 3: Alarm Flag Population Verification")
    logger.info("=" * 60)

    if not device.connect():
        return False

    if not device.authenticate():
        device.disconnect()
        return False

    time.sleep(1)

    # Send alarm
    device.send_location_report(with_alarm=True)

    # Listen for 0x9208
    result = device.listen_for_9208(timeout=15)

    if result:
        alarm_id, alarm_type, alarm_flag = result

        # Check if alarm flag is populated
        if alarm_flag == b'\x00' * 16:
            logger.error("✗ TEST FAILED: Alarm flag is all zeros!")
            logger.error("   BUG NOT FIXED: DC600ProtocolDecoder.java:759 adasAlarmId not set")
            device.disconnect()
            return False
        else:
            logger.info("✓ TEST PASSED: Alarm flag is properly populated!")
            logger.info(f"   Alarm flag: {alarm_flag.hex()}")
            device.disconnect()
            return True
    else:
        logger.error("✗ TEST FAILED: No 0x9208 received")
        device.disconnect()
        return False


def main():
    """Main test runner"""
    print("\n" + "=" * 60)
    print("DC600 PROTOCOL TEST SUITE")
    print("Simulating real device behavior from 7pm session logs")
    print("=" * 60 + "\n")

    # Configuration
    server = ServerConfig(
        host="165.22.228.97",  # Change to your server
        port=5999,
        timeout=30
    )

    device_config = DeviceConfig()

    print(f"Server: {server.host}:{server.port}")
    print(f"Device: {device_config.device_id}")
    print()

    # Run tests
    results = {}

    # Test 1: Normal flow
    device = DC600Device(server, device_config)
    results["Scenario 1: Normal Alarm Flow"] = run_test_scenario_1(device)
    time.sleep(3)

    # Test 2: Multiple alarms
    device = DC600Device(server, device_config)
    results["Scenario 2: Multiple Alarms"] = run_test_scenario_2(device)
    time.sleep(3)

    # Test 3: Alarm flag bug verification
    device = DC600Device(server, device_config)
    results["Scenario 3: Alarm Flag Verification"] = run_test_scenario_3(device)

    # Print summary
    print("\n" + "=" * 60)
    print("TEST RESULTS SUMMARY")
    print("=" * 60)
    for test_name, passed in results.items():
        status = "✓ PASSED" if passed else "✗ FAILED"
        print(f"{test_name}: {status}")

    total = len(results)
    passed = sum(results.values())
    print(f"\nTotal: {passed}/{total} tests passed")
    print("=" * 60 + "\n")

    return all(results.values())


if __name__ == "__main__":
    import sys
    success = main()
    sys.exit(0 if success else 1)
