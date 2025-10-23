# DC600 Video Storage Fix - Verification Guide

This guide shows exactly what to look for to verify the video storage fix is working correctly.

## Quick Test

```bash
# Windows
cd tools\dc600
python test_event_multimedia.py

# Linux/Mac
cd tools/dc600
python3 test_event_multimedia.py
```

## Success Indicators

### 1. Console Output (Test Script)

**✅ CORRECT (Fix Working):**
```
[12:35:02.206] [INFO] Sending packet 5/5 (1024 bytes)...
[12:35:02.307] [SUCCESS] ✓ LAST PACKET - Video upload successful!
[12:35:02.308] [SUCCESS] ✓ Multi-packet video storage fix is WORKING!
```

**❌ INCORRECT (Fix Not Working):**
```
[12:35:02.206] [INFO] Sending packet 5/5 (1024 bytes)...
[12:35:02.307] [WARNING] ! LAST PACKET - No upload response (check server logs)
```
→ Check Traccar logs for errors

---

### 2. Traccar Server Logs

**✅ CORRECT (Fix Working):**
```
INFO: RECEIVED MULTIMEDIA DATA UPLOAD (0x0801) - Device: 123456789012345, Packet 1/5
INFO: NEW MULTIMEDIA FILE STARTED - Device: 123456789012345, MultimediaId: 456, Type: 2, Total Packages: 5
DEBUG: MULTIMEDIA PACKET PROCESSED - Device: 123456789012345, MultimediaId: 456, Package 1/5, Size: 1024 bytes

INFO: RECEIVED MULTIMEDIA DATA UPLOAD (0x0801) - Device: 123456789012345, Packet 2/5
DEBUG: MULTIMEDIA PACKET PROCESSED - Device: 123456789012345, MultimediaId: 456, Package 2/5, Size: 1024 bytes

INFO: RECEIVED MULTIMEDIA DATA UPLOAD (0x0801) - Device: 123456789012345, Packet 3/5
DEBUG: MULTIMEDIA PACKET PROCESSED - Device: 123456789012345, MultimediaId: 456, Package 3/5, Size: 1024 bytes

INFO: RECEIVED MULTIMEDIA DATA UPLOAD (0x0801) - Device: 123456789012345, Packet 4/5
DEBUG: MULTIMEDIA PACKET PROCESSED - Device: 123456789012345, MultimediaId: 456, Package 4/5, Size: 1024 bytes

INFO: RECEIVED MULTIMEDIA DATA UPLOAD (0x0801) - Device: 123456789012345, Packet 5/5
DEBUG: MULTIMEDIA PACKET PROCESSED - Device: 123456789012345, MultimediaId: 456, Package 5/5, Size: 1024 bytes
INFO: LAST PACKET RECEIVED - SAVING MULTIMEDIA FILE - Device: 123456789012345, MultimediaId: 456, Type: 2, Packages: 5/5, Total Size: 5120 bytes, Extension: wmv
INFO: MULTIMEDIA FILE SAVED SUCCESSFULLY - Path: /opt/traccar/media/123456789012345/2025-10-22/video_456.wmv, Size: 5120 bytes
```

**Key Success Indicators:**
- ✅ "LAST PACKET RECEIVED - SAVING MULTIMEDIA FILE" appears ONLY on packet 5/5
- ✅ "Packages: 5/5" shows all packets received
- ✅ "MULTIMEDIA FILE SAVED SUCCESSFULLY" with file path
- ✅ File path is valid and accessible

**❌ INCORRECT (Fix Not Working - Old Bug):**
```
INFO: RECEIVED MULTIMEDIA DATA UPLOAD (0x0801) - Device: 123456789012345, Packet 1/5
INFO: NEW MULTIMEDIA FILE STARTED - Device: 123456789012345, MultimediaId: 456, Type: 2, Total Packages: 5
DEBUG: MULTIMEDIA PACKET PROCESSED - Package 1/5, Size: 1024 bytes

...packets 2-5 received but no "LAST PACKET RECEIVED" message...

INFO: RECEIVED MULTIMEDIA DATA UPLOAD (0x0801) - Device: 123456789012345, Packet 5/5
DEBUG: MULTIMEDIA PACKET PROCESSED - Package 5/5, Size: 1024 bytes

(NO "LAST PACKET RECEIVED - SAVING MULTIMEDIA FILE" MESSAGE!)
(NO "MULTIMEDIA FILE SAVED SUCCESSFULLY" MESSAGE!)
```

**Problem:**
- ❌ File is NOT saved (no save messages)
- ❌ Completion detection failed
- ❌ Bug: `if (buf.readableBytes() == totalPackages)` is still in code

---

### 3. Database Check

**✅ CORRECT (Fix Working):**

```sql
SELECT
    p.id,
    p.devicetime,
    p.attributes->>'$.multimediaId' as media_id,
    p.attributes->>'$.multimediaType' as media_type,
    p.attributes->>'$.packageNo' as package_no,
    p.attributes->>'$.totalPackages' as total_packages,
    p.attributes->>'$.totalReceived' as total_bytes,
    p.attributes->>'$.video' as video_path,
    p.attributes->>'$.event' as event_type
FROM tc_positions p
WHERE p.deviceid = (SELECT id FROM tc_devices WHERE uniqueid = '123456789012345')
  AND p.attributes->>'$.multimediaType' = '2'  -- Video type
ORDER BY p.devicetime DESC
LIMIT 10;
```

Expected Result:
| id | media_id | media_type | package_no | total_packages | total_bytes | video_path | event_type |
|----|----------|------------|------------|----------------|-------------|------------|------------|
| 501 | 456 | 2 | 5 | 5 | 5120 | /opt/.../video_456.wmv | multimediaUploadComplete |
| 500 | 456 | 2 | 4 | 5 | 4096 | NULL | multimediaDataReceived |
| 499 | 456 | 2 | 3 | 5 | 3072 | NULL | multimediaDataReceived |
| 498 | 456 | 2 | 2 | 5 | 2048 | NULL | multimediaDataReceived |
| 497 | 456 | 2 | 1 | 5 | 1024 | NULL | multimediaDataReceived |

**Key Indicators:**
- ✅ ONLY the last row (package 5/5) has `video_path` populated
- ✅ `package_no = total_packages` for the row with video
- ✅ `event_type = "multimediaUploadComplete"` for last packet
- ✅ Intermediate packets have `event_type = "multimediaDataReceived"`

**❌ INCORRECT (Fix Not Working):**
| id | media_id | media_type | package_no | total_packages | total_bytes | video_path | event_type |
|----|----------|------------|------------|----------------|-------------|------------|------------|
| 501 | 456 | 2 | 5 | 5 | 5120 | **NULL** | multimediaDataReceived |
| 500 | 456 | 2 | 4 | 5 | 4096 | **NULL** | multimediaDataReceived |
| ... | ... | ... | ... | ... | ... | **NULL** | ... |

**Problem:**
- ❌ ALL rows have `video_path = NULL` (no file saved)
- ❌ Last packet does NOT have "multimediaUploadComplete" event
- ❌ File was never saved to storage

---

### 4. File System Check

**✅ CORRECT (Fix Working):**

```bash
# Linux/Mac
ls -lh /opt/traccar/media/123456789012345/$(date +%Y-%m-%d)/

# Output:
# -rw-r--r-- 1 traccar traccar  522 Oct 22 12:34 image_123.jpg
# -rw-r--r-- 1 traccar traccar 5.0K Oct 22 12:35 video_456.wmv

# Windows
dir "C:\Program Files\Traccar\media\123456789012345\2025-10-22\"

# Output:
# 10/22/2025  12:34 PM               522 image_123.jpg
# 10/22/2025  12:35 PM             5,120 video_456.wmv
```

**Key Indicators:**
- ✅ `video_456.wmv` file exists
- ✅ File size matches total bytes received (5120 bytes)
- ✅ File is readable and not empty

**❌ INCORRECT (Fix Not Working):**

```bash
# Linux/Mac
ls -lh /opt/traccar/media/123456789012345/$(date +%Y-%m-%d)/

# Output:
# -rw-r--r-- 1 traccar traccar  522 Oct 22 12:34 image_123.jpg
# (NO video_456.wmv file!)
```

**Problem:**
- ❌ Video file does NOT exist in media directory
- ❌ `writeMediaFile()` was never called
- ❌ Bug preventing file save

---

## Diagnosis Flow Chart

```
┌─────────────────────────────────────┐
│   Run test_event_multimedia.py     │
└────────────────┬────────────────────┘
                 │
                 ▼
    ┌────────────────────────────┐
    │  Does test script show     │
    │  "✓ Multi-packet video     │
    │   storage fix is WORKING!" │
    └───────┬──────────────┬─────┘
           YES            NO
            │              │
            ▼              ▼
    ┌──────────────┐  ┌──────────────────────┐
    │ Check Traccar│  │ Check server is      │
    │ server logs  │  │ running and          │
    │              │  │ reachable            │
    └──────┬───────┘  └──────────────────────┘
           │
           ▼
    ┌────────────────────────────┐
    │ Do logs show               │
    │ "LAST PACKET RECEIVED -    │
    │  SAVING MULTIMEDIA FILE"?  │
    └───────┬──────────────┬─────┘
           YES            NO
            │              │
            ▼              ▼
    ┌──────────────┐  ┌──────────────────────┐
    │ Check        │  │ BUG DETECTED!        │
    │ database for │  │ Check line 1438:     │
    │ video path   │  │ Should be:           │
    │              │  │ if (packageNo ==     │
    └──────┬───────┘  │     totalPackages)   │
           │          └──────────────────────┘
           ▼
    ┌────────────────────────────┐
    │ Is video path populated    │
    │ in last packet row?        │
    └───────┬──────────────┬─────┘
           YES            NO
            │              │
            ▼              ▼
    ┌──────────────┐  ┌──────────────────────┐
    │ Check file   │  │ File save failed -   │
    │ system for   │  │ check permissions    │
    │ video file   │  │ and disk space       │
    └──────┬───────┘  └──────────────────────┘
           │
           ▼
    ┌────────────────────────────┐
    │ Does video file exist      │
    │ and match expected size?   │
    └───────┬──────────────┬─────┘
           YES            NO
            │              │
            ▼              ▼
    ┌──────────────┐  ┌──────────────────────┐
    │   SUCCESS!   │  │ File corruption or   │
    │   Fix is     │  │ storage issue        │
    │   working!   │  └──────────────────────┘
    └──────────────┘
```

---

## Common Issues and Solutions

### Issue 1: "No upload response" in test output

**Symptom:** Test script shows warning but completes

**Check:**
1. Traccar server logs for multimedia messages
2. Server may not send response (protocol variant)
3. If logs show "LAST PACKET RECEIVED", it's working

**Solution:** Check logs, not just test output

---

### Issue 2: Video file NOT saved, logs show all packets received

**Symptom:**
```
DEBUG: MULTIMEDIA PACKET PROCESSED - Package 5/5, Size: 1024 bytes
(NO "LAST PACKET RECEIVED - SAVING MULTIMEDIA FILE")
```

**Root Cause:** Bug still present in code

**Verification:**
```bash
grep -n "if (buf.readableBytes() == totalPackages)" DC600ProtocolDecoder.java
```

If this returns a match → **BUG STILL PRESENT**

**Solution:**
```bash
# Should be:
grep -n "if (packageNo == totalPackages)" DC600ProtocolDecoder.java
```

Should return line 1438

**Fix:** Re-apply the fix from DC600_VIDEO_STORAGE_FIX_FINAL.md

---

### Issue 3: Image works but video doesn't

**Symptom:**
- Single-packet image saved successfully
- Multi-packet video NOT saved

**Root Cause:** Classic symptom of the original bug

**Explanation:**
- Image (1 packet): `packageNo(1) == totalPackages(1)` → Works
- Video (5 packets): `buf.readableBytes()` never equals `5` → Fails

**Solution:** Apply the fix

---

### Issue 4: Connection refused

**Symptom:** Test can't connect to server

**Check:**
1. Traccar server is running
2. DC600 protocol enabled in traccar.xml:
   ```xml
   <entry key='dc600.port'>5049</entry>
   ```
3. Port 5049 is open (firewall)

**Solution:**
```bash
# Check if port is listening
netstat -an | grep 5049

# Test connection
telnet localhost 5049
```

---

## Summary Checklist

Use this checklist to verify the fix:

- [ ] Test script completes successfully
- [ ] Console shows "✓ Multi-packet video storage fix is WORKING!"
- [ ] Traccar logs show "LAST PACKET RECEIVED - SAVING MULTIMEDIA FILE"
- [ ] Log message appears ONLY on last packet (5/5)
- [ ] Database has video path in last packet row ONLY
- [ ] Video file exists in media directory
- [ ] File size matches expected size (5120 bytes for default test)
- [ ] Line 1438 in DC600ProtocolDecoder.java has `if (packageNo == totalPackages)`

**If ALL items checked → Fix is working correctly! ✅**

---

**Last Updated:** 2025-10-22
**Related:** DC600_VIDEO_STORAGE_FIX_FINAL.md
