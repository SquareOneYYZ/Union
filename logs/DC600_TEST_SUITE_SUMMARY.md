# DC600 Protocol Test Suite - Complete Summary

## Overview

This test suite was created to verify the DC600 protocol implementation, specifically the critical bug fix for alarm attachment request handling (0x9208 → 0x1210 flow).

## Files Created

### 1. `test_dc600_protocol.py` (Main Test Suite)
**Purpose**: Comprehensive test suite simulating real DC600 device behavior

**Features**:
- ✓ Full JT808 protocol message encoding/decoding
- ✓ Checksum calculation and validation
- ✓ Message escaping/unescaping (0x7D, 0x7E handling)
- ✓ Terminal authentication (0x0102)
- ✓ Heartbeat messages (0x0002)
- ✓ Location reports (0x0200) with and without alarms
- ✓ 0x70 multi-media event simulation
- ✓ 0x9208 listener and parser
- ✓ 0x1210 response generator
- ✓ Alarm flag validation logic

**Test Scenarios**:
1. **Scenario 1**: Normal alarm flow (primary test)
   - Authenticates → Sends heartbeat → Sends location reports → Sends alarm → Listens for 0x9208 → Validates alarm flag → Sends 0x1210
2. **Scenario 2**: Multiple concurrent alarms
   - Tests handling of 3 alarms in quick succession
3. **Scenario 3**: Alarm flag verification
   - Specifically tests that alarm flag is NOT all zeros (bug verification)

**Usage**:
```bash
python3 test_dc600_protocol.py
```

### 2. `quick_test.py` (Rapid Development Tool)
**Purpose**: Quick single-alarm test for rapid iteration during development

**Features**:
- ✓ Single alarm test (fastest way to verify fix)
- ✓ Clear pass/fail output
- ✓ Detailed alarm flag analysis
- ✓ Command-line server configuration

**Usage**:
```bash
# Test against local server
python3 quick_test.py localhost 5999

# Test against staging
python3 quick_test.py 165.22.228.97 5999
```

### 3. `analyze_9208.py` (Log Analysis Tool)
**Purpose**: Analyze 0x9208 messages from server logs to verify alarm flag population

**Features**:
- ✓ Extracts all 0x9208 messages from log files
- ✓ Parses message structure
- ✓ Validates alarm flag contents
- ✓ Identifies empty alarm flags (bug indicator)
- ✓ Provides summary statistics

**Usage**:
```bash
# Analyze server log file
python3 analyze_9208.py logs/logs-2.log

# Pipe from grep
grep '9208' logs/logs-2.log | python3 analyze_9208.py -
```

**Example Output**:
```
0x9208 Message #1
============================================================
Message ID:     0x9208
Device ID:      496076898991
Sequence:       9
Server:         165.22.228.97:5999

Alarm Flag:     30303033393036251024094226010100
Status:         ✓ POPULATED (GOOD)

  Device ID:    0003906
  Alarm Time:   251024094226
  Alarm ID:     1
  Alarm Type:   1
```

### 4. `test_config.json` (Configuration)
**Purpose**: Centralized configuration for test parameters

**Contents**:
```json
{
  "server": {
    "host": "165.22.228.97",
    "port": 5999
  },
  "device": {
    "device_id": "496076898991"
  }
}
```

### 5. `TEST_README.md` (Documentation)
**Purpose**: Complete user guide for the test suite

**Sections**:
- Prerequisites and setup
- Configuration instructions
- Usage examples
- Understanding test output
- Message format details
- Troubleshooting guide
- Protocol references
- Wireshark analysis tips

### 6. `DC600_TEST_SUITE_SUMMARY.md` (This File)
**Purpose**: High-level overview and quick reference

---

## The Bug Being Tested

### Root Cause
**Location**: `DC600ProtocolDecoder.java:169-179`

The `sendAlarmAttachmentRequest()` method only populates the alarm flag if the position has `adasAlarmId` or `dsmAlarmId` attributes. The 0x70 workaround code was NOT setting these attributes, resulting in an empty alarm flag being sent to the device.

### The Fix
**Location**: `DC600ProtocolDecoder.java:759`

```java
// Set alarm ID so sendAlarmAttachmentRequest populates the alarm flag
position.set("adasAlarmId", mediaId);  // Use mediaId as alarmId for workaround
```

### Why This Matters
Without a populated alarm flag, devices cannot determine:
- Which alarm the request is for
- Which media files to upload
- Whether the request is valid

Result: **Device silently ignores the 0x9208 request and never sends 0x1210**

---

## Message Flow

### Expected Flow (After Fix)
```
1. Device → 0x0200 with 0x70 (multi-media event)
   ├─ Server detects 0x70 (WORKAROUND log)
   └─ Server sets adasAlarmId = mediaId

2. Server → 0x9208 (alarm attachment request)
   ├─ Alarm flag populated:
   │  - Device ID: "0003906"
   │  - Alarm time: 251024094226
   │  - Alarm ID: 1
   │  - Alarm type: 1
   └─ Message sent to device

3. Device ← 0x9208 (receives valid request)
   ├─ Parses alarm flag
   ├─ Identifies media files
   └─ Prepares response

4. Device → 0x1210 (alarm attachment info)
   ├─ Lists 4 files:
   │  - CH1IMG20251024-075730-0.jpg
   │  - CH1IMG20251024-075730-1.jpg
   │  - CH1IMG20251024-075730-2.jpg
   │  - CH1EVT20251024-075730-0.mp4
   └─ Sends to server

5. Server ← 0x1210 (video upload flow initiated!)
```

### Broken Flow (Before Fix)
```
1. Device → 0x0200 with 0x70
   ├─ Server detects 0x70
   └─ Server does NOT set adasAlarmId

2. Server → 0x9208
   ├─ Alarm flag is ALL ZEROS!
   │  - 00 00 00 00 00 00 00 00
   │  - 00 00 00 00 00 00 00 00
   └─ Message sent to device

3. Device ← 0x9208
   ├─ Sees empty alarm flag
   ├─ Cannot identify alarm
   └─ SILENTLY IGNORES REQUEST

4. ✗ NO 0x1210 SENT
5. ✗ VIDEO UPLOAD FLOW BROKEN
```

---

## Verification Steps

### 1. Deploy Fix
```bash
./gradlew build
# Deploy to server
```

### 2. Run Quick Test
```bash
python3 tools/dc600/quick_test.py 165.22.228.97 5999
```

**Expected Output (Success)**:
```
Step 5: Listening for 0x9208 alarm attachment request...
INFO: ✓ Received 0x9208 alarm attachment request!
INFO:   Alarm flag (16 bytes): 30303033393036251024094226010100
INFO:   Device ID: 0003906
INFO: ✓ TEST PASSED: Alarm flag is properly populated!

✓ RESULT: ✓ PASSED
```

### 3. Analyze Server Logs
```bash
python3 tools/dc600/analyze_9208.py logs/production.log
```

**Expected Output (Success)**:
```
SUMMARY
============================================================
Total 0x9208 messages:     15
  ✓ With populated flag:   15
  ✗ With empty flag (bug): 0

✓ ALL 0x9208 MESSAGES HAVE POPULATED ALARM FLAGS
  Bug fix verified successfully!
```

### 4. Monitor Real Device
```bash
# On device, check for:
tail -f /var/log/device.log | grep "recv id = 0x9208"

# Should see:
[jt2013_M1][20251024-094226]: [recv id = 0x9208, seq = X, pack(0/0), len = 91]
[jt2013_M1][20251024-094227]: [send id = 0x1210, seq = Y, pack(0/0), len = Z]
```

---

## Test Data Source

All test messages are extracted from actual device logs:

**Source Logs**:
- `C:\Users\Filing Cabinet\Downloads\device logs\7pm\files\easy_log\20251024-075424.log`
- `C:\Users\Filing Cabinet\Downloads\device logs\7pm\files\rec_log\20251024-075418.log`

**Key Events** (from 7pm session):
- **Timestamp**: 2025-10-24 07:57:30
- **Alarm Type**: Swerve (warntype:42, ConvertType:38)
- **Media Captured**:
  - 3 photos: CH1IMG20251024-075730-{0,1,2}.jpg
  - 1 video: CH1EVT20251024-075730-0.mp4
- **Alarm ID**: 1761292650
- **Device ID**: 496076898991

**Extracted Messages**:
```
Terminal Auth (0x0102):
7E 01 02 00 0C 49 60 76 89 89 91 00 01
34 39 36 30 37 36 38 39 38 39 39 31 C2 7E

Location Report with Alarm (0x0200):
7E 02 00 00 5F 49 60 76 89 89 91 00 18 00 00 00 00 00 4C 00 0B 02 99 B0 2C
04 C0 75 8C 00 95 01 2A 00 7A 25 10 24 07 57 36 01 04 00 00 00 00 25 04 00
00 00 00 30 01 15 31 01 1E 70 2F 00 00 00 00 00 03 00 01 00 1C 00 00 14 00
96 02 99 B1 21 04 C0 76 42 25 10 24 07 57 30 04 01 30 00 00 00 00 00 00 25
10 24 07 57 30 00 04 00 62 7E
```

---

## Success Criteria

### Critical Test (Scenario 3)
✓ **PASS**: Alarm flag is populated with valid data
✗ **FAIL**: Alarm flag is all zeros

### Complete Flow (Scenario 1)
✓ **PASS**: Device receives 0x9208 and sends 0x1210
✗ **FAIL**: Device does not send 0x1210

### Multiple Alarms (Scenario 2)
✓ **PASS**: All 3 alarms trigger 0x9208 with valid flags
✗ **FAIL**: Some 0x9208 messages have empty flags

---

## Troubleshooting Matrix

| Symptom | Cause | Solution |
|---------|-------|----------|
| No 0x9208 received | Alarm not detected | Check for "WORKAROUND - Multi-media event detected" in logs |
| 0x9208 alarm flag all zeros | Bug not fixed | Verify `position.set("adasAlarmId", mediaId)` at line 759 |
| Connection refused | Server not running | Start server or check firewall |
| Auth failed | Device not registered | Register device ID 496076898991 |
| Timeout on 0x9208 | Network issue | Use tcpdump/Wireshark to capture traffic |

---

## Quick Reference Commands

```bash
# Run full test suite
python3 tools/dc600/test_dc600_protocol.py

# Quick single alarm test
python3 tools/dc600/quick_test.py localhost 5999

# Analyze server logs
python3 tools/dc600/analyze_9208.py logs/server.log

# Capture network traffic
tcpdump -i any -w dc600_test.pcap port 5999

# Check server logs for alarm detection
grep -A 5 "WORKAROUND" logs/server.log

# Check for 0x9208 transmission
grep "0x9208 WRITE SUCCESS" logs/server.log
```

---

## Integration with CI/CD

Add to your pipeline:

```yaml
# .github/workflows/test.yml
- name: Test DC600 Protocol
  run: |
    python3 tools/dc600/test_dc600_protocol.py
    if [ $? -ne 0 ]; then
      echo "DC600 protocol tests failed"
      exit 1
    fi
```

---

## Related Documentation

- **Root Cause Analysis**: `DC600_VIDEO_STORE_REQUEST_ROOT_CAUSE.md`
- **Implementation**: `src/main/java/org/traccar/protocol/DC600ProtocolDecoder.java`
- **Protocol Spec**: `ref-docs/T-JSATL12-2017.pdf`
- **Test Logs**: `C:\Users\Filing Cabinet\Downloads\device logs\7pm\`

---

## Contact & Support

For questions or issues:
1. Check `TEST_README.md` for detailed troubleshooting
2. Review server logs for alarm detection
3. Use `analyze_9208.py` to verify alarm flag population
4. Capture network traffic with Wireshark if needed

---

**Created**: 2025-10-24
**Purpose**: Test DC600 alarm attachment request bug fix
**Bug Location**: DC600ProtocolDecoder.java:759
**Fix**: `position.set("adasAlarmId", mediaId);`
