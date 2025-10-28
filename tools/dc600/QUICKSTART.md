# DC600 Device Simulator - Quick Start Guide

## Overview

This comprehensive Python script simulates a DC600 GPS tracking device with full JT808/JT1078 protocol support, including all ADAS and DSM alarm types based on manufacturer specifications.

## Quick Start (5 Steps)

### 1. Verify Python Installation

```bash
python3 --version
# Should be Python 3.7 or higher
```

### 2. Edit Configuration

Open `dc600_config.json` and set your server details:

```json
{
  "server_ip": "YOUR_SERVER_IP",
  "server_port": YOUR_PORT,
  "phone_number": "YOUR_DEVICE_ID"
}
```

### 3. Run the Simulator

```bash
cd "C:\Users\Filing Cabinet\IdeaProjects\test\tools\dc600"
python3 dc600_device_simulator.py -c dc600_config.json
```

### 4. Watch the Output

You should see:
```
[INFO] Connecting to SERVER_IP:PORT...
[INFO] Connected to server
[INFO] Sending registration request...
[INFO] Registration successful! Auth code: XXX
[INFO] Sending authentication with code: XXX
[INFO] Authentication successful!
[INFO] Device is running. Press Ctrl+C to stop.
```

### 5. Observe Alarm Testing

The simulator will automatically:
- Send heartbeat every 30 seconds
- Send location reports every 10 seconds
- Send ADAS alarms every 60 seconds (alternating with DSM alarms)
- Respond to all server commands

## What Gets Tested

### ADAS Alarms (From Real Device Logs)
Based on manufacturer test data in `easy_log/19700101-163544.log`:

- **warntype 1** - Forward Collision Warning (FCW)
- **warntype 2** - Pedestrian Collision Warning (PCW)
- **warntype 3** - Lane Departure Warning (LDW)
- **warntype 4** - Headway Monitoring Warning (HMW)
- **warntype 5** - Road Sign Recognition

### DSM Alarms (From Real Device Logs)
- **warntype 9** - Phone Call (no cellphone using)
- **warntype 10** - Smoking (no smoking)
- **warntype 11** - Distraction (please keep attention)
- **warntype 12** - Fatigue Driving (please take a break)
- **warntype 14** - Seatbelt Not Fastened (please fasten your seat belt)

### Multimedia Flow
1. Alarm occurs → Device sends 0x0200 with alarm flag and extra data (0x64/0x65)
2. Server sends 0x9208 alarm attachment request
3. Device responds with 0x1210 file info upload (list of videos + photos)
4. Device ready to upload files via 0x1211/0x1212

### Command Responses
The simulator accepts and responds to:
- 0x8801 - Camera shot immediately
- 0x8802 - Multimedia retrieval query
- 0x8803 - Multimedia upload by time
- 0x8805 - Single multimedia upload
- 0x9101 - Video live stream request
- 0x9201 - Video playback request
- 0x9208 - Alarm attachment upload request

## Configuration Examples

### Test on Staging Server

```json
{
  "server_ip": "143.198.33.215",
  "server_port": 5023,
  "phone_number": "013644081335",
  "test_scenarios": {
    "enable_adas_alarms": true,
    "enable_dsm_alarms": true,
    "alarm_interval": 60
  }
}
```

### Test on Production Server

```json
{
  "server_ip": "YOUR_PROD_IP",
  "server_port": 5023,
  "phone_number": "YOUR_DEVICE_ID",
  "test_scenarios": {
    "enable_adas_alarms": true,
    "enable_dsm_alarms": true,
    "alarm_interval": 120
  }
}
```

### Fast Testing (Every 10 Seconds)

```json
{
  "server_ip": "143.198.33.215",
  "server_port": 5023,
  "phone_number": "013644081335",
  "heartbeat_interval": 10,
  "location_interval": 5,
  "test_scenarios": {
    "enable_adas_alarms": true,
    "enable_dsm_alarms": true,
    "alarm_interval": 10,
    "simulate_movement": true
  }
}
```

## Validation Checklist

After running the simulator, verify on your server:

### Registration & Authentication
- [ ] Device appears in device list
- [ ] Device shows as online
- [ ] Authentication successful

### Location Reporting
- [ ] Location updates every 10 seconds
- [ ] GPS coordinates are valid
- [ ] Speed and direction are changing (if simulate_movement=true)

### ADAS Alarms
- [ ] Forward collision warning events created
- [ ] Lane departure warning events created
- [ ] Pedestrian collision warning events created
- [ ] Headway monitoring warning events created
- [ ] Road sign recognition events created

### DSM Alarms
- [ ] Fatigue driving events created
- [ ] Phone call events created
- [ ] Smoking events created
- [ ] Distraction events created
- [ ] Seatbelt unfastened events created

### Alarm Attachment Flow
- [ ] Server sends 0x9208 request after alarm
- [ ] Device responds with 0x1210 file info
- [ ] File list includes video + 3 photos
- [ ] Multimedia IDs are valid

### Command Handling
- [ ] Camera shot command works (0x8801)
- [ ] Multimedia retrieval works (0x8802)
- [ ] Video playback request accepted (0x9201)

## Troubleshooting

### "Connection refused"
```bash
# Check server IP and port
# Verify server is running
# Check firewall settings
```

### "Authentication failed"
```bash
# Verify device is registered on server
# Check phone number is correct (12 digits)
# Review server logs for error details
```

### "No alarms appearing"
```bash
# Check alarm_interval in config (default 60 seconds)
# Verify enable_adas_alarms and enable_dsm_alarms are true
# Wait at least alarm_interval seconds after authentication
# Check server logs for incoming 0x0200 messages with alarm flags
```

### "0x9208 not received"
```bash
# Verify server has alarm attachment upload feature enabled
# Check server logs for 0x9208 sending
# Ensure alarms include proper extra data (0x64/0x65)
```

## Advanced Usage

### Run Multiple Devices

```bash
# Terminal 1
python3 dc600_device_simulator.py -c device1.json

# Terminal 2
python3 dc600_device_simulator.py -c device2.json

# Terminal 3
python3 dc600_device_simulator.py -c device3.json
```

Each config should have unique `phone_number`.

### Custom Alarm Sequence

Edit the script to send specific alarms:

```python
# After authentication, send specific alarm
device.send_adas_alarm(ADASAlarmType.FORWARD_COLLISION_WARNING, level=2)
device.send_dsm_alarm(DSMAlarmType.FATIGUE_DRIVING, level=1)
```

### Debug Mode

Check `dc600_simulator.log` for detailed protocol messages:

```
2025-10-25 10:30:15 [DEBUG] Sending message 0x0200, body length: 95
2025-10-25 10:30:15 [DEBUG] Frame: 7e02004...checksum...7e
2025-10-25 10:30:16 [INFO] Received message 0x9208, sequence: 123
2025-10-25 10:30:16 [DEBUG] Body: 00010000000125...
```

## Based on Manufacturer Data

This simulator replicates exact behavior from:

**Device Logs:**
- `C:/Users/Filing Cabinet/Downloads/DC600 all alarms for ADAS and DSM/easy_log/19700101-163544.log`
- Real alarm types: warnType 1, 2, 3, 4, 5, 9, 10, 11, 12, 14
- Real alarm flow: recShare → capturetime → Send alarm

**Sample Media:**
- `C:/Users/Filing Cabinet/Downloads/DC600 all alarms for ADAS and DSM/Photo/CH1/*.jpg`
- `C:/Users/Filing Cabinet/Downloads/DC600 all alarms for ADAS and DSM/Photo/CH2/*.jpg`

**Protocol Specifications:**
- `ref-docs/DC600 Communication Protocol.pdf` - JT808 base protocol
- `ref-docs/DC600 ADAS/DSM Protocol.pdf` - T/JSATL12-2017 alarm extensions

## Support

Need help? Check:

1. **README** - Full documentation: `DC600_SIMULATOR_README.md`
2. **Logs** - Detailed activity: `dc600_simulator.log`
3. **Server Logs** - Server-side view of communication
4. **Protocol Docs** - Specification details: `ref-docs/`

---

**Enjoy testing! The simulator will run continuously until you press Ctrl+C.**
