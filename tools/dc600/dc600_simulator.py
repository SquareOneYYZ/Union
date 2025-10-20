#!/usr/bin/env python3
"""
DC600 Protocol Simulator
========================
Simulates a DC600 vehicle terminal device with full ADAS/DSM alarm capabilities.

Based on:
- JT/T 808-2011 (GPRS Communication Protocol)
- JT/T 1078-2016 (Video Communication Protocol)
- T/JSATL12-2017 (Active Safety Protocol)

Author: Generated for DC600 Protocol Testing
Date: 2024
"""

import socket
import struct
import time
import logging
import random
import threading
from enum import IntEnum
from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any
from pathlib import Path
from datetime import datetime
import json

# ============================================================================
# LOGGING CONFIGURATION
# ============================================================================

logging.basicConfig(
    level=logging.DEBUG,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler('dc600_simulator.log'),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# ============================================================================
# PROTOCOL CONSTANTS
# ============================================================================

class MessageID(IntEnum):
    """JT/T 808-2011 Message IDs"""
    # Terminal → Platform
    TERMINAL_GENERAL_RESPONSE = 0x0001
    TERMINAL_HEARTBEAT = 0x0002
    TERMINAL_REGISTRATION = 0x0100
    TERMINAL_AUTHENTICATION = 0x0102
    QUERY_PARAMETER_RESPONSE = 0x0104
    QUERY_ATTRIBUTE_RESPONSE = 0x0107
    TERMINAL_UPGRADE_RESULT = 0x0108
    LOCATION_REPORT = 0x0200
    LOCATION_BATCH_UPLOAD = 0x0704
    MULTIMEDIA_EVENT_INFO = 0x0800
    MULTIMEDIA_DATA_UPLOAD = 0x0801
    MULTIMEDIA_QUERY_RESPONSE = 0x0802
    CAMERA_RESPONSE = 0x0805

    # Platform → Terminal
    PLATFORM_GENERAL_RESPONSE = 0x8001
    TERMINAL_REGISTRATION_RESPONSE = 0x8100
    SET_TERMINAL_PARAMETERS = 0x8103
    QUERY_TERMINAL_PARAMETERS = 0x8104
    QUERY_SPECIFIC_PARAMETERS = 0x8106
    QUERY_TERMINAL_ATTRIBUTES = 0x8107
    TERMINAL_UPGRADE_PACKAGE = 0x8108
    MANUAL_ALARM_CONFIRM = 0x8203
    TEXT_MESSAGE_DOWN = 0x8300
    SET_CIRCLE_AREA = 0x8600
    DELETE_CIRCLE_AREA = 0x8601
    SET_RECTANGLE_AREA = 0x8602
    DELETE_RECTANGLE_AREA = 0x8603
    SET_POLYGON_AREA = 0x8604
    DELETE_POLYGON_AREA = 0x8605
    SET_ROUTE = 0x8606
    DELETE_ROUTE = 0x8607
    MULTIMEDIA_UPLOAD_RESPONSE = 0x8800
    CAMERA_COMMAND = 0x8801
    QUERY_MULTIMEDIA = 0x8802
    STORE_MULTIMEDIA_UPLOAD = 0x8803
    SINGLE_MULTIMEDIA_UPLOAD = 0x8805

    # JT/T 1078-2016 Video Messages
    VIDEO_REALTIME_REQUEST = 0x9101
    VIDEO_REALTIME_CONTROL = 0x9102
    VIDEO_PLAYBACK_REQUEST = 0x9201
    VIDEO_PLAYBACK_CONTROL = 0x9202
    VIDEO_FILE_UPLOAD_CMD = 0x9206

    # T/JSATL12-2017 Active Safety Messages
    ALARM_ATTACHMENT_UPLOAD_CMD = 0x9208
    ALARM_ATTACHMENT_INFO = 0x1210
    FILE_INFO_UPLOAD = 0x1211
    FILE_UPLOAD_COMPLETE = 0x1212
    FILE_UPLOAD_COMPLETE_RESPONSE = 0x9212


class AlarmBit(IntEnum):
    """JT/T 808 Table 24: Alarm flag bit definitions"""
    EMERGENCY = 0
    OVER_SPEED = 1
    DRIVING_MALFUNCTION = 2
    RISK_WARNING = 3
    OVER_SPEED_WARNING = 13
    FATIGUE_DRIVING_WARNING = 14
    ACCUMULATED_OVER_SPEED = 18
    TIMEOUT_PARKING = 19
    ENTER_EXIT_AREA = 20
    ENTER_EXIT_ROUTE = 21
    ROUTE_TIME_INSUFFICIENT = 22
    OFF_TRACK = 23


class StatusBit(IntEnum):
    """JT/T 808 Table 25: Status bit definitions"""
    ACC_ON = 0
    POSITIONED = 1
    SOUTH_LATITUDE = 2
    WEST_LONGITUDE = 3


class ADASAlarmType(IntEnum):
    """T/JSATL12 Table 4-15: ADAS Alarm Types"""
    FORWARD_COLLISION = 0x01
    LANE_DEPARTURE = 0x02
    VEHICLE_TOO_CLOSE = 0x03
    PEDESTRIAN_COLLISION = 0x04
    FREQUENT_LANE_CHANGE = 0x05
    ROAD_SIGN_OUT_OF_LIMIT = 0x06
    OBSTACLE = 0x07
    ROAD_SIGN_RECOGNITION = 0x10
    ACTIVE_CAPTURE = 0x11


class DSMAlarmType(IntEnum):
    """T/JSATL12 Table 4-17: DSM Alarm Types"""
    FATIGUE_DRIVING = 0x01
    CALLING = 0x02
    SMOKING = 0x03
    DISTRACTED_DRIVING = 0x04
    DRIVER_ABNORMAL = 0x05
    AUTO_CAPTURE = 0x10
    DRIVER_CHANGE = 0x11


class AlarmLevel(IntEnum):
    """Alarm severity levels"""
    FIRST_LEVEL = 0x01
    SECOND_LEVEL = 0x02


# ============================================================================
# DATA STRUCTURES
# ============================================================================

@dataclass
class DeviceConfig:
    """Device configuration"""
    terminal_id: str = "1234567"  # 7-byte terminal ID
    phone_number: str = "013800000000"  # 12-digit phone number (BCD[6])
    province_id: int = 0x0031  # Province code (e.g., 31 for Shanghai)
    city_id: int = 0x0115  # City code
    manufacturer_id: str = "ISTRT"  # 5-byte manufacturer code
    terminal_model: str = "DC600"  # 20-byte model
    terminal_type: int = 0x0041  # Terminal type flags
    firmware_version: str = "1.0.0"
    hardware_version: str = "1.0.0"

    server_ip: str = "127.0.0.1"
    server_port: int = 8888

    # Authentication code (received after registration)
    auth_code: str = ""

    # Message serial number
    msg_seq: int = 0

    # Alarm ID counter
    alarm_id: int = 0


@dataclass
class LocationData:
    """GPS location data"""
    alarm_flag: int = 0
    status_flag: int = 0x0003  # ACC on + positioned
    latitude: float = 31.230416  # Shanghai, China
    longitude: float = 121.473701
    altitude: int = 10  # meters
    speed: int = 0  # 1/10 km/h
    direction: int = 0  # 0-359 degrees
    timestamp: Optional[datetime] = None

    # Additional information
    mileage: Optional[int] = None  # 1/10 km
    signal_strength: Optional[int] = None  # 0-255
    satellite_count: Optional[int] = None  # number of satellites

    def __post_init__(self):
        if self.timestamp is None:
            self.timestamp = datetime.now()


@dataclass
class ADASAlarmData:
    """ADAS alarm data structure (Table 4-15)"""
    alarm_id: int
    flag_status: int  # 0x00=unavailable, 0x01=start, 0x02=end
    alarm_type: ADASAlarmType
    alarm_level: AlarmLevel
    preceding_vehicle_speed: int = 0  # km/h, 0-250
    vehicle_distance: int = 0  # ms, 0-100
    deviation_type: int = 0  # 0x01=left, 0x02=right (for lane departure)
    road_sign_type: int = 0  # 0x01=speed, 0x02=height, 0x03=weight
    road_sign_data: int = 0
    speed: int = 60  # km/h
    altitude: int = 10  # meters
    latitude: float = 31.230416
    longitude: float = 121.473701
    timestamp: datetime = field(default_factory=datetime.now)
    vehicle_status: int = 0x0003
    terminal_id: str = "1234567"
    serial_number: int = 0
    attachment_count: int = 1  # number of attachments


@dataclass
class DSMAlarmData:
    """DSM alarm data structure (Table 4-17)"""
    alarm_id: int
    flag_status: int
    alarm_type: DSMAlarmType
    alarm_level: AlarmLevel
    fatigue_level: int = 0  # 1-10 (for fatigue alarms)
    speed: int = 60  # km/h
    altitude: int = 10
    latitude: float = 31.230416
    longitude: float = 121.473701
    timestamp: datetime = field(default_factory=datetime.now)
    vehicle_status: int = 0x0003
    terminal_id: str = "1234567"
    serial_number: int = 0
    attachment_count: int = 1


# ============================================================================
# PROTOCOL UTILITIES
# ============================================================================

class JT808Protocol:
    """JT/T 808 protocol encoding/decoding utilities"""

    FLAG = 0x7E

    @staticmethod
    def escape_data(data: bytes) -> bytes:
        """Escape 0x7e and 0x7d in data"""
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
        """Unescape data"""
        result = bytearray()
        i = 0
        while i < len(data):
            if data[i] == 0x7D:
                if i + 1 < len(data):
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
            else:
                result.append(data[i])
                i += 1
        return bytes(result)

    @staticmethod
    def calculate_checksum(data: bytes) -> int:
        """Calculate XOR checksum"""
        checksum = 0
        for byte in data:
            checksum ^= byte
        return checksum

    @staticmethod
    def encode_bcd(value: str, length: int) -> bytes:
        """Encode string to BCD"""
        # Pad with zeros if needed
        value = value.zfill(length * 2)
        result = bytearray()
        for i in range(0, len(value), 2):
            high = int(value[i])
            low = int(value[i + 1])
            result.append((high << 4) | low)
        return bytes(result)

    @staticmethod
    def decode_bcd(data: bytes) -> str:
        """Decode BCD to string"""
        result = ""
        for byte in data:
            high = (byte >> 4) & 0x0F
            low = byte & 0x0F
            result += f"{high}{low}"
        return result

    @staticmethod
    def encode_datetime(dt: datetime) -> bytes:
        """Encode datetime to BCD[6] YY-MM-DD-hh-mm-ss"""
        time_str = dt.strftime("%y%m%d%H%M%S")
        return JT808Protocol.encode_bcd(time_str, 6)

    @staticmethod
    def build_message(msg_id: int, body: bytes, phone: str, seq: int) -> bytes:
        """Build complete JT/T 808 message"""
        # Message body attributes (no encryption, no subpackaging)
        body_attr = len(body) & 0x3FF

        # Header
        header = struct.pack(">H", msg_id)
        header += struct.pack(">H", body_attr)
        header += JT808Protocol.encode_bcd(phone, 6)
        header += struct.pack(">H", seq)

        # Calculate checksum (header + body)
        data_to_check = header + body
        checksum = JT808Protocol.calculate_checksum(data_to_check)

        # Escape the data
        escaped_data = JT808Protocol.escape_data(data_to_check + bytes([checksum]))

        # Add flags
        return bytes([JT808Protocol.FLAG]) + escaped_data + bytes([JT808Protocol.FLAG])

    @staticmethod
    def parse_message(data: bytes) -> Optional[Dict[str, Any]]:
        """Parse received JT/T 808 message"""
        if len(data) < 2 or data[0] != JT808Protocol.FLAG or data[-1] != JT808Protocol.FLAG:
            logger.error("Invalid message flags")
            return None

        # Remove flags and unescape
        escaped_data = data[1:-1]
        unescaped_data = JT808Protocol.unescape_data(escaped_data)

        if len(unescaped_data) < 13:  # Minimum header size + checksum
            logger.error("Message too short")
            return None

        # Verify checksum
        checksum_received = unescaped_data[-1]
        checksum_calculated = JT808Protocol.calculate_checksum(unescaped_data[:-1])

        if checksum_received != checksum_calculated:
            logger.error(f"Checksum mismatch: received {checksum_received:02X}, calculated {checksum_calculated:02X}")
            return None

        # Parse header
        msg_id = struct.unpack(">H", unescaped_data[0:2])[0]
        body_attr = struct.unpack(">H", unescaped_data[2:4])[0]
        body_length = body_attr & 0x3FF
        phone = JT808Protocol.decode_bcd(unescaped_data[4:10])
        seq = struct.unpack(">H", unescaped_data[10:12])[0]

        # Extract body
        body_start = 12
        body = unescaped_data[body_start:body_start + body_length]

        return {
            'msg_id': msg_id,
            'body_attr': body_attr,
            'phone': phone,
            'seq': seq,
            'body': body
        }


# ============================================================================
# DC600 SIMULATOR
# ============================================================================

class DC600Simulator:
    """DC600 Terminal Device Simulator"""

    def __init__(self, config: DeviceConfig):
        self.config = config
        self.socket: Optional[socket.socket] = None
        self.authenticated = False
        self.running = False
        self.receive_thread: Optional[threading.Thread] = None

        # Sample media files
        self.media_files = {
            'adas_image': 'samples/adas_collision.jpg',
            'adas_video': 'samples/adas_collision.h264',
            'dsm_image': 'samples/dsm_fatigue.jpg',
            'dsm_video': 'samples/dsm_fatigue.h264',
            'vehicle_data': 'samples/vehicle_status.bin'
        }

        # Create sample directory if not exists
        Path('samples').mkdir(exist_ok=True)
        self._create_sample_files()

    def _create_sample_files(self):
        """Create dummy sample files for testing"""
        # Create sample image files
        if not Path(self.media_files['adas_image']).exists():
            with open(self.media_files['adas_image'], 'wb') as f:
                f.write(b'\xFF\xD8\xFF\xE0' + b'\x00' * 1000)  # Dummy JPEG

        if not Path(self.media_files['dsm_image']).exists():
            with open(self.media_files['dsm_image'], 'wb') as f:
                f.write(b'\xFF\xD8\xFF\xE0' + b'\x00' * 1000)

        # Create sample video files
        if not Path(self.media_files['adas_video']).exists():
            with open(self.media_files['adas_video'], 'wb') as f:
                f.write(b'\x00\x00\x00\x01' + b'\x00' * 5000)  # Dummy H264

        if not Path(self.media_files['dsm_video']).exists():
            with open(self.media_files['dsm_video'], 'wb') as f:
                f.write(b'\x00\x00\x00\x01' + b'\x00' * 5000)

        # Create sample vehicle status data
        if not Path(self.media_files['vehicle_data']).exists():
            with open(self.media_files['vehicle_data'], 'wb') as f:
                # Create 10 data blocks as per Table 4-22
                for i in range(10):
                    block = self._create_vehicle_status_block(i, 10)
                    f.write(block)

    def _create_vehicle_status_block(self, block_num: int, total_blocks: int) -> bytes:
        """Create a vehicle status data block (Table 4-22)"""
        data = bytearray()

        # Total number of data blocks
        data.extend(struct.pack(">I", total_blocks))

        # Serial number of current block
        data.extend(struct.pack(">I", block_num))

        # Alarm flag (DWORD)
        data.extend(struct.pack(">I", 0))

        # Vehicle status (DWORD)
        data.extend(struct.pack(">I", 0x0003))

        # Latitude (DWORD) - degrees * 10^6
        data.extend(struct.pack(">I", int(31.230416 * 1000000)))

        # Longitude (DWORD)
        data.extend(struct.pack(">I", int(121.473701 * 1000000)))

        # Altitude (WORD) - meters
        data.extend(struct.pack(">H", 10))

        # Speed (WORD) - 1/10 km/h
        data.extend(struct.pack(">H", 600))  # 60 km/h

        # Direction (WORD) - degrees
        data.extend(struct.pack(">H", 90))

        # Time (BCD[6])
        data.extend(JT808Protocol.encode_datetime(datetime.now()))

        # X/Y/Z axis acceleration (WORD each) - g * 100
        data.extend(struct.pack(">H", 0))  # X
        data.extend(struct.pack(">H", 0))  # Y
        data.extend(struct.pack(">H", 100))  # Z = 1g

        # X/Y/Z axis angular velocity (WORD each) - deg/s * 100
        data.extend(struct.pack(">H", 0))  # X
        data.extend(struct.pack(">H", 0))  # Y
        data.extend(struct.pack(">H", 0))  # Z

        # Pulse speed (WORD) - 1/10 km/h
        data.extend(struct.pack(">H", 600))

        # OBD speed (WORD)
        data.extend(struct.pack(">H", 600))

        # Gear status (BYTE)
        data.append(3)  # 3rd gear

        # Accelerator pedal value (BYTE) - 1-100%
        data.append(30)

        # Brake pedal value (BYTE)
        data.append(0)

        # Brake status (BYTE)
        data.append(0)  # No brake

        # Engine speed (WORD) - RPM
        data.extend(struct.pack(">H", 2000))

        # Steering wheel angle (WORD) - degrees, signed
        data.extend(struct.pack(">H", 0))

        # Turn signal status (BYTE)
        data.append(0)  # No turn signal

        # Reserved (BYTE[2])
        data.extend(b'\x00\x00')

        # Check bit (XOR of all previous bytes)
        checksum = 0
        for byte in data:
            checksum ^= byte
        data.append(checksum)

        return bytes(data)

    def connect(self) -> bool:
        """Connect to server"""
        try:
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.socket.connect((self.config.server_ip, self.config.server_port))
            logger.info(f"Connected to {self.config.server_ip}:{self.config.server_port}")

            # Start receive thread
            self.running = True
            self.receive_thread = threading.Thread(target=self._receive_loop, daemon=True)
            self.receive_thread.start()

            return True
        except Exception as e:
            logger.error(f"Connection failed: {e}")
            return False

    def disconnect(self):
        """Disconnect from server"""
        self.running = False
        if self.socket:
            try:
                self.socket.close()
            except:
                pass
            self.socket = None
        logger.info("Disconnected from server")

    def _receive_loop(self):
        """Background thread to receive messages"""
        buffer = bytearray()

        while self.running and self.socket:
            try:
                data = self.socket.recv(4096)
                if not data:
                    logger.warning("Connection closed by server")
                    break

                buffer.extend(data)

                # Try to extract messages
                while True:
                    # Find start flag
                    start_idx = buffer.find(JT808Protocol.FLAG)
                    if start_idx == -1:
                        break

                    # Find end flag
                    end_idx = buffer.find(JT808Protocol.FLAG, start_idx + 1)
                    if end_idx == -1:
                        break

                    # Extract message
                    msg_data = bytes(buffer[start_idx:end_idx + 1])
                    buffer = buffer[end_idx + 1:]

                    # Process message
                    self._handle_received_message(msg_data)

            except Exception as e:
                logger.error(f"Receive error: {e}")
                break

    def _handle_received_message(self, data: bytes):
        """Handle received message from platform"""
        parsed = JT808Protocol.parse_message(data)
        if not parsed:
            logger.error("Failed to parse message")
            return

        msg_id = parsed['msg_id']
        body = parsed['body']
        seq = parsed['seq']

        logger.info(f"Received message: ID=0x{msg_id:04X}, Seq={seq}, Body length={len(body)}")

        # Handle different message types
        if msg_id == MessageID.PLATFORM_GENERAL_RESPONSE:
            self._handle_platform_general_response(body, seq)
        elif msg_id == MessageID.TERMINAL_REGISTRATION_RESPONSE:
            self._handle_registration_response(body, seq)
        elif msg_id == MessageID.SET_TERMINAL_PARAMETERS:
            self._handle_set_parameters(body, seq)
        elif msg_id == MessageID.QUERY_TERMINAL_PARAMETERS:
            self._handle_query_parameters(body, seq)
        elif msg_id == MessageID.QUERY_TERMINAL_ATTRIBUTES:
            self._handle_query_attributes(body, seq)
        elif msg_id == MessageID.TEXT_MESSAGE_DOWN:
            self._handle_text_message(body, seq)
        elif msg_id == MessageID.CAMERA_COMMAND:
            self._handle_camera_command(body, seq)
        elif msg_id == MessageID.VIDEO_REALTIME_REQUEST:
            self._handle_video_request(body, seq)
        elif msg_id == MessageID.VIDEO_REALTIME_CONTROL:
            self._handle_video_control(body, seq)
        elif msg_id == MessageID.ALARM_ATTACHMENT_UPLOAD_CMD:
            self._handle_alarm_attachment_command(body, seq)
        else:
            logger.warning(f"Unhandled message type: 0x{msg_id:04X}")
            # Send generic response
            self._send_terminal_general_response(seq, msg_id, 0)  # Success

    def _handle_platform_general_response(self, body: bytes, seq: int):
        """Handle platform general response (0x8001)"""
        if len(body) < 5:
            logger.error("Invalid platform response body")
            return

        response_seq = struct.unpack(">H", body[0:2])[0]
        response_id = struct.unpack(">H", body[2:4])[0]
        result = body[4]

        result_str = {0: "Success", 1: "Failure", 2: "Incorrect", 3: "Not supported", 4: "Alarm confirmed"}.get(result, "Unknown")
        logger.info(f"Platform response: Seq={response_seq}, ID=0x{response_id:04X}, Result={result_str}")

    def _handle_registration_response(self, body: bytes, seq: int):
        """Handle registration response (0x8100)"""
        if len(body) < 3:
            logger.error("Invalid registration response")
            return

        response_seq = struct.unpack(">H", body[0:2])[0]
        result = body[2]

        if result == 0:  # Success
            auth_code = body[3:].decode('gbk', errors='ignore')
            self.config.auth_code = auth_code
            logger.info(f"Registration successful! Auth code: {auth_code}")

            # Now authenticate
            time.sleep(0.5)
            self.authenticate()
        else:
            error_msg = {1: "Vehicle already registered", 2: "No vehicle in database",
                        3: "Terminal already registered", 4: "No terminal in database"}.get(result, "Unknown error")
            logger.error(f"Registration failed: {error_msg}")

    def _handle_set_parameters(self, body: bytes, seq: int):
        """Handle set terminal parameters (0x8103)"""
        logger.info("Received parameter setting command")
        # Just acknowledge
        self._send_terminal_general_response(seq, MessageID.SET_TERMINAL_PARAMETERS, 0)

    def _handle_query_parameters(self, body: bytes, seq: int):
        """Handle query terminal parameters (0x8104)"""
        logger.info("Received parameter query")
        # Send empty response for now
        response_body = struct.pack(">HB", seq, 0)  # Seq + 0 parameters
        self.send_message(MessageID.QUERY_PARAMETER_RESPONSE, response_body)

    def _handle_query_attributes(self, body: bytes, seq: int):
        """Handle query terminal attributes (0x8107)"""
        logger.info("Received attribute query")

        # Build attribute response (Table 20)
        response = bytearray()

        # Terminal type (WORD)
        response.extend(struct.pack(">H", self.config.terminal_type))

        # Manufacturer ID (5 bytes)
        response.extend(self.config.manufacturer_id.ljust(5, '\x00').encode('gbk'))

        # Terminal model (20 bytes)
        response.extend(self.config.terminal_model.ljust(20, '\x00').encode('gbk'))

        # Terminal ID (7 bytes)
        response.extend(self.config.terminal_id.ljust(7, '\x00').encode('gbk'))

        # SIM ICCID (BCD[10])
        response.extend(JT808Protocol.encode_bcd("89860000000000000000", 10))

        # Hardware version length
        hw_ver = self.config.hardware_version.encode('gbk')
        response.append(len(hw_ver))
        response.extend(hw_ver)

        # Firmware version length
        fw_ver = self.config.firmware_version.encode('gbk')
        response.append(len(fw_ver))
        response.extend(fw_ver)

        # GNSS module attribute (BYTE) - GPS + BeiDou
        response.append(0x03)

        # Communication module attribute (BYTE) - GPRS + TD-LTE
        response.append(0x21)

        self.send_message(MessageID.QUERY_ATTRIBUTE_RESPONSE, bytes(response))

    def _handle_text_message(self, body: bytes, seq: int):
        """Handle text message from platform (0x8300)"""
        if len(body) < 2:
            return

        flag = body[0]
        text = body[1:].decode('gbk', errors='ignore')
        logger.info(f"Received text message: {text} (flag={flag})")

        # Acknowledge
        self._send_terminal_general_response(seq, MessageID.TEXT_MESSAGE_DOWN, 0)

    def _handle_camera_command(self, body: bytes, seq: int):
        """Handle camera command (0x8801)"""
        if len(body) < 7:
            return

        channel_id = body[0]
        command = struct.unpack(">H", body[1:3])[0]
        interval = struct.unpack(">H", body[3:5])[0]
        save_flag = body[5]
        resolution = body[6]

        logger.info(f"Camera command: channel={channel_id}, cmd={command}, interval={interval}")

        # Build response (Table 84)
        response = bytearray()
        response.extend(struct.pack(">H", seq))  # Response serial number
        response.append(0)  # Success

        if command > 0:  # Photo count
            response.extend(struct.pack(">H", command))  # Number of photos
            # Add multimedia IDs
            for i in range(command):
                self.config.alarm_id += 1
                response.extend(struct.pack(">I", self.config.alarm_id))

        self.send_message(MessageID.CAMERA_RESPONSE, bytes(response))

    def _handle_video_request(self, body: bytes, seq: int):
        """Handle real-time video request (0x9101)"""
        logger.info("Received video stream request")
        # Just acknowledge
        self._send_terminal_general_response(seq, MessageID.VIDEO_REALTIME_REQUEST, 0)

    def _handle_video_control(self, body: bytes, seq: int):
        """Handle video control (0x9102)"""
        if len(body) < 2:
            return

        channel = body[0]
        control = body[1]

        control_str = {0: "Close", 1: "Switch stream", 2: "Pause", 3: "Resume", 4: "Close intercom"}.get(control, "Unknown")
        logger.info(f"Video control: channel={channel}, command={control_str}")

        self._send_terminal_general_response(seq, MessageID.VIDEO_REALTIME_CONTROL, 0)

    def _handle_alarm_attachment_command(self, body: bytes, seq: int):
        """Handle alarm attachment upload command (0x9208) - Table 4-21"""
        if len(body) < 70:
            logger.error("Invalid alarm attachment command")
            return

        # Parse command
        offset = 0
        ip_len = body[offset]
        offset += 1

        server_ip = body[offset:offset + ip_len].decode('ascii')
        offset += ip_len

        tcp_port = struct.unpack(">H", body[offset:offset + 2])[0]
        offset += 2

        udp_port = struct.unpack(">H", body[offset:offset + 2])[0]
        offset += 2

        alarm_flag = body[offset:offset + 16]
        offset += 16

        alarm_number = body[offset:offset + 32]
        offset += 32

        logger.info(f"Alarm attachment upload command: server={server_ip}:{tcp_port}")

        # Acknowledge
        self._send_terminal_general_response(seq, MessageID.ALARM_ATTACHMENT_UPLOAD_CMD, 0)

        # TODO: Connect to attachment server and upload files
        # For now, just log
        logger.info("Would upload alarm attachments to attachment server")

    def _send_terminal_general_response(self, response_seq: int, response_id: int, result: int):
        """Send terminal general response (0x0001)"""
        body = struct.pack(">HHB", response_seq, response_id, result)
        self.send_message(MessageID.TERMINAL_GENERAL_RESPONSE, body)

    def send_message(self, msg_id: int, body: bytes) -> bool:
        """Send message to platform"""
        if not self.socket:
            logger.error("Not connected")
            return False

        self.config.msg_seq = (self.config.msg_seq + 1) % 65536
        message = JT808Protocol.build_message(msg_id, body, self.config.phone_number, self.config.msg_seq)

        try:
            self.socket.sendall(message)
            logger.debug(f"Sent message: ID=0x{msg_id:04X}, Seq={self.config.msg_seq}, Body length={len(body)}")
            return True
        except Exception as e:
            logger.error(f"Send failed: {e}")
            return False

    def register(self) -> bool:
        """Send registration message (0x0100)"""
        logger.info("Sending registration...")

        body = bytearray()
        body.extend(struct.pack(">H", self.config.province_id))
        body.extend(struct.pack(">H", self.config.city_id))
        body.extend(self.config.manufacturer_id.ljust(5, '\x00').encode('gbk'))
        body.extend(self.config.terminal_model.ljust(20, '\x00').encode('gbk'))
        body.extend(self.config.terminal_id.ljust(7, '\x00').encode('gbk'))
        body.append(0)  # License plate color (0 = unregistered)
        body.extend("TEST1234".encode('gbk'))  # VIN or license plate

        return self.send_message(MessageID.TERMINAL_REGISTRATION, bytes(body))

    def authenticate(self) -> bool:
        """Send authentication message (0x0102)"""
        if not self.config.auth_code:
            logger.error("No auth code available")
            return False

        logger.info("Sending authentication...")
        body = self.config.auth_code.encode('gbk')
        result = self.send_message(MessageID.TERMINAL_AUTHENTICATION, body)

        if result:
            self.authenticated = True
            logger.info("Authentication sent, waiting for response...")

        return result

    def send_heartbeat(self) -> bool:
        """Send heartbeat (0x0002)"""
        return self.send_message(MessageID.TERMINAL_HEARTBEAT, b'')

    def send_location(self, location: LocationData) -> bool:
        """Send location report (0x0200) - Table 23"""
        body = bytearray()

        # Alarm sign (DWORD)
        body.extend(struct.pack(">I", location.alarm_flag))

        # Status (DWORD)
        body.extend(struct.pack(">I", location.status_flag))

        # Latitude (DWORD) - degrees * 10^6
        body.extend(struct.pack(">I", int(location.latitude * 1000000)))

        # Longitude (DWORD)
        body.extend(struct.pack(">I", int(location.longitude * 1000000)))

        # Altitude (WORD)
        body.extend(struct.pack(">H", location.altitude))

        # Speed (WORD) - 1/10 km/h
        body.extend(struct.pack(">H", location.speed))

        # Direction (WORD)
        body.extend(struct.pack(">H", location.direction))

        # Time (BCD[6])
        body.extend(JT808Protocol.encode_datetime(location.timestamp))

        # Additional information
        if location.mileage is not None:
            body.append(0x01)  # ID
            body.append(4)  # Length
            body.extend(struct.pack(">I", location.mileage))

        if location.signal_strength is not None:
            body.append(0x30)
            body.append(1)
            body.append(location.signal_strength)

        if location.satellite_count is not None:
            body.append(0x31)
            body.append(1)
            body.append(location.satellite_count)

        return self.send_message(MessageID.LOCATION_REPORT, bytes(body))

    def send_adas_alarm(self, alarm: ADASAlarmData) -> bool:
        """Send ADAS alarm in location report with additional info 0x64"""
        # Build alarm sign number (Table 4-16)
        alarm_sign = bytearray()
        alarm_sign.extend(self.config.terminal_id.ljust(7, '\x00').encode('ascii'))
        alarm_sign.extend(JT808Protocol.encode_datetime(alarm.timestamp))
        alarm_sign.append(alarm.serial_number)
        alarm_sign.append(alarm.attachment_count)
        alarm_sign.append(0)  # Reserved

        # Build ADAS alarm additional info (Table 4-15)
        adas_info = bytearray()
        adas_info.extend(struct.pack(">I", alarm.alarm_id))
        adas_info.append(alarm.flag_status)
        adas_info.append(alarm.alarm_type)
        adas_info.append(alarm.alarm_level)
        adas_info.append(alarm.preceding_vehicle_speed)
        adas_info.append(alarm.vehicle_distance)
        adas_info.append(alarm.deviation_type)
        adas_info.append(alarm.road_sign_type)
        adas_info.append(alarm.road_sign_data)
        adas_info.append(alarm.speed)
        adas_info.extend(struct.pack(">H", alarm.altitude))
        adas_info.extend(struct.pack(">I", int(alarm.latitude * 1000000)))
        adas_info.extend(struct.pack(">I", int(alarm.longitude * 1000000)))
        adas_info.extend(JT808Protocol.encode_datetime(alarm.timestamp))
        adas_info.extend(struct.pack(">H", alarm.vehicle_status))
        adas_info.extend(alarm_sign)

        # Build location report with ADAS additional info
        location = LocationData(
            alarm_flag=0,  # Set appropriate alarm bit if needed
            status_flag=alarm.vehicle_status,
            latitude=alarm.latitude,
            longitude=alarm.longitude,
            altitude=alarm.altitude,
            speed=alarm.speed * 10,  # Convert to 1/10 km/h
            timestamp=alarm.timestamp
        )

        body = bytearray()

        # Basic location info
        body.extend(struct.pack(">I", location.alarm_flag))
        body.extend(struct.pack(">I", location.status_flag))
        body.extend(struct.pack(">I", int(location.latitude * 1000000)))
        body.extend(struct.pack(">I", int(location.longitude * 1000000)))
        body.extend(struct.pack(">H", location.altitude))
        body.extend(struct.pack(">H", location.speed))
        body.extend(struct.pack(">H", location.direction))
        body.extend(JT808Protocol.encode_datetime(location.timestamp))

        # Add ADAS additional info
        body.append(0x64)  # Additional info ID
        body.append(len(adas_info))  # Length
        body.extend(adas_info)

        logger.info(f"Sending ADAS alarm: Type={alarm.alarm_type.name}, Level={alarm.alarm_level}")

        return self.send_message(MessageID.LOCATION_REPORT, bytes(body))

    def send_dsm_alarm(self, alarm: DSMAlarmData) -> bool:
        """Send DSM alarm in location report with additional info 0x65"""
        # Build alarm sign number
        alarm_sign = bytearray()
        alarm_sign.extend(self.config.terminal_id.ljust(7, '\x00').encode('ascii'))
        alarm_sign.extend(JT808Protocol.encode_datetime(alarm.timestamp))
        alarm_sign.append(alarm.serial_number)
        alarm_sign.append(alarm.attachment_count)
        alarm_sign.append(0)  # Reserved

        # Build DSM alarm additional info (Table 4-17)
        dsm_info = bytearray()
        dsm_info.extend(struct.pack(">I", alarm.alarm_id))
        dsm_info.append(alarm.flag_status)
        dsm_info.append(alarm.alarm_type)
        dsm_info.append(alarm.alarm_level)
        dsm_info.append(alarm.fatigue_level)
        dsm_info.extend(b'\x00' * 4)  # Reserved
        dsm_info.append(alarm.speed)
        dsm_info.extend(struct.pack(">H", alarm.altitude))
        dsm_info.extend(struct.pack(">I", int(alarm.latitude * 1000000)))
        dsm_info.extend(struct.pack(">I", int(alarm.longitude * 1000000)))
        dsm_info.extend(JT808Protocol.encode_datetime(alarm.timestamp))
        dsm_info.extend(struct.pack(">H", alarm.vehicle_status))
        dsm_info.extend(alarm_sign)

        # Build location report
        location = LocationData(
            alarm_flag=0,
            status_flag=alarm.vehicle_status,
            latitude=alarm.latitude,
            longitude=alarm.longitude,
            altitude=alarm.altitude,
            speed=alarm.speed * 10,
            timestamp=alarm.timestamp
        )

        body = bytearray()
        body.extend(struct.pack(">I", location.alarm_flag))
        body.extend(struct.pack(">I", location.status_flag))
        body.extend(struct.pack(">I", int(location.latitude * 1000000)))
        body.extend(struct.pack(">I", int(location.longitude * 1000000)))
        body.extend(struct.pack(">H", location.altitude))
        body.extend(struct.pack(">H", location.speed))
        body.extend(struct.pack(">H", location.direction))
        body.extend(JT808Protocol.encode_datetime(location.timestamp))

        # Add DSM additional info
        body.append(0x65)  # Additional info ID
        body.append(len(dsm_info))  # Length
        body.extend(dsm_info)

        logger.info(f"Sending DSM alarm: Type={alarm.alarm_type.name}, Level={alarm.alarm_level}")

        return self.send_message(MessageID.LOCATION_REPORT, bytes(body))

    def simulate_all_alarms(self, interval: float = 3.0):
        """Simulate all alarm types sequentially"""
        logger.info("=" * 60)
        logger.info("Starting comprehensive alarm simulation")
        logger.info("=" * 60)

        # 1. ADAS Alarms
        logger.info("\n### ADAS ALARMS ###")

        adas_alarms = [
            (ADASAlarmType.FORWARD_COLLISION, "Forward collision detected", {'preceding_vehicle_speed': 40, 'vehicle_distance': 20}),
            (ADASAlarmType.LANE_DEPARTURE, "Lane departure - left", {'deviation_type': 0x01}),
            (ADASAlarmType.LANE_DEPARTURE, "Lane departure - right", {'deviation_type': 0x02}),
            (ADASAlarmType.VEHICLE_TOO_CLOSE, "Vehicle too close warning", {'vehicle_distance': 15}),
            (ADASAlarmType.PEDESTRIAN_COLLISION, "Pedestrian collision warning", {'vehicle_distance': 25}),
            (ADASAlarmType.FREQUENT_LANE_CHANGE, "Frequent lane changes detected", {}),
            (ADASAlarmType.ROAD_SIGN_OUT_OF_LIMIT, "Speed limit sign - 60 km/h", {'road_sign_type': 0x01, 'road_sign_data': 60}),
            (ADASAlarmType.OBSTACLE, "Obstacle detected ahead", {}),
            (ADASAlarmType.ROAD_SIGN_RECOGNITION, "Road sign recognized", {'road_sign_type': 0x02, 'road_sign_data': 3}),
            (ADASAlarmType.ACTIVE_CAPTURE, "Active capture event", {}),
        ]

        for alarm_type, description, extra_params in adas_alarms:
            logger.info(f"\n> {description}")

            self.config.alarm_id += 1

            alarm = ADASAlarmData(
                alarm_id=self.config.alarm_id,
                flag_status=0x01,  # Start flag
                alarm_type=alarm_type,
                alarm_level=AlarmLevel.SECOND_LEVEL if 'COLLISION' in alarm_type.name else AlarmLevel.FIRST_LEVEL,
                speed=random.randint(50, 80),
                terminal_id=self.config.terminal_id,
                **extra_params
            )

            self.send_adas_alarm(alarm)
            time.sleep(interval)

        # 2. DSM Alarms
        logger.info("\n### DSM ALARMS ###")

        dsm_alarms = [
            (DSMAlarmType.FATIGUE_DRIVING, "Driver fatigue detected", {'fatigue_level': 7}),
            (DSMAlarmType.CALLING, "Driver using phone", {}),
            (DSMAlarmType.SMOKING, "Driver smoking", {}),
            (DSMAlarmType.DISTRACTED_DRIVING, "Driver distracted - looking away", {}),
            (DSMAlarmType.DRIVER_ABNORMAL, "Driver abnormal behavior", {}),
            (DSMAlarmType.AUTO_CAPTURE, "Auto capture event", {}),
            (DSMAlarmType.DRIVER_CHANGE, "Driver change detected", {}),
        ]

        for alarm_type, description, extra_params in dsm_alarms:
            logger.info(f"\n> {description}")

            self.config.alarm_id += 1

            alarm = DSMAlarmData(
                alarm_id=self.config.alarm_id,
                flag_status=0x01,
                alarm_type=alarm_type,
                alarm_level=AlarmLevel.SECOND_LEVEL if alarm_type == DSMAlarmType.FATIGUE_DRIVING else AlarmLevel.FIRST_LEVEL,
                speed=random.randint(50, 80),
                terminal_id=self.config.terminal_id,
                **extra_params
            )

            self.send_dsm_alarm(alarm)
            time.sleep(interval)

        # 3. Standard JT/T 808 Alarms
        logger.info("\n### STANDARD JT/T 808 ALARMS ###")

        standard_alarms = [
            (AlarmBit.EMERGENCY, "Emergency alarm - panic button"),
            (AlarmBit.OVER_SPEED, "Over speed alarm"),
            (AlarmBit.FATIGUE_DRIVING_WARNING, "Fatigue driving warning"),
            (AlarmBit.RISK_WARNING, "Risk warning"),
            (AlarmBit.TIMEOUT_PARKING, "Timeout parking"),
        ]

        for alarm_bit, description in standard_alarms:
            logger.info(f"\n> {description}")

            location = LocationData(
                alarm_flag=(1 << alarm_bit),
                status_flag=0x0003,
                latitude=31.230416 + random.uniform(-0.01, 0.01),
                longitude=121.473701 + random.uniform(-0.01, 0.01),
                speed=random.randint(300, 800),
                mileage=random.randint(10000, 50000),
                signal_strength=random.randint(20, 31),
                satellite_count=random.randint(8, 15)
            )

            self.send_location(location)
            time.sleep(interval)

        logger.info("\n" + "=" * 60)
        logger.info("Alarm simulation complete!")
        logger.info("=" * 60)


# ============================================================================
# MAIN EXECUTION
# ============================================================================

def main():
    """Main entry point"""
    print("""
╔══════════════════════════════════════════════════════════════╗
║          DC600 Protocol Simulator v1.0                       ║
║          Full ADAS/DSM Alarm Testing Suite                   ║
╚══════════════════════════════════════════════════════════════╝
    """)

    # Configuration
    config = DeviceConfig(
        terminal_id="TEST001",
        phone_number="013800138000",
        server_ip="127.0.0.1",  # Change to your server IP
        server_port=5999,        # Change to your server port
    )

    # Create simulator
    simulator = DC600Simulator(config)

    try:
        # Connect
        if not simulator.connect():
            print("Failed to connect to server")
            return

        time.sleep(1)

        # Register
        print("\n[1] Registering device...")
        simulator.register()
        time.sleep(3)  # Wait for registration response and authentication

        # Wait for authentication to complete
        if not simulator.authenticated:
            print("Waiting for authentication...")
            time.sleep(2)

        # Send heartbeat
        print("\n[2] Sending heartbeat...")
        simulator.send_heartbeat()
        time.sleep(1)

        # Send normal location
        print("\n[3] Sending normal location...")
        location = LocationData(
            speed=600,  # 60 km/h
            mileage=12345,
            signal_strength=28,
            satellite_count=12
        )
        simulator.send_location(location)
        time.sleep(2)

        # Simulate all alarms
        print("\n[4] Simulating all alarms...")
        print("    (Each alarm will be sent with 3-second interval)")
        print("")
        input("Press Enter to start alarm simulation...")

        simulator.simulate_all_alarms(interval=3.0)

        # Keep connection alive
        print("\n[5] Keeping connection alive...")
        print("    Sending periodic heartbeats. Press Ctrl+C to exit.")

        while True:
            time.sleep(30)
            simulator.send_heartbeat()
            logger.info("Heartbeat sent")

    except KeyboardInterrupt:
        print("\n\nShutting down...")
    except Exception as e:
        logger.error(f"Error: {e}", exc_info=True)
    finally:
        simulator.disconnect()
        print("Disconnected. Goodbye!")


if __name__ == "__main__":
    main()