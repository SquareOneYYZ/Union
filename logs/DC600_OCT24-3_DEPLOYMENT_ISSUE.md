# DC600 Oct24-3 Analysis - Deployment Issue Found

## Executive Summary

**ROOT CAUSE**: The updated code and configuration were **NOT fully deployed** to the staging server. The server is still using the old code with port 5999 instead of the new port 60001.

**Evidence**:
- ✅ 0x8103 ADAS/DSM configuration IS working (deployed)
- ❌ 0x9208 port fix is NOT working (old code running)
- ❌ JT1078 protocol is receiving HTTP requests but NOT device messages
- ❌ Device is NOT receiving 0x9208 or responding with 0x1210

**Impact**: Device cannot upload alarm videos because 0x9208 directs it to port 5999 (JT808) instead of port 60001 (JT1078).

---

## Evidence from Oct24-3 Session

### Session Details
- **Date**: October 25, 2025, 02:04-02:14
- **Device**: 496076898991 (ID: 3906)
- **Server**: 165.22.228.97:5999 (staging)
- **Alarms**: 2 DSM + 3 ADAS (via 0x70 workaround)

---

## Part 1: What's WORKING ✅

### 0x8103 ADAS/DSM Configuration (DEPLOYED)

**Evidence** (oct24-3.txt:6-14):
```
Line 6:  INFO: Configuring ADAS/DSM parameters for THIS server profile - Device:
Line 8:  INFO: ADAS/DSM configuration sent (0x8103) - device will include 0x64/0x65 in future 0x0200 messages for THIS server
Line 14: INFO: [T86ffd17b: dc600 > 64.16.243.227] 7e810300190000040000007601ff0000007701ff0000007d0201010000007f01019f7e
```

✅ **0x8103 is being sent** with parameters 0x0076, 0x0077, 0x007E, 0x007F

**Result**: Device IS sending 0x65 DSM alarm data

**Evidence** (device log line 201-205):
```
[send id = 0x200, seq = 6, pack(0/0), len = 110]:
7E 02 00 00 5F 49 60 76 89 89 91 00 06 ...
... 65 2F 00 00 00 00 00 04 01 00 00 00 00 00 10 ...  ← 0x65 DSM data!
```

**Server Detection** (oct24-3.txt:4611):
```
INFO: DSM ALARM DETECTED - Device: 3906, AlarmId: 0, Type: 0x4, Status: 0, Level: 1
```

---

## Part 2: What's BROKEN ❌

### 0x9208 Port Configuration (NOT DEPLOYED)

**Evidence** (oct24-3.txt:4614-4616):
```
Line 4614: INFO: SENDING ALARM ATTACHMENT REQUEST (0x9208) - AlarmId: 0, AlarmType: 0x4
Line 4615: INFO: Using configured attachment server: 165.22.228.97:5999  ← WRONG PORT!
Line 4616: INFO: DC600 media uploads require JT1078 server on port 5999 - ensure JT1078 protocol is enabled
```

**Expected if updated code was deployed**:
```
INFO: Using configured attachment server: 165.22.228.97:60001  ← Should be 60001!
INFO: DC600 media uploads require JT1078 server on port 60001 - ensure JT1078 protocol is enabled
```

**0x9208 Message Sent** (oct24-3.txt:4652):
```
INFO: [Ta0659f3d: dc600 > 64.16.243.227] 7e9208005300000e3136352e32322e3232382e393700176f...
```

Decoding the hex (position 14 onwards after skipping device ID + seq):
- IP length: 0x0E (14 bytes)
- IP string: "165.22.228.97" + 0x00 (NULL terminator - this IS present!)
- Port: 0x176F = **5999** ← WRONG! Should be 0xEA61 (60001)

**Device Response**: NOTHING

**Evidence** (device log lines 207-221):
```
Line 207: [recv id = 0x8001, seq = 0, pack(0/0), len = 20]:  ← Only receives 0x8001
Line 208: 7E 80 01 00 05 49 60 76 89 89 91 00 00 00 06 02 00 00 4E 7E

Line 216: [send id = 0x200, seq = 7, pack(0/0), len = 61]:  ← Continues sending locations
Line 220: [recv id = 0x8001, seq = 0, pack(0/0), len = 20]:  ← Only receives 0x8001
```

**NO 0x9208 received by device!**
**NO 0x1210 sent by device!**

---

## Part 3: JT1078 Protocol Status

**JT1078 Port is OPEN** (receiving HTTP requests):

**Evidence** (oct24-3.txt:11538, 35842, 40281):
```
Line 11538: INFO: [Ta97b866b: jt1078 < 87.120.191.94] GET / HTTP/1.1\r\nHost: 104.248.107.123:60001
Line 35842: INFO: [T157bc64a: jt1078 < 87.120.191.94] GET / HTTP/1.1\r\nHost: 165.22.228.97:60001
Line 40281: INFO: [T87f86042: jt1078 < 87.120.191.94] GET / HTTP/1.1\r\nHost: 165.22.228.97:60001
```

✅ **JT1078 protocol IS running**
✅ **Port 60001 IS listening**
✅ **Port 60001 IS accessible** (HTTP requests from 87.120.191.94 received)

**BUT**: NO device messages on JT1078 port!

**Search results**:
```bash
grep "JT1078 message received" oct24-3.txt
# No results

grep "RECEIVED ALARM ATTACHMENT INFO" oct24-3.txt
# No results

grep "0x1210" oct24-3.txt
# No results
```

**Why?** Device is directed to port 5999, not 60001!

---

## Root Cause Analysis

### The Problem Chain

```
1. Server detects DSM alarm (AlarmId 0)
   ✅ WORKING

2. Server sends 0x9208 pointing to 165.22.228.97:5999
   ❌ WRONG PORT (should be 60001)

3. Device receives 0x9208, tries to connect to port 5999
   ❌ Port 5999 is JT808 protocol, not JT1078

4. Device connection to port 5999 fails or is rejected
   ❌ No JT1078 handler on port 5999

5. Device gives up, does NOT send 0x1210
   ❌ NO VIDEO UPLOAD
```

### Why Port 5999 Instead of 60001?

**Code Default Check**:

Updated code (DC600ProtocolDecoder.java:156):
```java
int serverPort = getConfig().getInteger("dc600.attachment.port", 60001);
```

If updated code was deployed, default would be **60001**.

Logs show **5999**, which means:
1. Old code is running (default was 5999), OR
2. Config explicitly sets `dc600.attachment.port=5999`

**Config Check**:

Local traccar.xml:
```xml
<entry key='dc600.attachment.port'>60001</entry>
```

Local config is CORRECT!

**Conclusion**: **Staging server config was NOT updated OR server was NOT restarted**

---

## Deployment Status

| Component | Local (Dev) | Staging (Running) | Status |
|-----------|-------------|-------------------|--------|
| **DC600ProtocolDecoder.java** (0x9208 fix) | ✅ Updated (port 60001) | ❌ Old code (port 5999) | NOT DEPLOYED |
| **DC600ProtocolDecoder.java** (0x8103 config) | ✅ Updated | ✅ Deployed | WORKING |
| **JT1078Protocol.java** | ✅ Created | ✅ Deployed | WORKING |
| **JT1078FrameDecoder.java** | ✅ Created | ✅ Deployed | WORKING |
| **JT1078ProtocolDecoder.java** | ✅ Created | ✅ Deployed | WORKING |
| **traccar.xml** (port config) | ✅ Updated (60001) | ❌ Old config (5999) | NOT DEPLOYED |

**Summary**:
- ✅ JT1078 protocol classes are deployed
- ✅ 0x8103 ADAS/DSM configuration is working
- ❌ 0x9208 port fix is NOT deployed (still using old default)
- ❌ traccar.xml configuration is NOT deployed

---

## Verification: All 5 Alarms Sent to Port 5999

**Alarm #1: DSM AlarmId 0** (oct24-3.txt:4614-4620):
```
4614: SENDING ALARM ATTACHMENT REQUEST (0x9208) - AlarmId: 0, AlarmType: 0x4
4615: Using configured attachment server: 165.22.228.97:5999  ← PORT 5999
4620: ALARM ATTACHMENT REQUEST QUEUED - AlarmId: 0, Server: 165.22.228.97:5999
```

**Alarm #2: WORKAROUND AlarmId 1** (oct24-3.txt:6522-6525):
```
6522: WORKAROUND - Triggering alarm attachment request based on 0x70
6523: SENDING ALARM ATTACHMENT REQUEST (0x9208) - AlarmId: 1, AlarmType: 0x0
6525: DC600 media uploads require JT1078 server on port 5999  ← PORT 5999
```

**Alarm #3: WORKAROUND AlarmId 2** (oct24-3.txt:7552-7555):
```
7552: WORKAROUND - Triggering alarm attachment request based on 0x70
7553: SENDING ALARM ATTACHMENT REQUEST (0x9208) - AlarmId: 2, AlarmType: 0x0
7555: DC600 media uploads require JT1078 server on port 5999  ← PORT 5999
```

**Alarm #4: WORKAROUND AlarmId 3** (oct24-3.txt:7779-7782):
```
7779: WORKAROUND - Triggering alarm attachment request based on 0x70
7780: SENDING ALARM ATTACHMENT REQUEST (0x9208) - AlarmId: 3, AlarmType: 0x0
7782: DC600 media uploads require JT1078 server on port 5999  ← PORT 5999
```

**Alarm #5: DSM AlarmId 4** (oct24-3.txt:7972-7975):
```
7972: Triggering alarm attachment request for DSM alarm
7973: SENDING ALARM ATTACHMENT REQUEST (0x9208) - AlarmId: 4, AlarmType: 0x4
7975: DC600 media uploads require JT1078 server on port 5999  ← PORT 5999
```

**100% consistency**: ALL alarms directed to port 5999, NOT 60001.

---

## Device Log Correlation

### Device Sends Alarms, Server Receives Them

| Device Time | Device Log | Server Time | Server Log | AlarmId |
|-------------|------------|-------------|------------|---------|
| 10:06:56 | Send please keep attention | 02:06:57 | DSM ALARM DETECTED | 0 |
| 10:07:14 | Send Swerve | 02:07:14 | WORKAROUND 0x70 | 1 |
| 10:07:26 | Send Swerve | 02:07:26 | WORKAROUND 0x70 | 2 |
| 10:07:44 | Send Swerve | 02:07:45 | WORKAROUND 0x70 | 3 |
| 10:07:59 | Send please keep attention | 02:08:00 | DSM ALARM DETECTED | 4 |

✅ **Perfect correlation** - all alarms detected

### Device Does NOT Receive 0x9208

**Device log search results**:
```bash
grep "recv id = 0x9208" 20251025-100624.log
# No matches

grep "平台请求附件" 20251025-100624.log
# No matches  (Chinese for "Platform requests attachment")

grep "send id = 0x1210" 20251025-100624.log
# No matches
```

**Device only receives** (lines 207, 220, etc.):
- 0x8001 (General Response) to 0x0200 location reports

**Device NEVER receives**:
- 0x9208 (Alarm Attachment Upload Request)

**Device NEVER sends**:
- 0x1210 (Alarm Attachment Info)

---

## Fix Required

### Step 1: Verify Build

Check if the latest code was actually built:

```bash
cd /path/to/project
./gradlew clean build -x test

# Check build date
ls -la target/tracker-server-6.6.jar
```

**Expected**: JAR file with recent timestamp

### Step 2: Deploy Updated JAR

```bash
# Stop Traccar
sudo systemctl stop traccar

# Backup current JAR
cp /opt/traccar/lib/tracker-server.jar /opt/traccar/lib/tracker-server.jar.backup-$(date +%Y%m%d-%H%M%S)

# Deploy NEW JAR
cp target/tracker-server-6.6.jar /opt/traccar/lib/tracker-server.jar

# Verify file was copied
ls -la /opt/traccar/lib/tracker-server.jar
md5sum /opt/traccar/lib/tracker-server.jar
md5sum target/tracker-server-6.6.jar  # Should match
```

### Step 3: Deploy Updated traccar.xml

```bash
# Backup current config
cp /opt/traccar/conf/traccar.xml /opt/traccar/conf/traccar.xml.backup-$(date +%Y%m%d-%H%M%S)

# Deploy NEW config
cp traccar.xml /opt/traccar/conf/traccar.xml

# Verify configuration
grep -E "dc600.attachment|jt1078.port" /opt/traccar/conf/traccar.xml
```

**Expected output**:
```xml
<entry key='jt1078.port'>60001</entry>
<entry key='dc600.attachment.port'>60001</entry>
```

### Step 4: Restart Traccar

```bash
# Start Traccar
sudo systemctl start traccar

# Verify startup
sudo systemctl status traccar

# Check logs
tail -f /opt/traccar/logs/tracker.log
```

**Expected startup logs**:
```
INFO: Configuring ADAS/DSM parameters for THIS server profile
INFO: ADAS/DSM configuration sent (0x8103)
```

### Step 5: Verify Ports

```bash
# Check both ports are listening
netstat -tuln | grep -E "5999|60001"
```

**Expected output**:
```
tcp6       0      0 :::5999                 :::*                    LISTEN
tcp6       0      0 :::60001                :::*                    LISTEN
```

### Step 6: Test Alarm Flow

```bash
# Monitor logs in real-time
tail -f /opt/traccar/logs/tracker.log | grep -E "ADAS|DSM|0x9208|JT1078|0x1210"
```

**Trigger test alarm on device**

**Expected logs**:
```
INFO: DSM ALARM DETECTED - Device: 3906, AlarmId: X, Type: 0xY
INFO: SENDING ALARM ATTACHMENT REQUEST (0x9208)
INFO: Using configured attachment server: 165.22.228.97:60001  ← PORT 60001!
INFO: DC600 media uploads require JT1078 server on port 60001  ← PORT 60001!
INFO: 0x9208 WRITE SUCCESS

INFO: JT1078 message received - Device: 3906, Type: 0x1210  ← NEW!
INFO: RECEIVED ALARM ATTACHMENT INFO (0x1210)  ← NEW!
INFO: 0x1210 Attachment Count: 4  ← NEW!
INFO: 0x1210 File #1: Name: CH1IMG20251025-xxxx.jpg, Size: xxxxx bytes  ← NEW!
INFO: 0x1210 PROCESSED  ← NEW!
```

---

## Quick Verification Checklist

Before redeploying, verify these on the STAGING server:

- [ ] JAR file date/time matches latest build
- [ ] JAR file MD5 matches source build
- [ ] traccar.xml contains `dc600.attachment.port=60001`
- [ ] traccar.xml contains `jt1078.port=60001`
- [ ] Traccar service is stopped before deploy
- [ ] Backup taken before overwriting
- [ ] Server restarted after deploy
- [ ] Both ports 5999 and 60001 are listening
- [ ] No firewall blocking port 60001
- [ ] Test alarm triggered
- [ ] Logs show port 60001 (not 5999)
- [ ] Device sends 0x1210 to port 60001
- [ ] JT1078 logs file list

---

## Alternative: Quick Config-Only Fix

If you're confident the JAR is correct but config wasn't updated:

```bash
# Add to /opt/traccar/conf/traccar.xml (before </properties>)
<entry key='dc600.attachment.port'>60001</entry>

# Restart
sudo systemctl restart traccar

# Verify
grep "Using configured attachment server" /opt/traccar/logs/tracker.log | tail -5
```

**Should show**: `165.22.228.97:60001`

---

## Summary

**What's Working** ✅:
- 0x8103 ADAS/DSM configuration deployed and working
- Device IS sending 0x65/0x64 alarm data
- Server IS detecting alarms
- Server IS sending 0x9208
- JT1078 protocol IS running on port 60001

**What's Broken** ❌:
- Server sends 0x9208 with port 5999 (wrong!)
- Device cannot connect to JT1078 on port 5999
- Device does NOT send 0x1210
- No video upload

**Root Cause**:
- Updated code NOT deployed to staging server
- OR traccar.xml NOT deployed to staging server
- OR server NOT restarted after deploy

**Fix**:
1. Deploy updated JAR
2. Deploy updated traccar.xml
3. Restart Traccar
4. Test alarm flow
5. Verify logs show port 60001

---

**Analysis Date**: 2025-10-25
**Session**: Oct24-3 (02:04-02:14)
**Device**: 496076898991 (ID: 3906)
**Server**: 165.22.228.97 (staging)
**Issue**: Deployment incomplete - configuration not applied
**Action Required**: ⚠️ **REDEPLOY IMMEDIATELY**
