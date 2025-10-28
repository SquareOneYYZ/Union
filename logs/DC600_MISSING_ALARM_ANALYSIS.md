# DC600 Missing Alarm Types - Critical Analysis

**Date**: 2025-10-19
**Issue**: Real device data shows alarm types NOT captured as events

---

## Problem: Real Device Data Not Matching Specification

### User-Reported Data Patterns

```json
1. {"dsmAlarmId":104, "dsmStatus":243, "dsmType":225, "dsmLevel":?}
2. {"dsmAlarmId":0, "dsmStatus":0, "dsmType":0, "dsmLevel":11}
3. {"dsmAlarmId":0, "dsmStatus":0, "dsmType":0, "dsmLevel":10}
4. {"dsmAlarmId":0, "dsmStatus":0, "dsmLevel":9}
5. {"dsmAlarmId":0, "dsmStatus":0, "dsmLevel":8}
```

### Analysis

| Field | Pattern | Specification Range | Status |
|-------|---------|---------------------|--------|
| `dsmType: 225 (0xE1)` | Outside spec | 0x01-0x05, 0x10-0x12 | ❌ NOT IN SPEC |
| `dsmType: 0` | Type=0 with level 8-11 | Not defined | ❌ NOT IN SPEC |
| `dsmAlarmId: 0` | May indicate "no alarm" | Should be 1-255 | ⚠️ SPECIAL CASE |

---

## Root Cause Analysis

### Issue 1: Default Case Doesn't Set KEY_ALARM ❌

**Location**: `DC600ProtocolDecoder.java:479-481`

```java
default:
    position.set("dsmAlarmName", "unknown_" + alarmType);
    break;  // ← NO Position.KEY_ALARM SET!
```

**Impact**:
- `dsmType=225` (0xE1) falls into default case
- `dsmType=0` falls into default case
- **NO** `Position.KEY_ALARM` is set
- **NO** proper event triggered in Traccar
- Image capture is NOT triggered (requires KEY_ALARM, line 521)

### Issue 2: Event Correlation Created for alarmId=0 ⚠️

**Location**: `DC600ProtocolDecoder.java:435-441`

```java
String eventKey = position.getDeviceId() + "_" + alarmId;  // ← alarmId could be 0!
EventMediaCorrelation correlation = new EventMediaCorrelation();
correlation.deviceId = position.getDeviceId();
correlation.alarmId = alarmId;  // ← 0 might overwrite real alarms
```

**Impact**: When `alarmId=0`, creates correlation with key "deviceId_0" which may:
- Overwrite legitimate alarm correlations
- Cause media files to be incorrectly linked

### Issue 3: Alarm Attachment Requested for Invalid Types ⚠️

**Location**: `DC600ProtocolDecoder.java:503`

```java
sendAlarmAttachmentRequest(channel, remoteAddress, id, alarmId, alarmType);
// ← Called even for alarmId=0 or alarmType=225
```

**Impact**: Server sends 0x9208 requests for non-alarm data, wasting bandwidth

---

## Missing Alarm Type Investigation

### Hypothesis 1: dsmType=0 is "Fatigue Level Update" (NOT an alarm)

**Evidence:**
- All examples have `dsmAlarmId=0` (no active alarm)
- All have `dsmType=0` (no alarm type)
- All have varying `dsmLevel` (8, 9, 10, 11)
- Specification states fatigue level is 1-10 (higher = more severe)

**Conclusion**: `dsmType=0` with `dsmLevel > 0` appears to be **fatigue monitoring updates** WITHOUT an active alarm.

### Hypothesis 2: dsmType=225 (0xE1) is Vendor-Specific

**Evidence:**
- 0xE1 = 225 decimal
- Specification defines:
  - 0x01-0x05: Standard alarms
  - 0x06-0x0F: User-defined
  - 0x10-0x11: Events
  - 0x12-0x1F: User-defined
  - **0xE0-0xFF: NOT DEFINED**

**Binary Analysis:**
```
0xE1 = 11100001
       ^^^^^^^^
       Outside spec range (0x00-0x1F)
```

**Possible Meanings:**
1. Manufacturer-specific extension (iStartek proprietary)
2. Alarm "end" or "clear" signal
3. Composite alarm (multiple bits set)
4. Firmware bug/corruption

### Hypothesis 3: alarmId=0 Signals "No Active Alarm"

**Evidence:**
- When `alarmId=0`, usually `dsmType=0` and `dsmStatus=0`
- Might be periodic status update, not an alarm event

**Conclusion**: `alarmId=0` should be **filtered out** from event correlation

---

## Code Issues Summary

### Current Behavior for Unhandled Types

```java
// For dsmType=225 or dsmType=0:
position.set("dsmAlarmId", alarmId);          // ✅ Set
position.set("dsmStatus", alarmStatus);        // ✅ Set
position.set("dsmType", alarmType);            // ✅ Set
position.set("dsmLevel", alarmLevel);          // ✅ Set
position.set(Position.KEY_EVENT, ALARM_GENERAL); // ✅ Set (line 444)
position.set("dsmAlarmName", "unknown_225");   // ✅ Set (line 480)

// BUT:
position.set(Position.KEY_ALARM, ???);         // ❌ NOT SET!
```

### Why Events Aren't Created

Even though `Position.KEY_EVENT` is set at line 444, Traccar's event system may require **BOTH**:
1. `Position.KEY_EVENT` (set to alarm type)
2. `Position.KEY_ALARM` (set to same alarm type)

Without `KEY_ALARM`, the event handler may not recognize it as a proper alarm.

Additionally, image capture is explicitly skipped (line 521 checks for `KEY_ALARM`).

---

## Recommended Fixes

### Fix 1: Handle dsmType=0 as Fatigue Monitoring Update

```java
switch (alarmType) {
    case 0x00: // Fatigue monitoring update (NOT an alarm)
        if (alarmLevel > 0) {
            position.set("dsmAlarmName", "fatigueMonitoring");
            position.set("fatigueLevel", alarmLevel);
            LOGGER.debug("DSM Fatigue Monitoring - Device: {}, Level: {}",
                        position.getDeviceId(), alarmLevel);
            // Do NOT set KEY_ALARM - this is monitoring, not an alarm
            // Do NOT create event correlation
            // Do NOT request alarm attachments
        }
        // Skip event correlation and attachment request for type 0
        return; // Exit early, don't process as alarm

    case 0x01: // Fatigue driving alarm
        // ... existing code ...
```

### Fix 2: Add Support for Vendor-Specific Types

```java
    case 0xE1: // Vendor-specific alarm (iStartek extension)
        position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
        position.set("dsmAlarmName", "vendorSpecific_E1");
        position.set(Position.KEY_EVENT, Position.ALARM_GENERAL);
        LOGGER.warn("DSM VENDOR-SPECIFIC ALARM 0xE1 - Device: {}, AlarmId: {}, Status: {}, Level: {}",
                    position.getDeviceId(), alarmId, alarmStatus, alarmLevel);
        break;

    default:
        // For truly unknown types, still set KEY_ALARM to ensure event creation
        position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
        position.set("dsmAlarmName", "unknown_0x" + Integer.toHexString(alarmType).toUpperCase());
        LOGGER.warn("DSM UNKNOWN ALARM TYPE - Device: {}, AlarmId: {}, Type: 0x{}, Status: {}, Level: {}",
                    position.getDeviceId(), alarmId,
                    Integer.toHexString(alarmType).toUpperCase(), alarmStatus, alarmLevel);
        break;
}
```

### Fix 3: Filter alarmId=0 from Event Correlation

```java
// Only create event correlation for actual alarms (alarmId > 0)
if (alarmId > 0 && alarmType != 0x00) {
    String eventKey = position.getDeviceId() + "_" + alarmId;
    EventMediaCorrelation correlation = new EventMediaCorrelation();
    correlation.deviceId = position.getDeviceId();
    correlation.alarmId = alarmId;
    correlation.alarmType = "DSM_" + Integer.toHexString(alarmType).toUpperCase();
    correlation.eventTime = new Date();
    eventMediaMap.put(eventKey, correlation);

    // Only request alarm attachments for real alarms
    LOGGER.info("Triggering alarm attachment request for DSM alarm - Device: {}, AlarmId: {}, Type: 0x{}",
                position.getDeviceId(), alarmId, Integer.toHexString(alarmType).toUpperCase());
    sendAlarmAttachmentRequest(channel, remoteAddress, id, alarmId, alarmType);
} else {
    LOGGER.debug("Skipping event correlation for DSM monitoring data - AlarmId: {}, Type: 0x{}",
                alarmId, Integer.toHexString(alarmType).toUpperCase());
}
```

---

## ADAS Alarm Analysis

### Check for Similar Issues

Need to verify if ADAS has the same problem with `adasType=0` or unknown types.

**Action Required**: Check logs for patterns like:
- `"adasAlarmId":0`
- `"adasType":0`
- `"adasType"` values outside 0x01-0x07, 0x10-0x11

---

## Testing Checklist

After applying fixes, verify:

1. ✅ `dsmType=0` with levels 8-11 creates fatigue monitoring attribute (NOT an alarm event)
2. ✅ `dsmType=225` creates event with KEY_ALARM set
3. ✅ `alarmId=0` does NOT create event correlation
4. ✅ `alarmId=0` does NOT trigger alarm attachment request (0x9208)
5. ✅ Unknown DSM types (not in spec) still create events in tc_events table
6. ✅ Image capture is triggered for unknown alarm types

---

## Summary

### Critical Gaps Found

| Issue | Impact | Severity |
|-------|--------|----------|
| Default case doesn't set KEY_ALARM | No events created for unknown types | 🔴 **HIGH** |
| alarmId=0 creates bogus correlations | Media files incorrectly linked | 🟡 **MEDIUM** |
| Type 0 treated as alarm | Fatigue monitoring triggers false alarms | 🟡 **MEDIUM** |
| Unknown types waste bandwidth | Unnecessary 0x9208 requests | 🟢 **LOW** |

### Alarm Types Not in Specification

| Type | Decimal | Observed Data | Likely Meaning |
|------|---------|---------------|----------------|
| 0x00 | 0 | alarmId=0, levels 8-11 | Fatigue monitoring update |
| 0xE1 | 225 | alarmId=104, status=243 | Vendor-specific alarm |

**Recommendation**: Apply all three fixes to ensure complete alarm coverage.
