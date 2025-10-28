# DC600 VIDEO STORE REQUEST - ROOT CAUSE ANALYSIS

## Issue Summary

**Problem**: Video store request (0x1210) is not being sent/received after alarm events are detected and stored in the events table.

**Status**: ❌ BROKEN - Video upload flow is interrupted

---

## Timeline of Events

### Device Side (from device logs: 20251024-075424.log)

| Time | Event | Details |
|------|-------|---------|
| 07:57:30 | Alarm Detected | Swerve alarm (warntype:42, ConvertType:38) |
| 07:57:30 | Media Captured | 3 photos + 3 videos created |
| 07:57:36 | Report Sent | Device sends 0x0200 location report |
| 07:57:36 | DB Insert | Alarm inserted to device DB (KeyId = 1761292650) |
| 07:57:36 | Message TX | `7E 02 00 00 5F ...` (110 byte message) |
| 07:57:36 | ACK RX | `7E 80 01 00 05 ...` received from server |
| 07:57:36 | **MISSING** | **NO 0x9208 alarm attachment request received** |
| 07:57:36 | **MISSING** | **NO 0x1210 video store request sent** |

### Server Side (from staging logs: dc600-2.txt)

| Time | Event | Details |
|------|-------|---------|
| 00:00:01 | Message RX | `7E 02 00 00 5F ...` received from device |
| 00:00:01 | Decode | Message decoded and queued |
| 00:00:01 | ACK TX | `7E 80 01 00 05 ...` sent to device |
| **MISSING** | **NO** | **NO alarm detection logged** |
| **MISSING** | **NO** | **NO 0x9208 alarm attachment request sent** |
| **MISSING** | **NO** | **NO ADAS/DSM alarm events created** |

---

## Root Cause: Device Not Sending ADAS/DSM Alarm Data

### 1. Missing 0x64/0x65 Additional Information

The device is sending **multi-media event marker (0x70)** but **NOT** the ADAS (0x64) or DSM (0x65) alarm information in the 0x0200 location reports.

**Message Structure Analysis:**

Hex message received by server:
```
7e0200005f496076898991003400000000004c000b0299ac6504c07410008c0103011d25102408000001
0400000008250400000000300114310120702f0000000700030001001c000019008c0299acad04c07278
251024075953040130000000000000251024075953070400317e
```

**Additional Info Fields Found:**
- ✅ 0x01 (Mileage)
- ✅ 0x25 (Unknown field)
- ✅ 0x30 (RSSI)
- ✅ 0x31 (Satellites)
- ✅ 0x70 (Multi-media Event ID) - **Present but insufficient**
- ❌ 0x64 (ADAS Alarm Data) - **MISSING**
- ❌ 0x65 (DSM Alarm Data) - **MISSING**

### 2. Server Cannot Detect Alarm Without 0x64/0x65

Without ADAS/DSM alarm data, the `DC600ProtocolDecoder` cannot:

1. ❌ Identify what type of alarm occurred (swerve, fatigue, seatbelt, etc.)
2. ❌ Parse alarm ID, status, type, and level
3. ❌ Trigger custom alarm strings (e.g., `"seatBelt"`, `"forwardCollision"`)
4. ❌ Generate alarm events in the events table
5. ❌ Send 0x9208 alarm attachment request to device

**Evidence from Staging Logs:**

Searched for: `grep -i "adas\|dsm\|alarm detected\|0x9208" dc600-2.txt`

**Result**: **ZERO matches** - No ADAS/DSM alarm detection occurred

---

## Expected vs Actual Protocol Flow

### EXPECTED Flow (per T/JSATL12-2017 Standard)

```
1. Device detects alarm (e.g., Swerve, DSM seatbelt)
   |
2. Device captures photos/videos
   |
3. Device sends 0x0200 with BOTH:
   - 0x64/0x65 (ADAS/DSM alarm data) ← REQUIRED
   - 0x70 (Multi-media event marker)
   |
4. Server receives message
   |
5. Server parses 0x64/0x65, detects alarm type
   |
6. Server creates alarm event in events table
   |
7. Server sends 0x9208 (alarm attachment request)
   |
8. Device receives 0x9208
   |
9. Device sends 0x1210 (alarm attachment info)
   |
10. Video upload via FTP/JT1078
```

### ACTUAL Flow (what's happening now)

```
1. Device detects alarm
   |
2. Device captures photos/videos
   |
3. Device sends 0x0200 with ONLY:
   - 0x70 (Multi-media event marker)
   - NO 0x64/0x65 alarm data! ← PROBLEM
   |
4. Server receives message
   |
5. Server has no alarm data to parse
   |
6. NO alarm event created
   |
7. NO 0x9208 sent
   |
8. Device never receives request
   |
9. NO 0x1210 sent
   |
10. FLOW BROKEN ✗
```

---

## Specific Evidence

### Device Log Evidence (lines 630-648)

```
[INFO][myproduct][20251024-075730]: warntype:42, ConvertType:38 : Swerve, need_alarm = 1, level = 0
[INFO][jt2013_M1][20251024-075736]: 上报 Swerve 报警, time: 1761292650, file_cnt: 4
[INFO][jt2013_M1][20251024-075736]: Swerve 插入到数据库, KeyId = 1761292650
[INFO][jt2013_M1][20251024-075736]: reportflag = 1
[jt2013_M1][20251024-075736]: [send id = 0x200, seq = 24, pack(0/0), len = 110]:
7E 02 00 00 5F 49 60 76 89 89 91 00 18 00 00 00 00 00 4C 00 0B 02 99 B0 2C ...
```

**Device IS detecting alarms and capturing media** ✅
**Device IS sending 0x0200 messages** ✅
**Device IS storing alarm data locally** ✅

### Staging Log Evidence (lines 32, 99, 144)

```
2025-10-24 00:00:01  INFO: [T8cf42495: dc600 < 64.16.243.225] 7e0200005f...
2025-10-24 00:00:02  INFO: [T8cf42495] id: 496076898991, time: 2025-10-23 23:59:59...
```

**Server IS receiving 0x0200 messages** ✅
**Server IS parsing and storing positions** ✅
**Server IS NOT detecting ADAS/DSM alarms** ❌
**Server IS NOT sending 0x9208 requests** ❌

---

## Root Cause Summary

### Primary Issue

**The DC600 device firmware is NOT including ADAS (0x64) or DSM (0x65) alarm data in the 0x0200 location reports**, even though:

1. ✅ Alarms ARE being detected by device AI algorithms
2. ✅ Photos and videos ARE being captured
3. ✅ Multi-media event marker (0x70) IS being sent
4. ❌ **Alarm type information (0x64/0x65) is NOT being sent**

### Why This Breaks the Flow

Per T/JSATL12-2017 protocol specification, the server **requires** the 0x64/0x65 alarm data to:

1. Identify the alarm type (forward collision, fatigue, seatbelt, etc.)
2. Extract alarm ID, status, type code, and level
3. Trigger the alarm event generation logic
4. Send the 0x9208 alarm attachment upload request

Without this data, the server treats the message as a normal location report and **skips the entire alarm attachment request sequence**.

---

## Device Configuration Issue

### Possible Causes

1. **Device Parameter 0x0076 (ADAS Alarm Enable)** - May not be configured
2. **Device Parameter 0x0077 (DSM Alarm Enable)** - May not be configured
3. **Device Parameter 0x007E (ADAS Upload Settings)** - Not configured to send 0x64
4. **Device Parameter 0x007F (DSM Upload Settings)** - Not configured to send 0x65
5. **Firmware Version** - May not support T/JSATL12-2017 alarm reporting

### Device Logs Show Discrepancy

The device logs show:
```
[INFO][jt2013_M1][20251024-075736]: 上报 Swerve 报警
```
("Report Swerve alarm")

But the actual 0x0200 message sent **does NOT contain** the 0x64 (ADAS) additional information that would indicate a Swerve alarm to the server!

---

## Recommended Fixes

### Fix #1: Device Configuration (HIGHEST PRIORITY)

Configure the DC600 device to include ADAS/DSM alarm data in 0x0200 reports:

**Via Device Management Platform:**
1. Enable ADAS alarm reporting (Parameter 0x0076)
2. Enable DSM alarm reporting (Parameter 0x0077)
3. Configure ADAS upload settings (Parameter 0x007E) to include 0x64 in location reports
4. Configure DSM upload settings (Parameter 0x007F) to include 0x65 in location reports

**Via JT808 Commands:**
```
0x8103 - Set terminal parameters
  - 0x0076: ADAS enable
  - 0x0077: DSM enable
  - 0x007E: ADAS upload settings (include 0x64 in 0x0200)
  - 0x007F: DSM upload settings (include 0x65 in 0x0200)
```

### Fix #2: Verify Firmware Version

Check if device firmware supports T/JSATL12-2017:
- Current behavior suggests firmware may be using an older protocol version
- Contact iStartek to verify firmware version and upgrade if needed

### Fix #3: Alternative Workaround (Temporary)

If device cannot send 0x64/0x65, implement server-side workaround:

**Parse 0x70 multi-media event to infer alarm type:**

File: `DC600ProtocolDecoder.java` (add case for 0x70)

```java
case 0x70: // Multi-media Event ID (workaround for missing 0x64/0x65)
    if (length >= 0x2F) {  // 47 bytes
        long mediaId = buf.readUnsignedInt();
        int mediaType = buf.readUnsignedByte();
        int mediaFormat = buf.readUnsignedByte();
        int eventCode = buf.readUnsignedByte();  // May indicate alarm type
        int channelId = buf.readUnsignedByte();

        // Infer alarm type from event code (device-specific mapping)
        if (eventCode == 0x07) {
            position.addAlarm("unknown");  // Generic alarm
            LOGGER.warn("Multi-media event detected (0x70) - missing ADAS/DSM data");
            // Still send 0x9208 request
            sendAlarmAttachmentRequest(channel, remoteAddress, id, (int) mediaId, eventCode, position);
        }
    }
    break;
```

**Note**: This workaround is **not ideal** because it cannot determine the specific alarm type (seatbelt, fatigue, etc.) without the 0x64/0x65 data.

---

## Verification Steps

After applying Fix #1 (device configuration):

1. **Trigger an alarm** (e.g., not wearing seatbelt)
2. **Check device logs** for:
   ```
   上报 [alarm type] 报警
   ```
3. **Capture hex message** sent by device
4. **Verify 0x64 or 0x65** appears in additional info
5. **Check server logs** for:
   ```
   ADAS ALARM DETECTED - Device: X, AlarmId: Y, Type: 0xZ
   ```
   or
   ```
   DSM ALARM DETECTED - Device: X, AlarmId: Y, Type: 0xZ
   ```
6. **Verify 0x9208** is sent:
   ```
   SENDING ALARM ATTACHMENT REQUEST (0x9208)
   ```
7. **Check device logs** for 0x9208 receipt:
   ```
   [recv id = 0x9208, seq = X, pack(0/0), len = Y]
   ```
8. **Verify 0x1210** is sent by device:
   ```
   [send id = 0x1210, seq = X, pack(0/0), len = Y]
   ```

---

## Summary

| Component | Status | Details |
|-----------|--------|---------|
| Alarm Detection | ✅ Working | Device AI detects alarms correctly |
| Media Capture | ✅ Working | Photos and videos are captured |
| 0x0200 Sending | ✅ Working | Location reports are sent |
| 0x64/0x65 Data | ❌ **BROKEN** | **ADAS/DSM data NOT included** |
| Server Parsing | ⚠️ Idle | No alarm data to parse |
| Event Generation | ❌ **BROKEN** | **Cannot create events without alarm data** |
| 0x9208 Request | ❌ **BROKEN** | **Not sent (no alarm detected)** |
| 0x1210 Response | ❌ **BROKEN** | **Not sent (no request received)** |
| Video Upload | ❌ **BROKEN** | **Flow interrupted at alarm detection** |

**ROOT CAUSE**: Device firmware/configuration is not sending ADAS (0x64) or DSM (0x65) alarm data in 0x0200 location reports, preventing the server from detecting alarms and initiating the video attachment request sequence.

**PRIORITY**: **CRITICAL** - Fix device configuration immediately

**NEXT STEPS**: Contact device vendor (iStartek) to configure parameters 0x0076, 0x0077, 0x007E, 0x007F or upgrade firmware to support T/JSATL12-2017 alarm reporting.
