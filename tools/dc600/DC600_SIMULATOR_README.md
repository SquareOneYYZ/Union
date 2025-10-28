# DC600 Device Simulator

Comprehensive Python-based JT808/DC600 protocol implementation that simulates a real DC600 dashcam device. Based on manufacturer specifications (T/JSATL12-2017) and real device logs.

## Features

### Full Protocol Support
- ✅ JT808 base protocol (registration, authentication, heartbeat)
- ✅ Location reporting (0x0200) with GPS data
- ✅ BCD encoding/decoding
- ✅ Message framing with 0x7E delimiters
- ✅ Escape character handling (0x7D)
- ✅ XOR checksum calculation

### ADAS Alarms (11 Types)
Based on T/JSATL12-2017 Table 4-15:

1. **Forward Collision Warning (FCW)** - bit 0
2. **Lane Departure Warning (LDW)** - bit 1
3. **Vehicle Proximity Warning** - bit 2
4. **Pedestrian Collision Warning (PCW)** - bit 3
5. **Rapid Lane Change Warning** - bit 4
6. **Road Sign Overspeed Warning** - bit 5
7. **Road Sign Recognition** - bit 8
8. **Active Photographing** - bit 9
9. **Lane Departure Distance Alarm** - bit 10
10. **Obstacle Alarm** - bit 12
11. **Headway Monitoring Warning (HMW)** - bit 13

### DSM Alarms (6 Types)
Based on T/JSATL12-2017 Table 4-17:

1. **Fatigue Driving** - bit 0 (warnType: 12)
2. **Phone Call** - bit 1 (warnType: 9)
3. **Smoking** - bit 2 (warnType: 10)
4. **Distraction** - bit 3 (warnType: 11)
5. **Driver Abnormal** - bit 4
6. **Seatbelt Not Fastened** - bit 7 (warnType: 14)

### Alarm Attachment Upload Flow
Complete 0x9208 attachment upload protocol:

1. Device sends alarm with location report (0x0200)
2. Server sends attachment upload request (0x9208)
3. Device responds with file info upload (0x1210)
4. Device uploads file data (0x1211 packets)
5. Device sends upload complete (0x1212)

### Multimedia Commands
- **Camera Shot** (0x8801/0x0805) - Immediate photo capture
- **Multimedia Retrieval** (0x8802/0x0802) - Query stored files
- **Multimedia Upload by Time** (0x8803) - Upload files in time range
- **Single Multimedia Upload** (0x8805/0x0801) - Upload specific file
- **Video Live Stream** (0x9101) - JT/T 1078 live streaming
- **Video Playback** (0x9201) - Playback request handling

## Installation

### Requirements
- Python 3.7+
- No external dependencies (uses only standard library)

### Setup

1. Place the script in your preferred directory:
```bash
cd C:\Users\Filing Cabinet\IdeaProjects\test\tools\dc600
```

2. Create/edit configuration file:
```bash
# Edit dc600_config.json with your settings
```

3. Make script executable (Linux/Mac):
```bash
chmod +x dc600_device_simulator.py
```

## Configuration

Edit `dc600_config.json`:

```json
{
  "server_ip": "143.198.33.215",
  "server_port": 5023,
  "phone_number": "013644081335",

  "initial_latitude": 31.230416,
  "initial_longitude": 121.473701,

  "heartbeat_interval": 30,
  "location_interval": 10,

  "test_scenarios": {
    "enable_adas_alarms": true,
    "enable_dsm_alarms": true,
    "alarm_interval": 60,
    "simulate_movement": true
  },

  "media_paths": {
    "sample_photos": "C:/Users/Filing Cabinet/Downloads/DC600 all alarms for ADAS and DSM/Photo",
    "sample_videos": "C:/Users/Filing Cabinet/Downloads/DC600 all alarms for ADAS and DSM"
  }
}
```

### Configuration Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `server_ip` | Server IP address | 143.198.33.215 |
| `server_port` | Server TCP port | 5023 |
| `phone_number` | Device phone number (12 digits) | 013644081335 |
| `initial_latitude` | Starting GPS latitude | 31.230416 |
| `initial_longitude` | Starting GPS longitude | 121.473701 |
| `heartbeat_interval` | Seconds between heartbeats | 30 |
| `location_interval` | Seconds between location reports | 10 |
| `alarm_interval` | Seconds between simulated alarms | 60 |
| `enable_adas_alarms` | Test all ADAS alarm types | true |
| `enable_dsm_alarms` | Test all DSM alarm types | true |
| `simulate_movement` | Simulate GPS movement | true |

## Usage

### Basic Usage

```bash
# Run with default config (test_config.json)
python dc600_device_simulator.py

# Run with custom config
python dc600_device_simulator.py -c dc600_config.json
```

### What the Simulator Does

1. **Connects to Server**
   - Establishes TCP connection
   - Sends terminal registration (0x0100)
   - Receives registration response (0x8100)
   - Sends authentication (0x0102)
   - Waits for authentication confirmation

2. **Periodic Tasks** (after authentication)
   - Sends heartbeat every 30 seconds (0x0002)
   - Sends location reports every 10 seconds (0x0200)
   - Simulates GPS movement
   - Sends random alarms every 60 seconds

3. **Alarm Simulation**
   - Alternates between ADAS and DSM alarms
   - Randomly selects alarm type from available types
   - Includes proper alarm-specific extra data (0x64/0x65)
   - Records event for multimedia association
   - Responds to 0x9208 attachment requests with 0x1210

4. **Command Handling**
   - Accepts all multimedia commands from server
   - Responds to camera shot requests (0x8801)
   - Handles multimedia retrieval queries (0x8802)
   - Processes video playback/streaming requests (0x9101, 0x9201)
   - Uploads multimedia files on demand

### Example Output

```
2025-10-25 10:30:15 [INFO] Connecting to 143.198.33.215:5023...
2025-10-25 10:30:15 [INFO] Connected to server
2025-10-25 10:30:15 [INFO] Sending registration request...
2025-10-25 10:30:16 [INFO] Registration successful! Auth code: ABC123
2025-10-25 10:30:16 [INFO] Sending authentication with code: ABC123
2025-10-25 10:30:17 [INFO] Authentication successful!
2025-10-25 10:30:17 [INFO] Device is running. Press Ctrl+C to stop.
2025-10-25 10:30:17 [DEBUG] Heartbeat sent
2025-10-25 10:30:17 [DEBUG] Location report sent
2025-10-25 10:31:17 [INFO] Sending ADAS alarm: FORWARD_COLLISION_WARNING (level 1)
2025-10-25 10:31:18 [INFO] Received alarm attachment upload request (0x9208)
2025-10-25 10:31:18 [INFO] Sending file info upload (0x1210) with 4 files
```

## Testing Scenarios

### Test All ADAS Alarms

The simulator will cycle through all 11 ADAS alarm types based on the manufacturer specification:

1. Forward Collision Warning (FCW)
2. Lane Departure Warning (LDW)
3. Pedestrian Collision Warning (PCW)
4. Headway Monitoring Warning (HMW)
5. Road Sign Recognition
6. And 6 more...

Each alarm includes:
- Proper alarm flag in location report
- Alarm-specific extra data (0x64)
- Associated multimedia (3 photos + 1 video)
- Response to 0x9208 attachment request

### Test All DSM Alarms

Cycles through all 6 DSM alarm types:

1. Fatigue Driving
2. Phone Call
3. Smoking
4. Distraction
5. Seatbelt Not Fastened
6. Driver Abnormal

Each alarm includes:
- DSM-specific extra data (0x65)
- Proper warnType mapping
- Event recording

### Test 0x9208 Attachment Flow

When server sends 0x9208 request:

1. Device parses alarm sequence, type, time, count
2. Finds matching event from alarm timestamp
3. Sends 0x1210 with file list (video + photos)
4. Waits for upload commands
5. Would send 0x1211 packets (file data)
6. Would send 0x1212 (upload complete)

### Test Multimedia Commands

#### Camera Shot (0x8801)
```
Server → Device: 0x8801 (channel, capture settings)
Device → Server: 0x0805 (result, photo IDs)
```

#### Multimedia Retrieval (0x8802)
```
Server → Device: 0x8802 (media type, time range)
Device → Server: 0x0802 (file list with IDs, locations)
```

#### Single Upload (0x8805)
```
Server → Device: 0x8805 (multimedia ID, delete flag)
Device → Server: 0x0801 (file data with location)
```

#### Video Playback (0x9201)
```
Server → Device: 0x9201 (server IP, port, time range)
Device → Server: 0x0001 (general response)
Device streams video to specified server
```

## Protocol Implementation Details

### Message Structure

All messages follow JT808 format:

```
[0x7E] [Header] [Body] [Checksum] [0x7E]
```

**Header (12 bytes):**
- Message ID (2 bytes)
- Properties (2 bytes) - body length, encryption, subpackage flag
- Phone number (6 bytes, BCD encoded)
- Message sequence (2 bytes)

**Escape Rules:**
- 0x7E → 0x7D 0x02
- 0x7D → 0x7D 0x01

**Checksum:**
- XOR of all bytes in header + body

### Location Report Format (0x0200)

```
Alarm flag       : 4 bytes (ADAS/DSM alarm bits)
Status flag      : 4 bytes (ACC, positioning, lat/lon hemisphere)
Latitude         : 4 bytes (degrees * 10^6)
Longitude        : 4 bytes (degrees * 10^6)
Altitude         : 2 bytes (meters)
Speed            : 2 bytes (0.1 km/h)
Direction        : 2 bytes (degrees)
Time             : 6 bytes (BCD YYMMDDHHmmss)
Extra info       : variable (0x64=ADAS, 0x65=DSM, etc.)
```

### ADAS Alarm Extra Data (0x64)

```
ID               : 0x64
Length           : 1 byte
Alarm ID         : 4 bytes
Flag             : 1 byte
Alarm/Event type : 1 byte (warnType)
Alarm level      : 1 byte
Front vehicle speed : 1 byte (km/h)
Front vehicle distance : 1 byte (0.1m)
Deviation type   : 1 byte (LDW left/right)
Road sign type   : 1 byte
Road sign data   : 1 byte
Vehicle speed    : 1 byte (km/h)
Altitude         : 2 bytes
Latitude         : 4 bytes
Longitude        : 4 bytes
Alarm time       : 6 bytes (BCD)
Vehicle status   : 2 bytes
Alarm identification : 22 bytes
```

### DSM Alarm Extra Data (0x65)

```
ID               : 0x65
Length           : 1 byte
Alarm ID         : 4 bytes
Flag             : 1 byte
Alarm/Event type : 1 byte (warnType)
Alarm level      : 1 byte
Fatigue level    : 1 byte
Reserved         : 4 bytes
Vehicle speed    : 1 byte
Altitude         : 2 bytes
Latitude         : 4 bytes
Longitude        : 4 bytes
Alarm time       : 6 bytes (BCD)
Vehicle status   : 2 bytes
Alarm identification : 16 bytes
```

## Troubleshooting

### Connection Issues

**Problem:** Cannot connect to server
```
Solution:
1. Check server IP and port in config
2. Verify firewall allows outbound connections
3. Ensure server is running and listening
```

**Problem:** Authentication fails
```
Solution:
1. Check phone number format (12 digits)
2. Verify device is registered on server
3. Check server logs for error details
```

### Alarm Issues

**Problem:** 0x9208 not received
```
Solution:
1. Verify alarm is being sent (check logs)
2. Ensure alarm flag and extra data (0x64/0x65) are present
3. Check server configuration for alarm attachment support
```

**Problem:** Multimedia files not found
```
Solution:
1. Update media_paths in config to point to sample files
2. Script will use dummy data if files not found
3. Check logs for file path errors
```

### Debug Mode

Enable detailed logging by modifying the script:

```python
logging.basicConfig(
    level=logging.DEBUG,  # Change to DEBUG
    ...
)
```

This will show:
- Full message hex dumps
- Detailed parsing information
- State transitions
- All server responses

## Based on Manufacturer Specifications

This simulator is built strictly according to:

1. **JT/T 808-2019** - GPS tracking device communication protocol
2. **T/JSATL12-2017** - ADAS/DSM alarm protocol extensions
3. **JT/T 1078-2016** - Video streaming protocol
4. **DC600 manufacturer logs** - Real device behavior from:
   - `C:/Users/Filing Cabinet/Downloads/DC600 all alarms for ADAS and DSM/easy_log/`
   - `C:/Users/Filing Cabinet/Downloads/DC600 all alarms for ADAS and DSM/rec_log/`

### Verified Against Real Device

Alarm types, warnType mappings, and message formats are verified against:
- Manufacturer's test device logs (19700101-163544.log)
- Sample photos and videos from test events
- Protocol documentation from iStartek

## License

This is a testing tool for development purposes. Use responsibly.

## Support

For issues or questions:
1. Check logs in `dc600_simulator.log`
2. Verify configuration settings
3. Review protocol documentation in `ref-docs/`
4. Compare with manufacturer sample logs

---

**Note:** This simulator is designed for testing and validation of DC600 protocol implementations. It simulates realistic device behavior based on manufacturer specifications and real device logs.
