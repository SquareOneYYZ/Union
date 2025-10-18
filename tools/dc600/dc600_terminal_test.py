#!/usr/bin/env python3
"""
DC600 GPRS Protocol Terminal Simulator
Simulates a vehicle terminal for testing platform implementations
"""

import socket
import struct
import time
import random
from datetime import datetime
from typing import List, Tuple, Optional
from enum import IntEnum


class MessageID(IntEnum):
    """Message IDs from the protocol"""
    # Terminal to Platform
    TERMINAL_GENERAL_RESPONSE = 0x0001
    TERMINAL_HEARTBEAT = 0x0002
    TERMINAL_REGISTRATION = 0x0100
    TERMINAL_AUTHENTICATION = 0x0102
    CHECK_PARAMETER_RESPONSE = 0x0104
    CHECK_ATTRIBUTE_RESPONSE = 0x0107
    LOCATION_REPORT = 0x0200
    BATCH_LOCATION_UPLOAD = 0x0704
    MULTIMEDIA_EVENT_INFO = 0x0800
    MULTIMEDIA_DATA_UPLOAD = 0x0801
    MULTIMEDIA_RETRIEVE_RESPONSE = 0x0802
    CAMERA_TAKEN_RESPONSE = 0x0805
    TERMINAL_UPGRADE_RESULT = 0x0108
    
    # Platform to Terminal
    PLATFORM_GENERAL_RESPONSE = 0x8001
    REGISTRATION_RESPONSE = 0x8100
    PARAMETER_SETTING = 0x8103
    CHECK_PARAMETER = 0x8104
    CHECK_SPECIFIED_PARAMETER = 0x8106
    CHECK_ATTRIBUTE = 0x8107
    TERMINAL_CONTROL = 0x8105
    CONFIRM_ALARM = 0x8203
    TEXT_INFO = 0x8300
    TERMINAL_UPDATE = 0x8108
    CIRCLE_AREA_SET = 0x8600
    CIRCLE_AREA_DELETE = 0x8601
    RECTANGLE_AREA_SET = 0x8602
    RECTANGLE_AREA_DELETE = 0x8603
    POLYGON_AREA_SET = 0x8604
    POLYGON_AREA_DELETE = 0x8605
    ROUTE_SET = 0x8606
    ROUTE_DELETE = 0x8607
    CAMERA_COMMAND = 0x8801
    MULTIMEDIA_UPLOAD_RESPONSE = 0x8800
    MULTIMEDIA_RETRIEVE = 0x8802
    STORE_MULTIMEDIA_UPLOAD = 0x8803
    SINGLE_MULTIMEDIA_UPLOAD = 0x8805


class AlarmBit(IntEnum):
    """Alarm bit definitions from Table 24"""
    EMERGENCY = 0  # Emergency alarm
    OVERSPEED = 1  # Over speed alarm
    DRIVING_MALFUNCTION = 2  # Driving alarm malfunction
    RISK_WARNING = 3  # Risk warning
    OVERSPEED_WARNING = 13  # Over speed warning
    FATIGUE_WARNING = 14  # Fatigue driving warning
    DAILY_OVERSPEED = 18  # Accumulated over speed driving time of the day
    TIMEOUT_PARKING = 19  # Timeout parking
    AREA_IN_OUT = 20  # Enter and exit the area
    ROUTE_IN_OUT = 21  # Enter and exit the route
    ROUTE_TIME_ISSUE = 22  # Driving time of route not enough/too long
    OFF_TRACK = 23  # Off track alarm


class StatusBit(IntEnum):
    """Status bit definitions from Table 25"""
    ACC = 0  # 0: ACC off, 1: ACC on
    POSITIONED = 1  # 0: Not positioning, 1: Positioning
    SOUTH_LATITUDE = 2  # 0: North, 1: South
    WEST_LONGITUDE = 3  # 0: East, 1: West


class DC600Protocol:
    """DC600 Protocol implementation"""
    
    FLAG = 0x7e
    ESCAPE = 0x7d
    ESCAPE_FLAG = 0x02
    ESCAPE_ESCAPE = 0x01
    
    def __init__(self, phone_number: str = "013800138000"):
        """
        Initialize protocol handler
        
        Args:
            phone_number: Terminal phone number (12 digits)
        """
        self.phone_number = phone_number
        self.msg_serial = 0
        
    def _encode_bcd(self, number_str: str, length: int) -> bytes:
        """Encode string as BCD"""
        # Pad with zeros if needed
        number_str = number_str.zfill(length * 2)
        result = bytearray()
        for i in range(0, len(number_str), 2):
            high = int(number_str[i])
            low = int(number_str[i + 1])
            result.append((high << 4) | low)
        return bytes(result)
    
    def _decode_bcd(self, data: bytes) -> str:
        """Decode BCD to string"""
        result = ""
        for byte in data:
            high = (byte >> 4) & 0x0F
            low = byte & 0x0F
            result += f"{high}{low}"
        return result
    
    def _calculate_checksum(self, data: bytes) -> int:
        """Calculate XOR checksum"""
        checksum = 0
        for byte in data:
            checksum ^= byte
        return checksum
    
    def _escape_data(self, data: bytes) -> bytes:
        """Escape 0x7e and 0x7d in data"""
        result = bytearray()
        for byte in data:
            if byte == self.FLAG:
                result.extend([self.ESCAPE, self.ESCAPE_FLAG])
            elif byte == self.ESCAPE:
                result.extend([self.ESCAPE, self.ESCAPE_ESCAPE])
            else:
                result.append(byte)
        return bytes(result)
    
    def _unescape_data(self, data: bytes) -> bytes:
        """Unescape data"""
        result = bytearray()
        i = 0
        while i < len(data):
            if data[i] == self.ESCAPE and i + 1 < len(data):
                if data[i + 1] == self.ESCAPE_FLAG:
                    result.append(self.FLAG)
                    i += 2
                elif data[i + 1] == self.ESCAPE_ESCAPE:
                    result.append(self.ESCAPE)
                    i += 2
                else:
                    result.append(data[i])
                    i += 1
            else:
                result.append(data[i])
                i += 1
        return bytes(result)
    
    def encode_message(self, msg_id: int, body: bytes) -> bytes:
        """
        Encode a complete message
        
        Args:
            msg_id: Message ID
            body: Message body
            
        Returns:
            Complete encoded message with flags
        """
        self.msg_serial = (self.msg_serial + 1) % 65536
        
        # Build header
        header = bytearray()
        
        # Message ID (WORD)
        header.extend(struct.pack('>H', msg_id))
        
        # Message body attribute (WORD)
        body_len = len(body)
        body_attr = body_len & 0x3FF  # bits 0-9: length
        header.extend(struct.pack('>H', body_attr))
        
        # Terminal phone number (BCD[6])
        header.extend(self._encode_bcd(self.phone_number, 6))
        
        # Message serial number (WORD)
        header.extend(struct.pack('>H', self.msg_serial))
        
        # Combine header and body
        data = header + body
        
        # Calculate checksum
        checksum = self._calculate_checksum(data)
        data = data + bytes([checksum])
        
        # Escape data
        escaped = self._escape_data(data)
        
        # Add flags
        message = bytes([self.FLAG]) + escaped + bytes([self.FLAG])
        
        return message
    
    def decode_message(self, data: bytes) -> Optional[Tuple[int, int, bytes]]:
        """
        Decode a received message
        
        Args:
            data: Raw message data
            
        Returns:
            Tuple of (msg_id, serial, body) or None if invalid
        """
        # Check flags
        if len(data) < 3 or data[0] != self.FLAG or data[-1] != self.FLAG:
            return None
        
        # Remove flags and unescape
        escaped = data[1:-1]
        unescaped = self._unescape_data(escaped)
        
        if len(unescaped) < 13:  # Minimum: header (12) + checksum (1)
            return None
        
        # Verify checksum
        data_part = unescaped[:-1]
        checksum = unescaped[-1]
        calc_checksum = self._calculate_checksum(data_part)
        
        if checksum != calc_checksum:
            print(f"Checksum error: expected {calc_checksum:02x}, got {checksum:02x}")
            return None
        
        # Parse header
        msg_id = struct.unpack('>H', data_part[0:2])[0]
        body_attr = struct.unpack('>H', data_part[2:4])[0]
        body_len = body_attr & 0x3FF
        phone = self._decode_bcd(data_part[4:10])
        serial = struct.unpack('>H', data_part[10:12])[0]
        
        # Extract body
        body = data_part[12:12+body_len]
        
        return (msg_id, serial, body)


class DC600Terminal:
    """DC600 Terminal Simulator"""
    
    def __init__(self, host: str = "127.0.0.1", port: int = 8888, 
                 phone: str = "013800138000", use_tcp: bool = True):
        """
        Initialize terminal
        
        Args:
            host: Platform server host
            port: Platform server port
            phone: Terminal phone number
            use_tcp: Use TCP (True) or UDP (False)
        """
        self.host = host
        self.port = port
        self.phone = phone
        self.use_tcp = use_tcp
        self.protocol = DC600Protocol(phone)
        self.socket = None
        self.authenticated = False
        self.auth_code = ""
        
        # Default location (Beijing)
        self.latitude = 39.9042 * 1000000  # degrees * 10^6
        self.longitude = 116.4074 * 1000000
        self.altitude = 50  # meters
        self.speed = 0  # 1/10 km/h
        self.direction = 0  # degrees
        self.acc_on = False
        self.positioned = True
        
    def connect(self):
        """Connect to platform"""
        if self.use_tcp:
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.socket.connect((self.host, self.port))
            print(f"Connected to {self.host}:{self.port} (TCP)")
        else:
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            print(f"Using UDP mode, target: {self.host}:{self.port}")
    
    def disconnect(self):
        """Disconnect from platform"""
        if self.socket:
            self.socket.close()
            self.socket = None
            print("Disconnected")
    
    def send_message(self, msg_id: int, body: bytes):
        """Send a message to platform"""
        message = self.protocol.encode_message(msg_id, body)
        
        if self.use_tcp:
            self.socket.sendall(message)
        else:
            self.socket.sendto(message, (self.host, self.port))
        
        print(f"→ Sent {MessageID(msg_id).name if msg_id in MessageID.__members__.values() else f'0x{msg_id:04x}'}")
    
    def receive_message(self, timeout: float = 5.0) -> Optional[Tuple[int, int, bytes]]:
        """Receive and decode a message"""
        self.socket.settimeout(timeout)
        try:
            if self.use_tcp:
                # Read until we get a complete message (flag to flag)
                data = bytearray()
                while True:
                    byte = self.socket.recv(1)
                    if not byte:
                        return None
                    data.extend(byte)
                    if len(data) >= 2 and data[0] == 0x7e and data[-1] == 0x7e:
                        break
                data = bytes(data)
            else:
                data, _ = self.socket.recvfrom(4096)
            
            result = self.protocol.decode_message(data)
            if result:
                msg_id, serial, body = result
                print(f"← Received {MessageID(msg_id).name if msg_id in MessageID.__members__.values() else f'0x{msg_id:04x}'}")
            return result
        except socket.timeout:
            return None
    
    def register(self):
        """Send terminal registration (0x0100)"""
        body = bytearray()
        
        # Province domain ID (WORD) - Beijing: 11
        body.extend(struct.pack('>H', 11))
        
        # City domain ID (WORD) - Beijing: 0100
        body.extend(struct.pack('>H', 100))
        
        # Manufacturer ID (BYTE[5])
        body.extend(b'START')
        
        # Terminal type (BYTE[20])
        terminal_type = b'DC600' + b'\x00' * 15
        body.extend(terminal_type)
        
        # Terminal ID (BYTE[7])
        terminal_id = b'TEST001'
        body.extend(terminal_id)
        
        # License plate color (BYTE) - 0: not registered
        body.append(0)
        
        # VIN or License plate (STRING) - using VIN format
        vin = "TESTVIN1234567890"
        body.extend(vin.encode('gbk'))
        
        self.send_message(MessageID.TERMINAL_REGISTRATION, bytes(body))
        
        # Wait for response
        result = self.receive_message()
        if result:
            msg_id, serial, response_body = result
            if msg_id == MessageID.REGISTRATION_RESPONSE:
                result_code = response_body[2]
                if result_code == 0:
                    # Extract auth code
                    self.auth_code = response_body[3:].decode('gbk')
                    print(f"Registration successful, auth code: {self.auth_code}")
                    return True
                else:
                    print(f"Registration failed with code: {result_code}")
        return False
    
    def authenticate(self):
        """Send terminal authentication (0x0102)"""
        if not self.auth_code:
            print("No auth code available, register first")
            return False
        
        body = self.auth_code.encode('gbk')
        self.send_message(MessageID.TERMINAL_AUTHENTICATION, bytes(body))
        
        # Wait for response
        result = self.receive_message()
        if result:
            msg_id, serial, response_body = result
            if msg_id == MessageID.PLATFORM_GENERAL_RESPONSE:
                result_code = response_body[4]
                if result_code == 0:
                    print("Authentication successful")
                    self.authenticated = True
                    return True
                else:
                    print(f"Authentication failed with code: {result_code}")
        return False
    
    def send_heartbeat(self):
        """Send terminal heartbeat (0x0002)"""
        self.send_message(MessageID.TERMINAL_HEARTBEAT, b'')
    
    def send_location(self, alarm_bits: int = 0):
        """
        Send location information report (0x0200)
        
        Args:
            alarm_bits: Alarm flags (see AlarmBit enum)
        """
        body = bytearray()
        
        # Alarm sign (DWORD)
        body.extend(struct.pack('>I', alarm_bits))
        
        # Status (DWORD)
        status = 0
        if self.acc_on:
            status |= (1 << StatusBit.ACC)
        if self.positioned:
            status |= (1 << StatusBit.POSITIONED)
        body.extend(struct.pack('>I', status))
        
        # Latitude (DWORD)
        body.extend(struct.pack('>I', int(self.latitude)))
        
        # Longitude (DWORD)
        body.extend(struct.pack('>I', int(self.longitude)))
        
        # Altitude (WORD)
        body.extend(struct.pack('>H', self.altitude))
        
        # Speed (WORD) - 1/10 km/h
        body.extend(struct.pack('>H', self.speed))
        
        # Direction (WORD)
        body.extend(struct.pack('>H', self.direction))
        
        # Time (BCD[6]) - YY-MM-DD-hh-mm-ss
        now = datetime.now()
        time_str = now.strftime('%y%m%d%H%M%S')
        body.extend(self.protocol._encode_bcd(time_str, 6))
        
        # Additional info (optional) - adding mileage
        body.append(0x01)  # ID: mileage
        body.append(4)  # Length
        body.extend(struct.pack('>I', random.randint(10000, 50000)))  # Random mileage
        
        self.send_message(MessageID.LOCATION_REPORT, bytes(body))
    
    def send_general_response(self, response_serial: int, response_id: int, result: int = 0):
        """Send terminal general response (0x0001)"""
        body = struct.pack('>HHB', response_serial, response_id, result)
        self.send_message(MessageID.TERMINAL_GENERAL_RESPONSE, body)
    
    def handle_incoming_message(self, msg_id: int, serial: int, body: bytes):
        """Handle messages from platform"""
        if msg_id == MessageID.PLATFORM_GENERAL_RESPONSE:
            result = body[4]
            print(f"Platform response: {'Success' if result == 0 else f'Failed ({result})'}")
        
        elif msg_id == MessageID.CHECK_ATTRIBUTE:
            self.send_attribute_response()
        
        elif msg_id == MessageID.CHECK_PARAMETER:
            self.send_parameter_response()
        
        elif msg_id == MessageID.TEXT_INFO:
            flags = body[0]
            text = body[1:].decode('gbk', errors='ignore')
            print(f"Received text message: {text}")
            self.send_general_response(serial, msg_id, 0)
        
        elif msg_id == MessageID.CAMERA_COMMAND:
            print("Received camera command")
            self.send_camera_response(serial)
        
        elif msg_id == MessageID.PARAMETER_SETTING:
            print("Received parameter setting")
            self.send_general_response(serial, msg_id, 0)
        
        else:
            print(f"Unhandled message: 0x{msg_id:04x}")
            self.send_general_response(serial, msg_id, 0)
    
    def send_attribute_response(self):
        """Send check terminal attribute response (0x0107)"""
        body = bytearray()
        
        # Terminal type (WORD)
        term_type = 0x0001  # Passenger vehicle applicable
        body.extend(struct.pack('>H', term_type))
        
        # Manufacturer ID (BYTE[5])
        body.extend(b'START')
        
        # Terminal model (BYTE[20])
        model = b'DC600' + b'\x00' * 15
        body.extend(model)
        
        # Terminal ID (BYTE[7])
        body.extend(b'TEST001')
        
        # SIM ICCID (BCD[10])
        body.extend(self.protocol._encode_bcd('12345678901234567890', 10))
        
        # Hardware version length (BYTE)
        hw_ver = "v1.0.0"
        body.append(len(hw_ver))
        body.extend(hw_ver.encode('gbk'))
        
        # Firmware version length (BYTE)
        fw_ver = "v2.0.5"
        body.append(len(fw_ver))
        body.extend(fw_ver.encode('gbk'))
        
        # GNSS module attribute (BYTE)
        gnss_attr = 0x01 | 0x02  # GPS + Beidou
        body.append(gnss_attr)
        
        # Communication module attribute (BYTE)
        comm_attr = 0x01  # GPRS
        body.append(comm_attr)
        
        self.send_message(MessageID.CHECK_ATTRIBUTE_RESPONSE, bytes(body))
    
    def send_parameter_response(self):
        """Send check terminal parameter response (0x0104)"""
        body = bytearray()
        
        # Response serial number (WORD) - last received serial
        body.extend(struct.pack('>H', self.protocol.msg_serial))
        
        # Number of parameters (BYTE)
        body.append(3)
        
        # Parameter 1: Heartbeat interval (0x0001)
        body.extend(struct.pack('>I', 0x0001))  # Parameter ID
        body.append(4)  # Length
        body.extend(struct.pack('>I', 30))  # 30 seconds
        
        # Parameter 2: Max speed (0x0055)
        body.extend(struct.pack('>I', 0x0055))
        body.append(4)
        body.extend(struct.pack('>I', 120))  # 120 km/h
        
        # Parameter 3: Overspeed duration (0x0056)
        body.extend(struct.pack('>I', 0x0056))
        body.append(4)
        body.extend(struct.pack('>I', 10))  # 10 seconds
        
        self.send_message(MessageID.CHECK_PARAMETER_RESPONSE, bytes(body))
    
    def send_camera_response(self, command_serial: int):
        """Send camera immediately taken command response (0x0805)"""
        body = bytearray()
        
        # Response serial number (WORD)
        body.extend(struct.pack('>H', command_serial))
        
        # Result (BYTE) - 0: success
        body.append(0)
        
        # Number of multimedia IDs (WORD)
        body.extend(struct.pack('>H', 2))
        
        # Multimedia ID list
        body.extend(struct.pack('>I', 12345))
        body.extend(struct.pack('>I', 12346))
        
        self.send_message(MessageID.CAMERA_TAKEN_RESPONSE, bytes(body))


def run_test_sequence(terminal: DC600Terminal):
    """Run a comprehensive test sequence"""
    print("\n" + "="*60)
    print("DC600 Terminal Test Sequence")
    print("="*60 + "\n")
    
    # 1. Register
    print("[TEST 1] Terminal Registration")
    if not terminal.register():
        print("Registration failed, aborting tests")
        return
    time.sleep(1)
    
    # 2. Authenticate
    print("\n[TEST 2] Terminal Authentication")
    if not terminal.authenticate():
        print("Authentication failed, aborting tests")
        return
    time.sleep(1)
    
    # 3. Send heartbeat
    print("\n[TEST 3] Heartbeat")
    terminal.send_heartbeat()
    terminal.receive_message(timeout=2)
    time.sleep(1)
    
    # 4. Normal location report
    print("\n[TEST 4] Normal Location Report")
    terminal.acc_on = True
    terminal.send_location()
    terminal.receive_message(timeout=2)
    time.sleep(1)
    
    # 5. Emergency alarm
    print("\n[TEST 5] Emergency Alarm")
    alarm = 1 << AlarmBit.EMERGENCY
    terminal.send_location(alarm)
    result = terminal.receive_message(timeout=2)
    time.sleep(1)
    
    # 6. Overspeed alarm
    print("\n[TEST 6] Overspeed Alarm")
    terminal.speed = 1300  # 130 km/h (in 1/10 km/h)
    alarm = 1 << AlarmBit.OVERSPEED
    terminal.send_location(alarm)
    terminal.receive_message(timeout=2)
    time.sleep(1)
    
    # 7. Fatigue driving warning
    print("\n[TEST 7] Fatigue Driving Warning")
    alarm = 1 << AlarmBit.FATIGUE_WARNING
    terminal.send_location(alarm)
    terminal.receive_message(timeout=2)
    time.sleep(1)
    
    # 8. Area in/out alarm
    print("\n[TEST 8] Area Enter/Exit Alarm")
    alarm = 1 << AlarmBit.AREA_IN_OUT
    terminal.latitude = 40.0 * 1000000  # Move location
    terminal.send_location(alarm)
    terminal.receive_message(timeout=2)
    time.sleep(1)
    
    # 9. Multiple alarms
    print("\n[TEST 9] Multiple Alarms (Overspeed + Fatigue)")
    alarm = (1 << AlarmBit.OVERSPEED) | (1 << AlarmBit.FATIGUE_WARNING)
    terminal.send_location(alarm)
    terminal.receive_message(timeout=2)
    time.sleep(1)
    
    # 10. Moving vehicle simulation
    print("\n[TEST 10] Moving Vehicle Simulation")
    for i in range(5):
        terminal.latitude += 0.001 * 1000000
        terminal.longitude += 0.001 * 1000000
        terminal.direction = (terminal.direction + 10) % 360
        terminal.speed = 600 + i * 50  # Gradually increase speed
        terminal.send_location()
        time.sleep(0.5)
    
    # 11. Listen for platform commands
    print("\n[TEST 11] Listening for Platform Commands (10 seconds)")
    start_time = time.time()
    while time.time() - start_time < 10:
        result = terminal.receive_message(timeout=1)
        if result:
            msg_id, serial, body = result
            terminal.handle_incoming_message(msg_id, serial, body)
    
    print("\n" + "="*60)
    print("Test sequence completed")
    print("="*60)


def interactive_mode(terminal: DC600Terminal):
    """Interactive mode for manual testing"""
    print("\n" + "="*60)
    print("DC600 Terminal Interactive Mode")
    print("="*60)
    print("\nCommands:")
    print("  1 - Register")
    print("  2 - Authenticate")
    print("  3 - Send heartbeat")
    print("  4 - Send normal location")
    print("  5 - Send emergency alarm")
    print("  6 - Send overspeed alarm")
    print("  7 - Send fatigue warning")
    print("  8 - Toggle ACC")
    print("  9 - Listen for messages")
    print("  0 - Run full test sequence")
    print("  q - Quit")
    print()
    
    while True:
        cmd = input("Enter command: ").strip().lower()
        
        if cmd == 'q':
            break
        elif cmd == '1':
            terminal.register()
        elif cmd == '2':
            terminal.authenticate()
        elif cmd == '3':
            terminal.send_heartbeat()
            terminal.receive_message(timeout=2)
        elif cmd == '4':
            terminal.send_location()
            terminal.receive_message(timeout=2)
        elif cmd == '5':
            alarm = 1 << AlarmBit.EMERGENCY
            terminal.send_location(alarm)
            terminal.receive_message(timeout=2)
        elif cmd == '6':
            terminal.speed = 1300
            alarm = 1 << AlarmBit.OVERSPEED
            terminal.send_location(alarm)
            terminal.receive_message(timeout=2)
        elif cmd == '7':
            alarm = 1 << AlarmBit.FATIGUE_WARNING
            terminal.send_location(alarm)
            terminal.receive_message(timeout=2)
        elif cmd == '8':
            terminal.acc_on = not terminal.acc_on
            print(f"ACC is now {'ON' if terminal.acc_on else 'OFF'}")
        elif cmd == '9':
            print("Listening for 10 seconds...")
            for _ in range(10):
                result = terminal.receive_message(timeout=1)
                if result:
                    msg_id, serial, body = result
                    terminal.handle_incoming_message(msg_id, serial, body)
        elif cmd == '0':
            run_test_sequence(terminal)
        else:
            print("Unknown command")


if __name__ == "__main__":
    import sys
    
    # Configuration
    HOST = "127.0.0.1"  # Change to your platform IP
    PORT = 5999  # Change to your platform port
    PHONE = "013800138000"
    USE_TCP = True  # Change to False for UDP
    
    print("DC600 GPRS Protocol Terminal Simulator")
    print(f"Connecting to {HOST}:{PORT}")
    
    terminal = DC600Terminal(HOST, PORT, PHONE, USE_TCP)
    
    try:
        terminal.connect()
        
        if len(sys.argv) > 1 and sys.argv[1] == '--auto':
            # Automatic test mode
            run_test_sequence(terminal)
        else:
            # Interactive mode
            interactive_mode(terminal)
    
    except KeyboardInterrupt:
        print("\nInterrupted by user")
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
    finally:
        terminal.disconnect()
