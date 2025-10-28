# DC600 Protocol Decoder Bug Report

**Date**: 2025-10-22
**Severity**: HIGH
**Status**: WORKAROUND IMPLEMENTED
**Affects**: ADAS alarm processing (0x64)

---

## Bug Description

The DC600ProtocolDecoder has a buffer overrun bug at **line 539** when processing ADAS alarm additional information (ID 0x64).

### Error Encountered

```
java.lang.IndexOutOfBoundsException: readerIndex(50) + length(3) exceeds writerIndex(52)
    at org.traccar.protocol.DC600ProtocolDecoder.decodeLocationAdditionalInfo(DC600ProtocolDecoder.java:539)
```

---

## Root Cause Analysis

### Code at Line 539 (DC600ProtocolDecoder.java)

```java
// Lines 416-420: Read ADAS alarm basic data
if (length >= 4) {
    long alarmId = buf.readUnsignedInt();       // 4 bytes
    int alarmStatus = buf.readUnsignedByte();    // 1 byte
    int alarmType = buf.readUnsignedByte();      // 1 byte
    int alarmLevel = buf.readUnsignedByte();     // 1 byte
    // Total bytes read: 7

    // ... alarm processing ...

    // Lines 527-540: Extended data or skip
    if (length >= 32) {
        // Read extended data (25 more bytes)
        position.set("adasSpeed", buf.readUnsignedByte());
        position.set("adasAltitude", buf.readUnsignedShort() / 10.0);
        position.set("adasLatitude", buf.readInt() / 1000000.0);
        position.set("adasLongitude", buf.readInt() / 1000000.0);
        buf.skipBytes(6);   // Time
        buf.skipBytes(2);   // Vehicle status
        buf.skipBytes(Math.min(16, buf.readableBytes())); // Alarm ID
    } else {
        buf.skipBytes(length - 4);  // ❌ BUG: Should be (length - 7)
    }
}
```

### The Problem

**Lines 417-420 read 7 bytes total:**
- `alarmId` (4 bytes, line 417)
- `alarmStatus` (1 byte, line 418)
- `alarmType` (1 byte, line 419)
- `alarmLevel` (1 byte, line 420)

**Line 539 tries to skip `length - 4` bytes**, but it should skip `length - 7` bytes because 7 bytes were already read.

### Example Scenario

**Input**: ADAS alarm with 7-byte data (minimum structure)
- Additional info ID: 0x64
- Length: 7
- Data: alarm_id (4) + status (1) + type (1) + level (1) = 7 bytes

**Execution**:
1. Line 416: Check `length (7) >= 4`? YES
2. Lines 417-420: Read 7 bytes
3. Line 527: Check `length (7) >= 32`? NO
4. Line 539: Try to skip `7 - 4 = 3` bytes
5. **ERROR**: Only 0 bytes remain in buffer! (7 bytes already read)

---

## Why DSM Doesn't Have This Bug

The DSM alarm handler (case 0x65, lines 568-692) is **correct** because:

```java
// Lines 570-573: Read DSM alarm basic data
int alarmId = buf.readUnsignedByte();       // 1 byte
int alarmStatus = buf.readUnsignedByte();    // 1 byte
int alarmType = buf.readUnsignedByte();      // 1 byte
int alarmLevel = buf.readUnsignedByte();     // 1 byte
// Total bytes read: 4

// Lines 680-692: Extended data or skip
if (length >= 32) {
    // Read extended data
} else {
    buf.skipBytes(length - 4);  // ✅ CORRECT: 4 bytes were read
}
```

DSM reads exactly 4 bytes, then skips `length - 4` bytes → **Correct!**

---

## Impact

- **ADAS alarms with length < 32** will crash the decoder
- **Clients sending minimal ADAS data** (7 bytes) cannot connect
- **Only full ADAS structures** (42+ bytes, length >= 32) work

---

## Fix Options

### Option 1: Fix the Decoder (Recommended)

**File**: `DC600ProtocolDecoder.java`
**Line**: 539

**Change from:**
```java
buf.skipBytes(length - 4);
```

**Change to:**
```java
buf.skipBytes(length - 7);  // We read 7 bytes: alarmId(4) + status(1) + type(1) + level(1)
```

**Or safer:**
```java
if (buf.readableBytes() >= length - 7) {
    buf.skipBytes(length - 7);
}
```

### Option 2: Workaround in Client (Current Solution)

Send the **full 42-byte ADAS structure** to trigger the `length >= 32` branch:

```python
# ADAS data structure (42 bytes total)
adas_data = struct.pack(">I", alarm_id)              # 4 bytes
adas_data += struct.pack("B", adas_status)           # 1 byte
adas_data += struct.pack("B", adas_type)             # 1 byte
adas_data += struct.pack("B", adas_level)            # 1 byte
adas_data += struct.pack("B", speed)                 # 1 byte
adas_data += struct.pack(">H", int(altitude * 10))   # 2 bytes
adas_data += struct.pack(">i", latitude)             # 4 bytes
adas_data += struct.pack(">i", longitude)            # 4 bytes
adas_data += time_bcd                                # 6 bytes (BCD)
adas_data += struct.pack(">H", status & 0xFFFF)      # 2 bytes
adas_data += b'\x00' * 16                            # 16 bytes
# Total: 42 bytes
```

With length = 42 >= 32, the decoder takes the correct branch (lines 527-537) and avoids the buggy line 539.

---

## Testing

### Test Case 1: Minimal ADAS (7 bytes) - FAILS

**Input:**
```
Additional Info: 0x64
Length: 7
Data: [alarm_id(4), status(1), type(1), level(1)]
```

**Result:** `IndexOutOfBoundsException` at line 539

### Test Case 2: Full ADAS (42 bytes) - WORKS

**Input:**
```
Additional Info: 0x64
Length: 42
Data: [alarm_id(4), status(1), type(1), level(1), speed(1), altitude(2),
       lat(4), lon(4), time(6), vehicle_status(2), alarm_id(16)]
```

**Result:** ✅ Successfully processed, bug line skipped

### Test Case 3: DSM (4 bytes) - WORKS

**Input:**
```
Additional Info: 0x65
Length: 4
Data: [alarm_id(1), status(1), type(1), level(1)]
```

**Result:** ✅ No issue, decoder logic is correct for DSM

---

## Verification

After applying the workaround (sending 42-byte ADAS structure):

```bash
# Run test script
cd tools/dc600
python test_event_multimedia.py
```

**Expected Traccar logs:**
```
INFO: ADAS ALARM DETECTED - Device: 123456789012, AlarmId: 42, Type: 0x01, Status: 1, Level: 2
WARN: ADAS ALARM TYPE: Forward Collision Warning - Device: 123456789012, AlarmId: 42
INFO: Triggering alarm attachment request for ADAS alarm
```

**NO IndexOutOfBoundsException errors!**

---

## Recommended Action

1. ✅ **Immediate**: Use workaround in test script (already implemented)
2. 🔧 **Short-term**: Fix DC600ProtocolDecoder.java line 539:
   ```java
   buf.skipBytes(length - 7);  // Not (length - 4)
   ```
3. ✅ **Testing**: Verify fix with minimal 7-byte ADAS data
4. 🚀 **Deploy**: Rebuild and restart Traccar server

---

## Related Issues

- This bug only affects **ADAS alarms (0x64)**
- **DSM alarms (0x65)** are not affected (correct implementation)
- The bug was introduced when ADAS support was added
- Real devices likely send full 42-byte structures, so bug may not surface in production

---

## Protocol Specification Reference

**JT/T 808-2013 + T/JSATL12-2017**

ADAS Alarm Additional Info (0x64) structure:
- Alarm ID (4 bytes)
- Status (1 byte)
- Type (1 byte)
- Level (1 byte)
- **If length >= 32:**
  - Speed (1 byte)
  - Altitude (2 bytes)
  - Latitude (4 bytes)
  - Longitude (4 bytes)
  - Time (6 bytes, BCD)
  - Vehicle Status (2 bytes)
  - Alarm Identification (16 bytes)

**Minimum size**: 7 bytes (should be supported but currently causes crash)
**Full size**: 42 bytes (works correctly)

---

**Discovered**: 2025-10-22
**Reporter**: Test script analysis
**Status**: Workaround implemented, decoder fix recommended
**Priority**: HIGH (crashes decoder with valid minimal ADAS data)
