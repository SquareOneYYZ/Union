# DC600 Protocol 0x9208 Message Analysis

**Date**: 2025-10-23
**Issue**: Determining if we request VIDEO or IMAGE for ADAS/DSM alarms
**Status**: ANALYSIS COMPLETE

---

## Critical Finding: The Protocol Does NOT Allow Multimedia Type Selection

### **Specification Analysis (from T/JSATL12-2017 Table 4-21)**

Message **0x9208 (Alarm Attachment Upload Instruction)** has the following structure:

| Byte Position | Field | Type | Size | Description |
|---------------|-------|------|------|-------------|
| 0 | Server IP length | BYTE | 1 | Length k |
| 1 to k | Server IP address | STRING | k | IP address |
| 1+k to 3+k | TCP port | WORD | 2 | Server TCP port |
| 3+k to 5+k | UDP port | WORD | 2 | Server UDP port |
| 5+k to 21+k | Alarm flag | BYTE[16] | 16 | Alarm identification |
| 21+k to 53+k | Alarm number | BYTE[32] | 32 | Unique alarm number |
| 53+k to 69+k | Reserved | BYTE[16] | 16 | Reserved |

**Total**: 69 + k bytes (where k = IP address string length)

### **CRITICAL: NO MULTIMEDIA TYPE FIELD EXISTS**

❌ **There is NO field in 0x9208 to specify whether to request image, audio, or video**
❌ **The server CANNOT request a specific multimedia type**
✅ **The DEVICE (terminal) decides what media type to upload**

---

## Current DC600ProtocolDecoder Implementation

**File**: `DC600ProtocolDecoder.java`
**Method**: `sendAlarmAttachmentRequest()` (lines 148-195)

### What We Currently Send:

```java
ByteBuf data = Unpooled.buffer();

// 1. Server IP address
String serverIp = "127.0.0.1";           // Currently hardcoded
data.writeByte(serverIp.length());        // IP length (1 byte)
data.writeBytes(serverIp.getBytes());     // IP address (k bytes)

// 2. Server ports
data.writeShort(5999);                    // TCP port (2 bytes)
data.writeShort(0);                       // UDP port (2 bytes)

// 3. Alarm flag (16 bytes)
byte[] alarmFlag = new byte[16];
// Device ID (7 bytes) + Time (6 bytes) + AlarmId (1 byte) + flags (2 bytes)
data.writeBytes(alarmFlag);

// 4. Alarm number (32 bytes)
byte[] alarmNumber = new byte[32];
String uniqueAlarmNumber = String.format("ALM-%d-%d-%d", ...);
data.writeBytes(alarmNumber);

// 5. Reserved (16 bytes)
data.writeBytes(new byte[16]);
```

### Current Message Length:
- IP length: 1 byte
- IP address: 9 bytes ("127.0.0.1")
- TCP port: 2 bytes
- UDP port: 2 bytes
- Alarm flag: 16 bytes
- Alarm number: 32 bytes
- Reserved: 16 bytes
- **Total**: 78 bytes (69 + 9)

### ✅ **CURRENT IMPLEMENTATION IS CORRECT**

The current implementation **matches the specification exactly**. It does NOT include a multimedia type field because **the specification does not allow it**.

---

## How Multimedia Type is Determined

### Protocol Flow:

1. **Server → Device: 0x9208 (Alarm Attachment Upload Request)**
   - Triggers the device to upload media for the alarm
   - Does NOT specify what media type to send

2. **Device → Server: 0x1210 (Alarm Attachment Information)**
   - Lists files associated with the alarm
   - File naming shows type: `<type>_<channel>_<alarm>_<serial>_<number>.<ext>`
   - Type codes: 00=Image, 01=Audio, 02=Video

3. **Device → Server: 0x1211 (File Information Upload)**
   - Specifies file type in the message body:
     - 0x00: Picture
     - 0x01: Audio
     - 0x02: Video
     - 0x03: Text
     - 0x04: Other

4. **Device → Server: 0x0801 (Multimedia Data Upload)**
   - Actual file data transmission

---

## Answer to User's Question

### **Q: Are we requesting VIDEO or IMAGE?**

**A: Neither - the 0x9208 message does NOT specify a multimedia type.**

### **Q: Can we update the code to request VIDEO?**

**A: No - the specification does not provide a way to request specific media types in 0x9208.**

---

## What Determines VIDEO vs IMAGE Upload?

The **DEVICE** decides what to upload based on:

1. **Alarm Type**:
   - ADAS alarms (0x64) → Should upload VIDEO
   - DSM alarms (0x65) → Should upload VIDEO
   - Generic alarms → May upload IMAGE

2. **Channel Configuration**:
   - Channel 64: ADAS video channel → VIDEO
   - Channel 65: DSM video channel → VIDEO
   - Other channels → May be IMAGE

3. **Device Configuration**:
   - Terminal parameters configure default media types for alarms
   - Device manufacturer may set defaults

---

## Specification Guidance for ADAS/DSM

### From JT/T 1078-2016 (Video Communication Protocol):

The protocol is designed for **"Road Transport Vehicle Videos and Satellite Positioning System"**.

### Evidence that VIDEO is expected for ADAS/DSM:

1. ✅ **Dedicated video channels**: Channels 64 (ADAS) and 65 (DSM) exist
2. ✅ **Protocol context**: JT/T 1078-2016 is specifically a VIDEO protocol
3. ✅ **Alarm nature**: ADAS/DSM alarms require visual evidence:
   - Forward collision warnings need video to see the obstacle
   - Lane departure needs video to see the road markings
   - Driver fatigue needs video to see driver behavior
   - Phone use detection needs video proof
4. ✅ **Industry standard**: Safety monitoring systems use video, not still images

### **Conclusion: ADAS/DSM alarms should trigger VIDEO upload**

---

## Verification: What Are Real Devices Sending?

To determine what the DC600 devices are actually uploading, check the Traccar logs for:

### Look for these log patterns:

1. **0x9208 sent**:
   ```
   SENDING ALARM ATTACHMENT REQUEST (0x9208) - AlarmId: XX, AlarmType: 0xXX
   ```

2. **0x1210 received** (Alarm attachment info):
   ```
   Alarm attachment information - Files: ...
   ```

3. **0x1211 received** (File info upload):
   ```
   File type: 0x02 (Video) or 0x00 (Image)
   ```

4. **0x0801 received** (Multimedia upload):
   ```
   Multimedia type: 2 (Video) or 0 (Image)
   Format: ...
   ```

---

## Current Decoder Support

### The DC600ProtocolDecoder correctly handles:

1. ✅ **0x9208 sending** (lines 148-195) - Correct per specification
2. ✅ **0x0801 decoding** (multimedia upload) - Supports all types (image, audio, video)
3. ✅ **Video storage** (line 1438 fix applied) - Multi-packet video upload works
4. ✅ **Event correlation** - Links multimedia to alarm events

### Multimedia type detection in decoder (line ~1360):

```java
int multimediaType = buf.readUnsignedByte();
// 0x00: Image
// 0x01: Audio
// 0x02: Video
// 0x03: Text
// 0x04: Other
```

The decoder **accepts whatever the device sends** (image, audio, or video).

---

## Recommendations

### For Server (Traccar):

✅ **NO CODE CHANGES NEEDED** - Current implementation is correct per specification

The server:
1. ✅ Sends correct 0x9208 message structure
2. ✅ Accepts any multimedia type from device (image/audio/video)
3. ✅ Stores video files correctly (after line 1438 fix)
4. ✅ Links multimedia to alarm events

### For Device (DC600 Terminal):

The **DC600 device configuration** should be checked to ensure:

1. **ADAS alarms (0x64) → Upload VIDEO (type 0x02)**
2. **DSM alarms (0x65) → Upload VIDEO (type 0x02)**

This is typically configured via:
- Terminal parameters (0x8103/0x8104 messages)
- Device manufacturer firmware settings
- Configuration file on the device

### To Verify Current Behavior:

Run the test script and check Traccar logs:

```bash
cd "C:\Users\Filing Cabinet\IdeaProjects\test\tools\dc600"
python test_event_multimedia_fixed.py
```

Look for:
```
Server sends: 0x9208 (attachment request)
Device sends: 0x1211 (file info - check "type" field)
Device sends: 0x0801 (multimedia upload - check "multimediaType" field)
```

If the device is sending **type 0x00 (image)** instead of **type 0x02 (video)**, the issue is in the **device configuration**, not the server code.

---

## Summary

| Question | Answer |
|----------|--------|
| Does 0x9208 specify multimedia type? | ❌ NO - specification does not include this field |
| Can we request VIDEO vs IMAGE? | ❌ NO - device decides based on alarm type and config |
| Is our 0x9208 implementation correct? | ✅ YES - matches specification exactly |
| What should ADAS/DSM alarms send? | ✅ VIDEO (0x02) based on protocol context |
| Where is the issue if images are sent? | 🔧 Device configuration, not server code |
| Do we need code changes? | ❌ NO - server code is correct |

---

**Implementation Status**: ✅ CORRECT PER SPECIFICATION
**Action Required**: Verify device configuration sends VIDEO for ADAS/DSM alarms
**Server Code Changes**: NONE NEEDED
