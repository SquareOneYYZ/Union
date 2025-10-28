# DC600 Protocol Test Suite

This test suite simulates a real DC600 device based on actual device logs from the 7pm session. It tests the complete alarm-to-video-request flow, including the critical 0x9208 → 0x1210 sequence.

## Purpose

Tests the DC600 protocol decoder implementation, specifically:
- ✓ Alarm event detection and storage
- ✓ 0x9208 alarm attachment request generation
- ✓ **CRITICAL**: Alarm flag population (bug fix verification)
- ✓ 0x1210 response handling
- ✓ Video upload flow initiation

## Prerequisites

```bash
# Python 3.7 or later required
python3 --version

# No external dependencies - uses only Python standard library
```

## Configuration

Edit `test_config.json` to match your environment:

```json
{
  "server": {
    "host": "localhost",     // Change to your server IP
    "port": 5999,            // DC600 protocol port
    "timeout": 30
  }
}
```

## Usage

### Quick Test (Single Alarm)

```bash
# Run all test scenarios
python3 test_dc600_protocol.py

# Or use python if that's your Python 3 executable
python test_dc600_protocol.py
```

### Test Scenarios

#### Scenario 1: Normal Alarm Flow (PRIMARY TEST)
```
Device → 0x0102 (Authentication) → Server
Device ← 0x8001 (Auth OK) ← Server
Device → 0x0200 with 0x70 multi-media event → Server
Device ← 0x9208 (Alarm attachment request) ← Server
Device → 0x1210 (Attachment info) → Server
```

**Expected Result**: 0x9208 received with **populated alarm flag** (not all zeros)

**Bug Verification**: If alarm flag is all zeros, the bug at `DC600ProtocolDecoder.java:759` is NOT fixed.

#### Scenario 2: Multiple Alarms
Tests handling of 3 concurrent alarms to verify:
- Sequence number management
- Multiple 0x9208 requests
- Event correlation for different alarm IDs

#### Scenario 3: Alarm Flag Verification
Specifically tests the bug fix:
- Sends alarm with 0x70 event
- Receives 0x9208
- **Parses alarm flag to verify it's NOT all zeros**
- Logs parsed alarm ID, device ID, and timestamp

## Understanding Test Output

### Success (Bug Fixed)
```
INFO: ✓ Received 0x9208 alarm attachment request!
INFO:   Alarm flag (16 bytes): 30303033393036251024094226010100
INFO:   Device ID: 0003906
INFO:   Alarm time (BCD): 251024094226
INFO:   Alarm ID: 1
INFO:   Alarm type: 1
INFO: ✓ TEST PASSED: Received valid 0x9208 with populated alarm flag
```

### Failure (Bug Still Present)
```
ERROR:   ✗ ALARM FLAG IS ALL ZEROS - Device will ignore this request!
ERROR:   ✗ Server bug: adasAlarmId/dsmAlarmId attribute not set
ERROR: ✗ TEST FAILED: Alarm flag is all zeros!
ERROR:    BUG NOT FIXED: DC600ProtocolDecoder.java:759 adasAlarmId not set
```

### No 0x9208 Received
```
ERROR: ✗ TIMEOUT: No 0x9208 received within timeout period
ERROR: ✗ TEST FAILED: No valid 0x9208 received
ERROR:    Possible causes:
ERROR:    1. Server did not send 0x9208 (bug in alarm detection)
ERROR:    2. 0x9208 alarm flag was all zeros (bug in sendAlarmAttachmentRequest)
ERROR:    3. Network issue
```

## Message Format Details

### Key Messages from Actual Device Logs

#### Terminal Authentication (0x0102)
```hex
7E 01 02 00 0C 49 60 76 89 89 91 00 01
34 39 36 30 37 36 38 39 38 39 39 31  // Auth code: "496076898991"
C2 7E
```

#### Location Report with Alarm (0x0200)
```hex
7E 02 00 00 5F 49 60 76 89 89 91 00 18
[Location data: lat, lon, speed, status...]
70 2F 00 00 00 00 00 03 00 01...  // 0x70 multi-media event marker
[Media info: 3 photos + 1 video]
62 7E
```

#### Alarm Attachment Request (0x9208) - From Server
```hex
7E 92 08 00 52 00 00 0D
31 36 35 2E 32 32 2E 32 32 38 2E 39 37  // Server IP: "165.22.228.97"
17 6F                                    // Port: 5999
00 00                                    // Reserved
[ALARM FLAG - 16 bytes - CRITICAL!]
30 30 30 33 39 30 36                     // Device ID: "0003906"
25 10 24 09 42 26                        // Alarm time: 2025-10-24 09:42:26
01                                       // Alarm ID: 1
01                                       // Alarm type: 1
00                                       // Reserved
[Alarm number - 32 bytes]
F3 7E
```

#### Alarm Attachment Info (0x1210) - From Device
```hex
7E 12 10 [body] checksum 7E
Body contains:
- Terminal ID (7 bytes)
- Alarm flag (16 bytes)
- Alarm serial number (32 bytes)
- File count (1 byte)
- File info list:
  - File name length + name
  - File size (4 bytes)
  - Media type (1 byte): 0=Image, 1=Audio, 2=Video
  - Channel ID (1 byte)
  - Event code (1 byte)
```

## Troubleshooting

### Connection Refused
```
ERROR: ✗ Connection failed: [Errno 111] Connection refused
```
**Solution**:
1. Verify server is running: `netstat -an | grep 5999`
2. Check firewall rules
3. Verify correct server IP in `test_config.json`

### Authentication Failed
```
ERROR: ✗ Authentication failed with result code: 1
```
**Solution**:
1. Check if device ID `496076898991` is registered in your system
2. Verify authentication code matches
3. Check server logs for authentication errors

### Timeout on 0x9208
```
ERROR: ✗ TIMEOUT: No 0x9208 received within timeout period
```
**Possible Causes**:
1. **Server not detecting alarm** - Check server logs for:
   ```
   WORKAROUND - Multi-media event detected (0x70)
   SENDING ALARM ATTACHMENT REQUEST (0x9208)
   ```
2. **Alarm flag not populated** - Bug at `DC600ProtocolDecoder.java:759`
3. **Network issue** - Use Wireshark to capture traffic

### Alarm Flag All Zeros
```
ERROR: ✗ ALARM FLAG IS ALL ZEROS - Device will ignore this request!
```
**Root Cause**: The fix at `DC600ProtocolDecoder.java:759` was not applied or didn't work.

**Required Fix**:
```java
// DC600ProtocolDecoder.java:759
position.set("adasAlarmId", mediaId);  // Use mediaId as alarmId for workaround
```

This ensures `sendAlarmAttachmentRequest()` populates the alarm flag at line 169.

## Protocol References

- **T/JSATL12-2017**: Active Safety Intelligent Prevention and Control System
  - Table 4-15: ADAS alarm information (0x64)
  - Table 4-17: DSM alarm information (0x65)
  - Table 4-19: Multi-media event ID (0x70)
  - Table 4-21: Alarm attachment information message (0x1210)
  - Table 8-18: Alarm attachment upload request (0x9208)

- **JT/T 808-2011**: Road transport vehicle satellite positioning system
  - Terminal communication protocol

## Integration with CI/CD

```bash
# Add to your CI pipeline
python3 tools/dc600/test_dc600_protocol.py
if [ $? -ne 0 ]; then
    echo "DC600 protocol tests failed"
    exit 1
fi
```

## Wireshark Analysis

To capture and analyze DC600 protocol traffic:

```bash
# Capture on port 5999
tcpdump -i any -w dc600_test.pcap port 5999

# Or use Wireshark with display filter:
tcp.port == 5999
```

Look for:
1. **Device → Server**: 0x0200 messages with 0x70 field
2. **Server → Device**: 0x9208 messages
3. **Device → Server**: 0x1210 messages (only if 0x9208 alarm flag is valid)

## Expected Test Results

After deploying the fix at `DC600ProtocolDecoder.java:759`:

```
TEST RESULTS SUMMARY
============================================================
Scenario 1: Normal Alarm Flow: ✓ PASSED
Scenario 2: Multiple Alarms: ✓ PASSED
Scenario 3: Alarm Flag Verification: ✓ PASSED

Total: 3/3 tests passed
============================================================
```

## Source Data

Test messages are derived from actual device logs:
- **Device logs**: `C:\Users\Filing Cabinet\Downloads\device logs\7pm\files\`
  - `easy_log/20251024-075424.log`: Main communication log
  - `rec_log/20251024-075418.log`: Media capture log
- **Alarm timestamp**: 2025-10-24 07:57:30
- **Alarm type**: Swerve (warntype:42, ConvertType:38)
- **Media files**: 3 photos + 1 video captured

## Contact

For issues or questions about this test suite, refer to:
- Root cause analysis: `DC600_VIDEO_STORE_REQUEST_ROOT_CAUSE.md`
- Implementation: `src/main/java/org/traccar/protocol/DC600ProtocolDecoder.java`
