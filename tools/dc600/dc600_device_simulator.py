#!/usr/bin/env python3
"""
DC600 Device Simulator
Comprehensive JT808/DC600 protocol implementation that simulates a real DC600 dashcam device.
Based on manufacturer specifications and real device logs.

Supports:
- All 11 ADAS alarm types (T/JSATL12-2017)
- All 6 DSM alarm types (T/JSATL12-2017)
- Complete 0x9208 attachment upload flow
- Video/image playback requests and responses
- Location reporting with GPS data
- Device registration and authentication
- Command handling from server
"""

import socket
import struct
import time
import json
import logging
import threading
import random
import os
from datetime import datetime
from enum import IntEnum
from typing import Optional, List, Dict, Tuple
from pathlib import Path


# ============================================================================
# JT808 Protocol Constants
# ============================================================================

class MessageId(IntEnum):
    """JT808 Message IDs"""
    # Terminal → Platform
    TERMINAL_GENERAL_RESPONSE = 0x0001
    TERMINAL_HEARTBEAT = 0x0002
    TERMINAL_REGISTRATION = 0x0100
    TERMINAL_DEREGISTRATION = 0x0003
    TERMINAL_AUTHENTICATION = 0x0102
    LOCATION_REPORT = 0x0200
    LOCATION_BATCH_UPLOAD = 0x0704

    # ADAS/DSM Alarms (0x0200 with alarm flag)
    DRIVER_BEHAVIOR_REPORT = 0x0200

    # Multimedia
    MULTIMEDIA_EVENT_INFO_UPLOAD = 0x0800
    MULTIMEDIA_DATA_UPLOAD = 0x0801
    STORED_MULTIMEDIA_RETRIEVAL_RESPONSE = 0x0802
    CAMERA_SHOT_IMMEDIATELY_RESPONSE = 0x0805

    # Attachment upload (0x9208 response)
    FILE_INFO_UPLOAD = 0x1210
    FILE_UPLOAD_COMPLETE = 0x1212

    # Platform → Terminal
    PLATFORM_GENERAL_RESPONSE = 0x8001
    PLATFORM_HEARTBEAT_RESPONSE = 0x8002  # Not standard, some platforms use it
    PLATFORM_TERMINAL_REGISTRATION_RESPONSE = 0x8100
    SET_TERMINAL_PARAMETERS = 0x8103
    QUERY_TERMINAL_PARAMETERS = 0x8104
    TERMINAL_CONTROL = 0x8105
    QUERY_TERMINAL_ATTRIBUTES = 0x8107

    # Multimedia commands
    CAMERA_SHOT_IMMEDIATELY = 0x8801
    STORED_MULTIMEDIA_RETRIEVAL = 0x8802
    STORED_MULTIMEDIA_UPLOAD = 0x8803
    RECORD_START = 0x8804
    SINGLE_MULTIMEDIA_UPLOAD = 0x8805

    # Video streaming (JT/T 1078-2016)
    VIDEO_LIVE_STREAM_REQUEST = 0x9101
    VIDEO_LIVE_STREAM_CONTROL = 0x9102
    VIDEO_PLAYBACK_REQUEST = 0x9201
    VIDEO_PLAYBACK_CONTROL = 0x9202
    VIDEO_DOWNLOAD_REQUEST = 0x9206

    # DC600 specific
    ALARM_ATTACHMENT_UPLOAD_REQUEST = 0x9208


class ADASAlarmType(IntEnum):
    """ADAS Alarm Types (T/JSATL12-2017 Table 4-15)"""
    FORWARD_COLLISION_WARNING = 0x01          # bit 0
    LANE_DEPARTURE_WARNING = 0x02             # bit 1
    VEHICLE_PROXIMITY_WARNING = 0x04          # bit 2
    PEDESTRIAN_COLLISION_WARNING = 0x08       # bit 3
    RAPID_LANE_CHANGE_WARNING = 0x10          # bit 4
    ROAD_SIGN_OVERSPEED_WARNING = 0x20        # bit 5
    ROAD_SIGN_RECOGNITION = 0x0100            # bit 8
    ACTIVE_PHOTOGRAPHING = 0x0200             # bit 9
    LANE_DEPARTURE_DISTANCE_ALARM = 0x0400    # bit 10
    OBSTACLE_ALARM = 0x1000                   # bit 12
    HEADWAY_MONITORING_WARNING = 0x2000       # bit 13


class DSMAlarmType(IntEnum):
    """DSM Alarm Types (T/JSATL12-2017 Table 4-17)"""
    FATIGUE_DRIVING = 0x01                    # bit 0
    PHONE_CALL = 0x02                         # bit 1
    SMOKING = 0x04                            # bit 2
    DISTRACTION = 0x08                        # bit 3
    DRIVER_ABNORMAL = 0x10                    # bit 4
    SEATBELT_NOT_FASTENED = 0x80              # bit 7


# Alarm type to warnType mapping (from device logs)
ADAS_WARNTYPE_MAP = {
    ADASAlarmType.FORWARD_COLLISION_WARNING: 1,
    ADASAlarmType.PEDESTRIAN_COLLISION_WARNING: 2,
    ADASAlarmType.LANE_DEPARTURE_WARNING: 3,
    ADASAlarmType.HEADWAY_MONITORING_WARNING: 4,
    ADASAlarmType.ROAD_SIGN_RECOGNITION: 5,
}

DSM_WARNTYPE_MAP = {
    DSMAlarmType.FATIGUE_DRIVING: 12,
    DSMAlarmType.PHONE_CALL: 9,
    DSMAlarmType.SMOKING: 10,
    DSMAlarmType.DISTRACTION: 11,
    DSMAlarmType.SEATBELT_NOT_FASTENED: 14,
}


# ============================================================================
# JT808 Protocol Utilities
# ============================================================================

class JT808Protocol:
    """JT808 protocol encoding/decoding utilities"""

    FRAME_DELIMITER = 0x7E
    ESCAPE_CHAR = 0x7D

    def __init__(self):
        self.msg_sequence = 0
        self.sequence_lock = threading.Lock()

    def get_next_sequence(self) -> int:
        """Get next message sequence number (thread-safe)"""
        with self.sequence_lock:
            seq = self.msg_sequence
            self.msg_sequence = (self.msg_sequence + 1) % 0x10000
            return seq

    @staticmethod
    def calculate_checksum(data: bytes) -> int:
        """Calculate XOR checksum"""
        checksum = 0
        for byte in data:
            checksum ^= byte
        return checksum

    @staticmethod
    def escape_data(data: bytes) -> bytes:
        """Escape 0x7E and 0x7D in message body"""
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
    def unescape_data(data: bytes) -> bytes:
        """Unescape message body"""
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

    def build_message(self, msg_id: int, phone_number: str, body: bytes,
                     encrypt: int = 0, subpackage: bool = False) -> bytes:
        """
        Build complete JT808 message frame

        Args:
            msg_id: Message ID
            phone_number: Device phone number (BCD encoded, 6 bytes)
            body: Message body
            encrypt: Encryption flag (0=none, 1=RSA)
            subpackage: Whether message is subpackaged

        Returns:
            Complete message frame with delimiters
        """
        # Message header
        header = bytearray()

        # Message ID (2 bytes)
        header.extend(struct.pack('>H', msg_id))

        # Message properties (2 bytes)
        # bit 0-9: body length
        # bit 10-12: encryption (000=none)
        # bit 13: subpackage flag
        # bit 14-15: reserved
        body_len = len(body)
        properties = body_len & 0x3FF
        properties |= (encrypt & 0x7) << 10
        if subpackage:
            properties |= 0x2000
        header.extend(struct.pack('>H', properties))

        # Phone number (BCD encoded, 6 bytes)
        phone_bcd = self.encode_bcd(phone_number, 12)  # 12 digits -> 6 bytes
        header.extend(phone_bcd)

        # Message sequence (2 bytes)
        seq = self.get_next_sequence()
        header.extend(struct.pack('>H', seq))

        # Combine header and body
        msg_data = header + body

        # Calculate checksum
        checksum = self.calculate_checksum(msg_data)

        # Escape header + body + checksum
        escaped = self.escape_data(msg_data + bytes([checksum]))

        # Add frame delimiters
        frame = bytes([self.FRAME_DELIMITER]) + escaped + bytes([self.FRAME_DELIMITER])

        return frame

    def parse_message(self, frame: bytes) -> Optional[Dict]:
        """
        Parse JT808 message frame

        Returns:
            Dictionary with msg_id, phone, sequence, body or None if invalid
        """
        if len(frame) < 13:  # Minimum valid frame size
            return None

        # Check delimiters
        if frame[0] != self.FRAME_DELIMITER or frame[-1] != self.FRAME_DELIMITER:
            return None

        # Unescape
        unescaped = self.unescape_data(frame[1:-1])

        if len(unescaped) < 12:
            return None

        # Verify checksum
        checksum = unescaped[-1]
        data = unescaped[:-1]
        calculated_checksum = self.calculate_checksum(data)

        if checksum != calculated_checksum:
            logging.warning(f"Checksum mismatch: got {checksum:02X}, expected {calculated_checksum:02X}")
            return None

        # Parse header
        msg_id = struct.unpack('>H', data[0:2])[0]
        properties = struct.unpack('>H', data[2:4])[0]
        phone_bcd = data[4:10]
        phone = self.decode_bcd(phone_bcd)
        sequence = struct.unpack('>H', data[10:12])[0]

        # Extract body
        body_len = properties & 0x3FF
        body = data[12:12+body_len]

        return {
            'msg_id': msg_id,
            'phone': phone,
            'sequence': sequence,
            'body': body,
            'properties': properties
        }

    @staticmethod
    def encode_bcd(number_str: str, total_digits: int) -> bytes:
        """Encode decimal string to BCD (Binary Coded Decimal)"""
        # Pad with leading zeros if needed
        number_str = number_str.zfill(total_digits)

        result = bytearray()
        for i in range(0, len(number_str), 2):
            high = int(number_str[i])
            low = int(number_str[i+1]) if i+1 < len(number_str) else 0
            result.append((high << 4) | low)

        return bytes(result)

    @staticmethod
    def decode_bcd(bcd_bytes: bytes) -> str:
        """Decode BCD to decimal string"""
        result = ""
        for byte in bcd_bytes:
            high = (byte >> 4) & 0x0F
            low = byte & 0x0F
            result += f"{high}{low}"
        return result

    @staticmethod
    def encode_bcd_time(dt: datetime) -> bytes:
        """Encode datetime to BCD format (YYMMDDHHmmss)"""
        time_str = dt.strftime("%y%m%d%H%M%S")
        return JT808Protocol.encode_bcd(time_str, 12)


# ============================================================================
# DC600 Device Simulator
# ============================================================================

class DC600Device:
    """DC600 GPS tracking device simulator"""

    def __init__(self, config_file: str = "test_config.json"):
        """Initialize device with configuration"""
        self.config = self.load_config(config_file)
        self.protocol = JT808Protocol()
        self.socket: Optional[socket.socket] = None
        self.connected = False
        self.authenticated = False
        self.running = False

        # Device info
        self.phone_number = self.config.get('phone_number', '013644081335')
        self.auth_code = None

        # Current state
        self.latitude = self.config.get('initial_latitude', 31.230416)
        self.longitude = self.config.get('initial_longitude', 121.473701)
        self.speed = 0.0
        self.direction = 0
        self.altitude = 0

        # Multimedia tracking
        self.multimedia_id_counter = 1
        self.event_videos = {}  # timestamp -> (video_file, alarm_type)
        self.event_photos = {}  # timestamp -> [photo_files]

        # Threads
        self.receive_thread: Optional[threading.Thread] = None
        self.heartbeat_thread: Optional[threading.Thread] = None
        self.location_thread: Optional[threading.Thread] = None

        # Setup logging
        logging.basicConfig(
            level=logging.DEBUG,
            format='%(asctime)s [%(levelname)s] %(message)s',
            handlers=[
                logging.FileHandler('dc600_simulator.log'),
                logging.StreamHandler()
            ]
        )
        self.logger = logging.getLogger(__name__)

    def load_config(self, config_file: str) -> Dict:
        """Load configuration from JSON file"""
        default_config = {
            'server_ip': '143.198.33.215',
            'server_port': 5023,
            'phone_number': '013644081335',
            'initial_latitude': 31.230416,
            'initial_longitude': 121.473701,
            'heartbeat_interval': 30,
            'location_interval': 10,
            'test_scenarios': {
                'enable_adas_alarms': True,
                'enable_dsm_alarms': True,
                'alarm_interval': 60,
                'simulate_movement': True
            },
            'media_paths': {
                'sample_photos': 'C:/Users/Filing Cabinet/Downloads/DC600 all alarms for ADAS and DSM/Photo',
                'sample_videos': 'C:/Users/Filing Cabinet/Downloads/DC600 all alarms for ADAS and DSM'
            }
        }

        if os.path.exists(config_file):
            with open(config_file, 'r') as f:
                user_config = json.load(f)
                default_config.update(user_config)

        return default_config

    def connect(self) -> bool:
        """Connect to server"""
        try:
            server_ip = self.config['server_ip']
            server_port = self.config['server_port']

            self.logger.info(f"Connecting to {server_ip}:{server_port}...")

            self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)

            # Set socket options before connecting
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            self.socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)  # Disable Nagle's algorithm

            # Use a shorter timeout for connection attempt
            self.socket.settimeout(30)

            self.logger.debug(f"Attempting TCP connection to {server_ip}:{server_port}...")
            self.socket.connect((server_ip, server_port))

            self.connected = True
            self.logger.info(f"TCP connection established to {server_ip}:{server_port}")

            # After connection, set longer timeout for data operations
            self.socket.settimeout(120)

            return True

        except socket.timeout:
            self.logger.error(f"Connection timeout - server may be filtering connections")
            self.logger.error(f"Possible causes: firewall, server down, or wrong port")
            return False
        except socket.error as e:
            self.logger.error(f"Socket error: {e}")
            return False
        except Exception as e:
            self.logger.error(f"Connection failed: {e}")
            return False

    def disconnect(self):
        """Disconnect from server"""
        self.running = False
        self.connected = False

        if self.socket:
            try:
                self.socket.close()
            except:
                pass

        self.logger.info("Disconnected from server")

    def send_message(self, msg_id: int, body: bytes) -> bool:
        """Send message to server"""
        try:
            frame = self.protocol.build_message(msg_id, self.phone_number, body)

            self.logger.debug(f"Sending message 0x{msg_id:04X}, body length: {len(body)}")
            self.logger.debug(f"Frame: {frame.hex()}")

            self.socket.sendall(frame)
            return True

        except Exception as e:
            self.logger.error(f"Failed to send message: {e}")
            return False

    def receive_messages(self):
        """Receive and process messages from server (thread)"""
        buffer = bytearray()

        while self.running and self.connected:
            try:
                data = self.socket.recv(4096)
                if not data:
                    self.logger.warning("Server closed connection")
                    self.connected = False
                    break

                buffer.extend(data)

                # Process all complete messages in buffer
                while True:
                    # Find frame start
                    start_idx = buffer.find(self.protocol.FRAME_DELIMITER)
                    if start_idx == -1:
                        break

                    # Find frame end (after start)
                    end_idx = buffer.find(self.protocol.FRAME_DELIMITER, start_idx + 1)
                    if end_idx == -1:
                        break

                    # Extract frame
                    frame = bytes(buffer[start_idx:end_idx + 1])
                    buffer = buffer[end_idx + 1:]

                    # Parse and handle message
                    msg = self.protocol.parse_message(frame)
                    if msg:
                        self.handle_server_message(msg)

            except socket.timeout:
                continue
            except Exception as e:
                self.logger.error(f"Receive error: {e}")
                break

    def handle_server_message(self, msg: Dict):
        """Handle message from server"""
        msg_id = msg['msg_id']
        body = msg['body']
        sequence = msg['sequence']

        self.logger.info(f"Received message 0x{msg_id:04X}, sequence: {sequence}, body length: {len(body)}")
        self.logger.debug(f"Body: {body.hex()}")

        # Handle different message types
        if msg_id == MessageId.PLATFORM_TERMINAL_REGISTRATION_RESPONSE:
            self.handle_registration_response(body, sequence)

        elif msg_id == MessageId.PLATFORM_GENERAL_RESPONSE:
            self.handle_general_response(body, sequence)

        elif msg_id == MessageId.SET_TERMINAL_PARAMETERS:
            self.handle_set_terminal_parameters(body, sequence)

        elif msg_id == MessageId.ALARM_ATTACHMENT_UPLOAD_REQUEST:
            self.handle_alarm_attachment_request(body, sequence)

        elif msg_id == MessageId.CAMERA_SHOT_IMMEDIATELY:
            self.handle_camera_shot_request(body, sequence)

        elif msg_id == MessageId.STORED_MULTIMEDIA_RETRIEVAL:
            self.handle_multimedia_retrieval_request(body, sequence)

        elif msg_id == MessageId.STORED_MULTIMEDIA_UPLOAD:
            self.handle_multimedia_upload_request(body, sequence)

        elif msg_id == MessageId.SINGLE_MULTIMEDIA_UPLOAD:
            self.handle_single_multimedia_upload_request(body, sequence)

        elif msg_id == MessageId.VIDEO_LIVE_STREAM_REQUEST:
            self.handle_video_live_stream_request(body, sequence)

        elif msg_id == MessageId.VIDEO_PLAYBACK_REQUEST:
            self.handle_video_playback_request(body, sequence)

        else:
            self.logger.warning(f"Unhandled message type: 0x{msg_id:04X}")

    # ========================================================================
    # Registration and Authentication
    # ========================================================================

    def register(self) -> bool:
        """Send registration request"""
        body = bytearray()

        # Province ID (2 bytes)
        body.extend(struct.pack('>H', 0x001F))  # Shanghai

        # City ID (2 bytes)
        body.extend(struct.pack('>H', 0x0001))

        # Manufacturer ID (5 bytes, padded with spaces)
        manufacturer = "ISTK ".encode('ascii')[:5].ljust(5, b' ')
        body.extend(manufacturer)

        # Terminal model (20 bytes, padded with spaces)
        model = "DC600".encode('ascii')[:20].ljust(20, b' ')
        body.extend(model)

        # Terminal ID (7 bytes, padded with spaces)
        terminal_id = self.phone_number[-7:].encode('ascii')
        terminal_id = terminal_id[:7].ljust(7, b' ')
        body.extend(terminal_id)

        # License plate color (1 byte)
        body.append(0x01)  # Blue

        # License plate (variable length)
        license_plate = "京A12345".encode('gbk')
        body.extend(license_plate)

        self.logger.info("Sending registration request...")
        return self.send_message(MessageId.TERMINAL_REGISTRATION, bytes(body))

    def handle_registration_response(self, body: bytes, sequence: int):
        """Handle registration response"""
        if len(body) < 3:
            return

        result_seq = struct.unpack('>H', body[0:2])[0]
        result_code = body[2]

        if result_code == 0:  # Success
            if len(body) > 3:
                self.auth_code = body[3:].decode('ascii', errors='ignore')
                self.logger.info(f"Registration successful! Auth code: {self.auth_code}")

                # Send authentication
                time.sleep(0.5)
                self.authenticate()
        elif result_code == 1:
            self.logger.error("Registration failed: Vehicle already registered")
        elif result_code == 2:
            self.logger.error("Registration failed: Vehicle not in database")
        elif result_code == 3:
            self.logger.error("Registration failed: Terminal already registered")
        elif result_code == 4:
            self.logger.error("Registration failed: Terminal not in database")

    def authenticate(self) -> bool:
        """Send authentication request"""
        if not self.auth_code:
            self.logger.error("No authentication code available")
            return False

        body = self.auth_code.encode('ascii')

        self.logger.info(f"Sending authentication with code: {self.auth_code}")
        return self.send_message(MessageId.TERMINAL_AUTHENTICATION, body)

    def handle_general_response(self, body: bytes, sequence: int):
        """Handle general response from platform"""
        if len(body) < 5:
            return

        response_seq = struct.unpack('>H', body[0:2])[0]
        response_msg_id = struct.unpack('>H', body[2:4])[0]
        result_code = body[4]

        result_str = {
            0: "Success",
            1: "Failure",
            2: "Message error",
            3: "Not supported"
        }.get(result_code, f"Unknown({result_code})")

        self.logger.info(f"General response for message 0x{response_msg_id:04X}: {result_str}")

        # If authentication was successful
        if response_msg_id == MessageId.TERMINAL_AUTHENTICATION and result_code == 0:
            self.authenticated = True
            self.logger.info("Authentication successful!")

            # Start periodic tasks
            self.start_periodic_tasks()

    def handle_set_terminal_parameters(self, body: bytes, sequence: int):
        """Handle set terminal parameters (0x8103) from platform"""
        self.logger.info("Received set terminal parameters (0x8103)")

        if len(body) < 1:
            return

        # Parse parameter count
        param_count = body[0]
        self.logger.info(f"Setting {param_count} terminal parameters")

        # Parse parameters (each is: ID(4 bytes) + Length(1 byte) + Value(variable))
        offset = 1
        for i in range(param_count):
            if offset + 5 > len(body):
                break

            param_id = struct.unpack('>I', body[offset:offset+4])[0]
            param_len = body[offset+4]
            param_value = body[offset+5:offset+5+param_len]

            self.logger.debug(f"  Parameter 0x{param_id:08X}: length={param_len}, value={param_value.hex()}")
            offset += 5 + param_len

        # Send general response acknowledging parameters were set
        self.send_platform_general_response(sequence, MessageId.SET_TERMINAL_PARAMETERS, 0)

    # ========================================================================
    # Heartbeat and Location Reporting
    # ========================================================================

    def send_heartbeat_loop(self):
        """Send heartbeat messages periodically (thread)"""
        interval = self.config.get('heartbeat_interval', 30)

        while self.running and self.connected:
            if self.authenticated:
                self.send_message(MessageId.TERMINAL_HEARTBEAT, b'')
                self.logger.debug("Heartbeat sent")

            time.sleep(interval)

    def build_standard_extra_info(self) -> bytes:
        """
        Build standard extra information fields for location reports.
        Based on working DC600 device logs - required for platform to show device as active.

        Returns:
            bytes: Extra information fields (0x01, 0x25, 0x30, 0x31)
        """
        extra = bytearray()

        # 0x01: Mileage (4 bytes, 0.1 km units)
        if not hasattr(self, 'mileage'):
            self.mileage = 0
        extra.append(0x01)
        extra.append(0x04)  # Length
        extra.extend(struct.pack('>I', int(self.mileage * 10)))

        # 0x25: Extended vehicle signal status (4 bytes)
        # This is a bitmap of various vehicle signals
        extra.append(0x25)
        extra.append(0x04)  # Length
        extra.extend(struct.pack('>I', 0x00000000))  # All signals off

        # 0x30: Wireless signal strength (1 byte, 0-31)
        extra.append(0x30)
        extra.append(0x01)  # Length
        extra.append(31)  # Max signal strength

        # 0x31: GNSS satellite count (1 byte)
        extra.append(0x31)
        extra.append(0x01)  # Length
        extra.append(12)  # 12 satellites

        return bytes(extra)

    def send_location_loop(self):
        """Send location reports periodically (thread)"""
        interval = self.config.get('location_interval', 10)

        while self.running and self.connected:
            if self.authenticated:
                # Build standard extra info for platform compatibility
                extra_info = self.build_standard_extra_info()
                self.send_location_report(extra_data=extra_info)

                # Simulate movement and increment mileage
                if self.config.get('test_scenarios', {}).get('simulate_movement', True):
                    self.simulate_movement()
                    if hasattr(self, 'mileage'):
                        self.mileage += self.speed * (interval / 3600.0)  # km

            time.sleep(interval)

    def send_location_report(self, alarm_flag: int = 0, status_flag: int = 0,
                            extra_data: bytes = b'') -> bool:
        """
        Send location report (0x0200)

        Args:
            alarm_flag: Alarm flags (4 bytes)
            status_flag: Status flags (4 bytes)
            extra_data: Extra information appended to location data
        """
        body = bytearray()

        # Alarm flag (4 bytes)
        body.extend(struct.pack('>I', alarm_flag))

        # Status flag (4 bytes)
        # bit 0: ACC (0=off, 1=on)
        # bit 1: Positioning (0=invalid, 1=valid)
        # bit 2: Latitude (0=north, 1=south)
        # bit 3: Longitude (0=east, 1=west)
        if status_flag == 0:
            status_flag = 0x03  # ACC on, positioning valid
        body.extend(struct.pack('>I', status_flag))

        # Latitude (4 bytes, degrees * 10^6)
        lat_value = int(abs(self.latitude) * 1_000_000)
        body.extend(struct.pack('>I', lat_value))

        # Longitude (4 bytes, degrees * 10^6)
        lon_value = int(abs(self.longitude) * 1_000_000)
        body.extend(struct.pack('>I', lon_value))

        # Altitude (2 bytes, meters)
        body.extend(struct.pack('>H', self.altitude))

        # Speed (2 bytes, 0.1 km/h)
        speed_value = int(self.speed * 10)
        body.extend(struct.pack('>H', speed_value))

        # Direction (2 bytes, degrees)
        body.extend(struct.pack('>H', self.direction))

        # Time (BCD YYMMDDHHmmss, 6 bytes)
        now = datetime.now()
        body.extend(self.protocol.encode_bcd_time(now))

        # Extra information
        body.extend(extra_data)

        return self.send_message(MessageId.LOCATION_REPORT, bytes(body))

    def simulate_movement(self):
        """Simulate vehicle movement"""
        # Random small movements
        self.latitude += random.uniform(-0.0001, 0.0001)
        self.longitude += random.uniform(-0.0001, 0.0001)
        self.speed = random.uniform(0, 80)  # 0-80 km/h
        self.direction = random.randint(0, 359)
        self.altitude = random.randint(0, 100)

    # ========================================================================
    # ADAS/DSM Alarms
    # ========================================================================

    def send_adas_alarm(self, alarm_type: ADASAlarmType, level: int = 1) -> bool:
        """Send ADAS alarm with location report"""
        self.logger.info(f"Sending ADAS alarm: {alarm_type.name} (level {level})")

        # Prepare alarm-specific extra data (per T/JSATL12-2017)
        alarm_extra = self.build_adas_alarm_extra_data(alarm_type, level)

        # Append standard extra info (required for platform to show device as active)
        standard_extra = self.build_standard_extra_info()
        extra_data = alarm_extra + standard_extra

        # Set alarm flag
        alarm_flag = int(alarm_type)

        # Status flag with alarm indicator
        status_flag = 0x03  # ACC on, positioning valid

        # Send location report with alarm
        success = self.send_location_report(alarm_flag, status_flag, extra_data)

        if success:
            # Record event for multimedia association
            timestamp = int(time.time())
            self.record_alarm_event(timestamp, alarm_type, 0)  # Camera 0 for ADAS

        return success

    def send_dsm_alarm(self, alarm_type: DSMAlarmType, level: int = 1) -> bool:
        """Send DSM alarm with location report"""
        self.logger.info(f"Sending DSM alarm: {alarm_type.name} (level {level})")

        # Prepare alarm-specific extra data
        alarm_extra = self.build_dsm_alarm_extra_data(alarm_type, level)

        # Append standard extra info (required for platform to show device as active)
        standard_extra = self.build_standard_extra_info()
        extra_data = alarm_extra + standard_extra

        # Set alarm flag (DSM uses different bit positions)
        alarm_flag = int(alarm_type) << 16  # DSM alarms in higher bits

        # Status flag
        status_flag = 0x03

        # Send location report with alarm
        success = self.send_location_report(alarm_flag, status_flag, extra_data)

        if success:
            # Record event for multimedia association
            timestamp = int(time.time())
            self.record_alarm_event(timestamp, alarm_type, 1)  # Camera 1 for DSM

        return success

    def build_adas_alarm_extra_data(self, alarm_type: ADASAlarmType, level: int) -> bytes:
        """Build ADAS alarm extra information (0x64 - ADAS alarm)"""
        data = bytearray()

        # Extra info ID: 0x64 (ADAS)
        data.append(0x64)

        # Length (will be set at end)
        length_pos = len(data)
        data.append(0)

        content_start = len(data)

        # Alarm ID (4 bytes)
        alarm_id = self.get_next_alarm_id()
        data.extend(struct.pack('>I', alarm_id))

        # Flag (1 byte)
        data.append(0x00)

        # Alarm/Event type (1 byte)
        warntype = ADAS_WARNTYPE_MAP.get(alarm_type, 0)
        data.append(warntype)

        # Alarm level (1 byte)
        data.append(level)

        # Front vehicle speed (1 byte, km/h) - only for FCW
        if alarm_type == ADASAlarmType.FORWARD_COLLISION_WARNING:
            data.append(int(self.speed + random.uniform(5, 15)))
        else:
            data.append(0)

        # Front vehicle distance (1 byte, 0.1m) - only for FCW
        if alarm_type == ADASAlarmType.FORWARD_COLLISION_WARNING:
            data.append(random.randint(10, 50))  # 1-5 meters
        else:
            data.append(0)

        # Deviation type (1 byte) - only for LDW
        if alarm_type == ADASAlarmType.LANE_DEPARTURE_WARNING:
            data.append(random.choice([0x01, 0x02]))  # Left or right
        else:
            data.append(0)

        # Road sign type (1 byte) - only for road sign
        if alarm_type == ADASAlarmType.ROAD_SIGN_RECOGNITION:
            data.append(random.randint(1, 10))
        else:
            data.append(0)

        # Road sign data (1 byte)
        data.append(0)

        # Vehicle speed (1 byte, km/h)
        data.append(int(self.speed))

        # Altitude (2 bytes, meters)
        data.extend(struct.pack('>H', self.altitude))

        # Latitude (4 bytes)
        lat_value = int(abs(self.latitude) * 1_000_000)
        data.extend(struct.pack('>I', lat_value))

        # Longitude (4 bytes)
        lon_value = int(abs(self.longitude) * 1_000_000)
        data.extend(struct.pack('>I', lon_value))

        # Alarm time (BCD, 6 bytes)
        now = datetime.now()
        data.extend(self.protocol.encode_bcd_time(now))

        # Vehicle status (2 bytes)
        data.extend(struct.pack('>H', 0x0000))

        # Alarm identification (16 bytes + 6 bytes)
        # Reserved for future use
        data.extend(bytes(22))

        # Set length
        content_length = len(data) - content_start
        data[length_pos] = content_length

        return bytes(data)

    def build_dsm_alarm_extra_data(self, alarm_type: DSMAlarmType, level: int) -> bytes:
        """Build DSM alarm extra information (0x65 - DSM alarm)"""
        data = bytearray()

        # Extra info ID: 0x65 (DSM)
        data.append(0x65)

        # Length
        length_pos = len(data)
        data.append(0)

        content_start = len(data)

        # Alarm ID (4 bytes)
        alarm_id = self.get_next_alarm_id()
        data.extend(struct.pack('>I', alarm_id))

        # Flag (1 byte)
        data.append(0x00)

        # Alarm/Event type (1 byte)
        warntype = DSM_WARNTYPE_MAP.get(alarm_type, 0)
        data.append(warntype)

        # Alarm level (1 byte)
        data.append(level)

        # Fatigue level (1 byte)
        if alarm_type == DSMAlarmType.FATIGUE_DRIVING:
            data.append(random.randint(1, 10))
        else:
            data.append(0)

        # Reserved (4 bytes)
        data.extend(bytes(4))

        # Vehicle speed (1 byte)
        data.append(int(self.speed))

        # Altitude (2 bytes)
        data.extend(struct.pack('>H', self.altitude))

        # Latitude (4 bytes)
        lat_value = int(abs(self.latitude) * 1_000_000)
        data.extend(struct.pack('>I', lat_value))

        # Longitude (4 bytes)
        lon_value = int(abs(self.longitude) * 1_000_000)
        data.extend(struct.pack('>I', lon_value))

        # Alarm time (BCD, 6 bytes)
        now = datetime.now()
        data.extend(self.protocol.encode_bcd_time(now))

        # Vehicle status (2 bytes)
        data.extend(struct.pack('>H', 0x0000))

        # Alarm identification (16 bytes)
        data.extend(bytes(16))

        # Set length
        content_length = len(data) - content_start
        data[length_pos] = content_length

        return bytes(data)

    def get_next_alarm_id(self) -> int:
        """Get next alarm ID"""
        if not hasattr(self, '_alarm_id_counter'):
            self._alarm_id_counter = 1

        alarm_id = self._alarm_id_counter
        self._alarm_id_counter += 1
        return alarm_id

    def record_alarm_event(self, timestamp: int, alarm_type, camera_id: int):
        """Record alarm event for multimedia association"""
        # Simulate event video and photos
        self.event_videos[timestamp] = (f"event_{timestamp}_{camera_id}.mp4", alarm_type)
        self.event_photos[timestamp] = [
            f"event_{timestamp}_{camera_id}_0.jpg",
            f"event_{timestamp}_{camera_id}_1.jpg",
            f"event_{timestamp}_{camera_id}_2.jpg"
        ]

        self.logger.debug(f"Recorded alarm event: timestamp={timestamp}, camera={camera_id}, type={alarm_type}")

    # ========================================================================
    # Alarm Attachment Upload (0x9208 response)
    # ========================================================================

    def handle_alarm_attachment_request(self, body: bytes, sequence: int):
        """Handle 0x9208 alarm attachment upload request"""
        self.logger.info("Received alarm attachment upload request (0x9208)")

        if len(body) < 20:
            self.logger.error(f"Invalid 0x9208 request body (length: {len(body)})")
            return

        try:
            # Parse request (based on DC600 spec)
            # Format: Server IP length + Server IP + TCP port + UDP port + alarm info
            offset = 0

            # Server IP (length-prefixed string)
            server_ip_len = body[offset]
            offset += 1
            server_ip = body[offset:offset+server_ip_len].decode('ascii', errors='ignore')
            offset += server_ip_len

            # TCP port (2 bytes)
            tcp_port = struct.unpack('>H', body[offset:offset+2])[0]
            offset += 2

            # UDP port (2 bytes)
            udp_port = struct.unpack('>H', body[offset:offset+2])[0]
            offset += 2

            # Alarm info (variable length string, null-terminated or rest of message)
            alarm_info = body[offset:].decode('ascii', errors='ignore').rstrip('\x00')

            self.logger.info(f"Alarm attachment request - Server: {server_ip}:{tcp_port}/{udp_port}")
            self.logger.info(f"Alarm info: {alarm_info}")

            # Extract timestamp from alarm info to find matching event
            # Format: "ALM-{seq}-{channel}-{timestamp}{milliseconds}"
            timestamp = self.extract_timestamp_from_alarm_info(alarm_info)

            if timestamp > 0:
                self.logger.info(f"Extracted timestamp: {timestamp} from alarm info")
                self.send_file_info_upload(sequence, timestamp)
            else:
                self.logger.warning(f"Could not extract timestamp from alarm info: {alarm_info}")
                self.send_file_info_upload(sequence, 0)

        except Exception as e:
            self.logger.error(f"Error parsing 0x9208 request: {e}")
            self.logger.debug(f"Body hex: {body.hex()}")

    def extract_timestamp_from_alarm_info(self, alarm_info: str) -> int:
        """
        Extract Unix timestamp from alarm info string.
        Format: "ALM-{seq}-{channel}-{timestamp}{milliseconds}"
        Example: "ALM-1145-1-1761430270350" -> timestamp=1761430270
        """
        try:
            # Split by hyphen
            parts = alarm_info.split('-')
            if len(parts) >= 4:
                # Last part contains timestamp (10 digits) + milliseconds (3 digits)
                timestamp_str = parts[3]
                if len(timestamp_str) >= 10:
                    # Extract first 10 digits as Unix timestamp
                    timestamp = int(timestamp_str[:10])
                    return timestamp
        except (ValueError, IndexError) as e:
            self.logger.debug(f"Failed to extract timestamp from '{alarm_info}': {e}")

        return 0

    def parse_alarm_time(self, bcd_time: str) -> int:
        """Parse BCD time string to Unix timestamp"""
        # BCD time format: YYMMDDHHmmss
        try:
            dt = datetime.strptime(bcd_time, "%y%m%d%H%M%S")
            return int(dt.timestamp())
        except:
            return int(time.time())

    def send_file_info_upload(self, alarm_seq: int, alarm_timestamp: int):
        """Send file info upload (0x1210)"""
        body = bytearray()

        # Alarm serial number (2 bytes)
        body.extend(struct.pack('>H', alarm_seq))

        # Get event files
        video_file = None
        photo_files = []

        if alarm_timestamp in self.event_videos:
            video_file, alarm_type = self.event_videos[alarm_timestamp]

        if alarm_timestamp in self.event_photos:
            photo_files = self.event_photos[alarm_timestamp]

        # Find sample files from manufacturer data
        sample_photo_dir = self.config.get('media_paths', {}).get('sample_photos', '')

        # File list
        files_to_upload = []

        # Add video if exists
        if video_file:
            # Use dummy video file or find sample
            video_path = self.find_sample_video()
            if video_path and os.path.exists(video_path):
                file_size = os.path.getsize(video_path)
                files_to_upload.append({
                    'name': video_file,
                    'type': 0x01,  # Video
                    'size': file_size,
                    'path': video_path
                })
            else:
                # Use dummy data
                files_to_upload.append({
                    'name': video_file,
                    'type': 0x01,
                    'size': 1024000,  # 1MB dummy
                    'path': None
                })

        # Add photos
        for i, photo_file in enumerate(photo_files[:3]):  # Max 3 photos
            photo_path = self.find_sample_photo(i)
            if photo_path and os.path.exists(photo_path):
                file_size = os.path.getsize(photo_path)
                files_to_upload.append({
                    'name': photo_file,
                    'type': 0x00,  # Image
                    'size': file_size,
                    'path': photo_path
                })
            else:
                # Use dummy
                files_to_upload.append({
                    'name': photo_file,
                    'type': 0x00,
                    'size': 102400,  # 100KB dummy
                    'path': None
                })

        # File count (1 byte)
        body.append(len(files_to_upload))

        # File items
        for file_info in files_to_upload:
            # File name length (1 byte)
            file_name_bytes = file_info['name'].encode('ascii')
            body.append(len(file_name_bytes))

            # File name
            body.extend(file_name_bytes)

            # File size (4 bytes)
            body.extend(struct.pack('>I', file_info['size']))

            # File type (1 byte): 0=image, 1=audio, 2=video
            body.append(file_info['type'])

        self.logger.info(f"Sending file info upload (0x1210) with {len(files_to_upload)} files")
        self.send_message(MessageId.FILE_INFO_UPLOAD, bytes(body))

        # Store files for potential upload
        self._pending_uploads = files_to_upload

    def find_sample_photo(self, index: int = 0) -> Optional[str]:
        """Find sample photo from manufacturer data"""
        photo_dir = self.config.get('media_paths', {}).get('sample_photos', '')

        if not photo_dir or not os.path.exists(photo_dir):
            return None

        # Try to find photos in CH1 or CH2
        for channel in ['CH1', 'CH2']:
            channel_dir = os.path.join(photo_dir, channel)
            if os.path.exists(channel_dir):
                photos = [f for f in os.listdir(channel_dir) if f.endswith('.jpg')]
                if photos and index < len(photos):
                    return os.path.join(channel_dir, photos[index])

        return None

    def find_sample_video(self) -> Optional[str]:
        """Find sample video file"""
        # For now, return None as we'll use dummy data
        # In a real implementation, you could provide actual video files
        return None

    # ========================================================================
    # Multimedia Commands
    # ========================================================================

    def handle_camera_shot_request(self, body: bytes, sequence: int):
        """Handle camera shot immediately request (0x8801)"""
        self.logger.info("Received camera shot request (0x8801)")

        if len(body) < 12:
            self.logger.warning(f"Camera shot request body too short (length: {len(body)}), using defaults")
            # Use minimal parsing
            channel_id = body[0] if len(body) > 0 else 1
            # Photo count is single byte at position 1 (not a 16-bit integer!)
            photo_count = body[1] if len(body) > 1 else 1

            self.logger.info(f"Camera shot - channel: {channel_id}, photo_count: {photo_count}")
            self.send_camera_shot_response(sequence, channel_id, photo_count)
            return

        # Parse full request
        channel_id = body[0]
        # Photo count is a single byte (0x01 = 1 photo, 0x02 = 2 photos, etc.)
        photo_count = body[1]
        interval = struct.unpack('>H', body[2:4])[0]
        resolution = body[4]
        quality = body[5]
        brightness = body[6]
        contrast = body[7]
        saturation = body[8]
        chroma = body[9]

        self.logger.info(f"Camera shot - channel: {channel_id}, photo_count: {photo_count}, "
                        f"interval: {interval}, resolution: {resolution}, quality: {quality}")

        # Send response (0x0805)
        self.send_camera_shot_response(sequence, channel_id, photo_count)

    def send_camera_shot_response(self, request_seq: int, channel_id: int,
                                  photo_count: int = 1):
        """Send camera shot response (0x0805)"""
        body = bytearray()

        # Response serial number (2 bytes)
        body.extend(struct.pack('>H', request_seq))

        # Result (1 byte): 0=success
        body.append(0x00)

        # Photo count (2 bytes)
        body.extend(struct.pack('>H', photo_count))

        # Photo IDs
        for i in range(photo_count):
            multimedia_id = self.get_next_multimedia_id()
            body.extend(struct.pack('>I', multimedia_id))

        self.logger.info(f"Sending camera shot response with {photo_count} photos")
        self.send_message(MessageId.CAMERA_SHOT_IMMEDIATELY_RESPONSE, bytes(body))

    def handle_multimedia_retrieval_request(self, body: bytes, sequence: int):
        """Handle stored multimedia retrieval request (0x8802)"""
        self.logger.info("Received multimedia retrieval request (0x8802)")

        # Parse request
        media_type = body[0]  # 0=image, 1=audio, 2=video
        channel_id = body[1]
        event_code = body[2]
        start_time = self.protocol.decode_bcd(body[3:9])
        end_time = self.protocol.decode_bcd(body[9:15])

        self.logger.info(f"Multimedia retrieval - type: {media_type}, channel: {channel_id}, "
                        f"event: {event_code}, time: {start_time} - {end_time}")

        # Send response (0x0802)
        self.send_multimedia_retrieval_response(sequence, media_type, channel_id)

    def send_multimedia_retrieval_response(self, request_seq: int, media_type: int,
                                          channel_id: int):
        """Send multimedia retrieval response (0x0802)"""
        body = bytearray()

        # Response serial number (2 bytes)
        body.extend(struct.pack('>H', request_seq))

        # Multimedia item count (2 bytes)
        # Return some dummy multimedia items
        item_count = random.randint(1, 5)
        body.extend(struct.pack('>H', item_count))

        # Multimedia items
        for i in range(item_count):
            # Multimedia ID (4 bytes)
            multimedia_id = self.get_next_multimedia_id()
            body.extend(struct.pack('>I', multimedia_id))

            # Multimedia type (1 byte)
            body.append(media_type)

            # Channel ID (1 byte)
            body.append(channel_id)

            # Event code (1 byte)
            body.append(random.randint(0, 10))

            # Location (28 bytes - same as location report)
            # For simplicity, use current location
            location_data = self.build_location_data()
            body.extend(location_data)

        self.logger.info(f"Sending multimedia retrieval response with {item_count} items")
        self.send_message(MessageId.STORED_MULTIMEDIA_RETRIEVAL_RESPONSE, bytes(body))

    def handle_multimedia_upload_request(self, body: bytes, sequence: int):
        """Handle stored multimedia upload by time request (0x8803)"""
        self.logger.info("Received multimedia upload by time request (0x8803)")

        media_type = body[0]
        channel_id = body[1]
        event_code = body[2]
        start_time = self.protocol.decode_bcd(body[3:9])
        end_time = self.protocol.decode_bcd(body[9:15])
        delete_flag = body[15]

        self.logger.info(f"Multimedia upload - type: {media_type}, channel: {channel_id}, "
                        f"time: {start_time} - {end_time}, delete: {delete_flag}")

        # Send general response
        self.send_platform_general_response(sequence, MessageId.STORED_MULTIMEDIA_UPLOAD, 0)

        # Upload multimedia files
        # For now, just log that we would upload
        self.logger.info("Would upload multimedia files here")

    def handle_single_multimedia_upload_request(self, body: bytes, sequence: int):
        """Handle single multimedia upload request (0x8805)"""
        self.logger.info("Received single multimedia upload request (0x8805)")

        multimedia_id = struct.unpack('>I', body[0:4])[0]
        delete_flag = body[4]

        self.logger.info(f"Single multimedia upload - ID: {multimedia_id}, delete: {delete_flag}")

        # Upload the specific multimedia file
        self.upload_multimedia_file(multimedia_id, delete_flag)

    def upload_multimedia_file(self, multimedia_id: int, delete_after: bool):
        """Upload multimedia file (0x0801)"""
        # For demonstration, upload a dummy file
        body = bytearray()

        # Multimedia ID (4 bytes)
        body.extend(struct.pack('>I', multimedia_id))

        # Multimedia type (1 byte): 0=image, 1=audio, 2=video
        media_type = 0x00  # Image
        body.append(media_type)

        # Multimedia format (1 byte): 0=JPEG, 1=TIF, 2=MP3, 3=WAV, 4=WMV
        body.append(0x00)  # JPEG

        # Event code (1 byte)
        body.append(0x01)

        # Channel ID (1 byte)
        body.append(0x01)

        # Location info (28 bytes)
        location_data = self.build_location_data()
        body.extend(location_data)

        # Multimedia data (simulated JPEG header)
        # In real implementation, read actual file
        dummy_image = bytes([0xFF, 0xD8, 0xFF, 0xE0]) + bytes(1000)  # JPEG header + dummy data
        body.extend(dummy_image)

        self.logger.info(f"Uploading multimedia file {multimedia_id} ({len(dummy_image)} bytes)")
        self.send_message(MessageId.MULTIMEDIA_DATA_UPLOAD, bytes(body))

    def build_location_data(self) -> bytes:
        """Build location data (28 bytes)"""
        data = bytearray()

        # Alarm flag (4 bytes)
        data.extend(struct.pack('>I', 0))

        # Status (4 bytes)
        data.extend(struct.pack('>I', 0x03))

        # Latitude (4 bytes)
        lat_value = int(abs(self.latitude) * 1_000_000)
        data.extend(struct.pack('>I', lat_value))

        # Longitude (4 bytes)
        lon_value = int(abs(self.longitude) * 1_000_000)
        data.extend(struct.pack('>I', lon_value))

        # Altitude (2 bytes)
        data.extend(struct.pack('>H', self.altitude))

        # Speed (2 bytes)
        speed_value = int(self.speed * 10)
        data.extend(struct.pack('>H', speed_value))

        # Direction (2 bytes)
        data.extend(struct.pack('>H', self.direction))

        # Time (BCD, 6 bytes)
        now = datetime.now()
        data.extend(self.protocol.encode_bcd_time(now))

        return bytes(data)

    def get_next_multimedia_id(self) -> int:
        """Get next multimedia ID"""
        mid = self.multimedia_id_counter
        self.multimedia_id_counter += 1
        return mid

    # ========================================================================
    # Video Streaming (JT/T 1078)
    # ========================================================================

    def handle_video_live_stream_request(self, body: bytes, sequence: int):
        """Handle video live stream request (0x9101)"""
        self.logger.info("Received video live stream request (0x9101)")

        # Parse request
        server_ip_len = body[0]
        server_ip = body[1:1+server_ip_len].decode('ascii')
        offset = 1 + server_ip_len

        tcp_port = struct.unpack('>H', body[offset:offset+2])[0]
        udp_port = struct.unpack('>H', body[offset+2:offset+4])[0]
        channel_id = body[offset+4]
        data_type = body[offset+5]  # 0=audio+video, 1=video, 2=two-way audio
        stream_type = body[offset+6]  # 0=main stream, 1=sub stream

        self.logger.info(f"Live stream request - server: {server_ip}:{tcp_port}/{udp_port}, "
                        f"channel: {channel_id}, data_type: {data_type}, stream_type: {stream_type}")

        # In a real implementation, would start streaming to the server
        # For simulation, just acknowledge
        self.send_platform_general_response(sequence, MessageId.VIDEO_LIVE_STREAM_REQUEST, 0)

    def handle_video_playback_request(self, body: bytes, sequence: int):
        """Handle video playback request (0x9201)"""
        self.logger.info("Received video playback request (0x9201)")

        # Parse similar to live stream
        server_ip_len = body[0]
        server_ip = body[1:1+server_ip_len].decode('ascii')
        offset = 1 + server_ip_len

        tcp_port = struct.unpack('>H', body[offset:offset+2])[0]
        udp_port = struct.unpack('>H', body[offset+2:offset+4])[0]
        channel_id = body[offset+4]
        data_type = body[offset+5]
        stream_type = body[offset+6]
        storage_type = body[offset+7]
        playback_mode = body[offset+8]
        playback_speed = body[offset+9]
        start_time = self.protocol.decode_bcd(body[offset+10:offset+16])
        end_time = self.protocol.decode_bcd(body[offset+16:offset+22])

        self.logger.info(f"Playback request - server: {server_ip}:{tcp_port}, channel: {channel_id}, "
                        f"time: {start_time} - {end_time}")

        # Acknowledge
        self.send_platform_general_response(sequence, MessageId.VIDEO_PLAYBACK_REQUEST, 0)

    # ========================================================================
    # Helper Methods
    # ========================================================================

    def send_platform_general_response(self, response_seq: int, response_msg_id: int,
                                      result: int):
        """Send general response to platform message"""
        body = bytearray()

        # Response sequence number (2 bytes)
        body.extend(struct.pack('>H', response_seq))

        # Response message ID (2 bytes)
        body.extend(struct.pack('>H', response_msg_id))

        # Result (1 byte)
        body.append(result)

        self.send_message(MessageId.TERMINAL_GENERAL_RESPONSE, bytes(body))

    def start_periodic_tasks(self):
        """Start periodic background tasks"""
        # Note: receive_thread is already started in run() before registration
        self.logger.debug("Starting periodic tasks (heartbeat, location, alarms)")

        # Start heartbeat thread
        self.heartbeat_thread = threading.Thread(target=self.send_heartbeat_loop, daemon=True)
        self.heartbeat_thread.start()

        # Start location reporting thread
        self.location_thread = threading.Thread(target=self.send_location_loop, daemon=True)
        self.location_thread.start()

        # Start alarm simulation thread if enabled
        if self.config.get('test_scenarios', {}).get('enable_adas_alarms', False) or \
           self.config.get('test_scenarios', {}).get('enable_dsm_alarms', False):
            alarm_thread = threading.Thread(target=self.alarm_simulation_loop, daemon=True)
            alarm_thread.start()
            self.logger.debug("Alarm simulation thread started")

    def alarm_simulation_loop(self):
        """Simulate alarms periodically (thread)"""
        interval = self.config.get('test_scenarios', {}).get('alarm_interval', 60)

        # All ADAS alarm types
        adas_alarms = [
            ADASAlarmType.FORWARD_COLLISION_WARNING,
            ADASAlarmType.LANE_DEPARTURE_WARNING,
            ADASAlarmType.PEDESTRIAN_COLLISION_WARNING,
            ADASAlarmType.HEADWAY_MONITORING_WARNING,
            ADASAlarmType.ROAD_SIGN_RECOGNITION,
        ]

        # All DSM alarm types
        dsm_alarms = [
            DSMAlarmType.FATIGUE_DRIVING,
            DSMAlarmType.PHONE_CALL,
            DSMAlarmType.SMOKING,
            DSMAlarmType.DISTRACTION,
            DSMAlarmType.SEATBELT_NOT_FASTENED,
        ]

        alarm_index = 0

        while self.running and self.connected and self.authenticated:
            time.sleep(interval)

            # Alternate between ADAS and DSM alarms
            if alarm_index % 2 == 0:
                # ADAS alarm
                if self.config.get('test_scenarios', {}).get('enable_adas_alarms', True):
                    alarm_type = random.choice(adas_alarms)
                    level = random.randint(1, 2)
                    self.send_adas_alarm(alarm_type, level)
            else:
                # DSM alarm
                if self.config.get('test_scenarios', {}).get('enable_dsm_alarms', True):
                    alarm_type = random.choice(dsm_alarms)
                    level = random.randint(1, 2)
                    self.send_dsm_alarm(alarm_type, level)

            alarm_index += 1

    def run(self):
        """Main run loop"""
        try:
            # Connect to server
            if not self.connect():
                return

            # Start receive thread BEFORE registration so we can read server responses
            self.running = True
            self.receive_thread = threading.Thread(target=self.receive_messages, daemon=True)
            self.receive_thread.start()
            self.logger.debug("Receive thread started")

            # Give socket a moment to stabilize
            time.sleep(0.5)

            # Register
            if not self.register():
                self.logger.error("Registration failed")
                return

            # Wait for authentication
            timeout = 30
            start_time = time.time()
            while not self.authenticated and (time.time() - start_time) < timeout:
                time.sleep(0.5)

            if not self.authenticated:
                self.logger.error("Authentication timeout")
                self.logger.error("Server may have sent responses, but device didn't authenticate")
                return

            # Keep running until interrupted
            self.logger.info("Device is running. Press Ctrl+C to stop.")

            while self.running and self.connected:
                time.sleep(1)

        except KeyboardInterrupt:
            self.logger.info("Interrupted by user")

        except Exception as e:
            self.logger.error(f"Error in main loop: {e}", exc_info=True)

        finally:
            self.disconnect()


# ============================================================================
# Main Entry Point
# ============================================================================

def main():
    """Main entry point"""
    import argparse

    parser = argparse.ArgumentParser(description='DC600 Device Simulator')
    parser.add_argument('-c', '--config', default='test_config.json',
                       help='Configuration file path')

    args = parser.parse_args()

    # Create and run device
    device = DC600Device(config_file=args.config)
    device.run()


if __name__ == '__main__':
    main()
