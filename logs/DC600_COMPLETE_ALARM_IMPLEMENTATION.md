# DC600 Complete Alarm Implementation Guide

**Date**: 2025-10-19
**Purpose**: Complete alarm/event implementation based on specs + real device data

---

## Part 1: Complete Alarm Type Extraction from Specifications

### A. JT/T 808-2013 Alarm Bits (Table 24)

| Bit | Hex | Alarm Description | Currently Implemented | Position Constant |
|-----|-----|-------------------|----------------------|-------------------|
| 0 | 0x00000001 | Emergency alarm (SOS) | ✅ | ALARM_SOS |
| 1 | 0x00000002 | Overspeed alarm | ✅ | ALARM_OVERSPEED |
| 2 | 0x00000004 | Driving alarm malfunction | ✅ | ALARM_FAULT |
| 3 | 0x00000008 | Risk warning | ✅ | ALARM_GENERAL |
| 4 | 0x00000010 | GNSS module fault | ❌ | **MISSING** |
| 5 | 0x00000020 | GNSS antenna disconnected | ❌ | **MISSING** |
| 6 | 0x00000040 | GNSS antenna short circuit | ❌ | **MISSING** |
| 7 | 0x00000080 | Terminal main power under-voltage | ❌ | **MISSING** |
| 8 | 0x00000100 | Terminal main power off | ❌ | **MISSING** |
| 9 | 0x00000200 | Terminal LCD fault | ❌ | **MISSING** |
| 10 | 0x00000400 | TTS module fault | ❌ | **MISSING** |
| 11 | 0x00000800 | Camera fault | ❌ | **MISSING** |
| 12 | 0x00001000 | Road transport IC card module fault | ❌ | **MISSING** |
| 13 | 0x00002000 | Overspeed warning | ✅ | ALARM_OVERSPEED |
| 14 | 0x00004000 | Fatigue driving warning | ✅ | ALARM_FATIGUE_DRIVING |
| 18 | 0x00040000 | Accumulated overspeed time | ✅ | ALARM_OVERSPEED |
| 19 | 0x00080000 | Timeout parking | ✅ | ALARM_IDLE |
| 20 | 0x00100000 | Enter/exit area | ✅ | ALARM_GEOFENCE_ENTER |
| 21 | 0x00200000 | Enter/exit route | ✅ | ALARM_GEOFENCE_EXIT |
| 22 | 0x00400000 | Route driving time violation | ✅ | ALARM_GENERAL |
| 23 | 0x00800000 | Off track alarm | ✅ | ALARM_GENERAL |
| 24 | 0x01000000 | VSS fault | ❌ | **MISSING** |
| 25 | 0x02000000 | Vehicle oil amount abnormal | ❌ | **MISSING** |
| 26 | 0x04000000 | Vehicle stolen | ❌ | **MISSING** |
| 27 | 0x08000000 | Illegal ignition | ❌ | **MISSING** |
| 28 | 0x10000000 | Illegal displacement | ❌ | **MISSING** |
| 29 | 0x20000000 | Collision alarm | ❌ | **MISSING** |
| 30 | 0x40000000 | Rollover alarm | ❌ | **MISSING** |
| 31 | 0x80000000 | Illegal door opening | ❌ | **MISSING** |

**Missing from Implementation**: 20 alarm bits (bits 4-12, 24-31)

### B. T/JSATL12-2017 ADAS Alarms (Table 4-15)

| Code | Hex | Type | Description | Currently Implemented |
|------|-----|------|-------------|----------------------|
| 0x00 | 0 | Status | No alarm / monitoring update | ❌ NOT HANDLED |
| 0x01 | 1 | Alarm | Forward collision alarm | ✅ |
| 0x02 | 2 | Alarm | Lane departure alarm | ✅ |
| 0x03 | 3 | Alarm | Vehicle too close alarm | ✅ |
| 0x04 | 4 | Alarm | Pedestrian collision alarm | ✅ |
| 0x05 | 5 | Alarm | Frequent lane change alarm | ✅ |
| 0x06 | 6 | Alarm | Road sign out of limit alarm | ✅ |
| 0x07 | 7 | Alarm | Obstacle alarm | ✅ |
| 0x08-0x0F | 8-15 | User | User-defined alarms | ❌ NOT HANDLED |
| 0x10 | 16 | Event | Road sign recognition event | ❌ NOT HANDLED |
| 0x11 | 17 | Event | Actively capture event | ❌ NOT HANDLED |
| 0x12-0x1F | 18-31 | User | User-defined events | ❌ NOT HANDLED |

**Missing from Implementation**: Type 0x00, 0x08-0x0F, 0x10-0x1F (15 types)

### C. T/JSATL12-2017 DSM Alarms (Table 4-17)

| Code | Hex | Type | Description | Currently Implemented |
|------|-----|------|-------------|----------------------|
| 0x00 | 0 | Status | No alarm / fatigue monitoring | ❌ NOT HANDLED |
| 0x01 | 1 | Alarm | Fatigue driving alarm | ✅ |
| 0x02 | 2 | Alarm | Calling alarm (phone use) | ✅ |
| 0x03 | 3 | Alarm | Smoking alarm | ✅ |
| 0x04 | 4 | Alarm | Distracted driving alarm | ✅ |
| 0x05 | 5 | Alarm | Driver abnormal alarm | ✅ |
| 0x06-0x0F | 6-15 | User | User-defined alarms | ❌ NOT HANDLED |
| 0x10 | 16 | Event | Auto capture event | ❌ NOT HANDLED |
| 0x11 | 17 | Event | Driver change event | ❌ NOT HANDLED |
| 0x12-0x1F | 18-31 | User | User-defined events | ❌ NOT HANDLED |

**Additional Field**: `dsmLevel` (fatigue level 1-10, higher = more severe)

**Missing from Implementation**: Type 0x00, 0x06-0x0F, 0x10-0x1F (15 types)

### D. Real Device Data Analysis

#### Pattern 1: dsmType=225 (0xE1)
```json
{"dsmAlarmId":104, "dsmStatus":243, "dsmType":225}
```
- **0xE1 = 225 decimal**
- **Outside specification range** (0x00-0x1F)
- **Likely**: Vendor-specific extension or composite alarm
- **Current behavior**: Falls into `default` case, sets `dsmAlarmName` but **NO KEY_ALARM**

#### Pattern 2: dsmType=0 with dsmLevel
```json
{"dsmAlarmId":0, "dsmStatus":0, "dsmType":0, "dsmLevel":11}
{"dsmAlarmId":0, "dsmStatus":0, "dsmType":0, "dsmLevel":10}
{"dsmAlarmId":0, "dsmStatus":0, "dsmType":0, "dsmLevel":9}
{"dsmAlarmId":0, "dsmStatus":0, "dsmType":0, "dsmLevel":8}
```
- **Type 0** = No active alarm
- **alarmId 0** = No specific alarm instance
- **Varying levels 8-11** = Fatigue monitoring updates
- **Current behavior**: Treated as alarm, creates bogus event correlations

---

## Part 2: Critical Code Issues

### Issue 1: Default Case Missing KEY_ALARM ❌

**File**: `DC600ProtocolDecoder.java:479-481`

```java
default:
    position.set("dsmAlarmName", "unknown_" + alarmType);
    break;  // ❌ NO Position.KEY_ALARM - NO EVENT CREATED
```

**Impact**:
- Unknown types (0xE1, 0x08-0x0F, 0x10-0x1F) don't create events
- Image capture not triggered (requires KEY_ALARM)

### Issue 2: alarmId=0 Creates Bogus Correlations ⚠️

**File**: `DC600ProtocolDecoder.java:435`

```java
String eventKey = position.getDeviceId() + "_" + alarmId;  // alarmId could be 0!
```

**Impact**: Monitoring updates (type 0) create event correlations with key "device_0"

### Issue 3: Missing JT808 Alarm Bits ❌

**File**: `DC600ProtocolDecoder.java:225-276`

Only handles bits 0, 1, 2, 3, 13, 14, 18, 19, 20, 21, 22, 23
Missing bits 4-12, 24-31 (20 alarm types)

---

## Part 3: Required Position.java Constants

### Missing Constants Needed

```java
// Hardware fault alarms (JT808 bits 4-12)
public static final String ALARM_GPS_MODULE_FAULT = "gpsModuleFault";
public static final String ALARM_GPS_ANTENNA_DISCONNECTED = "gpsAntennaDisconnected";
public static final String ALARM_GPS_ANTENNA_SHORT = "gpsAntennaShort";
public static final String ALARM_MAIN_POWER_UNDER_VOLTAGE = "mainPowerUnderVoltage";
public static final String ALARM_MAIN_POWER_OFF = "mainPowerOff";
public static final String ALARM_LCD_FAULT = "lcdFault";
public static final String ALARM_TTS_FAULT = "ttsFault";
public static final String ALARM_CAMERA_FAULT = "cameraFault";
public static final String ALARM_IC_CARD_FAULT = "icCardFault";

// Vehicle fault alarms (JT808 bits 24-31)
public static final String ALARM_VSS_FAULT = "vssFault";
public static final String ALARM_OIL_ABNORMAL = "oilAbnormal";
public static final String ALARM_VEHICLE_STOLEN = "vehicleStolen";
public static final String ALARM_ILLEGAL_IGNITION = "illegalIgnition";
public static final String ALARM_ILLEGAL_DISPLACEMENT = "illegalDisplacement";
public static final String ALARM_COLLISION = "collision";
public static final String ALARM_ROLLOVER = "rollover";
public static final String ALARM_ILLEGAL_DOOR_OPEN = "illegalDoorOpen";
```

**Status**: Position.java already has `ALARM_GPS_ANTENNA_CUT` (line 139) which can be used for bit 5.

---

## Part 4: Complete Implementation Code

### Fix 1: Add Missing Constants to Position.java

**File**: `Position.java` (insert after line 160, before line 161)

```java
    public static final String ALARM_PHONE_CALL = "phoneCall";
    public static final String ALARM_SEAT_BELT = "seatBelt";

    // JT/T 808-2013 Hardware fault alarms (bits 4-12)
    public static final String ALARM_GPS_MODULE_FAULT = "gpsModuleFault";
    public static final String ALARM_GPS_ANTENNA_DISCONNECTED = "gpsAntennaDisconnected";
    public static final String ALARM_GPS_ANTENNA_SHORT = "gpsAntennaShort";
    public static final String ALARM_MAIN_POWER_UNDER_VOLTAGE = "mainPowerUnderVoltage";
    public static final String ALARM_MAIN_POWER_OFF = "mainPowerOff";
    public static final String ALARM_LCD_FAULT = "lcdFault";
    public static final String ALARM_TTS_FAULT = "ttsFault";
    public static final String ALARM_CAMERA_FAULT = "cameraFault";
    public static final String ALARM_IC_CARD_FAULT = "icCardFault";

    // JT/T 808-2013 Vehicle fault alarms (bits 24-31)
    public static final String ALARM_VSS_FAULT = "vssFault";
    public static final String ALARM_OIL_ABNORMAL = "oilAbnormal";
    public static final String ALARM_VEHICLE_STOLEN = "vehicleStolen";
    public static final String ALARM_ILLEGAL_IGNITION = "illegalIgnition";
    public static final String ALARM_ILLEGAL_DISPLACEMENT = "illegalDisplacement";
    public static final String ALARM_COLLISION = "collision";
    public static final String ALARM_ROLLOVER = "rollover";
    public static final String ALARM_ILLEGAL_DOOR_OPEN = "illegalDoorOpen";

    public Position() {
```

### Fix 2: Complete JT808 Alarm Bit Handling

**File**: `DC600ProtocolDecoder.java` - Replace `decodeAlarmSigns()` method (lines 225-281)

```java
private void decodeAlarmSigns(Position position, long alarmSign) {
    String alarmType = null;

    // Bit 0: Emergency alarm (SOS)
    if (BitUtil.check(alarmSign, 0)) {
        alarmType = Position.ALARM_SOS;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 1: Overspeed alarm
    if (BitUtil.check(alarmSign, 1)) {
        alarmType = Position.ALARM_OVERSPEED;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 2: Driving alarm malfunction
    if (BitUtil.check(alarmSign, 2)) {
        alarmType = Position.ALARM_FAULT;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 3: Risk warning
    if (BitUtil.check(alarmSign, 3)) {
        alarmType = Position.ALARM_GENERAL;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 4: GNSS module fault
    if (BitUtil.check(alarmSign, 4)) {
        alarmType = Position.ALARM_GPS_MODULE_FAULT;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 5: GNSS antenna disconnected
    if (BitUtil.check(alarmSign, 5)) {
        alarmType = Position.ALARM_GPS_ANTENNA_DISCONNECTED;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 6: GNSS antenna short circuit
    if (BitUtil.check(alarmSign, 6)) {
        alarmType = Position.ALARM_GPS_ANTENNA_SHORT;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 7: Terminal main power under-voltage
    if (BitUtil.check(alarmSign, 7)) {
        alarmType = Position.ALARM_MAIN_POWER_UNDER_VOLTAGE;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 8: Terminal main power off
    if (BitUtil.check(alarmSign, 8)) {
        alarmType = Position.ALARM_MAIN_POWER_OFF;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 9: Terminal LCD fault
    if (BitUtil.check(alarmSign, 9)) {
        alarmType = Position.ALARM_LCD_FAULT;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 10: TTS module fault
    if (BitUtil.check(alarmSign, 10)) {
        alarmType = Position.ALARM_TTS_FAULT;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 11: Camera fault
    if (BitUtil.check(alarmSign, 11)) {
        alarmType = Position.ALARM_CAMERA_FAULT;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 12: Road transport IC card module fault
    if (BitUtil.check(alarmSign, 12)) {
        alarmType = Position.ALARM_IC_CARD_FAULT;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 13: Overspeed warning
    if (BitUtil.check(alarmSign, 13)) {
        alarmType = Position.ALARM_OVERSPEED;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 14: Fatigue driving warning
    if (BitUtil.check(alarmSign, 14)) {
        alarmType = Position.ALARM_FATIGUE_DRIVING;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 18: Accumulated overspeed driving time
    if (BitUtil.check(alarmSign, 18)) {
        alarmType = Position.ALARM_OVERSPEED;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 19: Timeout parking
    if (BitUtil.check(alarmSign, 19)) {
        alarmType = Position.ALARM_IDLE;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 20: Enter and exit the area
    if (BitUtil.check(alarmSign, 20)) {
        alarmType = Position.ALARM_GEOFENCE_ENTER;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 21: Enter and exit the route
    if (BitUtil.check(alarmSign, 21)) {
        alarmType = Position.ALARM_GEOFENCE_EXIT;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 22: Driving time of route not enough/too long
    if (BitUtil.check(alarmSign, 22)) {
        alarmType = Position.ALARM_GENERAL;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 23: Off track alarm
    if (BitUtil.check(alarmSign, 23)) {
        alarmType = Position.ALARM_GENERAL;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 24: VSS fault
    if (BitUtil.check(alarmSign, 24)) {
        alarmType = Position.ALARM_VSS_FAULT;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 25: Vehicle oil amount abnormal
    if (BitUtil.check(alarmSign, 25)) {
        alarmType = Position.ALARM_OIL_ABNORMAL;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 26: Vehicle stolen
    if (BitUtil.check(alarmSign, 26)) {
        alarmType = Position.ALARM_VEHICLE_STOLEN;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 27: Illegal ignition
    if (BitUtil.check(alarmSign, 27)) {
        alarmType = Position.ALARM_ILLEGAL_IGNITION;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 28: Illegal displacement
    if (BitUtil.check(alarmSign, 28)) {
        alarmType = Position.ALARM_ILLEGAL_DISPLACEMENT;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 29: Collision alarm
    if (BitUtil.check(alarmSign, 29)) {
        alarmType = Position.ALARM_COLLISION;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 30: Rollover alarm
    if (BitUtil.check(alarmSign, 30)) {
        alarmType = Position.ALARM_ROLLOVER;
        position.set(Position.KEY_ALARM, alarmType);
    }
    // Bit 31: Illegal door opening
    if (BitUtil.check(alarmSign, 31)) {
        alarmType = Position.ALARM_ILLEGAL_DOOR_OPEN;
        position.set(Position.KEY_ALARM, alarmType);
    }

    // Set KEY_EVENT to ensure Traccar event system creates an event
    if (alarmType != null) {
        position.set(Position.KEY_EVENT, alarmType);
    }
}
```

### Fix 3: Complete ADAS Alarm Handling

**File**: `DC600ProtocolDecoder.java` - Replace ADAS switch statement (lines 358-392)

```java
switch (alarmType) {
    case 0x00: // No alarm - monitoring/status update only
        position.set("adasStatus", "monitoring");
        LOGGER.debug("ADAS Monitoring Update - Device: {}, Level: {}",
                    position.getDeviceId(), alarmLevel);
        // Don't set KEY_ALARM - this is monitoring, not an alarm
        // Don't create event correlation
        break;

    case 0x01: // Forward collision warning
        position.set(Position.KEY_ALARM, Position.ALARM_ACCIDENT);
        position.set("adasAlarmName", "forwardCollision");
        position.set(Position.KEY_EVENT, Position.ALARM_ACCIDENT);
        LOGGER.warn("ADAS ALARM TYPE: Forward Collision Warning - Device: {}, AlarmId: {}",
                position.getDeviceId(), alarmId);
        break;

    case 0x02: // Lane departure warning
        position.set(Position.KEY_ALARM, Position.ALARM_LANE_CHANGE);
        position.set("adasAlarmName", "laneDeparture");
        position.set(Position.KEY_EVENT, Position.ALARM_LANE_CHANGE);
        break;

    case 0x03: // Vehicle distance monitoring warning
        position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
        position.set("adasAlarmName", "vehicleTooClose");
        position.set(Position.KEY_EVENT, Position.ALARM_GENERAL);
        break;

    case 0x04: // Pedestrian collision warning
        position.set(Position.KEY_ALARM, Position.ALARM_ACCIDENT);
        position.set("adasAlarmName", "pedestrianCollision");
        position.set(Position.KEY_EVENT, Position.ALARM_ACCIDENT);
        break;

    case 0x05: // Frequent lane change warning
        position.set(Position.KEY_ALARM, Position.ALARM_LANE_CHANGE);
        position.set("adasAlarmName", "frequentLaneChange");
        position.set(Position.KEY_EVENT, Position.ALARM_LANE_CHANGE);
        break;

    case 0x06: // Road sign out of limit warning
        position.set(Position.KEY_ALARM, Position.ALARM_OVERSPEED);
        position.set("adasAlarmName", "roadSignViolation");
        position.set(Position.KEY_EVENT, Position.ALARM_OVERSPEED);
        break;

    case 0x07: // Obstacle warning
        position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
        position.set("adasAlarmName", "obstacleDetection");
        position.set(Position.KEY_EVENT, Position.ALARM_GENERAL);
        break;

    case 0x10: // Road sign recognition event
        position.set("adasEventName", "roadSignRecognition");
        position.set("event", "roadSignRecognition");
        LOGGER.info("ADAS EVENT: Road Sign Recognition - Device: {}, AlarmId: {}",
                    position.getDeviceId(), alarmId);
        // Don't set KEY_ALARM - this is an event, not an alarm
        break;

    case 0x11: // Actively capture event
        position.set("adasEventName", "activeCapture");
        position.set("event", "activeCapture");
        LOGGER.info("ADAS EVENT: Active Capture - Device: {}, AlarmId: {}",
                    position.getDeviceId(), alarmId);
        // Don't set KEY_ALARM - this is an event, not an alarm
        break;

    default:
        // Handle user-defined (0x08-0x0F, 0x12-0x1F) and vendor-specific types
        if (alarmType >= 0x08 && alarmType <= 0x0F) {
            position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
            position.set("adasAlarmName", "userDefined_" + alarmType);
            position.set(Position.KEY_EVENT, Position.ALARM_GENERAL);
            LOGGER.warn("ADAS USER-DEFINED ALARM - Device: {}, AlarmId: {}, Type: 0x{}",
                        position.getDeviceId(), alarmId, Integer.toHexString(alarmType).toUpperCase());
        } else if (alarmType >= 0x12 && alarmType <= 0x1F) {
            position.set("adasEventName", "userDefined_" + alarmType);
            position.set("event", "adasUserDefinedEvent");
            LOGGER.info("ADAS USER-DEFINED EVENT - Device: {}, AlarmId: {}, Type: 0x{}",
                        position.getDeviceId(), alarmId, Integer.toHexString(alarmType).toUpperCase());
        } else {
            // Vendor-specific or unknown type
            position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
            position.set("adasAlarmName", "vendorSpecific_0x" + Integer.toHexString(alarmType).toUpperCase());
            position.set(Position.KEY_EVENT, Position.ALARM_GENERAL);
            LOGGER.warn("ADAS VENDOR-SPECIFIC ALARM - Device: {}, AlarmId: {}, Type: 0x{}, Status: {}, Level: {}",
                        position.getDeviceId(), alarmId,
                        Integer.toHexString(alarmType).toUpperCase(), alarmStatus, alarmLevel);
        }
        break;
}
```

### Fix 4: Complete DSM Alarm Handling with Type 0 and 0xE1 Support

**File**: `DC600ProtocolDecoder.java` - Replace DSM case 0x65 (lines 418-507)

```java
case 0x65: // T/JSATL12-2017 Table 4-17: DSM alarm information
    if (length >= 4) {
        int alarmId = buf.readUnsignedByte();
        int alarmStatus = buf.readUnsignedByte();
        int alarmType = buf.readUnsignedByte();
        int alarmLevel = buf.readUnsignedByte();
        LOGGER.info("DSM ALARM DETECTED - Device: {}, AlarmId: {}, Type: 0x{}, Status: {}, Level: {}",
                position.getDeviceId(), alarmId,
                Integer.toHexString(alarmType).toUpperCase(),
                alarmStatus, alarmLevel);

        position.set("dsmAlarmId", alarmId);
        position.set("dsmStatus", alarmStatus);
        position.set("dsmType", alarmType);
        position.set("dsmLevel", alarmLevel);

        // Determine if this is an actual alarm or just monitoring data
        boolean isRealAlarm = (alarmId > 0 && alarmType > 0);

        // Map DSM alarm types to Traccar alarm constants
        switch (alarmType) {
            case 0x00: // No alarm - fatigue monitoring update
                position.set("dsmStatus", "fatigueMonitoring");
                position.set("fatigueLevel", alarmLevel);
                LOGGER.debug("DSM Fatigue Monitoring - Device: {}, Level: {}",
                            position.getDeviceId(), alarmLevel);
                // Don't set KEY_ALARM - this is monitoring, not an alarm
                // Don't create event correlation for monitoring data
                isRealAlarm = false;
                break;

            case 0x01: // Fatigue driving alarm
                position.set(Position.KEY_ALARM, Position.ALARM_FATIGUE_DRIVING);
                position.set("dsmAlarmName", "fatigueDriving");
                position.set(Position.KEY_EVENT, Position.ALARM_FATIGUE_DRIVING);
                LOGGER.warn("DSM ALARM TYPE: Fatigue Driving - Device: {}, AlarmId: {}, Level: {}",
                        position.getDeviceId(), alarmId, alarmLevel);
                break;

            case 0x02: // Calling/phone use alarm - CRITICAL FOR REQUIREMENT
                position.set(Position.KEY_ALARM, Position.ALARM_PHONE_CALL);
                position.set("dsmAlarmName", "phoneUse");
                position.set("phoneUseDetected", true);
                position.set(Position.KEY_EVENT, Position.ALARM_PHONE_CALL);
                LOGGER.warn("DSM ALARM TYPE: PHONE USE DETECTED - Device: {}, AlarmId: {}",
                        position.getDeviceId(), alarmId);
                break;

            case 0x03: // Smoking alarm
                position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
                position.set("dsmAlarmName", "smoking");
                position.set(Position.KEY_EVENT, Position.ALARM_GENERAL);
                LOGGER.warn("DSM ALARM TYPE: Smoking Detected - Device: {}, AlarmId: {}",
                        position.getDeviceId(), alarmId);
                break;

            case 0x04: // Distracted driving alarm
                position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
                position.set("dsmAlarmName", "distractedDriving");
                position.set(Position.KEY_EVENT, Position.ALARM_GENERAL);
                LOGGER.warn("DSM ALARM TYPE: Distracted Driving - Device: {}, AlarmId: {}",
                        position.getDeviceId(), alarmId);
                break;

            case 0x05: // Driver abnormal alarm
                position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
                position.set("dsmAlarmName", "driverAbnormal");
                position.set(Position.KEY_EVENT, Position.ALARM_GENERAL);
                break;

            case 0x10: // Auto capture event
                position.set("dsmEventName", "autoCapture");
                position.set("event", "dsmAutoCapture");
                LOGGER.info("DSM EVENT: Auto Capture - Device: {}, AlarmId: {}",
                            position.getDeviceId(), alarmId);
                // Don't set KEY_ALARM - this is an event, not an alarm
                isRealAlarm = false;
                break;

            case 0x11: // Driver change event
                position.set("dsmEventName", "driverChange");
                position.set("event", "driverChange");
                LOGGER.info("DSM EVENT: Driver Change - Device: {}, AlarmId: {}",
                            position.getDeviceId(), alarmId);
                // Don't set KEY_ALARM - this is an event, not an alarm
                isRealAlarm = false;
                break;

            default:
                // Handle user-defined (0x06-0x0F, 0x12-0x1F) and vendor-specific types (like 0xE1)
                if (alarmType >= 0x06 && alarmType <= 0x0F) {
                    position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
                    position.set("dsmAlarmName", "userDefined_" + alarmType);
                    position.set(Position.KEY_EVENT, Position.ALARM_GENERAL);
                    LOGGER.warn("DSM USER-DEFINED ALARM - Device: {}, AlarmId: {}, Type: 0x{}",
                                position.getDeviceId(), alarmId, Integer.toHexString(alarmType).toUpperCase());
                } else if (alarmType >= 0x12 && alarmType <= 0x1F) {
                    position.set("dsmEventName", "userDefined_" + alarmType);
                    position.set("event", "dsmUserDefinedEvent");
                    LOGGER.info("DSM USER-DEFINED EVENT - Device: {}, AlarmId: {}, Type: 0x{}",
                                position.getDeviceId(), alarmId, Integer.toHexString(alarmType).toUpperCase());
                    isRealAlarm = false;
                } else {
                    // Vendor-specific type (like 0xE1 = 225)
                    position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
                    position.set("dsmAlarmName", "vendorSpecific_0x" + Integer.toHexString(alarmType).toUpperCase());
                    position.set(Position.KEY_EVENT, Position.ALARM_GENERAL);
                    LOGGER.warn("DSM VENDOR-SPECIFIC ALARM 0x{} - Device: {}, AlarmId: {}, Status: {}, Level: {}",
                                Integer.toHexString(alarmType).toUpperCase(), position.getDeviceId(),
                                alarmId, alarmStatus, alarmLevel);
                }
                break;
        }

        // Read additional data if present
        if (length >= 32) {
            position.set("dsmSpeed", buf.readUnsignedByte()); // Vehicle speed (1 km/h)
            position.set("dsmAltitude", buf.readUnsignedShort() / 10.0); // Altitude (1/10 m)
            position.set("dsmLatitude", buf.readInt() / 1000000.0); // Latitude (degree * 10^6)
            position.set("dsmLongitude", buf.readInt() / 1000000.0); // Longitude (degree * 10^6)
            // BCD timestamp (6 bytes)
            buf.skipBytes(6);
            // Vehicle status (2 bytes)
            buf.skipBytes(2);
            // Alarm identification (16 bytes)
            buf.skipBytes(Math.min(16, buf.readableBytes()));
        } else {
            buf.skipBytes(length - 4);
        }

        // Only create event correlation and request attachments for REAL alarms
        // Skip monitoring updates (type 0x00), events (0x10-0x11), and alarmId=0
        if (isRealAlarm) {
            // Create event correlation for multimedia linking
            String eventKey = position.getDeviceId() + "_" + alarmId;
            EventMediaCorrelation correlation = new EventMediaCorrelation();
            correlation.deviceId = position.getDeviceId();
            correlation.alarmId = alarmId;
            correlation.alarmType = "DSM_" + Integer.toHexString(alarmType).toUpperCase();
            correlation.eventTime = new Date();
            eventMediaMap.put(eventKey, correlation);

            LOGGER.info("Triggering alarm attachment request for DSM alarm - Device: {}, AlarmId: {}, Type: 0x{}",
                        position.getDeviceId(), alarmId, Integer.toHexString(alarmType).toUpperCase());

            // Trigger automatic alarm attachment request
            sendAlarmAttachmentRequest(channel, remoteAddress, id, alarmId, alarmType);
        } else {
            LOGGER.debug("Skipping event correlation for DSM monitoring/event data - AlarmId: {}, Type: 0x{}",
                        alarmId, Integer.toHexString(alarmType).toUpperCase());
        }
    } else {
        buf.skipBytes(length);
    }
    break;
```

---

## Part 5: Complete Implementation Status Table

### JT/T 808-2013 Alarm Bits

| Bit | Hex Mask | Alarm Type | Spec Description | Status | Position Constant | Code Fix |
|-----|----------|------------|------------------|--------|-------------------|----------|
| 0 | 0x00000001 | Emergency | SOS button pressed | ✅ Implemented | ALARM_SOS | None |
| 1 | 0x00000002 | Overspeed | Speed exceeded limit | ✅ Implemented | ALARM_OVERSPEED | None |
| 2 | 0x00000004 | Fault | Driving alarm malfunction | ✅ Implemented | ALARM_FAULT | None |
| 3 | 0x00000008 | Warning | Risk warning | ✅ Implemented | ALARM_GENERAL | None |
| 4 | 0x00000010 | GPS Fault | GNSS module fault | ❌ **MISSING** | ALARM_GPS_MODULE_FAULT | **Fix 1, Fix 2** |
| 5 | 0x00000020 | GPS Antenna | GNSS antenna disconnected | ❌ **MISSING** | ALARM_GPS_ANTENNA_DISCONNECTED | **Fix 1, Fix 2** |
| 6 | 0x00000040 | GPS Short | GNSS antenna short circuit | ❌ **MISSING** | ALARM_GPS_ANTENNA_SHORT | **Fix 1, Fix 2** |
| 7 | 0x00000080 | Power Low | Main power under-voltage | ❌ **MISSING** | ALARM_MAIN_POWER_UNDER_VOLTAGE | **Fix 1, Fix 2** |
| 8 | 0x00000100 | Power Off | Main power disconnected | ❌ **MISSING** | ALARM_MAIN_POWER_OFF | **Fix 1, Fix 2** |
| 9 | 0x00000200 | LCD Fault | Terminal LCD fault | ❌ **MISSING** | ALARM_LCD_FAULT | **Fix 1, Fix 2** |
| 10 | 0x00000400 | TTS Fault | TTS module fault | ❌ **MISSING** | ALARM_TTS_FAULT | **Fix 1, Fix 2** |
| 11 | 0x00000800 | Camera Fault | Camera fault | ❌ **MISSING** | ALARM_CAMERA_FAULT | **Fix 1, Fix 2** |
| 12 | 0x00001000 | IC Card | IC card module fault | ❌ **MISSING** | ALARM_IC_CARD_FAULT | **Fix 1, Fix 2** |
| 13 | 0x00002000 | Overspeed Warn | Overspeed warning | ✅ Implemented | ALARM_OVERSPEED | None |
| 14 | 0x00004000 | Fatigue | Fatigue driving | ✅ Implemented | ALARM_FATIGUE_DRIVING | None |
| 18 | 0x00040000 | Speed Time | Accumulated overspeed | ✅ Implemented | ALARM_OVERSPEED | None |
| 19 | 0x00080000 | Parking | Timeout parking | ✅ Implemented | ALARM_IDLE | None |
| 20 | 0x00100000 | Geofence | Enter/exit area | ✅ Implemented | ALARM_GEOFENCE_ENTER | None |
| 21 | 0x00200000 | Route | Enter/exit route | ✅ Implemented | ALARM_GEOFENCE_EXIT | None |
| 22 | 0x00400000 | Route Time | Route time violation | ✅ Implemented | ALARM_GENERAL | None |
| 23 | 0x00800000 | Off Track | Off track alarm | ✅ Implemented | ALARM_GENERAL | None |
| 24 | 0x01000000 | VSS Fault | Vehicle speed sensor fault | ❌ **MISSING** | ALARM_VSS_FAULT | **Fix 1, Fix 2** |
| 25 | 0x02000000 | Oil Abnormal | Vehicle oil amount abnormal | ❌ **MISSING** | ALARM_OIL_ABNORMAL | **Fix 1, Fix 2** |
| 26 | 0x04000000 | Stolen | Vehicle stolen | ❌ **MISSING** | ALARM_VEHICLE_STOLEN | **Fix 1, Fix 2** |
| 27 | 0x08000000 | Illegal Ignition | Illegal vehicle ignition | ❌ **MISSING** | ALARM_ILLEGAL_IGNITION | **Fix 1, Fix 2** |
| 28 | 0x10000000 | Illegal Move | Illegal displacement | ❌ **MISSING** | ALARM_ILLEGAL_DISPLACEMENT | **Fix 1, Fix 2** |
| 29 | 0x20000000 | Collision | Collision alarm | ❌ **MISSING** | ALARM_COLLISION | **Fix 1, Fix 2** |
| 30 | 0x40000000 | Rollover | Rollover alarm | ❌ **MISSING** | ALARM_ROLLOVER | **Fix 1, Fix 2** |
| 31 | 0x80000000 | Door | Illegal door opening | ❌ **MISSING** | ALARM_ILLEGAL_DOOR_OPEN | **Fix 1, Fix 2** |

**Summary**: 12/32 implemented (37.5%), 20 missing

### T/JSATL12-2017 ADAS Alarms

| Code | Hex | Type | Description | Status | Constant | Code Fix |
|------|-----|------|-------------|--------|----------|----------|
| 0x00 | 0 | Status | No alarm / monitoring | ⚠️ **Partial** | N/A (monitoring) | **Fix 3** |
| 0x01 | 1 | Alarm | Forward collision | ✅ Implemented | ALARM_ACCIDENT | None |
| 0x02 | 2 | Alarm | Lane departure | ✅ Implemented | ALARM_LANE_CHANGE | None |
| 0x03 | 3 | Alarm | Vehicle too close | ✅ Implemented | ALARM_GENERAL | None |
| 0x04 | 4 | Alarm | Pedestrian collision | ✅ Implemented | ALARM_ACCIDENT | None |
| 0x05 | 5 | Alarm | Frequent lane change | ✅ Implemented | ALARM_LANE_CHANGE | None |
| 0x06 | 6 | Alarm | Road sign violation | ✅ Implemented | ALARM_OVERSPEED | None |
| 0x07 | 7 | Alarm | Obstacle detection | ✅ Implemented | ALARM_GENERAL | None |
| 0x08-0x0F | 8-15 | User Alarm | User-defined alarms | ❌ **MISSING** | ALARM_GENERAL | **Fix 3** |
| 0x10 | 16 | Event | Road sign recognition | ❌ **MISSING** | N/A (event) | **Fix 3** |
| 0x11 | 17 | Event | Active capture | ❌ **MISSING** | N/A (event) | **Fix 3** |
| 0x12-0x1F | 18-31 | User Event | User-defined events | ❌ **MISSING** | N/A (event) | **Fix 3** |

**Summary**: 7/12 ranges implemented (58%), 5 ranges missing

### T/JSATL12-2017 DSM Alarms

| Code | Hex | Type | Description | Real Device Example | Status | Constant | Code Fix |
|------|-----|------|-------------|---------------------|--------|----------|----------|
| 0x00 | 0 | Status | Fatigue monitoring | `alarmId:0, type:0, level:8-11` | ⚠️ **Partial** | N/A (monitoring) | **Fix 4** |
| 0x01 | 1 | Alarm | Fatigue driving | - | ✅ Implemented | ALARM_FATIGUE_DRIVING | None |
| 0x02 | 2 | Alarm | Phone use / calling | - | ✅ Implemented | ALARM_PHONE_CALL | None |
| 0x03 | 3 | Alarm | Smoking | - | ✅ Implemented | ALARM_GENERAL | None |
| 0x04 | 4 | Alarm | Distracted driving | - | ✅ Implemented | ALARM_GENERAL | None |
| 0x05 | 5 | Alarm | Driver abnormal | - | ✅ Implemented | ALARM_GENERAL | None |
| 0x06-0x0F | 6-15 | User Alarm | User-defined alarms | - | ❌ **MISSING** | ALARM_GENERAL | **Fix 4** |
| 0x10 | 16 | Event | Auto capture | - | ❌ **MISSING** | N/A (event) | **Fix 4** |
| 0x11 | 17 | Event | Driver change | - | ❌ **MISSING** | N/A (event) | **Fix 4** |
| 0x12-0x1F | 18-31 | User Event | User-defined events | - | ❌ **MISSING** | N/A (event) | **Fix 4** |
| 0xE1 | 225 | **Vendor** | **Vendor-specific** | `alarmId:104, status:243, type:225` | ❌ **MISSING** | ALARM_GENERAL | **Fix 4** |

**Summary**: 5/11 ranges implemented (45%), 6 ranges missing

---

## Part 6: Quick Implementation Summary

### Files to Modify

1. **Position.java** - Add 18 new alarm constants (Fix 1)
2. **DC600ProtocolDecoder.java** - Update 4 methods (Fixes 2, 3, 4)

### Lines of Code Changes

- **Position.java**: Insert 18 lines after line 160
- **DC600ProtocolDecoder.java**:
  - Replace `decodeAlarmSigns()` (lines 225-281) - expand from 57 to 180 lines
  - Replace ADAS switch (lines 358-392) - expand from 35 to 90 lines
  - Replace DSM case (lines 418-507) - expand from 90 to 160 lines

### Testing Checklist

After implementation:

1. ✅ Test JT808 bits 4-12, 24-31 create proper events
2. ✅ Test `dsmType=0` with `dsmLevel=8-11` creates monitoring attribute (NOT alarm)
3. ✅ Test `dsmType=225` (0xE1) creates vendor-specific alarm event
4. ✅ Test `alarmId=0` does NOT create event correlation
5. ✅ Test ADAS user-defined types (0x08-0x0F) create alarm events
6. ✅ Test ADAS/DSM events (0x10-0x11) create event attributes (NOT alarms)
7. ✅ Verify all alarm types trigger image capture (check `Position.KEY_ALARM` is set)
8. ✅ Verify events appear in `tc_events` table
9. ✅ Verify no bogus 0x9208 requests for monitoring data

---

## Part 7: Implementation Priority

### CRITICAL (Must Fix) 🔴

1. **Fix 4**: DSM type 0x00 and 0xE1 handling
   - **Your device is sending this data right now**
   - Not creating events in database
   - Not triggering image capture

### HIGH (Should Fix) 🟡

2. **Fix 2**: JT808 alarm bits 4-12, 24-31
   - 20 missing hardware/vehicle fault alarms
   - May be active on devices in field

### MEDIUM (Nice to Have) 🟢

3. **Fix 3**: ADAS user-defined and events
   - Improves protocol compliance
   - Future-proofs for new alarm types

### LOW (Optional) ⚪

4. Event types (0x10-0x11)
   - Informational only
   - Not safety-critical

---

**Next Steps**: Apply Fix 1 (Position.java constants) and Fix 4 (DSM handling) immediately to capture your device's current alarm data.
