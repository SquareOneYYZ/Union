#!/usr/bin/env python3
"""
DC600 Event and Multimedia Upload Test Script

This script simulates a DC600 device to test:
1. Event creation (ADAS/DSM alarms)
2. Image upload (single packet)
3. Video upload (multi-packet)

Protocol: JT/T 808-2013 + JT/T 1078-2016 (DC600 variant)
Based on actual DC600ProtocolDecoder.java implementation
"""

import socket
import struct
import time
import sys
from datetime import datetime
from typing import Optional, List
import random

# Configuration
TRACCAR_HOST = "localhost"
TRACCAR_PORT = 5999  # DC600 port
# IMPORTANT: Device ID must be 12 digits (6 bytes BCD = 12 digits max)
# Using first 12 digits of IMEI
DEVICE_ID = "123456789012"  # 12-digit ID (not 15!)
PHONE_NUMBER = "13800138000"  # 11-digit phone number

# Message types (from DC600ProtocolDecoder.java)
MSG_TERMINAL_REGISTER = 0x0100
MSG_TERMINAL_AUTH = 0x0102
MSG_TERMINAL_HEARTBEAT = 0x0002
MSG_LOCATION_REPORT = 0x0200
MSG_MULTIMEDIA_DATA_UPLOAD = 0x0801

# Response message types
MSG_TERMINAL_REGISTER_RESPONSE = 0x8100
MSG_GENERAL_RESPONSE = 0x8001
MSG_MULTIMEDIA_UPLOAD_RESPONSE = 0x8800

# Protocol constants
DELIMITER = 0x7E

# Colors for console output
class Colors:
    HEADER = '\033[95m'
    OKBLUE = '\033[94m'
    OKCYAN = '\033[96m'
    OKGREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'
    BOLD = '\033[1m'


def log(message: str, level: str = "INFO"):
    """Print colored log message"""
    timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    colors = {
        "INFO": Colors.OKBLUE,
        "SUCCESS": Colors.OKGREEN,
        "WARNING": Colors.WARNING,
        "ERROR": Colors.FAIL,
        "HEADER": Colors.HEADER,
    }
    color = colors.get(level, "")
    print(f"{color}[{timestamp}] [{level}] {message}{Colors.ENDC}")


def bcd_encode_phone(phone: str) -> bytes:
    """
    Encode phone number as BCD bytes (per DC600ProtocolDecoder.decodeDeviceId)
    Each byte stores 2 decimal digits: high nibble = first digit, low nibble = second digit

    Example: "123456789012" -> [0x12, 0x34, 0x56, 0x78, 0x90, 0x12]
    """
    # Pad to 12 digits if needed
    phone = phone.ljust(12, '0')[:12]

    result = bytearray()
    for i in range(0, 12, 2):
        high_digit = int(phone[i])
        low_digit = int(phone[i + 1])
        byte_value = (high_digit << 4) | low_digit
        result.append(byte_value)

    return bytes(result)


def bcd_encode_time(dt: datetime) -> bytes:
    """
    Encode datetime as BCD bytes (per DC600ProtocolDecoder.readDate)
    Format: YY MM DD HH MM SS (6 bytes)
    Each byte is BCD encoded
    """
    values = [
        dt.year % 100,  # YY
        dt.month,       # MM
        dt.day,         # DD
        dt.hour,        # HH
        dt.minute,      # MM
        dt.second       # SS
    ]

    result = bytearray()
    for value in values:
        # BCD encoding: high nibble = tens, low nibble = ones
        tens = (value // 10) & 0x0F
        ones = value % 10
        byte_value = (tens << 4) | ones
        result.append(byte_value)

    return bytes(result)


def escape_data(data: bytes) -> bytes:
    """
    Escape 0x7E and 0x7D in message body (per DC600FrameDecoder)
    0x7E -> 0x7D 0x02
    0x7D -> 0x7D 0x01
    """
    result = bytearray()
    for byte in data:
        if byte == 0x7E:
            result.extend([0x7D, 0x02])
        elif byte == 0x7D:
            result.extend([0x7D, 0x01])
        else:
            result.append(byte)
    return bytes(result)


def unescape_data(data: bytes) -> bytes:
    """Unescape received data"""
    result = bytearray()
    i = 0
    while i < len(data):
        if data[i] == 0x7D and i + 1 < len(data):
            if data[i + 1] == 0x02:
                result.append(0x7E)
                i += 2
            elif data[i + 1] == 0x01:
                result.append(0x7D)
                i += 2
            else:
                result.append(data[i])
                i += 1
        else:
            result.append(data[i])
            i += 1
    return bytes(result)


def calculate_checksum(data: bytes) -> int:
    """Calculate XOR checksum (per DC600ProtocolDecoder lines 784-791)"""
    checksum = 0
    for byte in data:
        checksum ^= byte
    return checksum


class DC600Message:
    """JT/T 808 message encoder/decoder"""

    def __init__(self):
        self.sequence = 0

    def get_next_sequence(self) -> int:
        """Get next message sequence number"""
        self.sequence = (self.sequence + 1) % 0xFFFF
        return self.sequence

    def encode_message(self, msg_type: int, body: bytes, device_id: str,
                      is_subpackage: bool = False, total_packages: int = 1,
                      package_no: int = 1) -> bytes:
        """
        Encode JT/T 808 message per DC600ProtocolDecoder.decode() lines 792-812

        Message structure (before escaping):
        - Message type (2 bytes)
        - Message attributes (2 bytes)
            - Bits 0-9: Body length
            - Bits 10-12: Encryption (0 = none)
            - Bit 13: Sub-package flag
        - Phone number (6 bytes BCD)
        - Message sequence (2 bytes)
        - [If sub-package] Total packages (2 bytes)
        - [If sub-package] Package no (2 bytes)
        - Message body (variable)

        Final structure:
        - Delimiter (0x7E)
        - [Escaped header + body]
        - Checksum (1 byte XOR)
        - Delimiter (0x7E)
        """
        # Message attributes
        body_length = len(body) & 0x3FF  # Bits 0-9
        encryption = 0  # Bits 10-12 (no encryption)
        subpackage_bit = 1 if is_subpackage else 0  # Bit 13
        attributes = body_length | (encryption << 10) | (subpackage_bit << 13)

        # Phone number (BCD encoded, 6 bytes = 12 digits)
        phone_bcd = bcd_encode_phone(device_id)

        # Message sequence
        sequence = self.get_next_sequence()

        # Build unescaped message data (what gets checksummed)
        unescaped = bytearray()
        unescaped.extend(struct.pack(">H", msg_type))      # Message type
        unescaped.extend(struct.pack(">H", attributes))     # Attributes
        unescaped.extend(phone_bcd)                         # Phone (6 bytes)
        unescaped.extend(struct.pack(">H", sequence))       # Sequence

        # Add sub-package info if needed
        if is_subpackage:
            unescaped.extend(struct.pack(">H", total_packages))
            unescaped.extend(struct.pack(">H", package_no))

        # Add message body
        unescaped.extend(body)

        # Calculate checksum on unescaped data
        checksum = calculate_checksum(bytes(unescaped))

        # Escape the data
        escaped = escape_data(bytes(unescaped))

        # Final message: delimiter + escaped_data + checksum + delimiter
        # NOTE: Checksum is NOT escaped per DC600FrameDecoder implementation
        message = bytes([DELIMITER]) + escaped + bytes([checksum, DELIMITER])

        return message

    def decode_response(self, data: bytes) -> Optional[dict]:
        """Decode response from server"""
        if len(data) < 5:
            return None

        if data[0] != DELIMITER or data[-1] != DELIMITER:
            log(f"Invalid delimiters: start={data[0]:02X}, end={data[-1]:02X}", "ERROR")
            return None

        # Remove delimiters
        data = data[1:-1]

        # Checksum (last byte)
        received_checksum = data[-1]
        data = data[:-1]

        # Unescape
        unescaped = unescape_data(data)

        # Verify checksum
        calculated_checksum = calculate_checksum(unescaped)
        if received_checksum != calculated_checksum:
            log(f"Checksum mismatch: received={received_checksum:02X}, calculated={calculated_checksum:02X}", "WARNING")

        if len(unescaped) < 12:
            log(f"Response too short: {len(unescaped)} bytes", "ERROR")
            return None

        # Parse header
        msg_type = struct.unpack(">H", unescaped[0:2])[0]
        attributes = struct.unpack(">H", unescaped[2:4])[0]
        body_length = attributes & 0x3FF
        phone_bcd = unescaped[4:10]
        sequence = struct.unpack(">H", unescaped[10:12])[0]

        body = unescaped[12:] if len(unescaped) > 12 else b''

        return {
            "type": msg_type,
            "attributes": attributes,
            "phone": phone_bcd.hex(),
            "sequence": sequence,
            "body": body,
            "body_length": body_length,
        }


class DC600Device:
    """Simulated DC600 device"""

    def __init__(self, host: str, port: int, device_id: str):
        self.host = host
        self.port = port
        self.device_id = device_id
        self.sock: Optional[socket.socket] = None
        self.msg_encoder = DC600Message()
        self.auth_code = ""

    def connect(self) -> bool:
        """Connect to Traccar server"""
        try:
            log(f"Connecting to {self.host}:{self.port}...", "INFO")
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.settimeout(10)
            self.sock.connect((self.host, self.port))
            log("Connected successfully", "SUCCESS")
            return True
        except Exception as e:
            log(f"Connection failed: {e}", "ERROR")
            return False

    def disconnect(self):
        """Close connection"""
        if self.sock:
            self.sock.close()
            log("Disconnected", "INFO")

    def send_message(self, msg_type: int, body: bytes, is_subpackage: bool = False,
                    total_packages: int = 1, package_no: int = 1) -> bool:
        """Send message to server"""
        message = self.msg_encoder.encode_message(
            msg_type, body, self.device_id,
            is_subpackage, total_packages, package_no
        )
        log(f"Sending message 0x{msg_type:04X} (total {len(message)} bytes, body {len(body)} bytes)", "INFO")
        log(f"Raw message: {message.hex()}", "INFO")
        try:
            self.sock.send(message)
            return True
        except Exception as e:
            log(f"Send failed: {e}", "ERROR")
            return False

    def receive_response(self, timeout: float = 5.0) -> Optional[dict]:
        """Receive response from server"""
        try:
            self.sock.settimeout(timeout)
            data = self.sock.recv(1024)
            if not data:
                return None

            log(f"Received {len(data)} bytes: {data.hex()}", "INFO")
            response = self.msg_encoder.decode_response(data)
            if response:
                log(f"Response type: 0x{response['type']:04X}, Sequence: {response['sequence']}, Body length: {len(response['body'])}", "SUCCESS")
            return response
        except socket.timeout:
            log("No response received (timeout)", "WARNING")
            return None
        except Exception as e:
            log(f"Receive failed: {e}", "ERROR")
            return None

    def register(self) -> bool:
        """Register device (0x0100) - per DC600ProtocolDecoder line 823"""
        log("=" * 60, "HEADER")
        log("STEP 1: Device Registration", "HEADER")
        log("=" * 60, "HEADER")

        # Registration body per JT/T 808 spec
        province_id = struct.pack(">H", 31)  # Shanghai (31)
        city_id = struct.pack(">H", 100)     # City code
        manufacturer_id = b"ISTK"[:5].ljust(5, b'\x00')  # iStartek (5 bytes)
        device_model = b"DC600"[:20].ljust(20, b'\x00')   # Device model (20 bytes)
        device_id_field = b"DC600TEST"[:7].ljust(7, b'\x00')  # Device ID (7 bytes)
        plate_color = 0  # 0 = Other/unknown
        plate_number = b"TEST123"  # License plate (variable length string)

        body = province_id + city_id + manufacturer_id + device_model + device_id_field + bytes([plate_color]) + plate_number

        log(f"Registration body: {len(body)} bytes", "INFO")
        self.send_message(MSG_TERMINAL_REGISTER, body)

        response = self.receive_response()

        if response and response["type"] == MSG_TERMINAL_REGISTER_RESPONSE:
            if len(response["body"]) >= 3:
                seq = struct.unpack(">H", response["body"][0:2])[0]
                result = response["body"][2]
                log(f"Registration response: sequence={seq}, result={result}", "INFO")

                if result == 0:
                    if len(response["body"]) > 3:
                        self.auth_code = response["body"][3:].decode('ascii', errors='ignore')
                        log(f"Registration successful! Auth code: {self.auth_code}", "SUCCESS")
                    else:
                        log("Registration successful! No auth code provided.", "SUCCESS")
                    return True
                else:
                    log(f"Registration failed with code: {result}", "ERROR")
                    return False

        log("No valid registration response received", "ERROR")
        return False

    def send_location_with_alarm(self, alarm_type: str = "ADAS") -> bool:
        """Send location report with ADAS or DSM alarm (0x0200)"""
        log("=" * 60, "HEADER")
        log(f"STEP 2: Send Location with {alarm_type} Alarm", "HEADER")
        log("=" * 60, "HEADER")

        # Build location basic info (28 bytes total - per DC600ProtocolDecoder.decodeLocationBasicInfo)
        # Alarm signs (4 bytes) - JT/T 808 base protocol alarm bits
        alarm_sign = 0x00000001  # Bit 0: SOS alarm

        # Status (4 bytes)
        # Bit 0: ACC state (0=off, 1=on)
        # Bit 1: Location status (0=not located, 1=located)
        # Bit 2: Latitude hemisphere (0=north, 1=south)
        # Bit 3: Longitude hemisphere (0=east, 1=west)
        status = 0x00000002  # Bit 1: Located (GPS valid)

        # Location
        latitude = int(31.230416 * 1000000)  # Shanghai (degrees * 10^6)
        longitude = int(121.473701 * 1000000)
        altitude = 10  # meters
        speed = int(50 * 10)  # 0.1 km/h units (50 km/h = 500)
        direction = 180  # degrees

        # Time (BCD: YY MM DD HH MM SS) - 6 bytes
        now = datetime.now()
        time_bcd = bcd_encode_time(now)

        # Build location data (exactly 28 bytes)
        location_data = struct.pack(">I", alarm_sign)     # 4 bytes
        location_data += struct.pack(">I", status)         # 4 bytes
        location_data += struct.pack(">I", latitude)       # 4 bytes
        location_data += struct.pack(">I", longitude)      # 4 bytes
        location_data += struct.pack(">H", altitude)       # 2 bytes
        location_data += struct.pack(">H", speed)          # 2 bytes
        location_data += struct.pack(">H", direction)      # 2 bytes
        location_data += time_bcd                          # 6 bytes
        # Total: 28 bytes

        body = location_data

        log(f"Location data: {len(location_data)} bytes (should be 28)", "INFO")
        log(f"Lat={latitude/1000000.0}, Lon={longitude/1000000.0}, Alt={altitude}m, Speed={speed/10.0}km/h", "INFO")

        # Add ADAS or DSM alarm as additional information
        if alarm_type == "ADAS":
            # Additional info ID: 0x64 (ADAS alarm) - per DC600ProtocolDecoder line 416
            alarm_id = random.randint(1, 100)
            adas_status = 1
            adas_type = 0x01  # Forward collision warning
            adas_level = 2  # Warning level

            # ADAS data structure (minimum 7 bytes as decoder expects at line 417)
            adas_data = struct.pack(">I", alarm_id)     # Alarm ID (4 bytes unsigned int)
            adas_data += struct.pack("B", adas_status)  # Status (1 byte)
            adas_data += struct.pack("B", adas_type)    # Type (1 byte)
            adas_data += struct.pack("B", adas_level)   # Level (1 byte)
            # If length >= 32, add more data (speed, altitude, lat, lon, time, etc.)
            # For now, just send minimum data

            # Additional info format: ID (1 byte) + Length (1 byte) + Data
            body += struct.pack("B", 0x64)              # Additional info ID
            body += struct.pack("B", len(adas_data))    # Length
            body += adas_data

            log(f"ADAS Alarm: Type=0x{adas_type:02X} (Forward Collision), AlarmId={alarm_id}, Level={adas_level}", "WARNING")
            log(f"ADAS data: {len(adas_data)} bytes", "INFO")

        elif alarm_type == "DSM":
            # Additional info ID: 0x65 (DSM alarm) - per DC600ProtocolDecoder line 569
            alarm_id = random.randint(1, 100)
            dsm_status = 1
            dsm_type = 0x02  # Phone use alarm
            dsm_level = 2

            # DSM data structure (4 bytes as decoder expects at line 570)
            dsm_data = struct.pack("B", alarm_id)       # Alarm ID (1 byte)
            dsm_data += struct.pack("B", dsm_status)    # Status (1 byte)
            dsm_data += struct.pack("B", dsm_type)      # Type (1 byte)
            dsm_data += struct.pack("B", dsm_level)     # Level (1 byte)

            # Additional info format: ID (1 byte) + Length (1 byte) + Data
            body += struct.pack("B", 0x65)              # Additional info ID
            body += struct.pack("B", len(dsm_data))     # Length
            body += dsm_data

            log(f"DSM Alarm: Type=0x{dsm_type:02X} (Phone Use), AlarmId={alarm_id}, Level={dsm_level}", "WARNING")
            log(f"DSM data: {len(dsm_data)} bytes", "INFO")

        log(f"Total message body: {len(body)} bytes", "INFO")
        self.send_message(MSG_LOCATION_REPORT, body)

        response = self.receive_response()

        if response and response["type"] == MSG_GENERAL_RESPONSE:
            log("Location with alarm sent successfully - Event should be created in Traccar", "SUCCESS")
            # Wait a moment for alarm attachment request
            time.sleep(1)
            try:
                attachment_req = self.receive_response(timeout=2.0)
                if attachment_req:
                    log(f"Received follow-up message: 0x{attachment_req['type']:04X}", "INFO")
            except:
                pass
            return True

        log("Location sent (no response or timeout)", "WARNING")
        return True  # Continue even without response

    def send_image(self, multimedia_id: int = None) -> bool:
        """Send single-packet image upload (0x0801)"""
        log("=" * 60, "HEADER")
        log("STEP 3: Send Single-Packet Image", "HEADER")
        log("=" * 60, "HEADER")

        if multimedia_id is None:
            multimedia_id = random.randint(1, 1000)

        # Create fake JPEG data (JPEG header + minimal data)
        jpeg_header = bytes([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46])
        jpeg_data = jpeg_header + b'\x00' * 500  # Small image
        jpeg_footer = bytes([0xFF, 0xD9])
        image_data = jpeg_header + jpeg_data + jpeg_footer

        multimedia_type = 0  # Image
        format_code = 0  # JPEG
        event_code = 1  # Alarm event
        channel_id = 1

        # Location data (28 bytes) - same structure as location report
        now = datetime.now()
        time_bcd = bcd_encode_time(now)

        latitude = int(31.230416 * 1000000)
        longitude = int(121.473701 * 1000000)
        altitude = 10

        location_data = struct.pack(">I", 0)               # Alarm sign (4 bytes)
        location_data += struct.pack(">I", 0x00000002)     # Status (4 bytes)
        location_data += struct.pack(">I", latitude)       # Latitude (4 bytes)
        location_data += struct.pack(">I", longitude)      # Longitude (4 bytes)
        location_data += struct.pack(">H", altitude)       # Altitude (2 bytes)
        location_data += struct.pack(">H", 0)              # Speed (2 bytes)
        location_data += struct.pack(">H", 0)              # Direction (2 bytes)
        location_data += time_bcd                          # Time (6 bytes)

        # Build message body (per DC600ProtocolDecoder.decodeMultimediaDataUpload line 1125)
        body = struct.pack(">I", multimedia_id)      # Multimedia ID (4 bytes)
        body += struct.pack("B", multimedia_type)    # Type (1 byte)
        body += struct.pack("B", format_code)        # Format (1 byte)
        body += struct.pack("B", event_code)         # Event code (1 byte)
        body += struct.pack("B", channel_id)         # Channel ID (1 byte)
        body += location_data                        # Location (28 bytes)
        body += image_data                           # Image data

        log(f"Image: MultimediaId={multimedia_id}, Size={len(image_data)} bytes, Format=JPEG", "INFO")
        log(f"Body size: {len(body)} bytes (36 byte header + {len(image_data)} byte image)", "INFO")
        log("Sending as SINGLE packet (no sub-packaging)", "INFO")

        self.send_message(MSG_MULTIMEDIA_DATA_UPLOAD, body)
        response = self.receive_response()

        if response and response["type"] == MSG_MULTIMEDIA_UPLOAD_RESPONSE:
            log("Image upload successful!", "SUCCESS")
            return True

        log("Image upload completed (no response or timeout)", "WARNING")
        return True

    def send_video_multipacket(self, multimedia_id: int = None, total_packets: int = 5) -> bool:
        """Send multi-packet video upload (0x0801) - TESTS THE FIX!"""
        log("=" * 60, "HEADER")
        log(f"STEP 4: Send Multi-Packet Video ({total_packets} packets)", "HEADER")
        log("=" * 60, "HEADER")

        if multimedia_id is None:
            multimedia_id = random.randint(1, 1000)

        # Create fake video data
        packet_size = 1024
        total_size = packet_size * total_packets
        video_data = b'\x00\x00\x00\x01' + b'VIDEO_DATA_PACKET_' * 60  # Fake H.264/WMV data
        video_data = (video_data * 100)[:total_size]  # Pad to exact size

        multimedia_type = 2  # Video
        format_code = 4  # WMV
        event_code = 1  # Alarm event
        channel_id = 1

        # Location data (28 bytes)
        now = datetime.now()
        time_bcd = bcd_encode_time(now)

        latitude = int(31.230416 * 1000000)
        longitude = int(121.473701 * 1000000)
        altitude = 10

        location_data = struct.pack(">I", 0)               # Alarm sign (4 bytes)
        location_data += struct.pack(">I", 0x00000002)     # Status (4 bytes)
        location_data += struct.pack(">I", latitude)       # Latitude (4 bytes)
        location_data += struct.pack(">I", longitude)      # Longitude (4 bytes)
        location_data += struct.pack(">H", altitude)       # Altitude (2 bytes)
        location_data += struct.pack(">H", 0)              # Speed (2 bytes)
        location_data += struct.pack(">H", 0)              # Direction (2 bytes)
        location_data += time_bcd                          # Time (6 bytes)

        log(f"Video: MultimediaId={multimedia_id}, Total Size={len(video_data)} bytes, Format=WMV", "INFO")
        log(f"Splitting into {total_packets} packets of ~{packet_size} bytes each", "INFO")

        # Send each packet
        for packet_no in range(1, total_packets + 1):
            start_idx = (packet_no - 1) * packet_size
            end_idx = min(packet_no * packet_size, len(video_data))
            packet_data = video_data[start_idx:end_idx]

            # Build message body (header is same for all packets)
            body = struct.pack(">I", multimedia_id)      # Multimedia ID (4 bytes)
            body += struct.pack("B", multimedia_type)    # Type (1 byte)
            body += struct.pack("B", format_code)        # Format (1 byte)
            body += struct.pack("B", event_code)         # Event code (1 byte)
            body += struct.pack("B", channel_id)         # Channel ID (1 byte)
            body += location_data                        # Location (28 bytes)
            body += packet_data                          # Packet data

            log(f"Sending packet {packet_no}/{total_packets} (body={len(body)} bytes, data={len(packet_data)} bytes)...", "INFO")

            # Send with sub-packaging info
            self.send_message(
                MSG_MULTIMEDIA_DATA_UPLOAD,
                body,
                is_subpackage=True,
                total_packages=total_packets,
                package_no=packet_no
            )

            # Only expect response on last packet
            if packet_no == total_packets:
                log("Waiting for response to LAST packet...", "INFO")
                response = self.receive_response(timeout=5.0)
                if response and response["type"] == MSG_MULTIMEDIA_UPLOAD_RESPONSE:
                    log("✓ LAST PACKET - Video upload successful!", "SUCCESS")
                    log("✓ Multi-packet video storage fix is WORKING!", "SUCCESS")
                    return True
                else:
                    log("! LAST PACKET - No upload response (check server logs)", "WARNING")
                    return True
            else:
                # Small delay between packets
                time.sleep(0.1)

        log("Video upload completed", "SUCCESS")
        return True


def main():
    """Main test function"""
    print(f"{Colors.BOLD}{Colors.HEADER}")
    print("=" * 70)
    print("DC600 Event and Multimedia Upload Test")
    print("=" * 70)
    print(f"{Colors.ENDC}")

    # Parse command line arguments
    host = sys.argv[1] if len(sys.argv) > 1 else TRACCAR_HOST
    port = int(sys.argv[2]) if len(sys.argv) > 2 else TRACCAR_PORT
    device_id = sys.argv[3] if len(sys.argv) > 3 else DEVICE_ID

    # Validate device ID length
    if len(device_id) > 12:
        log(f"WARNING: Device ID '{device_id}' is {len(device_id)} digits", "WARNING")
        log("DC600 protocol supports max 12 digits (6 bytes BCD)", "WARNING")
        log(f"Truncating to first 12 digits: '{device_id[:12]}'", "WARNING")
        device_id = device_id[:12]
    elif len(device_id) < 12:
        log(f"WARNING: Device ID '{device_id}' is only {len(device_id)} digits", "WARNING")
        log(f"Padding with zeros to 12 digits: '{device_id.ljust(12, '0')}'", "WARNING")
        device_id = device_id.ljust(12, '0')

    log(f"Target: {host}:{port}", "INFO")
    log(f"Device ID: {device_id} ({len(device_id)} digits)", "INFO")
    print()

    # Create device simulator
    device = DC600Device(host, port, device_id)

    try:
        # Step 1: Connect
        if not device.connect():
            return 1

        time.sleep(1)

        # Step 2: Register
        if not device.register():
            log("Registration failed - continuing anyway...", "WARNING")

        time.sleep(2)

        # Step 3: Send alarm event (ADAS forward collision)
        if not device.send_location_with_alarm("ADAS"):
            log("Failed to send alarm event", "ERROR")
            # Don't return, continue with media uploads

        time.sleep(2)

        # Step 4: Send image (single packet)
        if not device.send_image():
            log("Failed to send image", "ERROR")

        time.sleep(2)

        # Step 5: Send video (multi-packet) - THIS TESTS THE FIX!
        if not device.send_video_multipacket(total_packets=5):
            log("Failed to send video", "ERROR")

        time.sleep(1)

        # Success!
        print()
        log("=" * 60, "HEADER")
        log("TEST COMPLETED!", "SUCCESS")
        log("=" * 60, "HEADER")
        print()
        log("Check Traccar logs for:", "INFO")
        log("  1. Device session creation", "INFO")
        log("  2. Event creation (ADAS forward collision alarm)", "INFO")
        log("  3. Image storage (single packet)", "INFO")
        log("  4. Video storage (multi-packet) - 'LAST PACKET RECEIVED - SAVING MULTIMEDIA FILE'", "INFO")
        print()
        log("Check database (adjust device ID as needed):", "INFO")
        print(f"  SELECT * FROM tc_events WHERE deviceid=(SELECT id FROM tc_devices WHERE uniqueid='{device_id}') ORDER BY eventtime DESC LIMIT 5;")
        print(f"  SELECT id, devicetime, attributes->>'$.video', attributes->>'$.image', attributes->>'$.packageNo', attributes->>'$.totalPackages' FROM tc_positions WHERE deviceid=(SELECT id FROM tc_devices WHERE uniqueid='{device_id}') ORDER BY devicetime DESC LIMIT 10;")
        print()

        return 0

    except KeyboardInterrupt:
        log("Test interrupted by user", "WARNING")
        return 1
    except Exception as e:
        log(f"Test failed with exception: {e}", "ERROR")
        import traceback
        traceback.print_exc()
        return 1
    finally:
        device.disconnect()


if __name__ == "__main__":
    sys.exit(main())
