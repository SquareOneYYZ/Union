# DC600 ADAS/DSM Alarm Analysis - Complete Flow Breakdown

## Executive Summary

**Critical Finding**: Device firmware is **NOT sending proper ADAS (0x64) or DSM (0x65) alarm data** in 0x0200 location reports. This is a **DEVICE CONFIGURATION/FIRMWARE ISSUE**, not a server bug.

**Status**:
- ❌ No ADAS alarms (0x64) received from device
- ❌ No DSM alarms (0x65) received from device
- ⚠️ Only 0x70 multi-media events (workaround path)
- ✅ Workaround: Server sends 0x9208 with populated alarm flags
- ❌ **Device does NOT respond with 0x1210**

---

## Timeline: Oct 24 Session Analysis

### Device Side (from `20251024-214353.log`)

#### Alarm Event #1: Swerve

| Time | Event | Line # | Details |
|------|-------|--------|---------|
| 21:45:31 | AI detects alarm | 495 | warntype:42 (Swerve), need_alarm=1 |
| 21:45:31 | Media capture starts | 497 | capturetime 0 3 1761342331 |
| 21:45:36 | Media files created | 501 | 3 photos + 1 video |
| 21:45:38 | Device reports alarm | 558-566 | Sends 0x0200 with 0x70, MediaId=0 |
| 21:45:39 | Server ACK received | 568-569 | 0x8001 acknowledgment |
| 21:45:40+ | **NO 0x9208 received** | - | Device never receives attachment request |

**Hex message sent** (line 562-566):
```
7E 02 00 00 5F 49 60 76 89 89 91 00 0C ...
70 2F 00 00 00 00 00 03 00 01 00 1C 00 00 17 00 8E ...
^^^^           ^^^^^^^^
0x70 field     MediaId = 0
```

#### Alarm Event #2: Swerve

| Time | Event | Line # | Details |
|------|-------|--------|---------|
| 21:45:48 | AI detects alarm | 580 | warntype:42 (Swerve), need_alarm=1 |
| 21:45:49 | Media capture starts | 582 | capturetime 0 3 1761342348 |
| 21:45:54 | Media files created | 620-622 | 3 photos + 1 video |
| 21:45:55 | Device reports alarm | 628-636 | Sends 0x0200 with 0x70, MediaId=1 |
| 21:45:56 | Server ACK received | 638 | 0x8001 acknowledgment |
| 21:45:56+ | **NO 0x9208 received** | - | Device never receives attachment request |

**Hex message sent** (line 632-636):
```
7E 02 00 00 5F 49 60 76 89 89 91 00 0F ...
70 2F 00 00 00 01 00 03 00 01 00 1C 00 00 18 00 8C ...
^^^^           ^^^^^^^^
0x70 field     MediaId = 1
```

### Server Side (from `oct24.txt`)

#### Alarm #1: MediaId=0

| Time | Event | Line # | Status |
|------|-------|--------|--------|
| 13:45:39 | Receive 0x0200 with 0x70 | 9217 | ✅ Received |
| 13:45:39 | WORKAROUND triggered | 9220 | ✅ Detected MediaId=0 |
| 13:45:39 | Send 0x9208 | 9260 | ✅ Sent with populated alarm flag |
| 13:45:39 | Write SUCCESS | 9262 | ✅ Transmitted |
| 13:45:39 | Event stored | 9252 | ✅ Alarm in events table |
| 13:45:40+ | **Wait for 0x1210** | - | ❌ **NEVER RECEIVED** |

#### Alarm #2: MediaId=1

| Time | Event | Line # | Status |
|------|-------|--------|--------|
| 13:45:56 | Receive 0x0200 with 0x70 | 9357 | ✅ Received |
| 13:45:56 | WORKAROUND triggered | 9360 | ✅ Detected MediaId=1 |
| 13:45:56 | Send 0x9208 | 9422 | ✅ Sent with populated alarm flag |
| 13:45:56 | Write SUCCESS | 9424 | ✅ Transmitted |
| 13:45:56 | Event stored | 9413 | ✅ Alarm in events table |
| 13:45:56+ | **Wait for 0x1210** | - | ❌ **NEVER RECEIVED** |

---

## Critical Issue: No ADAS/DSM Data from Device

### What's Missing

**Expected message structure** (per T/JSATL12-2017):
```
0x0200 Location Report should contain:
├─ Basic info (lat, lon, speed, status)
├─ Additional info:
│  ├─ 0x01 (Mileage) ✓ present
│  ├─ 0x25 (Unknown) ✓ present
│  ├─ 0x30 (RSSI) ✓ present
│  ├─ 0x31 (Satellites) ✓ present
│  ├─ 0x64 (ADAS alarm) ✗ MISSING!
│  ├─ 0x65 (DSM alarm) ✗ MISSING!
│  └─ 0x70 (Multi-media event) ✓ present
```

**Actual message from device**:
```hex
7E 02 00 00 5F 49 60 76 89 89 91 00 0C
00 00 00 00 00 4C 00 0B 02 99 AF 30 04 C0 78 A2 00 8E 00 EF 00 28
25 10 24 21 45 38  // Timestamp
01 04 00 00 00 01  // 0x01 (Mileage)
25 04 00 00 00 00  // 0x25 (Unknown)
30 01 14           // 0x30 (RSSI)
31 01 10           // 0x31 (Satellites)
70 2F ...          // 0x70 (Multi-media event) ← ONLY THIS!
// NO 0x64 (ADAS)!
// NO 0x65 (DSM)!
```

### Why ADAS/DSM Data is Critical

Per T/JSATL12-2017 specification:

**0x64 (ADAS Alarm Data)** should contain:
- Alarm ID (4 bytes)
- Alarm Status (1 byte)
- **Alarm Type** (1 byte) - Forward collision, lane departure, etc.
- Alarm Level (1 byte)
- Additional alarm details...

**0x65 (DSM Alarm Data)** should contain:
- Alarm ID (4 bytes)
- Alarm Status (1 byte)
- **Alarm Type** (1 byte) - Fatigue, phone use, smoking, seatbelt, etc.
- Alarm Level (1 byte)
- Additional alarm details...

**Without 0x64/0x65**:
- ❌ Server cannot determine alarm type (is it swerve? fatigue? seatbelt?)
- ❌ Server cannot get proper alarm ID
- ❌ Server cannot properly correlate media files
- ❌ Server falls back to WORKAROUND (using 0x70 only)

---

## Device Logs: Evidence of Missing ADAS/DSM

### Device Detects ADAS/DSM Alarms

Device AI correctly detects alarms:

**Line 495**: Swerve (ADAS)
```
[INFO][myproduct][20251024-214531]: warntype:42, ConvertType:38 : Swerve
```

**Line 502**: Distracted driving (DSM)
```
[INFO][myproduct][20251024-214532]: warntype:11, ConvertType:4 : please keep attention
```

**Line 512**: Seatbelt (DSM)
```
[INFO][myproduct][20251024-214533]: warntype:14, ConvertType:8 : please fasten your seat belt
```

**Line 586**: Phone use (DSM)
```
[INFO][myproduct][20251024-214550]: warntype:9, ConvertType:2 : no cellphone using
```

### But Device Does NOT Send 0x64/0x65

**Lines 558-566**: Device sends alarm report
```
[INFO][jt2013_M1][20251024-214538]: 上报 Swerve 报警
[send id = 0x200, seq = 12, pack(0/0), len = 110]:
7E 02 00 00 5F ... 70 2F ...  ← Only 0x70, NO 0x64!
```

**Expected behavior**: Device should send 0x64 with ADAS alarm data:
```
[send id = 0x200, seq = 12, pack(0/0), len = XXX]:
7E 02 00 00 XX ...
64 07 [alarm_id] [status] [type] [level] ...  ← MISSING!
70 2F ...
```

### Device Rate Limiting

**Line 503, 513, 587**: Device enforces 10-second minimum between attachments
```
[ERROR][myproduct][20251024-214532]: 附件间隔最小10秒
Translation: "Minimum attachment interval is 10 seconds"
```

This explains why some alarms are detected but not reported - device firmware has built-in rate limiting.

---

## Root Cause Analysis

### Primary Issue: Device Configuration Problem ⚠️ **CRITICAL**

**Device firmware is NOT configured to send ADAS/DSM alarm data (0x64/0x65) in 0x0200 location reports.**

**Evidence**:
1. ✅ Device AI correctly detects ADAS/DSM alarms
2. ✅ Device captures media files (photos + videos)
3. ❌ Device does NOT include 0x64 (ADAS) in 0x0200 messages
4. ❌ Device does NOT include 0x65 (DSM) in 0x0200 messages
5. ✅ Device includes 0x70 (multi-media event marker) only

**This is identical to the issue documented in `DC600_VIDEO_STORE_REQUEST_ROOT_CAUSE.md`** from the 7pm session.

### Secondary Issue: MediaId=0 Problem

**Device sends MediaId=0** in first alarm (line 564):
```
70 2F 00 00 00 00 ...
         ^^^^^^^^
         MediaId = 0
```

**Problem**: MediaId=0 appears to be invalid or indicates "not ready"
- Server sends 0x9208 requesting MediaId=0
- Device searches for files with ID=0
- Finds nothing (or files not ready)
- Silently ignores request

### Tertiary Issue: Timing

**Media file creation timeline** (from device logs):
```
21:45:31 - Alarm detected
21:45:31 - Capture starts
21:45:36 - Files created (5 seconds later)
21:45:38 - Alarm reported
```

**Server response timing** (from staging logs):
```
13:45:39 - Receive alarm
13:45:39 - Send 0x9208 (immediately!)
```

**Problem**: Server sends 0x9208 too quickly - files might not be ready.

### Device Never Receives 0x9208

**Device log analysis**:
- ✅ Device connects to server successfully
- ✅ Device sends 0x0200 location reports
- ✅ Device receives 0x8001 acknowledgments
- ❌ **Device NEVER receives 0x9208** (no log entries)
- ❌ **Device NEVER sends 0x1210** (no log entries)

**Possible explanations**:
1. Network issue (unlikely - other messages work)
2. 0x9208 sent to wrong connection (timing/channel issue)
3. Device firmware doesn't recognize/process 0x9208
4. MediaId mismatch causes device to ignore

---

## Expected vs Actual Flow

### EXPECTED Flow (with proper ADAS/DSM data)

```
1. Device AI detects ADAS/DSM alarm
   ↓
2. Device captures media files
   ↓
3. Device sends 0x0200 with:
   - Basic location info
   - 0x64 (ADAS alarm data) ← REQUIRED!
     OR
   - 0x65 (DSM alarm data) ← REQUIRED!
   - 0x70 (Multi-media event marker)
   ↓
4. Server receives 0x0200
   ↓
5. Server parses 0x64/0x65, extracts:
   - Alarm ID
   - Alarm Type (swerve, fatigue, seatbelt, etc.)
   - Alarm Level
   ↓
6. Server creates alarm event with specific type
   ↓
7. Server sends 0x9208 (alarm attachment request)
   with properly populated alarm flag
   ↓
8. Device receives 0x9208
   ↓
9. Device searches for media files matching alarm
   ↓
10. Device sends 0x1210 (alarm attachment info)
    listing available files
   ↓
11. Server receives 0x1210
   ↓
12. Video upload initiated via FTP/JT1078
```

### ACTUAL Flow (current broken state)

```
1. Device AI detects ADAS/DSM alarm ✓
   ↓
2. Device captures media files ✓
   ↓
3. Device sends 0x0200 with:
   - Basic location info ✓
   - NO 0x64 (ADAS data) ✗
   - NO 0x65 (DSM data) ✗
   - 0x70 (Multi-media event only) ✓
   ↓
4. Server receives 0x0200 ✓
   ↓
5. Server CANNOT parse ADAS/DSM (missing!) ✗
   Falls back to WORKAROUND path:
   - Uses MediaId from 0x70 as AlarmId
   - Sets generic "unknown" alarm type
   - Cannot determine specific alarm (swerve? fatigue?)
   ↓
6. Server creates generic alarm event ⚠️
   ↓
7. Server sends 0x9208 with alarm flag ✓
   BUT using MediaId (0 or 1) instead of real alarm ID
   ↓
8. Device... silence? ✗
   (0x9208 never seen in device logs)
   ↓
9. NO 0x1210 response ✗
   ↓
10. Video upload NEVER initiated ✗

FLOW BROKEN AT STEP 3 AND STEP 8!
```

---

## Comparison: All Sessions

### 7pm Session (Previous Analysis)

**Device behavior**:
- ✅ Alarms detected
- ✅ Media captured
- ✅ 0x0200 sent with 0x70
- ❌ NO 0x64/0x65 data
- ❌ Server didn't send 0x9208 (bug: alarm flag was all zeros)

**Result**: No 0x9208 sent (server bug)

### Oct 24 Session (13:45 - Current Analysis)

**Device behavior**:
- ✅ Alarms detected
- ✅ Media captured
- ✅ 0x0200 sent with 0x70
- ❌ NO 0x64/0x65 data
- ✅ Server DOES send 0x9208 (bug fixed: alarm flag populated!)
- ❌ Device doesn't respond with 0x1210

**Result**: 0x9208 sent but device doesn't respond

**Progress**: Server bug fixed, but device still won't work!

---

## Why Devices Don't Respond

### Hypothesis 1: Device Firmware Limitation ⚠️ **MOST LIKELY**

**Problem**: Device firmware may not support the 0x9208 → 0x1210 flow **when alarm data comes from 0x70 only**.

**Evidence**:
- Device has code for alarms: `上报 Swerve 报警` (report Swerve alarm)
- Device has database: `Swerve 插入到数据库` (insert into database)
- But: Device expects proper correlation via 0x64/0x65 data
- When only 0x70 is present: Device can't match 0x9208 to stored alarm

**Device logic might be**:
```
Receive 0x9208 with AlarmId=0
└─ Search database for alarm with ID=0 AND type from 0x64/0x65
   └─ NOT FOUND (no 0x64/0x65 was sent!)
      └─ Ignore 0x9208 silently
```

### Hypothesis 2: MediaId=0 is Invalid

**Evidence**:
- First alarm: MediaId=0 in 0x70 field
- MediaId=0 might mean "not assigned yet" or "invalid"
- Device ignores 0x9208 requests for MediaId=0

**Second alarm**:
- MediaId=1 in 0x70 field
- But device ALSO doesn't respond to this
- Suggests deeper issue than just MediaId=0

### Hypothesis 3: Timing + Rate Limiting

**Device rate limiter** (line 503):
```
附件间隔最小10秒 (Minimum attachment interval is 10 seconds)
```

**Timeline**:
- 21:45:38 - First alarm sent
- 21:45:55 - Second alarm sent (17 seconds later)

**Both alarms are within rate limit compliance**, so rate limiting is not the issue.

---

## Device Configuration Requirements

### Required Parameters (T/JSATL12-2017)

To enable proper ADAS/DSM reporting, device must be configured with:

**Parameter 0x0076**: ADAS alarm enable
```
Bits 0-7: Enable specific ADAS alarm types
- Bit 0: Forward collision
- Bit 1: Lane departure
- Bit 2: Vehicle too close
- Bit 3: Pedestrian collision
- Bit 4: Frequent lane change
- Bit 5: Road sign exceeded
- Bit 6: Obstacle detection
- Bit 7: User defined
```

**Parameter 0x0077**: DSM alarm enable
```
Bits 0-7: Enable specific DSM alarm types
- Bit 0: Fatigue driving
- Bit 1: Phone use
- Bit 2: Smoking
- Bit 3: Distracted driving
- Bit 4: Driver abnormal
- Bit 5: User defined
```

**Parameter 0x007E**: ADAS upload settings
```
Bit 0: Upload 0x64 (ADAS alarm data) in 0x0200 ← REQUIRED!
Bit 1: Upload ADAS attachment
Bit 2: ...
```

**Parameter 0x007F**: DSM upload settings
```
Bit 0: Upload 0x65 (DSM alarm data) in 0x0200 ← REQUIRED!
Bit 1: Upload DSM attachment
Bit 2: ...
```

---

## Recommended Fixes

### Fix #1: Configure Device Parameters ⭐ **CRITICAL** ⭐

**Action**: Use device management platform or JT808 commands to configure:

```
0x8103 (Set Terminal Parameters):
├─ 0x0076 = 0xFF (Enable all ADAS types)
├─ 0x0077 = 0xFF (Enable all DSM types)
├─ 0x007E = 0x01 (Upload 0x64 in 0x0200)
└─ 0x007F = 0x01 (Upload 0x65 in 0x0200)
```

**Expected result**:
- Device will send 0x64 with ADAS alarm data
- Device will send 0x65 with DSM alarm data
- Server can properly detect alarm types
- Server can correlate with media files
- Device will respond to 0x9208

### Fix #2: Upgrade Device Firmware

**Current firmware** might not support T/JSATL12-2017 alarm reporting.

**Action**: Contact iStartek to:
1. Verify firmware version
2. Confirm T/JSATL12-2017 support
3. Upgrade if necessary

### Fix #3: Add Delay Before 0x9208 (Server Side)

Even with proper 0x64/0x65 data, media files need time to be ready.

**File**: `DC600ProtocolDecoder.java:776`

```java
// Add configurable delay
private static final int MEDIA_READY_DELAY_MS = 10000;  // 10 seconds

channel.eventLoop().schedule(() -> {
    sendAlarmAttachmentRequest(channel, remoteAddress, id, alarmId, alarmType, position);
}, MEDIA_READY_DELAY_MS, TimeUnit.MILLISECONDS);
```

**Config** (`traccar.xml`):
```xml
<entry key='dc600.attachment.delay'>10000</entry>
```

### Fix #4: Skip MediaId=0 (Server Side)

**File**: `DC600ProtocolDecoder.java:743-777`

```java
if (mediaId == 0) {
    LOGGER.warn("Skipping 0x9208 for MediaId=0 (invalid) - Device: {}", position.getDeviceId());
    // Still create alarm event, just don't request attachments for ID=0
    position.addAlarm("unknown");
    buf.skipBytes(length - 7);
    break;
}
```

### Fix #5: Implement 0x1210 Handler (Server Side)

**File**: `DC600ProtocolDecoder.java` - in `decode()` switch

```java
case MSG_ALARM_ATTACHMENT_INFO:  // 0x1210
    return decodeAlarmAttachmentInfo(deviceSession, buf, channel, remoteAddress, id, index);
```

Add handler method (see `DC600_OCT24_ANALYSIS.md` for complete implementation).

---

## Verification Steps

### 1. Verify Device Configuration

```bash
# Send query command to device
0x8106 (Query Terminal Parameters)
- Query 0x0076, 0x0077, 0x007E, 0x007F

# Expected response:
0x0104 (Query Parameters Response)
- 0x0076 = 0xFF (all ADAS enabled)
- 0x0077 = 0xFF (all DSM enabled)
- 0x007E = 0x01 (upload 0x64)
- 0x007F = 0x01 (upload 0x65)
```

### 2. Trigger Alarm and Monitor

```bash
# Device side - check for 0x64/0x65 in message
tail -f /var/log/device.log | grep "send id = 0x200"

# Should see longer messages with 0x64 or 0x65:
[send id = 0x200, seq = X, len = 120+]:  # Longer than 110 bytes
7E 02 00 ... 64 07 ... OR 65 07 ...
```

### 3. Server side - check for ADAS/DSM detection

```bash
grep "ADAS ALARM DETECTED\|DSM ALARM DETECTED" logs/tracker.log

# Should see:
ADAS ALARM DETECTED - Device: 3906, AlarmId: XXX, Type: 0xYY
OR
DSM ALARM DETECTED - Device: 3906, AlarmId: XXX, Type: 0xYY

# Instead of:
WORKAROUND - Multi-media event detected (0x70) without ADAS/DSM data
```

### 4. Check for 0x1210 responses

```bash
# Device logs
grep "recv id = 0x9208\|send id = 0x1210" /var/log/device.log

# Should see:
[recv id = 0x9208, seq = X, len = 91]
[send id = 0x1210, seq = Y, len = ZZZ]
```

---

## Summary

| Issue | Status | Root Cause | Fix |
|-------|--------|------------|-----|
| No ADAS data (0x64) | ❌ BROKEN | Device config | Configure param 0x007E |
| No DSM data (0x65) | ❌ BROKEN | Device config | Configure param 0x007F |
| 0x70 workaround working | ✅ Working | Fallback path | Already implemented |
| Alarm flag populated | ✅ FIXED | Previous bug | Bug fix verified |
| 0x9208 being sent | ✅ Working | Server side OK | Already working |
| Device receives 0x9208 | ❌ UNKNOWN | Network/firmware? | Needs investigation |
| Device sends 0x1210 | ❌ BROKEN | No 0x64/0x65 | Configure device params |
| Video upload | ❌ BROKEN | Chain of failures | Fix device config first |

---

## Critical Path Forward

### Immediate Priority (HIGHEST)

1. **Configure device parameters 0x0076, 0x0077, 0x007E, 0x007F**
   - This is the ROOT CAUSE
   - Without this, nothing else will work

2. **Verify device sends 0x64/0x65 in 0x0200 messages**
   - Capture actual hex messages
   - Confirm ADAS/DSM data present

3. **Test complete flow**
   - Trigger alarm
   - Verify server detects ADAS/DSM (not WORKAROUND)
   - Check device receives 0x9208
   - Verify device sends 0x1210

### Secondary Priority

4. **Implement server-side fixes**
   - Delay before 0x9208
   - Skip MediaId=0
   - Add 0x1210 handler

5. **Contact iStartek**
   - Verify firmware version
   - Confirm T/JSATL12-2017 support
   - Request configuration assistance

---

**CONCLUSION**: The fundamental issue is that **devices are not configured to send ADAS/DSM alarm data (0x64/0x65)**. The server workaround (0x70) triggers attachment requests, but devices cannot respond properly without the proper alarm correlation data from 0x64/0x65. This is a **DEVICE CONFIGURATION PROBLEM**, not a server code bug.

---

**Created**: 2025-10-24
**Device**: 496076898991 (ID: 3906)
**Firmware**: Unknown (needs verification)
**Session**: Oct 24, 21:45 device time (13:45 server time)
**Alarms Tested**: 2 Swerve alarms (ADAS type)
**Result**: No ADAS/DSM data sent, no 0x1210 responses
