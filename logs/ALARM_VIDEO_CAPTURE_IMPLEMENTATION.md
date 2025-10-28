# DC600 Alarm Video Capture Implementation

## Overview

This document describes the implementation of automatic video/image capture for all alarms and alerts in the DC600 protocol, while maintaining full video streaming capabilities.

## Implementation Date
2025-10-18

## Changes Summary

### 1. Automatic Image Capture for All Alarms ✓

**File**: `DC600ProtocolDecoder.java`

#### New Method: `sendImageCaptureRequest()` (lines 170-193)
Automatically sends image capture requests to the device when any alarm is detected.

**Features**:
- Captures from channel 1
- Immediate capture (no timing/scheduling)
- Saves to device storage
- Standard resolution and quality settings
- Default brightness/contrast/saturation values

**Message Format**: JT/T 808 Section 8.30 (0x8801)

```java
private void sendImageCaptureRequest(Channel channel, SocketAddress remoteAddress, ByteBuf id) {
    ByteBuf data = Unpooled.buffer();
    data.writeByte(0x01);              // Channel number
    data.writeByte(0x00);              // Capture immediately
    data.writeByte(0x00);              // No timing
    data.writeShort(0x0000);           // No interval
    data.writeByte(0x01);              // Save to storage
    data.writeByte(0x01);              // Standard resolution
    data.writeByte(0x01);              // Standard quality
    data.writeByte(0x55);              // Brightness (default)
    data.writeByte(0x55);              // Contrast (default)
    data.writeByte(0x55);              // Saturation (default)
    data.writeByte(0x55);              // Chroma (default)
    // Send to device...
}
```

#### Modified Method: `decodeLocationReport()` (lines 476-487)
Now automatically triggers image capture when alarms are detected in location reports.

```java
Position position = decodeLocationBasicInfo(deviceSession, buf, timeZone);
decodeLocationAdditionalInfo(position, buf, channel, remoteAddress, id);

// NEW: Automatically trigger image capture for any alarm event
if (position.hasAttribute(Position.KEY_ALARM)) {
    sendImageCaptureRequest(channel, remoteAddress, id);
}

return position;
```

#### Modified Method: `decodeLocationBatch()` (lines 492-514)
Also triggers image capture for alarms in batch uploads.

```java
for (int i = 0; i < count; i++) {
    Position position = decodeLocationBasicInfo(deviceSession, locationBuf, timeZone);
    decodeLocationAdditionalInfo(position, locationBuf, channel, remoteAddress, id);

    // NEW: Automatically trigger image capture for any alarm event in batch
    if (position.hasAttribute(Position.KEY_ALARM)) {
        sendImageCaptureRequest(channel, remoteAddress, id);
    }

    positions.add(position);
}
```

### 2. Image Capture Response Handling ✓

**File**: `DC600ProtocolDecoder.java`

#### New Method: `decodeImageCaptureResponse()` (lines 821-876)
Properly handles responses from the device after image capture requests.

**Response Fields Captured**:
- `imageCaptureResult`: 0=success, 1=failure, 2=channel not supported
- `imageCaptureStatus`: Human-readable status
- `mediaIdCount`: Number of media IDs returned
- `mediaIds`: Comma-separated list of media IDs
- `event`: Event type (imageCaptureSuccess, imageCaptureFailed, etc.)

**Message Format**: JT/T 808 Section 8.31 (0x0805)

#### Added Switch Case (lines 661-664)
```java
case MSG_IMAGE_CAPTURE_RESPONSE:
    // Section 8.31: Image/video capture response (0x0805)
    sendGeneralResponse(channel, remoteAddress, id, type, index);
    return decodeImageCaptureResponse(deviceSession, buf);
```

### 3. ADAS/DSM Alarm Attachment Requests (Already Implemented)

**Verified**: ADAS and DSM alarms already trigger automatic alarm attachment requests per T/JSATL12-2017 specification.

- **ADAS Alarms** (line 397): Forward collision, lane departure, pedestrian collision, etc.
- **DSM Alarms** (line 460): Fatigue driving, phone use, smoking, distracted driving, etc.

These automatically call `sendAlarmAttachmentRequest()` which requests the terminal to upload all alarm-related media files.

### 4. Video Streaming Capabilities Retained ✓

**File**: `DC600ProtocolEncoder.java`

All video streaming commands remain fully functional:

#### Live Streaming (lines 109-142)
- Command: `TYPE_LIVE_STREAM`
- Message ID: 0x9101
- Server: 143.198.33.215:9101 (TCP)
- Supports audio/video with primary/sub streams

#### Stop Live Streaming (lines 145-158)
- Command: `TYPE_STOP_LIVE_STREAM`
- Message ID: 0x9102
- Gracefully stops active streams

#### Video Playback (lines 160-177)
- Command: `TYPE_VIDEO_PLAYBACK`
- Message ID: 0x9201
- Server: 165.22.228.97:5999 (TCP)
- Supports time-range playback

#### Video Download (lines 179-188)
- Command: `TYPE_VIDEO_DOWNLOAD`
- Message ID: 0x9206
- Downloads recorded media from device

## Alarm Types That Trigger Auto-Capture

### Standard JT/T 808 Alarms (Table 24)
All trigger image capture via `decodeAlarmSigns()`:

1. **Bit 0**: SOS Emergency
2. **Bit 1**: Overspeed
3. **Bit 2**: Fault
4. **Bit 3**: General alarm
5. **Bit 13**: Overspeed warning
6. **Bit 14**: Fatigue driving
7. **Bit 18**: Day overspeed
8. **Bit 19**: Idle timeout
9. **Bit 20**: Geofence enter
10. **Bit 21**: Geofence exit
11. **Bit 22**: GPS module failure
12. **Bit 23**: GPS antenna disconnected

### ADAS Alarms (T/JSATL12-2017 Table 4-15)
Trigger both image capture AND alarm attachment request:

1. **0x01**: Forward collision warning
2. **0x02**: Lane departure warning
3. **0x03**: Vehicle distance monitoring
4. **0x04**: Pedestrian collision warning
5. **0x05**: Frequent lane change
6. **0x06**: Road sign violation
7. **0x07**: Obstacle detection

### DSM Alarms (T/JSATL12-2017 Table 4-17)
Trigger both image capture AND alarm attachment request:

1. **0x01**: Fatigue driving
2. **0x02**: Phone use detection ⭐
3. **0x03**: Smoking
4. **0x04**: Distracted driving
5. **0x05**: Driver abnormal

## Data Flow

### When Alarm Occurs:

```
Device → Alarm in Location Report (0x0200) → Platform
                                              ↓
                                    decodeLocationReport()
                                              ↓
                                    Detect alarm attribute
                                              ↓
                                  ┌──────────┴──────────┐
                                  ↓                     ↓
                    Standard JT808 Alarm        ADAS/DSM Alarm
                                  ↓                     ↓
                    sendImageCaptureRequest()   sendAlarmAttachmentRequest()
                                  ↓             + sendImageCaptureRequest()
                                  └──────────┬──────────┘
                                             ↓
Platform → Image Capture Request (0x8801) → Device
                                             ↓
                           Device captures image/video
                                             ↓
Device → Image Capture Response (0x0805) → Platform
                                             ↓
                              decodeImageCaptureResponse()
                                             ↓
                              Store media IDs in database
```

### For ADAS/DSM Alarms (Additional Flow):

```
Platform → Alarm Attachment Request (0x9208) → Device
                                                 ↓
                               Device prepares all alarm media
                                                 ↓
Device → Alarm Attachment Info (0x1210) → Platform
                                            ↓
                          Lists all files (images, videos, audio)
                                            ↓
Device → File Upload (0x1211/0x1212) → Platform
                                         ↓
                       Store all alarm media files
```

## Testing Recommendations

### 1. Test Basic Alarm Image Capture
```
Trigger: SOS button press
Expected:
- Position report with ALARM_SOS
- Automatic 0x8801 message sent to device
- Device responds with 0x0805 containing media ID
```

### 2. Test ADAS Alarm Capture
```
Trigger: Forward collision warning (ADAS type 0x01)
Expected:
- Position report with ALARM_ACCIDENT
- Automatic 0x8801 message (image capture)
- Automatic 0x9208 message (alarm attachment request)
- Device responds with 0x0805 (capture response)
- Device responds with 0x1210 (attachment list)
- Device uploads files via 0x1211/0x1212
```

### 3. Test DSM Phone Use Detection
```
Trigger: Driver using phone (DSM type 0x02)
Expected:
- Position report with ALARM_PHONE_CALL
- phoneUseDetected = true
- Automatic 0x8801 message (image capture)
- Automatic 0x9208 message (alarm attachment request)
- Full media capture and upload
```

### 4. Test Video Streaming Still Works
```
Command: Send TYPE_LIVE_STREAM command via API
Expected:
- 0x9101 message sent to device
- Device responds with 0x1001
- Video stream established on server
```

### 5. Test Batch Upload with Alarms
```
Trigger: Device sends batch upload (0x0704) with multiple positions containing alarms
Expected:
- Image capture triggered for each position with alarm
- Multiple 0x8801 messages sent
```

## Configuration Notes

### Image Capture Parameters
Can be customized in `sendImageCaptureRequest()` (line 177-187):

- **Channel**: Currently set to 1 (modify if multi-camera)
- **Resolution**: 0x01 (standard), can be 0x00 (high) or 0x02 (low)
- **Quality**: 0x01 (standard), range 0x01-0x0A (1-10)
- **Brightness/Contrast/Saturation**: 0x55 (default), range 0x00-0xFF

### Video Server Configuration
Located in `DC600ProtocolEncoder.java`:

- **Live Stream Server**: 143.198.33.215:9101
- **Playback Server**: 165.22.228.97:5999
- Modify these for your production environment

## Protocol Compliance

✅ **JT/T 808-2013**: Base protocol with alarm detection
✅ **JT/T 1078-2016**: Video streaming protocol
✅ **T/JSATL12-2017**: ADAS/DSM alarm attachments

All implementations follow official specifications with section references in code comments.

## Build Status

✅ **Build**: Successful
✅ **Checkstyle**: Passed
✅ **Compilation**: No errors

## Summary

This implementation ensures that:

1. ✅ **All alarms trigger automatic image/video capture**
   - Standard JT808 alarms (SOS, overspeed, geofence, etc.)
   - ADAS alarms (collision, lane departure, etc.)
   - DSM alarms (fatigue, phone use, smoking, etc.)

2. ✅ **ADAS/DSM alarms get comprehensive media capture**
   - Automatic image capture request (0x8801)
   - Automatic alarm attachment request (0x9208)
   - Full file list and upload via 0x1210/0x1211/0x1212

3. ✅ **Video streaming capabilities fully preserved**
   - Live streaming (start/stop)
   - Video playback
   - Video download
   - Image capture on demand

4. ✅ **Complete response handling**
   - Image capture responses parsed (0x0805)
   - Media IDs tracked and stored
   - Error conditions properly handled

The system now provides complete alarm event documentation through automatic multimedia capture while maintaining all existing video streaming functionality.
