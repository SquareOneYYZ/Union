# DC600 Event-Media Correlation System

**Created**: 2025-10-19
**Purpose**: Link alarm events with their corresponding multimedia files (images, videos, audio)

---

## Overview

The DC600 protocol now includes a comprehensive event-media correlation system that:

1. ✅ **Records all events in the Traccar events table** (`tc_events`)
2. ✅ **Links multimedia files (images/videos/audio) to alarm events**
3. ✅ **Enables retrieval of event media through position attributes**
4. ✅ **Automatically correlates alarm events with subsequent media uploads**

---

## How It Works

### 1. Alarm Detection → Event Creation

When an alarm is detected (ADAS, DSM, or standard JT808 alarm):

```
Device → Location Report (0x0200) with alarm → Platform
                                                  ↓
                                      Decoder detects alarm
                                                  ↓
                        Position created with Position.KEY_ALARM and Position.KEY_EVENT
                                                  ↓
                              Event recorded in tc_events table
                                                  ↓
                        EventMediaCorrelation created with alarmId
```

**Key Attributes Set**:
- `Position.KEY_ALARM` - Alarm type (e.g., "sos", "accident", "phoneCall")
- `Position.KEY_EVENT` - **CRITICAL**: Triggers Traccar's event system
- `adasAlarmId` or `dsmAlarmId` - Alarm serial number from device
- `adasAlarmType` or `dsmAlarmType` - Alarm type code

### 2. Image Capture → Media ID Correlation

Platform automatically sends image capture request (0x8801):

```
Platform → Image Capture Request (0x8801) → Device
                                               ↓
                                    Device captures images
                                               ↓
Device → Image Capture Response (0x0805) → Platform
                                          ↓
                          Response contains media IDs
                                          ↓
            EventMediaCorrelation updated with expected media IDs
```

**What's Stored**:
- Media IDs that will be uploaded
- Linked to the EventMediaCorrelation by device ID and alarm ID

### 3. Multimedia Upload → Media File Storage

Device uploads actual media files:

```
Device → Multimedia Data Upload (0x0801) → Platform
                                              ↓
                            Decoder matches multimedia ID to event
                                              ↓
                              Media file saved to storage
                                              ↓
                    Position created with media file path and event correlation
                                              ↓
                            Position.KEY_IMAGE/VIDEO/AUDIO set
                                              ↓
                    EventMediaCorrelation updated with file path
```

**Key Attributes Set**:
- `Position.KEY_IMAGE` - Image file path (for type 0)
- `Position.KEY_VIDEO` - Video file path (for type 2)
- `Position.KEY_AUDIO` - Audio file path (for type 1)
- `multimediaFile` - File path
- `eventAlarmId` - Original alarm ID (links back to event)
- `eventAlarmType` - Original alarm type (e.g., "DSM_02" for phone use)
- `correlatedEvent` - Boolean flag indicating linked to event
- `event` - Set to "alarmMultimediaComplete"

**Position Validity**:
- Multimedia positions linked to events are marked as **VALID** (`position.setValid(true)`)
- Position timestamp matches the original alarm event time
- This ensures proper event recording in Traccar

---

## Database Schema

### Events Table (`tc_events`)

```sql
SELECT
    e.id as event_id,
    e.type as event_type,
    e.eventtime,
    e.deviceid,
    e.positionid,
    d.name as device_name
FROM tc_events e
JOIN tc_devices d ON e.deviceid = d.id
WHERE e.type LIKE '%alarm%'
ORDER BY e.eventtime DESC;
```

### Positions with Media (`tc_positions`)

```sql
SELECT
    p.id as position_id,
    p.deviceid,
    p.fixtime,
    p.attributes->>'$.image' as image_file,
    p.attributes->>'$.video' as video_file,
    p.attributes->>'$.audio' as audio_file,
    p.attributes->>'$.eventAlarmId' as alarm_id,
    p.attributes->>'$.eventAlarmType' as alarm_type,
    p.attributes->>'$.multimediaFile' as media_file
FROM tc_positions p
WHERE p.attributes->'$.correlatedEvent' = 'true'
ORDER BY p.fixtime DESC;
```

---

## Querying Events with Media

### 1. Find All Events with Associated Media

```sql
SELECT
    e.id as event_id,
    e.type as event_type,
    e.eventtime,
    d.name as device_name,
    p1.attributes->>'$.adasAlarmId' as adas_alarm_id,
    p1.attributes->>'$.dsmAlarmId' as dsm_alarm_id,
    GROUP_CONCAT(DISTINCT p2.attributes->>'$.multimediaFile') as media_files,
    GROUP_CONCAT(DISTINCT p2.attributes->>'$.image') as images,
    GROUP_CONCAT(DISTINCT p2.attributes->>'$.video') as videos
FROM tc_events e
JOIN tc_devices d ON e.deviceid = d.id
JOIN tc_positions p1 ON e.positionid = p1.id
LEFT JOIN tc_positions p2 ON
    p2.deviceid = e.deviceid
    AND p2.attributes->'$.correlatedEvent' = 'true'
    AND (
        p2.attributes->>'$.eventAlarmId' = p1.attributes->>'$.adasAlarmId'
        OR p2.attributes->>'$.eventAlarmId' = p1.attributes->>'$.dsmAlarmId'
    )
WHERE e.type LIKE '%alarm%'
GROUP BY e.id
ORDER BY e.eventtime DESC;
```

### 2. Find All Phone Use Detection Events with Videos

```sql
SELECT
    e.id as event_id,
    e.eventtime,
    d.name as device_name,
    d.uniqueid as device_imei,
    p1.attributes->>'$.dsmAlarmId' as alarm_id,
    p1.attributes->>'$.phoneUseDetected' as phone_detected,
    p2.attributes->>'$.video' as video_file,
    p2.attributes->>'$.image' as image_file,
    p2.attributes->>'$.multimediaFile' as media_file
FROM tc_events e
JOIN tc_devices d ON e.deviceid = d.id
JOIN tc_positions p1 ON e.positionid = p1.id
LEFT JOIN tc_positions p2 ON
    p2.deviceid = e.deviceid
    AND p2.attributes->'$.eventAlarmId' = p1.attributes->>'$.dsmAlarmId'
    AND p2.attributes->'$.correlatedEvent' = 'true'
WHERE e.type = 'alarm'
    AND p1.attributes->>'$.phoneUseDetected' = 'true'
ORDER BY e.eventtime DESC;
```

### 3. Find All ADAS Forward Collision Events with Media

```sql
SELECT
    e.id as event_id,
    e.eventtime,
    d.name as device_name,
    p1.attributes->>'$.adasAlarmId' as alarm_id,
    p1.attributes->>'$.adasAlarmName' as alarm_name,
    p2.attributes->>'$.video' as video_file,
    p2.attributes->>'$.image' as image_file
FROM tc_events e
JOIN tc_devices d ON e.deviceid = d.id
JOIN tc_positions p1 ON e.positionid = p1.id
LEFT JOIN tc_positions p2 ON
    p2.deviceid = e.deviceid
    AND p2.attributes->'$.eventAlarmId' = p1.attributes->>'$.adasAlarmId'
    AND p2.attributes->'$.correlatedEvent' = 'true'
WHERE e.type = 'alarm'
    AND p1.attributes->>'$.adasAlarmName' = 'forwardCollision'
ORDER BY e.eventtime DESC;
```

### 4. Get Media Count per Event Type

```sql
SELECT
    p1.attributes->>'$.adasAlarmName' as adas_alarm_type,
    p1.attributes->>'$.dsmAlarmName' as dsm_alarm_type,
    COUNT(DISTINCT e.id) as event_count,
    COUNT(DISTINCT p2.id) as media_position_count,
    COUNT(DISTINCT CASE WHEN p2.attributes->'$.image' IS NOT NULL THEN p2.id END) as image_count,
    COUNT(DISTINCT CASE WHEN p2.attributes->'$.video' IS NOT NULL THEN p2.id END) as video_count
FROM tc_events e
JOIN tc_positions p1 ON e.positionid = p1.id
LEFT JOIN tc_positions p2 ON
    p2.deviceid = e.deviceid
    AND p2.attributes->'$.correlatedEvent' = 'true'
    AND (
        p2.attributes->>'$.eventAlarmId' = p1.attributes->>'$.adasAlarmId'
        OR p2.attributes->>'$.eventAlarmId' = p1.attributes->>'$.dsmAlarmId'
    )
WHERE e.type LIKE '%alarm%'
GROUP BY adas_alarm_type, dsm_alarm_type
ORDER BY event_count DESC;
```

---

## API Usage

### REST API: Get Events with Media

```bash
# Get all events for a device
curl -u admin:admin "http://localhost:8082/api/events?deviceId=1"

# Get specific event details
curl -u admin:admin "http://localhost:8082/api/events/123"

# Get positions with media for a device
curl -u admin:admin "http://localhost:8082/api/positions?deviceId=1&from=2025-10-01T00:00:00Z&to=2025-10-31T23:59:59Z"
```

### Extract Media from Position Attributes

```javascript
// JavaScript example
fetch('/api/positions?deviceId=1&from=2025-10-01T00:00:00Z')
  .then(response => response.json())
  .then(positions => {
    positions.forEach(pos => {
      if (pos.attributes.correlatedEvent) {
        console.log('Event Alarm ID:', pos.attributes.eventAlarmId);
        console.log('Event Type:', pos.attributes.eventAlarmType);
        console.log('Video File:', pos.attributes.video);
        console.log('Image File:', pos.attributes.image);
        console.log('Audio File:', pos.attributes.audio);
      }
    });
  });
```

---

## Event Types and Their Media

### Standard JT808 Alarms
All trigger image capture automatically:

| Alarm Type | Position.KEY_ALARM | Event Type | Media Types |
|------------|-------------------|------------|-------------|
| SOS Emergency | `sos` | alarm | Image, Video |
| Overspeed | `overspeed` | alarm | Image, Video |
| Fault | `fault` | alarm | Image |
| Geofence Enter | `geofenceEnter` | alarm | Image, Video |
| Geofence Exit | `geofenceExit` | alarm | Image, Video |
| Fatigue Driving | `fatigueDriving` | alarm | Image, Video |

### ADAS Alarms (T/JSATL12-2017)
Trigger both image capture AND alarm attachment request:

| Code | Alarm Name | Position.KEY_ALARM | Media Types |
|------|------------|-------------------|-------------|
| 0x01 | Forward Collision | `accident` | Image, Video |
| 0x02 | Lane Departure | `laneChange` | Image, Video |
| 0x03 | Vehicle Too Close | `general` | Image, Video |
| 0x04 | Pedestrian Collision | `accident` | Image, Video |
| 0x05 | Frequent Lane Change | `laneChange` | Image, Video |
| 0x06 | Road Sign Violation | `overspeed` | Image, Video |
| 0x07 | Obstacle Detection | `general` | Image, Video |

### DSM Alarms (T/JSATL12-2017)
Trigger both image capture AND alarm attachment request:

| Code | Alarm Name | Position.KEY_ALARM | Media Types | Notes |
|------|------------|-------------------|-------------|-------|
| 0x01 | Fatigue Driving | `fatigueDriving` | Image, Video | |
| 0x02 | **Phone Use** | `phoneCall` | **Image, Video** | **phoneUseDetected=true** |
| 0x03 | Smoking | `general` | Image, Video | |
| 0x04 | Distracted Driving | `general` | Image, Video | |
| 0x05 | Driver Abnormal | `general` | Image, Video | |

---

## Media File Storage

### File Path Format

Media files are stored using Traccar's `BaseProtocolDecoder.writeMediaFile()` method:

```
{storage.path}/media/{uniqueId}/{timestamp}.{extension}
```

**Example**:
```
/var/lib/traccar/media/123456789012345/2025101912345678.jpg
/var/lib/traccar/media/123456789012345/2025101912345679.mp4
```

### File Extensions

| Format Code | Extension | MIME Type | Description |
|-------------|-----------|-----------|-------------|
| 0 | `.jpg` | image/jpeg | JPEG image |
| 1 | `.tif` | image/tiff | TIFF image |
| 2 | `.mp3` | audio/mpeg | MP3 audio |
| 3 | `.wav` | audio/wav | WAV audio |
| 4 | `.wmv` | video/x-ms-wmv | WMV video |

### Accessing Media Files

Via Traccar API:
```bash
# Download media file
curl -u admin:admin "http://localhost:8082/api/media/{device_id}/{filename}" -o video.mp4
```

---

## Troubleshooting

### Events Not Appearing in tc_events Table

**Check**:
1. Verify `Position.KEY_EVENT` is being set (check logs for "ALARM DETECTED")
2. Ensure position is marked as valid (`position.setValid(true)`)
3. Check Traccar event handlers are enabled
4. Verify alarm type is in Traccar's supported alarm list

**Solution**:
```java
// Ensure both KEY_ALARM and KEY_EVENT are set
position.set(Position.KEY_ALARM, Position.ALARM_PHONE_CALL);
position.set(Position.KEY_EVENT, Position.ALARM_PHONE_CALL); // Critical!
```

### Media Not Linked to Events

**Check**:
1. Verify media IDs are being stored in EventMediaCorrelation
2. Check logs for "Linked X media IDs to event AlarmId: Y"
3. Verify multimedia upload contains matching media ID
4. Check eventMediaMap is not being cleared prematurely

**Debug**:
```bash
# Check decoder logs
grep "EVENT CORRELATION" traccar.log
grep "Multimedia.*linked to event" traccar.log
grep "Event AlarmId.*now has.*media files" traccar.log
```

### Old Correlations Not Cleaning Up

The system automatically removes correlations older than 1 hour. Check:
```bash
grep "Removing old event correlation" traccar.log
```

---

## Implementation Details

### EventMediaCorrelation Class

```java
private static final class EventMediaCorrelation {
    private long deviceId;                      // Device database ID
    private int alarmId;                         // Alarm serial number from device
    private String alarmType;                    // "ADAS_01", "DSM_02", etc.
    private Date eventTime;                      // When alarm occurred
    private List<Integer> expectedMediaIds;      // Media IDs from capture response
    private List<String> receivedMediaPaths;     // Actual file paths
}
```

### Correlation Lifecycle

1. **Creation**: When alarm is detected (ADAS/DSM)
2. **Media ID Update**: When image capture response received (0x0805)
3. **File Path Update**: When multimedia data uploaded (0x0801)
4. **Cleanup**: Automatically removed after 1 hour

### Memory Management

- **Cleanup Frequency**: 10% chance on each multimedia upload
- **Max Age**: 1 hour (3600000 ms)
- **Storage**: HashMap with device_alarmId key
- **Cleanup Method**: `cleanupOldCorrelations()`

---

## Summary

The DC600 event-media correlation system provides:

1. ✅ **Automatic event recording** in Traccar's events table
2. ✅ **Media file linking** to alarm events via correlation IDs
3. ✅ **SQL queryable** event-media relationships
4. ✅ **API accessible** event and media data
5. ✅ **Memory managed** with automatic cleanup
6. ✅ **Production ready** with comprehensive logging

All alarms (JT808, ADAS, DSM) now trigger proper events AND capture multimedia, with automatic correlation that survives the time delay between alarm detection and media upload.

**Key Innovation**: Uses `eventAlarmId` and `eventAlarmType` position attributes to link multimedia uploads back to original alarm events, even when they arrive in separate protocol messages minutes apart.
