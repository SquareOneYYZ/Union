# DC600 Video Store Request - Oct 24 Analysis

## Issue Summary

**Problem**: After alarms are detected and stored in the events table, devices are NOT sending 0x1210 (alarm attachment information) responses, preventing video upload initiation.

**Status**: ❌ PARTIALLY WORKING - 0x9208 requests are being sent with populated alarm flags, but devices are not responding with 0x1210

---

## Timeline of Events (Oct 24, 13:45)

### Alarm #1: MediaId=0

| Time | Event | Details | Line # |
|------|-------|---------|--------|
| 13:45:39 | Device → 0x0200 with 0x70 | MediaId=0, EventCode=0x0 | 9217 |
| 13:45:39 | Server detects 0x70 | WORKAROUND triggered | 9220 |
| 13:45:39 | Server → 0x9208 | AlarmId=0, with POPULATED alarm flag | 9260 |
| 13:45:39 | Write SUCCESS | 0x9208 sent successfully | 9262 |
| 13:45:42+ | **Device silence** | **NO 0x1210 response** | - |

### Alarm #2: MediaId=1

| Time | Event | Details | Line # |
|------|-------|---------|--------|
| 13:45:56 | Device → 0x0200 with 0x70 | MediaId=1, EventCode=0x0 | 9357 |
| 13:45:56 | Server detects 0x70 | WORKAROUND triggered | 9360 |
| 13:45:56 | Server → 0x9208 | AlarmId=1, with POPULATED alarm flag | 9422 |
| 13:45:56 | Write SUCCESS | 0x9208 sent successfully | 9424 |
| 13:45:56+ | **Device silence** | **NO 0x1210 response** | - |

---

## Evidence Analysis

### ✅ What's Working

1. **0x70 Detection** - Server correctly detects multi-media events
   ```
   9220: WARN: WORKAROUND - Multi-media event detected (0x70) without ADAS/DSM data (0x64/0x65)
         - Device: 3906, MediaId: 0, EventCode: 0x0
   ```

2. **0x9208 Generation** - Server builds and sends alarm attachment requests
   ```
   9222: INFO: SENDING ALARM ATTACHMENT REQUEST (0x9208) - AlarmId: 0, AlarmType: 0x0
   ```

3. **Alarm Flag Population** - The bug fix is working! Alarm flags are properly populated:
   ```
   9224: INFO: ALARM ATTACHMENT REQUEST RAW DATA - AlarmId: 0, Length: 91
         Hex: 7e9208005200000d3136352e32322e3232382e3937176f
              000030303033393036251024134538000100  ← POPULATED!
              ^^^^
              Device ID: "0003906"
              Timestamp: 251024134538 (Oct 24, 13:45:38)
              Alarm ID: 0
              Alarm type: 1
   ```

4. **Network Transmission** - Messages are successfully written to the socket
   ```
   9225: INFO: CHANNEL DIAGNOSTICS - isActive: true, isOpen: true, isWritable: true
   9262: INFO: 0x9208 WRITE SUCCESS - AlarmId: 0, bytes: 91
   ```

5. **Event Storage** - Alarm events ARE being stored in the events table
   ```
   9252: INFO: Event id: 496076898991, time: 2025-10-24 13:45:38, type: alarm, notifications: 0
   9413: INFO: Event id: 496076898991, time: 2025-10-24 13:45:55, type: alarm, notifications: 0
   ```

### ❌ What's NOT Working

1. **Device Response** - Devices are NOT sending 0x1210 responses

   **Expected sequence after 0x9208**:
   ```
   Device ← 0x9208 (alarm attachment request)
   Device → 0x1210 (alarm attachment information)  ← MISSING!
   ```

   **Actual sequence**:
   ```
   13:45:39 - Server sends 0x9208 for MediaId=0
   13:45:42 - Device sends 0x0200 (normal location report, NOT 0x1210)
   13:45:47 - Device sends 0x0200 (normal location report)
   13:45:56 - Device sends 0x0200 with another 0x70 alarm
   13:45:59 - Device sends 0x0002 (heartbeat)
   ```

   **No 0x1210 response received at all!**

---

## Root Cause Analysis

### Primary Issue: Device Not Responding to 0x9208

The device is **silently ignoring** the 0x9208 alarm attachment requests despite:
- Properly populated alarm flags ✓
- Valid network connection ✓
- Successful message transmission ✓

### Possible Causes

#### Hypothesis 1: MediaId=0 is Invalid ⚠️ **MOST LIKELY**

**Analysis of 0x70 Field**:

Alarm #1 hex (line 9217):
```
702f 00000000 00 03 00 01 00 1c ...
^^^^            ^^^^^^^^
ID + Len        Media ID = 0
```

Alarm #2 hex (line 9357):
```
702f 00000001 00 03 00 01 00 1c ...
^^^^            ^^^^^^^^
ID + Len        Media ID = 1
```

**Problem**: MediaId=0 might not correspond to any actual media files on the device.

**Why this matters**:
- The device receives 0x9208 with MediaId/AlarmId=0
- Device searches its database for media files with ID=0
- Finds NO files (or files not ready yet)
- Silently ignores the request (no error response defined in protocol)

**Evidence**:
- Both alarms (MediaId=0 and MediaId=1) are ignored
- Suggests either:
  1. MediaId=0 is always invalid (special case)
  2. Both files are not ready when 0x9208 is received
  3. Device expects different correlation logic

#### Hypothesis 2: Timing Issue ⏱️

**Problem**: Server sends 0x9208 **immediately** after receiving 0x0200 with 0x70

**Timing from logs**:
```
13:45:39.xxx - Server receives 0x0200 with alarm
13:45:39.xxx - Server sends 0x9208 (same second!)
```

**Device side** (based on previous 7pm logs):
```
07:57:30 - Alarm detected
07:57:30 - Media capture STARTS
07:57:35 - Video files CREATED (5 seconds later)
07:57:36 - Alarm reported via 0x0200
```

**Potential issue**: Media files take 5+ seconds to create. Server sends 0x9208 before files are ready.

**Solution needed**: Add delay or wait for device confirmation that files are ready.

#### Hypothesis 3: Alarm Flag Format Issue 🔧

**Current alarm flag** (from line 9224):
```
30303033393036  251024134538  00  01  00
^^^^^^^^^^^^^^  ^^^^^^^^^^^^  ^^  ^^  ^^
Device ID       Timestamp     ID  Type Reserved
"0003906"       BCD format    0   1   0
```

**Potential issues**:

1. **Device ID format**: Sending "0003906" (7 ASCII bytes) but device expects BCD device ID?

2. **Alarm ID mismatch**: Using MediaId (0 or 1) as AlarmId, but device expects actual alarm event ID?

3. **Alarm Type**: Sending 0x01 (hardcoded), but device expects actual alarm type from 0x0200 status field?

#### Hypothesis 4: Missing Additional Data in 0x9208 📋

Per T/JSATL12-2017 Table 8-18, the 0x9208 body should contain:
- Server IP address ✓ (present)
- TCP port ✓ (present)
- UDP port (not sent)
- **Alarm ID** (using MediaId) ⚠️
- **Alarm flag** (16 bytes) ✓ (present and populated)
- **Alarm number** (32 bytes) ✓ (present: "ALM-3906-0-1761313539296")
- Reserved (16 bytes) ✓ (present)

**Possible issue**: Device expects specific format in "Alarm number" field or additional correlation data

#### Hypothesis 5: Device Firmware Limitation 🐛

**Problem**: Device firmware might not support responding to 0x9208 requests

**Evidence against this**: Device logs from 7pm session show the device HAS the code to handle attachments:
```
[jt2013_M1][20251024-075736]: 上报 Swerve 报警, time: 1761292650, file_cnt: 4
[jt2013_M1][20251024-075736]: Swerve 插入到数据库, KeyId = 1761292650
```

Device logs show it's tracking media files and alarm IDs, so firmware SHOULD support 0x1210.

---

## Detailed Message Analysis

### 0x9208 Message #1 (MediaId=0)

**Hex** (line 9224):
```
7e9208005200000d3136352e32322e3232382e3937176f
000030303033393036251024134538000100
414c4d2d333930362d302d31373631333133353339323936
0000000000000000000000000000000000000000000000
00b67e
```

**Parsed**:
```
7e                     - Delimiter
9208                   - Message ID
0052                   - Body length (82 bytes)
00 00 0d               - Properties + Device ID header (BCD)
313635...2e3937       - Server IP: "165.22.228.97" (13 bytes)
176f                   - Port: 5999
0000                   - Reserved

ALARM FLAG (16 bytes):
30303033393036         - Device ID: "0003906"
251024134538           - Time: 2025-10-24 13:45:38
00                     - Alarm ID: 0 ← From MediaId
01                     - Alarm type: 1
00                     - Reserved

414c4d2d...            - Alarm number: "ALM-3906-0-1761313539296" (32 bytes)
000000...              - Reserved (16 bytes)
b6                     - Checksum
7e                     - Delimiter
```

**Issues identified**:
1. ✗ Alarm ID is 0 (might be invalid)
2. ⚠️ Alarm type is hardcoded to 0x01 (should match actual alarm type)
3. ⚠️ Timestamp in alarm flag is when server sends request, not when alarm occurred

### 0x0200 Message #1 (Incoming Alarm)

**Hex** (line 9217):
```
7e0200005f496076898991000c00000000004c000b0299af3004c078a2008e00ef
0028251024214538010400000001250400000000300114310110
702f0000000000030001001c000017008e0299ae1604c078d9
251024214531040130000000000000251024214531000400497e
```

**0x70 Field** (Multi-media Event ID):
```
70                     - Additional info ID
2f                     - Length (47 bytes)
00000000               - Media ID: 0 ← PROBLEM!
00                     - Media type: 0 (image)
03                     - Media format: 3
00                     - Event code: 0
01                     - Channel ID: 1
001c                   - Event item type: 0x001c
000017                 - Event item count: 23
008e                   - Reserved
0299ae1604c078d9       - Location (lat/lon)
251024214531           - Timestamp: 2025-10-24 21:45:31 (BCD)
04                     - ...
0130000000000000       - ...
251024214531           - Timestamp repeat
000400                 - ...
```

**Key observation**: MediaId=0 in the incoming 0x0200 message!

---

## Comparison with Previous Sessions

### 7pm Session (From Previous Analysis)

**Device behavior** (from DC600_VIDEO_STORE_REQUEST_ROOT_CAUSE.md):
- ✓ Device detected alarm
- ✓ Device captured media files
- ✓ Device sent 0x0200 with 0x70
- ✗ Device did NOT send 0x64/0x65 ADAS/DSM alarm data
- ✗ Server did NOT send 0x9208 (alarm flag was all zeros - bug)
- ✗ Device never received 0x9208
- ✗ NO 0x1210 sent

### Oct 24 Session (Current Analysis)

**Device behavior**:
- ✓ Device detected alarm
- ✓ Device captured media files
- ✓ Device sent 0x0200 with 0x70
- ✗ Device did NOT send 0x64/0x65 ADAS/DSM alarm data (same as before)
- ✓ Server DID send 0x9208 (alarm flag properly populated - **bug fixed!**)
- ✓ Device received 0x9208 (network connection confirmed)
- ✗ Device did NOT respond with 0x1210 ← **NEW PROBLEM**

**Progress**: Bug fix is working (alarm flag populated), but devices still won't respond!

---

## Root Cause Summary

### Confirmed Working ✅
1. 0x70 workaround detection
2. 0x9208 alarm flag population (bug fix verified)
3. Network transmission
4. Event table storage

### Confirmed Broken ❌
1. Device 0x1210 response
2. Video upload flow initiation

### Most Likely Root Cause 🎯

**MediaId=0 in 0x70 field is invalid or files are not ready when 0x9208 is sent.**

**Evidence**:
- First alarm: MediaId=0 → No response
- Second alarm: MediaId=1 → No response
- Both sent immediately after 0x0200 received
- Device logs show media files take 5+ seconds to create
- Device might be searching for MediaId but finding nothing ready

---

## Recommended Fixes

### Fix #1: Add Delay Before Sending 0x9208 ⏱️ **HIGHEST PRIORITY**

**Problem**: Server sends 0x9208 immediately, but media files need time to be created/saved.

**Solution**: Add configurable delay after receiving 0x0200 with 0x70.

**Location**: `DC600ProtocolDecoder.java` (around line 776)

```java
// CURRENT CODE:
sendAlarmAttachmentRequest(channel, remoteAddress, id, (int) mediaId, eventCode, position);

// PROPOSED FIX:
// Schedule 0x9208 to be sent after a delay to allow media files to be ready
scheduleAlarmAttachmentRequest(channel, remoteAddress, id, (int) mediaId, eventCode, position,
    MEDIA_READY_DELAY_MS);  // e.g., 10000ms = 10 seconds
```

**Implementation**:
```java
private static final int MEDIA_READY_DELAY_MS = 10000;  // 10 seconds

private void scheduleAlarmAttachmentRequest(Channel channel, SocketAddress remoteAddress,
                                            ByteBuf id, int alarmId, int alarmType, Position position,
                                            int delayMs) {
    // Schedule the request to be sent after delay
    channel.eventLoop().schedule(() -> {
        try {
            sendAlarmAttachmentRequest(channel, remoteAddress, id, alarmId, alarmType, position);
        } catch (Exception e) {
            LOGGER.error("Failed to send scheduled 0x9208", e);
        }
    }, delayMs, TimeUnit.MILLISECONDS);

    LOGGER.info("Scheduled 0x9208 alarm attachment request for AlarmId: {} after {}ms delay",
                alarmId, delayMs);
}
```

**Configuration** (in `traccar.xml`):
```xml
<entry key='dc600.attachment.delay'>10000</entry>  <!-- milliseconds -->
```

### Fix #2: Handle MediaId=0 Specially 🔧

**Problem**: MediaId=0 might be a special value indicating "no media" or "media not ready"

**Solution**: Skip 0x9208 if MediaId=0

```java
// DC600ProtocolDecoder.java:759-776
if (length >= 7) {
    long mediaId = buf.readUnsignedInt();
    int mediaType = buf.readUnsignedByte();
    int mediaFormat = buf.readUnsignedByte();
    int eventCode = buf.readUnsignedByte();

    // ADD THIS CHECK:
    if (mediaId == 0) {
        LOGGER.warn("WORKAROUND - Skipping 0x9208 for MediaId=0 (invalid or not ready) - Device: {}",
                    position.getDeviceId());
        buf.skipBytes(length - 7);
        break;  // Don't send 0x9208 for MediaId=0
    }

    // Rest of existing code...
}
```

### Fix #3: Use Actual Alarm Timestamp from 0x0200 🕐

**Problem**: Currently using current server time in alarm flag, but device might expect the original alarm timestamp

**Solution**: Parse and use the timestamp from the 0x0200 location basic info

**Location**: `DC600ProtocolDecoder.java:169-179`

```java
// In sendAlarmAttachmentRequest method, instead of:
Date alarmTime = position.getDeviceTime() != null ? position.getDeviceTime() : new Date();

// Use:
Date alarmTime = position.getDeviceTime();  // This comes from 0x0200 basic info
if (alarmTime == null) {
    LOGGER.error("No device time in position for alarm attachment request");
    return;  // Don't send if we don't have the original timestamp
}
```

### Fix #4: Implement 0x1210 Response Handler 📥

**Problem**: Maybe devices ARE sending 0x1210 but our decoder isn't handling them

**Solution**: Add 0x1210 message handler to DC600ProtocolDecoder

```java
// DC600ProtocolDecoder.java - in decode() method switch statement
case MSG_ALARM_ATTACHMENT_INFO:  // 0x1210
    return decodeAlarmAttachmentInfo(deviceSession, buf, channel, remoteAddress, id, index);
```

**Implementation**:
```java
private Object decodeAlarmAttachmentInfo(DeviceSession deviceSession, ByteBuf buf,
                                         Channel channel, SocketAddress remoteAddress,
                                         ByteBuf id, int index) {
    LOGGER.info("RECEIVED 0x1210 ALARM ATTACHMENT INFO - Device: {}", deviceSession.getDeviceId());

    // Terminal ID (7 bytes BCD)
    byte[] terminalId = new byte[7];
    buf.readBytes(terminalId);
    LOGGER.info("  Terminal ID: {}", DataConverter.printHex(terminalId));

    // Alarm flag (16 bytes)
    byte[] alarmFlag = new byte[16];
    buf.readBytes(alarmFlag);
    LOGGER.info("  Alarm flag: {}", DataConverter.printHex(alarmFlag));

    // Alarm serial number (32 bytes)
    byte[] alarmNumber = new byte[32];
    buf.readBytes(alarmNumber);
    String alarmNumStr = new String(alarmNumber, StandardCharsets.US_ASCII).trim();
    LOGGER.info("  Alarm number: {}", alarmNumStr);

    // Reserved (8 bytes)
    buf.skipBytes(8);

    // File info list count
    int fileCount = buf.readUnsignedByte();
    LOGGER.info("  File count: {}", fileCount);

    // Parse each file info
    for (int i = 0; i < fileCount; i++) {
        int fileNameLen = buf.readUnsignedByte();
        byte[] fileNameBytes = new byte[fileNameLen];
        buf.readBytes(fileNameBytes);
        String fileName = new String(fileNameBytes, StandardCharsets.US_ASCII);

        long fileSize = buf.readUnsignedInt();
        int mediaType = buf.readUnsignedByte();
        int channelId = buf.readUnsignedByte();
        int eventCodeFile = buf.readUnsignedByte();

        LOGGER.info("  File #{}: name={}, size={}, type={}, channel={}, event=0x{}",
                    i+1, fileName, fileSize, mediaType, channelId,
                    Integer.toHexString(eventCodeFile).toUpperCase());

        // TODO: Store file information for video upload coordination
    }

    // Send acknowledgment
    sendGeneralResponse(channel, remoteAddress, id, MSG_ALARM_ATTACHMENT_INFO, index);

    LOGGER.info("✓ 0x1210 processed successfully - Video upload can now be initiated");

    return null;  // No Position object for this message type
}
```

### Fix #5: Enhanced Logging for Debugging 📋

Add more diagnostic logging to trace the issue:

```java
// After sending 0x9208
LOGGER.info("Waiting for 0x1210 response from device {} for AlarmId {}",
            position.getDeviceId(), alarmId);

// In the main decode loop, log ALL incoming messages for this device
if (deviceSession.getDeviceId() == 3906) {  // Your test device
    LOGGER.debug("Received message type 0x{} from device {}",
                 Integer.toHexString(type).toUpperCase(),
                 deviceSession.getDeviceId());
}
```

---

## Verification Steps

After implementing fixes:

### 1. Test with Delay Fix
```bash
# Set delay to 15 seconds in traccar.xml
<entry key='dc600.attachment.delay'>15000</entry>

# Restart server
# Trigger alarm from device
# Check logs for:
grep "Scheduled 0x9208" logs/tracker.log
grep "RECEIVED 0x1210" logs/tracker.log
```

### 2. Monitor Device Logs
```bash
# On device, tail the logs
tail -f /var/log/device.log | grep -E "recv id = 0x9208|send id = 0x1210"

# Should see:
[jt2013_M1][timestamp]: [recv id = 0x9208, seq = X, len = 91]
[jt2013_M1][timestamp]: [send id = 0x1210, seq = Y, len = Z]
```

### 3. Capture Network Traffic
```bash
tcpdump -i any -w dc600_oct24.pcap port 5999

# Analyze with Wireshark:
# Filter: tcp.port == 5999
# Look for:
#   - 0x9208 from server (should see in hex)
#   - 0x1210 from device (if sent)
```

### 4. Test with Python Simulator
```bash
cd tools/dc600
python3 quick_test.py 165.22.228.97 5999

# This will send realistic messages and listen for 0x9208
# Then respond with 0x1210 to verify server handling
```

---

## Next Steps

1. **Immediate**: Implement Fix #1 (delay) and Fix #4 (0x1210 handler)
2. **Test**: Deploy to staging and test with real device
3. **Monitor**: Watch for 0x1210 responses in logs
4. **Contact Vendor**: If still no response, contact iStartek about:
   - Required delay between 0x0200 and 0x9208
   - MediaId=0 handling
   - 0x1210 response requirements
   - Firmware version verification

---

## Summary

| Component | Status | Details |
|-----------|--------|---------|
| Alarm Detection | ✅ Working | 0x70 events detected via WORKAROUND |
| Event Storage | ✅ Working | Alarms stored in events table |
| 0x9208 Generation | ✅ Working | Properly formatted with populated alarm flag |
| 0x9208 Transmission | ✅ Working | Successfully sent to device |
| **Device Reception** | ⚠️ **Unknown** | **Cannot confirm if device receives 0x9208** |
| **0x1210 Response** | ❌ **BROKEN** | **Device NOT responding** |
| 0x1210 Handler | ❌ **MISSING** | **No code to process 0x1210** |
| Video Upload | ❌ **BROKEN** | **Cannot initiate without 0x1210** |

**CRITICAL**: The alarm flag bug is FIXED, but we have a NEW problem: devices are not responding with 0x1210.

**PRIORITY**: Implement delay before sending 0x9208 and add 0x1210 response handler.

---

**Created**: 2025-10-24
**Session**: Oct 24, 13:45 UTC
**Device**: 496076898991 (ID: 3906)
**Alarms Tested**: 2 (MediaId: 0 and 1)
**Result**: 0x9208 sent, NO 0x1210 received
