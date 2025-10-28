# DC600 Oct24-1 Server Switching Analysis

## Executive Summary

**Critical Finding**: During the oct24-1 session, the device **IS SENDING 0x64/0x65 ADAS/DSM data to the user's server**, but the **user's server is NOT triggering 0x9208 alarm attachment requests** despite receiving this data correctly.

**Root Cause**: The issue is NOT device configuration during this session. The issue is in the server's alarm detection and processing logic. The server receives ADAS alarm data but fails to trigger the media upload request (0x9208).

---

## Session Timeline (October 25, 2025, 05:54-06:00 AM)

### Device Log: `20251025-055457.log`
### Device ID: 496076898991

| Time | Event | Server | Details |
|------|-------|--------|---------|
| 05:54:57 | Device boot | - | Log file starts |
| 05:55:09 | **Connected** | iotstagingenv.duckdns.org:5999 | User's server |
| 05:55:40 | **Lane Departure Alarm** | User's server | 0x64 ADAS data sent |
| 05:55:40 | Server response | User's server | **0x8001 ONLY - NO 0x9208** ❌ |
| 05:56:07 | **Switched** | device.istarmap.com:9092 | Vendor server |
| 05:57:06 | **Forward Collision Alarm** | Vendor server | 0x64 ADAS data sent |
| 05:57:06 | Server response | Vendor server | **0x8001 + 0x9208** ✅ |
| 05:58:00 | Connection failures | Vendor server | Temporary network issue |
| 05:58:12 | Reconnected | Vendor server | Connection restored |
| 05:58:25 | **Distracted Driving Alarm** | Vendor server | 0x65 DSM data sent |
| 05:58:25 | Server response | Vendor server | **0x8001 + 0x9208** ✅ |
| 05:59:11 | **Forward Collision Alarm** | Vendor server | 0x64 ADAS data sent |
| 05:59:11 | Server response | Vendor server | **0x8001 + 0x9208** ✅ |

---

## Detailed Analysis

### Alarm 1: Lane Departure (User's Server) - **FAILED**

**Device Log Lines: 181-198**

**Device sends alarm** (05:55:40):
```
[INFO][myproduct][20251025-055540]: Send lane departure, Alarm time: 1761371735, jpg: 3, video: 1
[INFO][jt2013_M1][20251025-055540]: 上报 lane departure 报警, time: 1761371735, file_cnt: 4
[INFO][jt2013_M1][20251025-055540]: lane departure 插入到数据库, KeyId = 1761371735
```

**0x0200 Message with 0x64 ADAS Data** (Line 185-189):
```
[jt2013_M1][20251025-055540]: [send id = 0x200, seq = 7, pack(0/0), len = 110]:
7E 02 00 00 5F 49 60 76 89 89 91 00 07 00 00 00 00 00 4C 00 0B 02 98 E9 72 04 BF 4F 98 00 74 00
A9 00 83 25 10 25 05 55 40 01 04 00 00 00 06 25 04 00 00 00 00 30 01 1F 31 01 24 64 2F 00 00 00
00 00 02 01 00 00 00 00 00 26 00 75 02 98 EA 2F 04 BF 50 DC 25 10 25 05 55 35 04 01 30 00 00 00
00 00 00 25 10 25 05 55 35 00 04 00 BA 7E
```

**Decoded Additional Info Section**:
- `01 04 00 00 00 06` - 0x01: Mileage (6 km)
- `25 04 00 00 00 00` - 0x25: Unknown
- `30 01 1F` - 0x30: RSSI (31)
- `31 01 24` - 0x31: Satellites (36)
- **`64 2F ...`** - **0x64: ADAS alarm data (47 bytes)** ← PRESENT!

**Server Response** (Line 197-198):
```
[jt2013_M1][20251025-055540]: [recv id = 0x8001, seq = 0, pack(0/0), len = 20]:
7E 80 01 00 05 49 60 76 89 89 91 00 00 00 07 02 00 00 4F 7E
```

**Decoded Server Response**:
- Message ID: **0x8001** (Platform General Response)
- Sequence: 00 00
- Acknowledging: 00 07 (0x0200 sequence 7)
- Response ID: 02 00 (0x0200)
- Result: 00 (success)

**What's Missing**: **NO 0x9208** (Alarm Attachment Upload Request)

**Conclusion**: ❌ User's server acknowledged location report but **IGNORED ADAS alarm data**

---

### Alarm 2: Forward Collision (Vendor Server) - **SUCCESS**

**Device Log Lines: 522-551**

**Device sends alarm** (05:57:06):
```
[INFO][myproduct][20251025-055706]: Send please keep distance, Alarm time: 1761371820, jpg: 3, video: 1
[INFO][jt2013_M1][20251025-055706]: 上报 please keep distance 报警, time: 1761371820, file_cnt: 4
[INFO][jt2013_M1][20251025-055706]: please keep distance 插入到数据库, KeyId = 1001761371820
```

**0x0200 Message with 0x64 ADAS Data** (Line 526-530):
```
[jt2013_M1][20251025-055706]: [send id = 0x200, seq = 11, pack(0/0), len = 110]:
7E 02 00 00 5F 49 60 76 89 89 91 00 0B 00 00 00 00 00 4C 00 0B 02 98 E8 32 04 BF 4B E2 00 73 01
43 00 27 25 10 25 05 57 06 01 04 00 00 00 07 25 04 00 00 00 00 30 01 1F 31 01 23 64 2F 00 00 00
01 00 03 01 00 00 00 00 00 1D 00 73 02 98 E7 FF 04 BF 4D 7B 25 10 25 05 57 00 04 01 30 00 00 00
00 00 00 25 10 25 05 57 00 01 04 00 D8 7E
```

**Decoded Additional Info Section**:
- `01 04 00 00 00 07` - 0x01: Mileage (7 km)
- `25 04 00 00 00 00` - 0x25: Unknown
- `30 01 1F` - 0x30: RSSI (31)
- `31 01 23` - 0x31: Satellites (35)
- **`64 2F ...`** - **0x64: ADAS alarm data (47 bytes)** ← PRESENT!

**Server Response #1** (Line 532-533):
```
[jt2013_M1][20251025-055706]: [recv id = 0x8001, seq = 38721, pack(0/0), len = 20]:
7E 80 01 00 05 49 60 76 89 89 91 97 41 00 0B 02 00 00 95 7E
```
- **0x8001**: General Response (acknowledges 0x0200)

**Server Response #2** (Line 535-538):
```
[jt2013_M1][20251025-055706]: [recv id = 0x9208, seq = 1, pack(0/0), len = 96]:
7E 92 08 00 51 49 60 76 89 89 91 00 01 0C 34 37 2E 38 34 2E 36 38 2E 35 31 00 EA 61 00 00 30 00
00 00 00 00 00 25 10 25 05 57 00 01 04 00 65 61 30 30 66 65 65 30 66 63 33 61 34 34 37 30 39 31
63 37 33 36 37 66 35 66 61 39 63 66 65 30 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 8E 7E
```
- **0x9208**: Alarm Attachment Upload Request ✅
- Server IP: 47.84.68.51
- Port: 60001 (0xEA61)
- Alarm serial number: 0x30000000 (correlates with AlarmID from 0x64)

**Device Acknowledgment** (Line 540-541):
```
[jt2013_M1][20251025-055706]: [send id = 0x1, seq = 12, pack(0/0), len = 20]:
7E 00 01 00 05 49 60 76 89 89 91 00 0C 00 01 92 08 00 5D 7E
```
- **0x0001**: Terminal General Response (acknowledges 0x9208)

**Platform Request Logged** (Line 543):
```
[INFO][jt2013_M1][20251025-055706]: 平台请求附件: please keep distance, 报警时间: 1761371820
```

**Media Preparation** (Lines 548-551):
```
[INFO][jt2013_M1][20251025-055709]: Ai FileName: /mnt/mmc/Photo/CH1/CH1IMG20251025-055700-3.jpg
[INFO][jt2013_M1][20251025-055709]: Ai ReFileName: 00_64_6403_0_ea00fee0fc3a447091c7367f5fa9cfe0.jpg
[INFO][jt2013_M1][20251025-055709]: Ai Name: 00_64_6403_0.jpg
```

**Conclusion**: ✅ Vendor server correctly detected ADAS alarm and triggered media upload flow

---

### Alarm 3: Distracted Driving (Vendor Server) - **SUCCESS**

**Device Log Lines: 1143-1176**

**Device sends alarm** (05:58:25):
```
[INFO][myproduct][20251025-055825]: Send please keep attention, Alarm time: 1761371898, jpg: 3, video: 1
[INFO][jt2013_M1][20251025-055825]: 上报 please keep attention 报警, time: 1761371898, file_cnt: 4
[INFO][jt2013_M1][20251025-055825]: please keep attention 插入到数据库, KeyId = 1761371898
```

**0x0200 Message with 0x65 DSM Data** (Line 1151-1155):
```
[jt2013_M1][20251025-055825]: [send id = 0x200, seq = 6, pack(0/0), len = 110]:
7E 02 00 00 5F 49 60 76 89 89 91 00 06 00 00 00 00 00 4C 00 0B 02 98 FA EC 04 BF 33 57 00 71 01
CA 00 29 25 10 25 05 58 25 01 04 00 00 00 0C 25 04 00 00 00 00 30 01 1F 31 01 21 65 2F 00 00 00
00 00 04 01 00 00 00 00 00 27 00 75 02 98 F9 1C 04 BF 35 93 25 10 25 05 58 18 04 01 30 00 00 00
00 00 00 25 10 25 05 58 18 00 04 00 23 7E
```

**Decoded Additional Info Section**:
- `01 04 00 00 00 0C` - 0x01: Mileage (12 km)
- `25 04 00 00 00 00` - 0x25: Unknown
- `30 01 1F` - 0x30: RSSI (31)
- `31 01 21` - 0x31: Satellites (33)
- **`65 2F ...`** - **0x65: DSM alarm data (47 bytes)** ← PRESENT!

**Platform Request Logged** (Line 1176):
```
[INFO][jt2013_M1][20251025-055825]: 平台请求附件: please keep attention, 报警时间: 1761371898
```

**Conclusion**: ✅ Vendor server correctly detected DSM alarm and triggered media upload flow

---

### Alarm 4: Forward Collision (Vendor Server) - **SUCCESS**

**Device Log Lines: 1454-1477**

**Device sends alarm** (05:59:11):
```
[INFO][myproduct][20251025-055911]: Send watchout for car ahead, Alarm time: 1761371945, jpg: 3, video: 1
[INFO][jt2013_M1][20251025-055911]: 上报 watchout for car ahead 报警, time: 1761371945, file_cnt: 4
[INFO][jt2013_M1][20251025-055911]: watchout for car ahead 插入到数据库, KeyId = 1001761371945
```

**Platform Request Logged** (Line 1477):
```
[INFO][jt2013_M1][20251025-055911]: 平台请求附件: watchout for car ahead, 报警时间: 1761371945
```

**Conclusion**: ✅ Vendor server correctly detected ADAS alarm and triggered media upload flow

---

## Server Switching Behavior Analysis

### Connection Timeline

1. **05:55:09**: Connected to iotstagingenv.duckdns.org:5999 (user's server)
2. **05:56:07**: Switched to device.istarmap.com:9092 (vendor server)
3. **05:58:00**: Connection failure (temporary network issue)
4. **05:58:12**: Reconnected to vendor server
5. **05:58:24**: Connection established (secondary confirmation)

### Findings

✅ **Server switching works correctly**
- Device successfully transitions between servers
- No corruption of messages during switching
- Device maintains proper sequence numbers

✅ **Configuration persistence**
- Device sends 0x64/0x65 to **both** user's server and vendor server
- This contradicts the oct24 session where device sent only 0x70 to user's server
- Suggests device configuration CAN vary between sessions

✅ **Network resilience**
- Device handles temporary connection failures gracefully
- Automatic reconnection works correctly

❌ **User's server alarm processing BROKEN**
- Server receives 0x64 ADAS data but doesn't process it
- Server sends only 0x8001 (General Response), not 0x9208
- Alarm detection/triggering logic is not working

---

## Comparison: User's Server vs Vendor Server

| Aspect | User's Server | Vendor Server |
|--------|---------------|---------------|
| **Receives 0x0200** | ✅ Yes | ✅ Yes |
| **0x64/0x65 in message** | ✅ Present | ✅ Present |
| **Sends 0x8001 response** | ✅ Yes | ✅ Yes |
| **Sends 0x9208 request** | ❌ **NO** | ✅ **YES** |
| **Alarm detection** | ❌ **FAILED** | ✅ **SUCCESS** |
| **Media upload triggered** | ❌ **NO** | ✅ **YES** |

---

## Root Cause Analysis

### What We Learned from Oct24-1 Session

1. **Device Configuration is NOT the issue** (at least not during this session)
   - Device DOES send 0x64/0x65 ADAS/DSM data to user's server
   - Same data format as sent to vendor server
   - Proper message structure and encoding

2. **User's server receives the data correctly**
   - 110-byte 0x0200 messages with 0x64/0x65 sections
   - Server sends 0x8001 acknowledgment (proves message was received and parsed)

3. **User's server FAILS to detect/process the alarm**
   - No 0x9208 (Alarm Attachment Upload Request) sent
   - Alarm data appears to be ignored during processing
   - Event/alarm triggering logic is broken

### Contradicts Previous Findings

In the **oct24 session** (analyzed previously):
- Device sent **ONLY 0x70** (multi-media event) to user's server
- Device sent **0x64/0x65** to vendor server
- Conclusion: Device has server-specific configuration profiles

In the **oct24-1 session** (this analysis):
- Device sends **0x64/0x65** to user's server
- Device sends **0x64/0x65** to vendor server
- Conclusion: Device configuration CAN include ADAS/DSM for user's server

**Hypothesis**: Device configuration changed between oct24 and oct24-1 sessions, OR the 0x8103 parameter configuration we implemented WAS deployed and IS working!

---

## Issues Found

### Issue #1: User's Server Not Triggering 0x9208 ⚠️ **CRITICAL**

**Symptom**: When device sends 0x0200 with 0x64/0x65 ADAS/DSM data:
- User's server sends 0x8001 (General Response) ✅
- User's server does NOT send 0x9208 (Alarm Attachment Upload Request) ❌

**Affected Code**: `DC600ProtocolDecoder.java`

**Probable Causes**:

1. **ADAS/DSM alarm detection not working** (lines 434-649)
   - `case 0x64:` block may not be reached
   - `case 0x65:` block may not be reached
   - Position processing might be stopping before ADAS/DSM handlers

2. **Alarm event not being created**
   - Even if 0x64/0x65 is parsed, alarm event might not be generated
   - Event handlers might be filtering out the alarm

3. **0x9208 trigger logic not working** (lines 657-739)
   - `triggerAlarmAttachmentRequest()` might not be called
   - Conditions for triggering 0x9208 might not be met
   - AlarmId might not be properly set on position object

**Evidence from Oct24 Logs**:
- oct24.txt shows "WORKAROUND - Multi-media event detected (0x70) without ADAS/DSM data"
- This means the 0x70 workaround WAS being triggered
- But the proper 0x64/0x65 handlers were NOT being triggered
- This suggests 0x64/0x65 case blocks are NOT being reached

**Debugging Steps**:

1. Add more logging in DC600ProtocolDecoder.java:
   ```java
   case 0x64: // ADAS alarm
       LOGGER.info("ENTERING CASE 0x64 - ADAS ALARM HANDLER");
       if (length >= 7) {
           // ... existing code ...
       } else {
           LOGGER.warn("0x64 ADAS data too short: {} bytes", length);
       }
       break;
   ```

2. Add logging before switch statement:
   ```java
   while (buf.readableBytes() >= 2) {
       int fieldId = buf.readUnsignedByte();
       int length = buf.readUnsignedByte();
       LOGGER.debug("Additional Info Field - ID: 0x{}, Length: {}",
                    Integer.toHexString(fieldId), length);
   ```

3. Check if position object has alarm data set:
   ```java
   if (position.getAttributes().containsKey("adasAlarmId")) {
       LOGGER.info("Position has adasAlarmId: {}", position.get("adasAlarmId"));
   }
   if (position.getAttributes().containsKey("dsmAlarmId")) {
       LOGGER.info("Position has dsmAlarmId: {}", position.get("dsmAlarmId"));
   }
   ```

### Issue #2: Inconsistent Device Configuration Between Sessions

**Symptom**:
- **Oct24 session**: Device sent only 0x70 to user's server
- **Oct24-1 session**: Device sent 0x64/0x65 to user's server

**Possible Explanations**:

1. **0x8103 configuration WAS deployed between oct24 and oct24-1**
   - We implemented automatic ADAS/DSM parameter configuration
   - If this was deployed to staging server between the two sessions, it would explain the difference

2. **Device was manually reconfigured**
   - User or vendor might have changed device settings

3. **Server profile behavior**
   - Device might use different profiles based on server behavior
   - If server sends 0x8103 during registration, device enables ADAS/DSM
   - If server doesn't send 0x8103, device falls back to basic mode

**Verification Needed**:
- Check if updated DC600ProtocolDecoder.java with 0x8103 was deployed
- Check staging server logs for "Configuring ADAS/DSM parameters" message
- Check staging server logs for "Device accepted ADAS/DSM configuration" message

---

## Recommendations

### Priority 1: Fix Alarm Detection Logic ⚠️ **CRITICAL**

The user's server is receiving 0x64/0x65 data but NOT processing it. This must be fixed.

**Action Items**:

1. **Add extensive debug logging** to DC600ProtocolDecoder.java:
   - Log every additional info field ID encountered
   - Log when entering case 0x64 and case 0x65
   - Log when alarm attributes are set on position
   - Log when `triggerAlarmAttachmentRequest()` is called

2. **Verify message parsing flow**:
   - Ensure additional info parsing loop is reached
   - Ensure field ID and length are read correctly
   - Ensure case 0x64/0x65 blocks are entered

3. **Check position processing pipeline**:
   - Ensure position object reaches event handlers
   - Ensure alarm events are created from ADAS/DSM attributes
   - Ensure event handlers trigger 0x9208 requests

4. **Test with oct24-1 messages**:
   - Use the Python test suite to send the exact lane departure message
   - Monitor server logs for alarm detection
   - Verify 0x9208 is sent back

### Priority 2: Verify 0x8103 Configuration Deployment

**Action Items**:

1. **Check if 0x8103 implementation was deployed**:
   - Review deployment history between oct24 and oct24-1 sessions
   - Check current DC600ProtocolDecoder.java on staging server
   - Verify `configureDeviceAdasDsmProfile()` method exists

2. **Check staging server logs**:
   - Search for "Configuring ADAS/DSM parameters for THIS server profile"
   - Search for "Device accepted ADAS/DSM configuration"
   - Verify device 496076898991 received configuration

3. **If NOT deployed**:
   - Deploy the updated code with 0x8103 configuration
   - Monitor first device connection for configuration sequence

### Priority 3: Reproduce and Debug

**Action Items**:

1. **Trigger test alarm**:
   - Connect device to user's server
   - Trigger lane departure or forward collision alarm
   - Monitor server logs in real-time

2. **Use Python test script**:
   - Send exact 0x0200 message from line 185-189 (lane departure)
   - Monitor server response
   - Verify if 0x9208 is sent

3. **Compare with vendor server**:
   - Capture network traffic of device → vendor server
   - Compare vendor server's 0x9208 format with our implementation
   - Verify alarm serial number correlation logic

---

## Files Referenced

### Device Logs
- `C:\Users\Filing Cabinet\Downloads\device logs\oct24-1\easy_log\20251025-055457.log`

### Code Files
- `src/main/java/org/traccar/protocol/DC600ProtocolDecoder.java`
  - Lines 434-580: ADAS (0x64) handler
  - Lines 582-649: DSM (0x65) handler
  - Lines 657-739: Alarm attachment request trigger
  - Lines 740-785: 0x70 workaround (temporary)
  - Lines 247-283: 0x8103 ADAS/DSM configuration method
  - Lines 976-990: 0x8103 response handler

### Documentation
- `DC600_DEFINITIVE_ROOT_CAUSE.md` - Previous analysis showing server-specific config
- `DC600_SERVER_COMPARISON_ANALYSIS.md` - Comparison with working server
- `DC600_CONFIGURATION_IMPLEMENTATION.md` - 0x8103 implementation details

---

## Summary Table

| Alarm | Time | Type | Server | 0x64/0x65 Sent? | Server 0x8001? | Server 0x9208? | Status |
|-------|------|------|--------|----------------|----------------|----------------|--------|
| Lane Departure | 05:55:40 | ADAS (0x64) | User's | ✅ YES | ✅ YES | ❌ **NO** | ❌ FAILED |
| Forward Collision | 05:57:06 | ADAS (0x64) | Vendor | ✅ YES | ✅ YES | ✅ YES | ✅ SUCCESS |
| Distracted Driving | 05:58:25 | DSM (0x65) | Vendor | ✅ YES | ✅ YES | ✅ YES | ✅ SUCCESS |
| Forward Collision | 05:59:11 | ADAS (0x64) | Vendor | ✅ YES | ✅ YES | ✅ YES | ✅ SUCCESS |

---

## Key Takeaways

1. ✅ **Server switching works correctly** - No issues with device transitioning between servers

2. ✅ **Device DOES send ADAS/DSM data to user's server** (during oct24-1 session)

3. ❌ **User's server FAILS to process ADAS/DSM alarms** - receives data but doesn't trigger 0x9208

4. ✅ **Vendor server processes alarms correctly** - sends 0x8001 + 0x9208, receives media

5. ⚠️ **Device configuration is inconsistent between sessions** - oct24 vs oct24-1 behavior differs

6. 🔍 **Need to verify 0x8103 deployment** - implementation might already be working

7. 🐛 **Critical bug in user's server alarm detection** - must add logging and debug

---

**Analysis Date**: 2025-10-24
**Device ID**: 496076898991
**Session**: Oct24-1 (October 25, 2025, 05:54-06:00 AM)
**Servers Tested**: iotstagingenv.duckdns.org:5999 (user), device.istarmap.com:9092 (vendor)
**Conclusion**: User's server receives ADAS/DSM data correctly but fails to trigger media upload request
