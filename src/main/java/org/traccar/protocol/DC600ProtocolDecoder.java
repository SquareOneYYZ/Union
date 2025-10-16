/*
 * Copyright 2015 - 2024 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocolDecoder;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.*;
import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;
import org.traccar.model.WifiAccessPoint;
import org.traccar.session.DeviceSession;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public class DC600ProtocolDecoder extends BaseProtocolDecoder {
    private static final Logger LOGGER = LoggerFactory.getLogger(DC600ProtocolDecoder.class);

    public DC600ProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    public static final int MSG_TERMINAL_GENERAL_RESPONSE = 0x0001;
    public static final int MSG_GENERAL_RESPONSE = 0x8001;
    public static final int MSG_GENERAL_RESPONSE_2 = 0x4401;
    public static final int MSG_HEARTBEAT = 0x0002;
    public static final int MSG_HEARTBEAT_2 = 0x0506;
    public static final int MSG_TERMINAL_REGISTER = 0x0100;
    public static final int MSG_TERMINAL_REGISTER_RESPONSE = 0x8100;
    public static final int MSG_TERMINAL_CONTROL = 0x8105;
    public static final int MSG_TERMINAL_AUTH = 0x0102;
    public static final int MSG_LOCATION_REPORT = 0x0200;
    public static final int MSG_LOCATION_BATCH_2 = 0x0210;
    public static final int MSG_ACCELERATION = 0x2070;
    public static final int MSG_LOCATION_REPORT_2 = 0x5501;
    public static final int MSG_LOCATION_REPORT_BLIND = 0x5502;
    public static final int MSG_LOCATION_BATCH = 0x0704;
    public static final int MSG_OIL_CONTROL = 0XA006;
    public static final int MSG_TIME_SYNC_REQUEST = 0x0109;
    public static final int MSG_TIME_SYNC_RESPONSE = 0x8109;
    public static final int MSG_PHOTO = 0x8888;
    public static final int MSG_TRANSPARENT = 0x0900;
    public static final int MSG_PARAMETER_SETTING = 0x0310;
    public static final int MSG_SEND_TEXT_MESSAGE = 0x8300;
    public static final int MSG_REPORT_TEXT_MESSAGE = 0x6006;
    // new added
    public static final int MSG_VIDEO_ATTRIBUTES_QUERY = 0x9003;
    public static final int MSG_VIDEO_ATTRIBUTES_UPLOAD = 0x1003;
    public static final int MSG_PASSENGER_TRAFFIC_UPLOAD = 0x1005;
    public static final int MSG_VIDEO_RESOURCE_LIST_QUERY = 0x9205;
    public static final int MSG_VIDEO_RESOURCE_LIST_UPLOAD = 0x1205;
    public static final int MSG_PTZ_ROTATION = 0x9301;
    public static final int MSG_PTZ_FOCUS = 0x9302;
    public static final int MSG_PTZ_APERTURE = 0x9303;
    public static final int MSG_PTZ_WIPER = 0x9304;
    public static final int MSG_PTZ_INFRARED = 0x9305;
    public static final int MSG_PTZ_ZOOM = 0x9306;
    public static final int MSG_VIDEO_LIVE_STREAM_REQUEST = 0x9101;
    public static final int MSG_VIDEO_LIVE_STREAM_RESPONSE = 0x1101;
    public static final int MSG_VIDEO_LIVE_STREAM_CONTROL = 0x9102;
    public static final int MSG_VIDEO_PLAYBACK_REQUEST = 0x9201;
    public static final int MSG_VIDEO_PLAYBACK_RESPONSE = 0x1201;
    public static final int MSG_VIDEO_PLAYBACK_CONTROL = 0x9202;
    public static final int MSG_VIDEO_DOWNLOAD_REQUEST = 0x9203;
    public static final int MSG_VIDEO_DOWNLOAD_RESPONSE = 0x1203;
    public static final int MSG_IMAGE_CAPTURE_REQUEST = 0x9001;
    public static final int MSG_IMAGE_CAPTURE_RESPONSE = 0x1001;
    public static final int MSG_IMAGE_UPLOAD_REQUEST = 0x9002;
    public static final int MSG_IMAGE_UPLOAD_RESPONSE = 0x1002;
    public static final int MSG_AUDIO_LIVE_STREAM_REQUEST = 0x9103;
    public static final int MSG_AUDIO_LIVE_STREAM_RESPONSE = 0x1103;
    public static final int MSG_AUDIO_LIVE_STREAM_CONTROL = 0x9104;
    public static final int MSG_ALARM_ATTACHMENT_UPLOAD = 0x9208;
    public static final int MSG_ALARM_ATTACHMENT_INFO = 0x1210;
    public static final int MSG_FILE_UPLOAD_COMPLETE = 0x1212;
    public static final int MSG_FILE_UPLOAD_COMPLETE_RESPONSE = 0x9212;
    public static final int MSG_PARAMETER_QUERY = 0x8103;
    public static final int MSG_PARAMETER_RESPONSE = 0x0104;
    public static final int MSG_PARAMETER_SETTING_ADAS = 0xF364;
    public static final int MSG_PARAMETER_SETTING_DSM = 0xF365;
    public static final int VIDEO_CHANNEL_ADAS = 64;
    public static final int VIDEO_CHANNEL_DSM = 65;
    public static final int MSG_FILE_DATA_UPLOAD = 0x1211;
    public static final int RESULT_SUCCESS = 0;
    private int delimiter = 0x7e;
    public boolean isAlternative() {
        return delimiter == 0xe7;
    }
    public static ByteBuf formatMessage(int delimiter, int type, ByteBuf id, boolean shortIndex, ByteBuf data) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(delimiter);
        buf.writeShort(type);
        buf.writeShort(data.readableBytes());
        buf.writeBytes(id);
        if (shortIndex) {
            buf.writeByte(1);
        } else {
            buf.writeShort(0);
        }
        buf.writeBytes(data);
        data.release();
        buf.writeByte(Checksum.xor(buf.nioBuffer(1, buf.readableBytes() - 1)));
        buf.writeByte(delimiter);
        return buf;
    }
    private void sendGeneralResponse(
            Channel channel, SocketAddress remoteAddress, ByteBuf id, int type, int index) {
        if (channel != null) {
            ByteBuf response = Unpooled.buffer();
            response.writeShort(index);
            response.writeShort(type);
            response.writeByte(RESULT_SUCCESS);
            channel.writeAndFlush(new NetworkMessage(
                    formatMessage(delimiter, MSG_GENERAL_RESPONSE, id, false, response), remoteAddress));
        }
    }
    private void sendGeneralResponse2(
            Channel channel, SocketAddress remoteAddress, ByteBuf id, int type) {
        if (channel != null) {
            ByteBuf response = Unpooled.buffer();
            response.writeShort(type);
            response.writeByte(RESULT_SUCCESS);
            channel.writeAndFlush(new NetworkMessage(
                    formatMessage(delimiter, MSG_GENERAL_RESPONSE_2, id, true, response), remoteAddress));
        }
    }
    private void decodeAlarm(Position position, String model, long value) {
        if (model != null && Set.of("G-360P", "G-508P").contains(model)) {
            if (BitUtil.check(value, 0) || BitUtil.check(value, 4)) {
                position.addAlarm(Position.ALARM_REMOVING);
            }
            if (BitUtil.check(value, 1)) {
                position.addAlarm(Position.ALARM_TAMPERING);
            }
        } else {
            if (BitUtil.check(value, 0)) {
                position.addAlarm(Position.ALARM_SOS);
            }
            if (BitUtil.check(value, 1)) {
                position.addAlarm(Position.ALARM_OVERSPEED);
            }
            if (BitUtil.check(value, 5)) {
                position.addAlarm(Position.ALARM_GPS_ANTENNA_CUT);
            }
            if (BitUtil.check(value, 4) || BitUtil.check(value, 9)
                    || BitUtil.check(value, 10) || BitUtil.check(value, 11)) {
                position.addAlarm(Position.ALARM_FAULT);
            }
            if (BitUtil.check(value, 7) || BitUtil.check(value, 18)) {
                position.addAlarm(Position.ALARM_LOW_BATTERY);
            }
            if (BitUtil.check(value, 8)) {
                position.addAlarm(Position.ALARM_POWER_OFF);
            }
            if (BitUtil.check(value, 15)) {
                position.addAlarm(Position.ALARM_VIBRATION);
            }
            if (BitUtil.check(value, 16) || BitUtil.check(value, 17)) {
                position.addAlarm(Position.ALARM_TAMPERING);
            }
            if (BitUtil.check(value, 20)) {
                position.addAlarm(Position.ALARM_GEOFENCE);
            }
            if (BitUtil.check(value, 28)) {
                position.addAlarm(Position.ALARM_MOVEMENT);
            }
            if (BitUtil.check(value, 29) || BitUtil.check(value, 30)) {
                position.addAlarm(Position.ALARM_ACCIDENT);
            }
        }
    }
    private int readSignedWord(ByteBuf buf) {
        int value = buf.readUnsignedShort();
        return BitUtil.check(value, 15) ? -BitUtil.to(value, 15) : BitUtil.to(value, 15);
    }
    private Date readDate(ByteBuf buf, TimeZone timeZone) {
        DateBuilder dateBuilder = new DateBuilder(timeZone)
                .setYear(BcdUtil.readInteger(buf, 2))
                .setMonth(BcdUtil.readInteger(buf, 2))
                .setDay(BcdUtil.readInteger(buf, 2))
                .setHour(BcdUtil.readInteger(buf, 2))
                .setMinute(BcdUtil.readInteger(buf, 2))
                .setSecond(BcdUtil.readInteger(buf, 2));
        return dateBuilder.getDate();
    }
    private String decodeId(ByteBuf id, Channel channel, SocketAddress remoteAddress) {
        // For JT/T 1078, device IDs are BCD encoded
        StringBuilder deviceId = new StringBuilder();
        for (int i = 0; i < id.readableBytes(); i++) {
            int b = id.getUnsignedByte(i);
            int high = (b & 0xF0) >> 4;
            int low = b & 0x0F;
            deviceId.append(high).append(low);
        }
        String result = deviceId.toString();
        // Handle partial IMEI case - try to match with known devices
        if (result.length() == 12 && channel != null) {
            // This could be a truncated IMEI, try to find full device ID
            DeviceSession session = getDeviceSession(channel, remoteAddress, result);
            if (session == null) {
                // Try with common IMEI prefixes
                String[] prefixes = {"866", "860", "862", "864", "863"};
                for (String prefix : prefixes) {
                    String fullId = prefix + result;
                    session = getDeviceSession(channel, remoteAddress, fullId);
                    if (session != null) {
                        return fullId;
                    }
                }
            }
        }
        return result;
    }
    private String generateProtocolFileName(int fileType, int channel, int alarmType, String serialNumber,
                                            String alarmNumber, String suffix) {
        return String.format("%02d_%02d_%02d_%s_%s.%s",
                fileType, channel, alarmType, serialNumber, alarmNumber, suffix);
    }
    private Position createMediaPosition(DeviceSession deviceSession, String mediaType, String fileName) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, null);
        position.set(mediaType, fileName);
        return position;
    }
    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {
        ByteBuf buf = (ByteBuf) msg;
        if (buf.readableBytes() < 3) {
            return null;
        }
        byte firstByte = buf.getByte(buf.readerIndex());
        // DC600 protocol starts with 0x7e (hex 7e) or '(' for BASE messages
        // Reject messages that don't match these patterns
        if (firstByte != 0x7e && firstByte != '(') {
            return null;
        }
        // Additional filter: Check if this looks like HTTP/RTSP (text protocols)
        if (buf.readableBytes() >= 5) {
            String potentialText = buf.toString(0, Math.min(buf.readableBytes(), 20), StandardCharsets.US_ASCII);
            if (potentialText.startsWith("OPTIONS")
                    || potentialText.startsWith("GET")
                    || potentialText.startsWith("POST")
                    || potentialText.startsWith("HTTP")) {
                return null; // Filter out HTTP/RTSP traffic
            }
        }
        // Continue with your existing DC600 decoding...
        if (buf.getByte(buf.readerIndex()) == '(') {
            String sentence = buf.toString(StandardCharsets.US_ASCII);
            if (sentence.contains("BASE,2")) {
                DateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
                dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                String response = sentence.replace("TIME", dateFormat.format(new Date()));
                if (channel != null) {
                    channel.writeAndFlush(new NetworkMessage(
                            Unpooled.copiedBuffer(response, StandardCharsets.US_ASCII), remoteAddress));
                }
                return null;
            } else {
                return decodeResult(channel, remoteAddress, sentence);
            }
        }
        // Check if we have enough data for basic header (1 + 2 + 2 = 5 bytes)
        if (buf.readableBytes() < 5) {
            return null; // Not enough data for basic header
        }
        delimiter = buf.readUnsignedByte();
        int type = buf.readUnsignedShort();
        int attribute = buf.readUnsignedShort();
        // Check if we have enough data for ID
        int idLength = isAlternative() ? 7 : 6;
        if (buf.readableBytes() < idLength) {
            return null;
        }
        ByteBuf id = buf.readSlice(idLength);
        int index;
        if (type == MSG_LOCATION_REPORT_2 || type == MSG_LOCATION_REPORT_BLIND) {
            if (buf.readableBytes() < 1) {
                return null;
            }
            index = buf.readUnsignedByte();
        } else {
            if (buf.readableBytes() < 2) {
                return null;
            }
            index = buf.readUnsignedShort();
        }
        String deviceIdString = decodeId(id, channel, remoteAddress);
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, deviceIdString);
        if (deviceSession == null) {
            // Log and return null if no device session found
            System.getLogger(DC600ProtocolDecoder.class.getName()).log(System.Logger.Level.DEBUG,
                    "No device session found for ID: " + deviceIdString);
            return null;
        }
        if (!deviceSession.contains(DeviceSession.KEY_TIMEZONE)) {
            deviceSession.set(DeviceSession.KEY_TIMEZONE, getTimeZone(deviceSession.getDeviceId(), "GMT+8"));
        }
        if (type == MSG_TERMINAL_REGISTER) {
            if (channel != null) {
                ByteBuf response = Unpooled.buffer();
                response.writeShort(index);
                response.writeByte(RESULT_SUCCESS);
                response.writeBytes(decodeId(id, channel, remoteAddress).getBytes(StandardCharsets.US_ASCII));
                channel.writeAndFlush(new NetworkMessage(
                        formatMessage(delimiter, MSG_TERMINAL_REGISTER_RESPONSE, id, false, response), remoteAddress));
            }
        } else if (type == MSG_REPORT_TEXT_MESSAGE) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            buf.readUnsignedByte(); // encoding
            Charset charset = Charset.isSupported("GBK") ? Charset.forName("GBK") : StandardCharsets.US_ASCII;
            position.set(Position.KEY_RESULT, buf.readCharSequence(buf.readableBytes() - 2, charset).toString());
            return position;
        } else if (type == MSG_TERMINAL_AUTH || type == MSG_HEARTBEAT || type == MSG_HEARTBEAT_2 || type == MSG_PHOTO) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
        } else if (type == MSG_LOCATION_REPORT) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            LOGGER.debug("Processing MSG_LOCATION_REPORT (0x0200) - buffer size: {}", buf.readableBytes());
            Position position = decodeLocation(deviceSession, buf, channel, remoteAddress, id);
            if (position != null && position.getString(Position.KEY_ALARM) != null) {
                LOGGER.debug("Position created with alarms: {}", position.getString(Position.KEY_ALARM));
            }
            return position;
        } else if (type == MSG_LOCATION_REPORT_2 || type == MSG_LOCATION_REPORT_BLIND) {
            if (BitUtil.check(attribute, 15)) {
                sendGeneralResponse2(channel, remoteAddress, id, type);
            }
            return decodeLocation2(deviceSession, buf, type);
        } else if (type == MSG_LOCATION_BATCH || type == MSG_LOCATION_BATCH_2) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            return decodeLocationBatch(deviceSession, buf, type);
        } else if (type == MSG_TIME_SYNC_REQUEST) {
            if (channel != null) {
                Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                ByteBuf response = Unpooled.buffer();
                response.writeShort(calendar.get(Calendar.YEAR));
                response.writeByte(calendar.get(Calendar.MONTH) + 1);
                response.writeByte(calendar.get(Calendar.DAY_OF_MONTH));
                response.writeByte(calendar.get(Calendar.HOUR_OF_DAY));
                response.writeByte(calendar.get(Calendar.MINUTE));
                response.writeByte(calendar.get(Calendar.SECOND));
                channel.writeAndFlush(new NetworkMessage(
                        formatMessage(delimiter, MSG_TERMINAL_REGISTER_RESPONSE, id, false, response),
                        remoteAddress));
            }
        } else if (type == MSG_ACCELERATION) {
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            List<String> gSensorReadings = new ArrayList<>();
            while (buf.readableBytes() > 6) {
                Date gSensorTime = readDate(buf, deviceSession.get(DeviceSession.KEY_TIMEZONE));
                int x = readSignedWord(buf);
                int y = readSignedWord(buf);
                int z = readSignedWord(buf);
                gSensorReadings.add(String.format("{\"time\":%d,\"x\":%d,\"y\":%d,\"z\":%d}",
                        gSensorTime.getTime(), x, y, z));
            }
            position.set(Position.KEY_G_SENSOR, "[" + String.join(",", gSensorReadings) + "]");
            return position;
        } else if (type == MSG_TRANSPARENT) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            return decodeTransparent(deviceSession, buf);
        } else if (type == MSG_VIDEO_ATTRIBUTES_QUERY) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            // Platform queries terminal video attributes
        } else if (type == MSG_VIDEO_ATTRIBUTES_UPLOAD) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            // Decode video attributes (Table 11)
            position.set("videoAudioEncoding", buf.readUnsignedByte());
            position.set("videoAudioChannels", buf.readUnsignedByte());
            position.set("videoAudioSampleRate", buf.readUnsignedByte());
            position.set("videoAudioSampleBits", buf.readUnsignedByte());
            position.set("videoAudioFrameLength", buf.readUnsignedShort());
            position.set("videoAudioOutputSupported", buf.readUnsignedByte());
            position.set("videoVideoEncoding", buf.readUnsignedByte());
            position.set("videoMaxAudioChannels", buf.readUnsignedByte());
            position.set("videoMaxVideoChannels", buf.readUnsignedByte());
            return position;
        } else if (type == MSG_PASSENGER_TRAFFIC_UPLOAD) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            // Decode passenger traffic data (Table 16)
            position.setTime(readDate(buf, deviceSession.get(DeviceSession.KEY_TIMEZONE))); // Start time
            buf.skipBytes(6); // Skip end time BCD[6]
            position.set("passengersBoarded", buf.readUnsignedShort());
            position.set("passengersDeparted", buf.readUnsignedShort());
            return position;
        } else if (type == MSG_VIDEO_RESOURCE_LIST_QUERY) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            // Platform queries video resource list
        } else if (type == MSG_VIDEO_RESOURCE_LIST_UPLOAD) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            // Terminal responds with video resource list (would need complex decoding)
        } else if (type == MSG_PTZ_ROTATION
                || type == MSG_PTZ_FOCUS
                || type == MSG_PTZ_APERTURE
                || type == MSG_PTZ_WIPER
                || type == MSG_PTZ_INFRARED
                || type == MSG_PTZ_ZOOM) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            // Basic PTZ control acknowledgment
            position.set("ptzControl", type);
            position.set("ptzChannel", buf.readUnsignedByte());
            position.set("ptzCommand", buf.readUnsignedByte());
            return position;
            } else if (type == MSG_IMAGE_CAPTURE_REQUEST) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
        } else if (type == MSG_IMAGE_CAPTURE_RESPONSE) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            position.set("imageCaptureResult", buf.readUnsignedByte());
            return position;
        } else if (type == MSG_IMAGE_UPLOAD_REQUEST) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
        } else if (type == MSG_IMAGE_UPLOAD_RESPONSE) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            int result = buf.readUnsignedByte();
            if (result == 0x00 && buf.readableBytes() > 0) {
                int imageLength = buf.readUnsignedShort();
                ByteBuf imageData = buf.readSlice(imageLength);
                String fileName =  writeMediaFile(deviceSession.getUniqueId(), imageData, "jpg");
                return createMediaPosition(deviceSession, Position.KEY_IMAGE, fileName);
            }
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            position.set("imageUploadResult", result);
            return position;
        } else if (type == MSG_VIDEO_LIVE_STREAM_REQUEST) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
        } else if (type == MSG_VIDEO_LIVE_STREAM_RESPONSE) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            int result = buf.readUnsignedByte();
            position.set("liveStreamResult", buf.readUnsignedByte());
            String serverIp = buf.readCharSequence(16, StandardCharsets.US_ASCII).toString().trim();
            int serverPort = buf.readUnsignedShort();
            position.set("liveStreamServerIp", serverIp);
            position.set("liveStreamServerPort", serverPort);
            if (result == 0x00) {
                LOGGER.info("LIVE STREAM RESPONSE SUCCESS - Device: {}, Server: {}:{}, Result: {}",
                        deviceSession.getDeviceId(), serverIp, serverPort, result);
            } else {
                LOGGER.warn("LIVE STREAM RESPONSE FAILED - Device: {}, Error Code: 0x{}, Server: {}:{}",
                        deviceSession.getDeviceId(), Integer.toHexString(result), serverIp, serverPort);
            }
            return position;
        } else if (type == MSG_VIDEO_LIVE_STREAM_CONTROL) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            position.set("liveStreamControl", buf.readUnsignedByte());
            return position;
        } else if (type == MSG_VIDEO_PLAYBACK_REQUEST) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
        } else if (type == MSG_VIDEO_PLAYBACK_RESPONSE) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            position.set("playbackResult", buf.readUnsignedByte());
            return position;
        } else if (type == MSG_VIDEO_PLAYBACK_CONTROL) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            position.set("playbackControl", buf.readUnsignedByte());
            return position;
        } else if (type == MSG_VIDEO_DOWNLOAD_REQUEST) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
        } else if (type == MSG_VIDEO_DOWNLOAD_RESPONSE) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            int result = buf.readUnsignedByte();
            if (result == 0x00 && buf.readableBytes() > 0) {
                int videoLength = buf.readUnsignedShort();
                ByteBuf videoData = buf.readSlice(videoLength);
                String fileName = writeMediaFile(deviceSession.getUniqueId(), videoData, "mp4");
                return createMediaPosition(deviceSession, Position.KEY_VIDEO, fileName);
            }
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            position.set("videoDownloadResult", result);
            return position;
        } else if (type == MSG_AUDIO_LIVE_STREAM_REQUEST) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
        } else if (type == MSG_AUDIO_LIVE_STREAM_RESPONSE) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            position.set("audioStreamResult", buf.readUnsignedByte());
            return position;
        } else if (type == MSG_AUDIO_LIVE_STREAM_CONTROL) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            position.set("audioStreamControl", buf.readUnsignedByte());
            return position;
        } else if (type == MSG_ALARM_ATTACHMENT_UPLOAD) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
//            return handleVideoUpload(deviceSession, buf);
            return decodeAlarmAttachmentUpload(deviceSession, buf);
        } else if (type == MSG_ALARM_ATTACHMENT_INFO) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            return decodeAlarmAttachmentInfo(deviceSession, buf);
        } else if (type == MSG_FILE_UPLOAD_COMPLETE) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            return decodeFileUploadComplete(deviceSession, buf);
        } else if (type == MSG_PARAMETER_QUERY || type == MSG_PARAMETER_RESPONSE) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            return decodeParameterMessage(deviceSession, buf, type);
        } else if (type == MSG_FILE_DATA_UPLOAD) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            if (buf.readableBytes() > 0) {
                return handleVideoUpload(deviceSession, buf);
            }
//            return decodeFileDataUpload(deviceSession, buf);
        } else if (type == MSG_FILE_UPLOAD_COMPLETE_RESPONSE) {
            sendGeneralResponse(channel, remoteAddress, id, type, index);
            return decodeFileUploadCompleteResponse(deviceSession, buf);
        }
        return null;
    }
    private Position decodeResult(Channel channel, SocketAddress remoteAddress, String sentence) {
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress);
        if (deviceSession != null) {
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            position.set(Position.KEY_RESULT, sentence);
            return position;
        }
        return null;
    }
    private void decodeExtension(Position position, ByteBuf buf, int endIndex) {
        while (buf.readerIndex() < endIndex) {
            int type = buf.readUnsignedByte();
            int length = buf.readUnsignedByte();
            switch (type) {
                case 0x01 -> position.set(Position.KEY_ODOMETER, buf.readUnsignedInt() * 100L);
                case 0x02 -> position.set(Position.KEY_FUEL_LEVEL, buf.readUnsignedShort() * 0.1);
                case 0x03 -> position.set(Position.KEY_OBD_SPEED, buf.readUnsignedShort() * 0.1);
                case 0x56 -> {
                    buf.readUnsignedByte(); // power level
                    position.set(Position.KEY_BATTERY_LEVEL, buf.readUnsignedByte());
                }
                case 0x61 -> position.set(Position.KEY_POWER, buf.readUnsignedShort() * 0.01);
                case 0x69 -> position.set(Position.KEY_BATTERY, buf.readUnsignedShort() * 0.01);
                case 0x80 -> position.set(Position.KEY_OBD_SPEED, buf.readUnsignedByte());
                case 0x81 -> position.set(Position.KEY_RPM, buf.readUnsignedShort());
                case 0x82 -> position.set(Position.KEY_POWER, buf.readUnsignedShort() * 0.1);
                case 0x83 -> position.set(Position.KEY_ENGINE_LOAD, buf.readUnsignedByte());
                case 0x84 -> position.set(Position.KEY_COOLANT_TEMP, buf.readUnsignedByte() - 40);
                case 0x85 -> position.set(Position.KEY_FUEL_CONSUMPTION, buf.readUnsignedShort());
                case 0x86 -> position.set("intakeTemp", buf.readUnsignedByte() - 40);
                case 0x87 -> position.set("intakeFlow", buf.readUnsignedShort());
                case 0x88 -> position.set("intakePressure", buf.readUnsignedByte());
                case 0x89 -> position.set(Position.KEY_THROTTLE, buf.readUnsignedByte());
                case 0x8B -> {
                    position.set(Position.KEY_VIN, buf.readCharSequence(17, StandardCharsets.US_ASCII).toString());
                }
                case 0x8C -> position.set(Position.KEY_OBD_ODOMETER, buf.readUnsignedInt() * 100L);
                case 0x8D -> position.set(Position.KEY_ODOMETER_TRIP, buf.readUnsignedShort() * 1000L);
                case 0x8E -> position.set(Position.KEY_FUEL_LEVEL, buf.readUnsignedByte());
                case 0xA0 -> {
                    String codes = buf.readCharSequence(length, StandardCharsets.US_ASCII).toString();
                    position.set(Position.KEY_DTCS, codes.replace(',', ' '));
                }
                case 0xCC -> {
                    position.set(Position.KEY_ICCID, buf.readCharSequence(20, StandardCharsets.US_ASCII).toString());
                }
                default -> buf.skipBytes(length);
            }
        }
    }
    private void decodeCoordinates(Position position, DeviceSession deviceSession, ByteBuf buf) {
        int status = buf.readInt();
        String model = getDeviceModel(deviceSession);
        position.set(Position.KEY_IGNITION, BitUtil.check(status, 0));
        if ("G1C Pro".equals(model)) {
            position.set(Position.KEY_MOTION, BitUtil.check(status, 4));
        }
        position.set(Position.KEY_BLOCKED, BitUtil.check(status, 10));
        position.set(Position.KEY_CHARGE, BitUtil.check(status, 26));
        position.setValid(BitUtil.check(status, 1));
        double lat = buf.readUnsignedInt() * 0.000001;
        double lon = buf.readUnsignedInt() * 0.000001;
        if (BitUtil.check(status, 2)) {
            position.setLatitude(-lat);
        } else {
            position.setLatitude(lat);
        }
        if (BitUtil.check(status, 3)) {
            position.setLongitude(-lon);
        } else {
            position.setLongitude(lon);
        }
    }
    private double decodeCustomDouble(ByteBuf buf) {
        int b1 = buf.readByte();
        int b2 = buf.readUnsignedByte();
        int sign = b1 != 0 ? b1 / Math.abs(b1) : 1;
        return sign * (Math.abs(b1) + b2 / 255.0);
    }
    private Position decodeLocation(DeviceSession deviceSession, ByteBuf buf, Channel channel,
                                    SocketAddress remoteAddress, ByteBuf id) {
        if (buf.readableBytes() < 20) {
            return null;
        }
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        LOGGER.debug("Starting decodeLocation - readable bytes: {}", buf.readableBytes());
        String model = getDeviceModel(deviceSession);
        long alarmValue = buf.readUnsignedInt();
        decodeAlarm(position, model, alarmValue);
        LOGGER.debug("After alarm read - readable bytes: {}, alarmValue: {}", buf.readableBytes(), alarmValue);
        decodeCoordinates(position, deviceSession, buf);
        position.setAltitude(buf.readShort());
        position.setSpeed(UnitsConverter.knotsFromKph(buf.readUnsignedShort() * 0.1));
        position.setCourse(buf.readUnsignedShort());
        position.setTime(readDate(buf, deviceSession.get(DeviceSession.KEY_TIMEZONE)));
        LOGGER.debug("After basic location - readable bytes: {}", buf.readableBytes());
        Network network = new Network();
        LOGGER.debug("Before extension parsing - readerIndex: {}, readableBytes: {}",
                buf.readerIndex(), buf.readableBytes());
        while (buf.readableBytes() > 2) {
            int subtype = buf.readUnsignedByte();
            int length = buf.readUnsignedByte();
            LOGGER.debug("Processing extension in main switch - ID: 0x{}, length: {}",
                    Integer.toHexString(subtype), length);
            if (buf.readableBytes() < length) {
                break;
            }
            int endIndex = buf.readerIndex() + length;
            String stringValue;
            if (subtype == 0x64) {
                LOGGER.debug("ADAS ALARM 0x64 WILL BE PROCESSED IN MAIN SWITCH!");
            }
            try {
            switch (subtype) {
                case 0x01:
                    position.set(Position.KEY_ODOMETER, buf.readUnsignedInt() * 100);
                    break;
                case 0x02:
                    int fuel = buf.readUnsignedShort();
                    if (BitUtil.check(fuel, 15)) {
                        position.set(Position.KEY_FUEL_LEVEL, BitUtil.to(fuel, 15));
                    } else {
                        position.set(Position.KEY_FUEL_LEVEL, fuel / 10.0);
                    }
                    break;
                case 0x25:
                    position.set(Position.KEY_INPUT, buf.readUnsignedInt());
                    break;
                case 0x2B:
                case 0xA7:
                    position.set(Position.PREFIX_ADC + 1, buf.readUnsignedShort() / 100.0);
                    position.set(Position.PREFIX_ADC + 2, buf.readUnsignedShort() / 100.0);
                    break;
                case 0x30:
                    position.set(Position.KEY_RSSI, buf.readUnsignedByte());
                    position.set("gsmSignal", buf.getUnsignedByte(buf.readerIndex() - 1));
                    break;
                case 0x31:
                    int satellites = buf.readUnsignedByte();
                    position.set(Position.KEY_SATELLITES, satellites);
                    position.set("satellitesCount", satellites);
                    break;
                case 0x33:
                    if (length == 1) {
                        position.set("mode", buf.readUnsignedByte());
                    } else {
                        stringValue = buf.readCharSequence(length, StandardCharsets.US_ASCII).toString();
                        if (stringValue.startsWith("*M00")) {
                            String lockStatus = stringValue.substring(8, 8 + 7);
                            position.set(Position.KEY_BATTERY, Integer.parseInt(lockStatus.substring(2, 5)) * 0.01);
                        }
                    }
                    break;
                case 0x51:
                    if (length == 2 || length == 16) {
                        for (int i = 1; i <= length / 2; i++) {
                            int value = buf.readUnsignedShort();
                            if (value != 0xffff) {
                                if (BitUtil.check(value, 15)) {
                                    position.set(Position.PREFIX_TEMP + i, -BitUtil.to(value, 15) / 10.0);
                                } else {
                                    position.set(Position.PREFIX_TEMP + i, value / 10.0);
                                }
                            }
                        }
                    }
                    break;
                case 0x56:
                    position.set(Position.KEY_BATTERY_LEVEL, buf.readUnsignedByte() * 10);
                    buf.readUnsignedByte(); // reserved
                    break;
                case 0x57:
                    int alarm = buf.readUnsignedShort();
                    position.addAlarm(BitUtil.check(alarm, 8) ? Position.ALARM_ACCELERATION : null);
                    position.addAlarm(BitUtil.check(alarm, 9) ? Position.ALARM_BRAKING : null);
                    position.addAlarm(BitUtil.check(alarm, 10) ? Position.ALARM_CORNERING : null);
                    buf.readUnsignedShort(); // external switch state
                    buf.skipBytes(4); // reserved
                    break;
                case 0x60:
                    int event = buf.readUnsignedShort();
                    position.set(Position.KEY_EVENT, event);
                    if (event >= 0x0061 && event <= 0x0066) {
                        buf.skipBytes(6); // lock id
                        stringValue = buf.readCharSequence(8, StandardCharsets.US_ASCII).toString();
                        position.set(Position.KEY_DRIVER_UNIQUE_ID, stringValue);
                    }
                    break;
                case 0x63:
                    for (int i = 1; i <= length / 11; i++) {
                        position.set("lock" + i + "Id", ByteBufUtil.hexDump(buf.readSlice(6)));
                        position.set("lock" + i + "Battery", buf.readUnsignedShort() * 0.001);
                        position.set("lock" + i + "Seal", buf.readUnsignedByte() == 0x31);
                        buf.readUnsignedByte(); // physical state
                        buf.readUnsignedByte(); // rssi
                    }
                    break;
                case 0x64: // ADAS Alarm Information (Table 4-15)
                    LOGGER.debug("ENTERED ADAS ALARM CASE 0x64!");
                    if (length >= 40) {
                        position.set("adasAlarmId", buf.readUnsignedInt());
                        int adasFlagStatus = buf.readUnsignedByte();
                        position.set("adasFlagStatus", adasFlagStatus);
                        int adasAlarmType = buf.readUnsignedByte();
                        position.set("adasAlarmType", adasAlarmType);
                        decodeAdasAlarmType(position, adasAlarmType);
                        int adasAlarmLevel = buf.readUnsignedByte();
                        position.set("adasAlarmLevel", adasAlarmLevel);
                        position.set("precedingVehicleSpeed", buf.readUnsignedByte());
                        position.set("precedingVehicleDistance", buf.readUnsignedByte());
                        int deviationType = buf.readUnsignedByte();
                        if (deviationType > 0) {
                            position.set("deviationType", deviationType);
                        }
                        int roadSignType = buf.readUnsignedByte();
                        if (roadSignType > 0) {
                            position.set("roadSignType", roadSignType);
                        }
                        position.set("roadSignData", buf.readUnsignedByte());
                        position.set("vehicleSpeed", buf.readUnsignedByte());
                        position.setAltitude(buf.readUnsignedShort());
                        position.setLatitude(buf.readUnsignedInt() * 0.000001);
                        position.setLongitude(buf.readUnsignedInt() * 0.000001);
                        position.setTime(readDate(buf, deviceSession.get(DeviceSession.KEY_TIMEZONE)));
                        position.set(Position.KEY_STATUS, buf.readUnsignedShort());
                        // Read alarm identification number (Table 4-16)
                        byte[] alarmSign = new byte[16];
                        buf.readBytes(alarmSign);
                        position.set("alarmSignNumber", ByteBufUtil.hexDump(Unpooled.wrappedBuffer(alarmSign)));
                        LOGGER.debug("CHECKING VIDEO REQUEST - ADAS Level: {}, Channel: {}",
                                adasAlarmLevel, (channel != null));
                     // Request video for high-risk ADAS alarms
                        if (channel != null) {
                            LOGGER.debug("SENDING VIDEO REQUEST - ADAS Level: {}", adasAlarmLevel);
                            ByteBuf response = Unpooled.buffer();
                            String serverIp = "165.22.228.97";
                            response.writeByte(serverIp.length());
                            response.writeBytes(serverIp.getBytes(StandardCharsets.US_ASCII));
                            response.writeShort(5999); // TCP port
                            response.writeShort(0);    // UDP port
                            response.writeByte(VIDEO_CHANNEL_ADAS);
                            response.writeBytes(alarmSign);
                            response.writeBytes(new byte[32]);
                            ByteBuf videoRequestMsg = formatMessage(delimiter, MSG_ALARM_ATTACHMENT_UPLOAD, id,
                                    false, response);
                            // Log the video request message
                            byte[] videoRequestBytes = new byte[videoRequestMsg.readableBytes()];
                            videoRequestMsg.getBytes(videoRequestMsg.readerIndex(), videoRequestBytes);
                            String videoRequestHex = DataConverter.printHex(videoRequestBytes);
                            LOGGER.info("ALARM VIDEO REQUEST ADAS - MsgID: 0x{}, Raw: {}",
                                    Integer.toHexString(MSG_ALARM_ATTACHMENT_UPLOAD).toUpperCase(),
                                    videoRequestHex);

                            channel.writeAndFlush(new NetworkMessage(videoRequestMsg, remoteAddress));
                        }
                        LOGGER.debug("ADAS alarm processed - type: {}", adasAlarmType);
                    } else {
                        LOGGER.debug("ADAS alarm too short - length: {}", length);
                    }
                    break;
                case 0x65: // DSM Alarm Information (Table 4-17)
                    LOGGER.debug("ENTERED DSM ALARM CASE 0x65!");
                    if (length >= 40) {
                        position.set("dsmAlarmId", buf.readUnsignedInt());
                        int dsmFlagStatus = buf.readUnsignedByte();
                        position.set("dsmFlagStatus", dsmFlagStatus);
                        int dsmAlarmType = buf.readUnsignedByte();
                        position.set("dsmAlarmType", dsmAlarmType);
                        LOGGER.debug("DSM ALARM DETAILS - Type: 0x{}, Flag: {}, ID: {}",
                                Integer.toHexString(dsmAlarmType), dsmFlagStatus, position.getLong("dsmAlarmId"));
                        decodeDsmAlarmType(position, dsmAlarmType);
                        int dsmAlarmLevel = buf.readUnsignedByte();
                        position.set("dsmAlarmLevel", dsmAlarmLevel);
                        LOGGER.debug("DSM ALARM LEVEL: {}", dsmAlarmLevel);
                        if (dsmAlarmType == 0x01) { // Fatigue driving
                            position.set("fatigueLevel", buf.readUnsignedByte());
                        } else {
                            buf.skipBytes(1); // reserved
                        }
                        buf.skipBytes(4); // reserved bytes
                        position.set("vehicleSpeed", buf.readUnsignedByte());
                        position.setAltitude(buf.readUnsignedShort());
                        position.setLatitude(buf.readUnsignedInt() * 0.000001);
                        position.setLongitude(buf.readUnsignedInt() * 0.000001);
                        position.setTime(readDate(buf, deviceSession.get(DeviceSession.KEY_TIMEZONE)));
                        position.set(Position.KEY_STATUS, buf.readUnsignedShort());
                        // Read alarm identification number
                        byte[] dsmAlarmSign = new byte[16];
                        buf.readBytes(dsmAlarmSign);
                        position.set("dsmAlarmSignNumber", ByteBufUtil.hexDump(Unpooled.wrappedBuffer(dsmAlarmSign)));
                        position.set("dsmAlarmSignNumber", ByteBufUtil.hexDump(Unpooled.wrappedBuffer(dsmAlarmSign)));

                        LOGGER.debug("CHECKING VIDEO REQUEST - DSM Level: {}, Channel: {}",
                                dsmAlarmLevel, (channel != null));
                       // Request video for high-risk DSM alarms
                        if (channel != null) {
                            LOGGER.debug("SENDING VIDEO REQUEST - DSM Level: {}", dsmAlarmLevel);
                            ByteBuf response = Unpooled.buffer();
                            String serverIp = "165.22.228.97";
                            response.writeByte(serverIp.length());
                            response.writeBytes(serverIp.getBytes(StandardCharsets.US_ASCII));
                            response.writeShort(5999); // TCP port
                            response.writeShort(0);    // UDP port
                            response.writeByte(VIDEO_CHANNEL_DSM);
                            response.writeBytes(dsmAlarmSign);
                            response.writeBytes(new byte[32]);
                            ByteBuf videoRequestMsg = formatMessage(delimiter, MSG_ALARM_ATTACHMENT_UPLOAD, id,
                                    false, response);
                            // Log the video request message
                            byte[] videoRequestBytes = new byte[videoRequestMsg.readableBytes()];
                            videoRequestMsg.getBytes(videoRequestMsg.readerIndex(), videoRequestBytes);
                            String videoRequestHex = DataConverter.printHex(videoRequestBytes);
                            LOGGER.info("ALARM VIDEO REQUEST DSM - MsgID: 0x{}, Raw: {}",
                                    Integer.toHexString(MSG_ALARM_ATTACHMENT_UPLOAD).toUpperCase(),
                                    videoRequestHex);

                            channel.writeAndFlush(new NetworkMessage(videoRequestMsg, remoteAddress));
                        }
                    }
                    break;
                case 0x67:
                    stringValue = buf.readCharSequence(8, StandardCharsets.US_ASCII).toString();
                    position.set("password", stringValue);
                    break;
                case 0x70:
                    buf.readUnsignedInt(); // alarm serial number
                    buf.readUnsignedByte(); // alarm status
                    switch (buf.readUnsignedByte()) {
                        case 0x01 -> position.addAlarm(Position.ALARM_ACCELERATION);
                        case 0x02 -> position.addAlarm(Position.ALARM_BRAKING);
                        case 0x03 -> position.addAlarm(Position.ALARM_CORNERING);
                        case 0x16 -> position.addAlarm(Position.ALARM_ACCIDENT);
                    }
                    break;
                case 0x68:
                    position.set(Position.KEY_BATTERY_LEVEL, buf.readUnsignedShort() * 0.01);
                    break;
                case 0x69:
                    position.set(Position.KEY_BATTERY, buf.readUnsignedShort() * 0.01);
                    break;
                case 0x80:
                    buf.readUnsignedByte(); // content
                    endIndex = buf.writerIndex() - 2;
                    decodeExtension(position, buf, endIndex);
                    break;
                case 0x91:
                    position.set(Position.KEY_BATTERY, buf.readUnsignedShort() * 0.1);
                    position.set(Position.KEY_RPM, buf.readUnsignedShort());
                    position.set(Position.KEY_OBD_SPEED, buf.readUnsignedByte());
                    position.set(Position.KEY_THROTTLE, buf.readUnsignedByte() * 100 / 255);
                    position.set(Position.KEY_ENGINE_LOAD, buf.readUnsignedByte() * 100 / 255);
                    position.set(Position.KEY_COOLANT_TEMP, buf.readUnsignedByte() - 40);
                    buf.readUnsignedShort();
                    position.set(Position.KEY_FUEL_CONSUMPTION, buf.readUnsignedShort() * 0.01);
                    buf.readUnsignedShort();
                    buf.readUnsignedInt();
                    buf.readUnsignedShort();
                    position.set(Position.KEY_FUEL_USED, buf.readUnsignedShort() * 0.01);
                    break;
                case 0x94:
                    if (length > 0) {
                        stringValue = buf.readCharSequence(length, StandardCharsets.US_ASCII).toString();
                        position.set(Position.KEY_VIN, stringValue);
                    }
                    break;
                case 0xAC:
                    position.set(Position.KEY_ODOMETER, buf.readUnsignedInt());
                    break;
                case 0xBC:
                    stringValue = buf.readCharSequence(length, StandardCharsets.US_ASCII).toString();
                    position.set("driver", stringValue.trim());
                    break;
                case 0xBD:
                    stringValue = buf.readCharSequence(length, StandardCharsets.US_ASCII).toString();
                    position.set(Position.KEY_DRIVER_UNIQUE_ID, stringValue);
                    break;
                case 0xD0:
                    long userStatus = buf.readUnsignedInt();
                    if (BitUtil.check(userStatus, 3)) {
                        position.addAlarm(Position.ALARM_VIBRATION);
                    }
                    break;
                case 0xD3:
                    position.set(Position.KEY_POWER, buf.readUnsignedShort() * 0.1);
                    break;
                case 0xD4:
                case 0xE1:
                    if (length == 1) {
                        position.set(Position.KEY_BATTERY_LEVEL, buf.readUnsignedByte());
                    } else {
                        position.set(Position.KEY_DRIVER_UNIQUE_ID, String.valueOf(buf.readUnsignedInt()));
                    }
                    break;
                case 0xD5:
                    if (length == 2) {
                        position.set(Position.KEY_BATTERY, buf.readUnsignedShort() * 0.01);
                    } else {
                        int count = buf.readUnsignedByte();
                        for (int i = 1; i <= count; i++) {
                            position.set("lock" + i + "Id", ByteBufUtil.hexDump(buf.readSlice(5)));
                            position.set("lock" + i + "Card", ByteBufUtil.hexDump(buf.readSlice(5)));
                            position.set("lock" + i + "Battery", buf.readUnsignedByte());
                            int status = buf.readUnsignedShort();
                            position.set("lock" + i + "Locked", !BitUtil.check(status, 5));
                        }
                    }
                    break;
                case 0xDA:
                    buf.readUnsignedShort(); // string cut count
                    int deviceStatus = buf.readUnsignedByte();
                    position.set("string", BitUtil.check(deviceStatus, 0));
                    position.set(Position.KEY_MOTION, BitUtil.check(deviceStatus, 2));
                    position.set("cover", BitUtil.check(deviceStatus, 3));
                    break;
                case 0xE2:
                    if (!"DT800".equals(model)) {
                        position.set(Position.KEY_FUEL_LEVEL, buf.readUnsignedInt() * 0.1);
                    }
                    break;
                case 0xE3:
                    buf.readUnsignedByte(); // reserved
                    position.set(Position.KEY_BATTERY_LEVEL, buf.readUnsignedByte());
                    position.set(Position.KEY_BATTERY, buf.readUnsignedShort() / 100.0);
                    break;
                case 0xE6:
                    while (buf.readerIndex() < endIndex) {
                        int sensorIndex = buf.readUnsignedByte();
                        buf.skipBytes(6); // mac
                        position.set(Position.PREFIX_TEMP + sensorIndex, decodeCustomDouble(buf));
                        position.set("humidity" + sensorIndex, decodeCustomDouble(buf));
                    }
                    break;
                case 0xEB:
                    if (buf.getUnsignedShort(buf.readerIndex()) > 200) {
                        int mcc = buf.readUnsignedShort();
                        int mnc = buf.readUnsignedByte();
                        while (buf.readerIndex() < endIndex) {
                            network.addCellTower(CellTower.from(
                                    mcc, mnc, buf.readUnsignedShort(), buf.readUnsignedShort(),
                                    buf.readUnsignedByte()));
                        }
                    } else {
                        while (buf.readerIndex() < endIndex) {
                            int extendedLength = buf.readUnsignedShort();
                            int extendedEndIndex = buf.readerIndex() + extendedLength;
                            int extendedType = buf.readUnsignedShort();
                            switch (extendedType) {
                                case 0x0001:
                                    position.set("fuel1", buf.readUnsignedShort() * 0.1);
                                    buf.readUnsignedByte(); // unused
                                    break;
                                case 0x0023:
                                    position.set("fuel2", Double.parseDouble(
                                            buf.readCharSequence(6, StandardCharsets.US_ASCII).toString()));
                                    break;
                                case 0x00B2:
                                    position.set(Position.KEY_ICCID, ByteBufUtil.hexDump(
                                            buf.readSlice(10)).replaceAll("f", ""));
                                    break;
                                case 0x00B9:
                                    buf.readUnsignedByte(); // count
                                    String[] wifi = buf.readCharSequence(
                                            extendedLength - 3, StandardCharsets.US_ASCII).toString().split(",");
                                    for (int i = 0; i < wifi.length / 2; i++) {
                                        network.addWifiAccessPoint(
                                                WifiAccessPoint.from(wifi[i * 2], Integer.parseInt(wifi[i * 2 + 1])));
                                    }
                                    break;
                                case 0x00C6:
                                    int batteryAlarm = buf.readUnsignedByte();
                                    if (batteryAlarm == 0x03 || batteryAlarm == 0x04) {
                                        position.set(Position.KEY_ALARM, Position.ALARM_LOW_BATTERY);
                                    }
                                    position.set("batteryAlarm", batteryAlarm);
                                    break;
                                case 0x00CE:
                                    position.set(Position.KEY_POWER, buf.readUnsignedShort() * 0.01);
                                    break;
                                case 0x00D8:
                                    network.addCellTower(CellTower.from(
                                            buf.readUnsignedShort(), buf.readUnsignedByte(),
                                            buf.readUnsignedShort(), buf.readUnsignedInt()));
                                    break;
                                case 0x00A8:
                                case 0x00E1:
                                    position.set(Position.KEY_BATTERY_LEVEL, buf.readUnsignedByte());
                                    break;
                                default:
                                    break;
                            }
                            buf.readerIndex(extendedEndIndex);
                        }
                    }
                    break;
                case 0xED:
                    stringValue = buf.readCharSequence(length, StandardCharsets.US_ASCII).toString();
                    position.set(Position.KEY_CARD, stringValue.trim());
                    break;
                case 0xEE:
                    position.set(Position.KEY_RSSI, buf.readUnsignedByte());
                    position.set(Position.KEY_POWER, buf.readUnsignedShort() * 0.001);
                    position.set(Position.KEY_BATTERY, buf.readUnsignedShort() * 0.001);
                    position.set(Position.KEY_SATELLITES, buf.readUnsignedByte());
                    break;
                case 0xF1:
                    position.set(Position.KEY_POWER, buf.readUnsignedInt() * 0.001);
                    break;
                case 0xF3:
                    while (buf.readerIndex() < endIndex) {
                        int extendedType = buf.readUnsignedShort();
                        int extendedLength = buf.readUnsignedByte();
                        switch (extendedType) {
                            case 0x0002 -> position.set(Position.KEY_OBD_SPEED, buf.readUnsignedShort() * 0.1);
                            case 0x0003 -> position.set(Position.KEY_RPM, buf.readUnsignedShort());
                            case 0x0004 -> position.set(Position.KEY_POWER, buf.readUnsignedShort() * 0.001);
                            case 0x0005 -> position.set(Position.KEY_OBD_ODOMETER, buf.readUnsignedInt() * 100);
                            case 0x0007 -> position.set(Position.KEY_FUEL_CONSUMPTION, buf.readUnsignedShort() * 0.1);
                            case 0x0008 -> position.set(Position.KEY_ENGINE_LOAD, buf.readUnsignedShort() * 0.1);
                            case 0x0009 -> position.set(Position.KEY_COOLANT_TEMP, buf.readUnsignedShort() - 40);
                            case 0x000B -> position.set("intakePressure", buf.readUnsignedShort());
                            case 0x000C -> position.set("intakeTemp", buf.readUnsignedShort() - 40);
                            case 0x000D -> position.set("intakeFlow", buf.readUnsignedShort());
                            case 0x000E -> position.set(Position.KEY_THROTTLE, buf.readUnsignedShort() * 100 / 255);
                            case 0x0050 -> {
                                position.set(Position.KEY_VIN, buf.readSlice(17).toString(StandardCharsets.US_ASCII));
                            }
                            case 0x0100 -> position.set(Position.KEY_ODOMETER_TRIP, buf.readUnsignedShort() * 0.1);
                            case 0x0102 -> position.set("tripFuel", buf.readUnsignedShort() * 0.1);
                            case 0x0112 -> position.set("hardAccelerationCount", buf.readUnsignedShort());
                            case 0x0113 -> position.set("hardDecelerationCount", buf.readUnsignedShort());
                            case 0x0114 -> position.set("hardCorneringCount", buf.readUnsignedShort());
                            default -> buf.skipBytes(extendedLength);
                        }
                    }
                    break;
                case 0xF4:
                    while (buf.readerIndex() < endIndex) {
                        String mac = ByteBufUtil.hexDump(buf.readSlice(6)).replaceAll("(..)", "$1:");
                        network.addWifiAccessPoint(WifiAccessPoint.from(
                                mac.substring(0, mac.length() - 1), buf.readByte()));
                    }
                    break;
                case 0xF6:
                    position.set(Position.KEY_EVENT, buf.readUnsignedByte());
                    int fieldMask = buf.readUnsignedByte();
                    if (BitUtil.check(fieldMask, 0)) {
                        buf.readUnsignedShort(); // light
                    }
                    if (BitUtil.check(fieldMask, 1)) {
                        position.set(Position.PREFIX_TEMP + 1, buf.readShort() * 0.1);
                    }
                    break;
                case 0xF7:
                    position.set(Position.KEY_BATTERY, buf.readUnsignedInt() * 0.001);
                    if (length >= 5) {
                        short batteryStatus = buf.readUnsignedByte();
                        if (batteryStatus == 2 || batteryStatus == 3) {
                            position.set(Position.KEY_CHARGE, true);
                        }
                    }
                    if (length >= 6) {
                        position.set(Position.KEY_BATTERY_LEVEL, buf.readUnsignedByte());
                    }
                    break;
                case 0xFE:
                    if (length == 1) {
                        position.set(Position.KEY_BATTERY_LEVEL, buf.readUnsignedByte());
                    } else if (length == 2) {
                        position.set(Position.KEY_POWER, buf.readUnsignedShort() * 0.1);
                    } else {
                        int mark = buf.readUnsignedByte();
                        if (mark == 0x7C) {
                            while (buf.readerIndex() < endIndex) {
                                int extendedType = buf.readUnsignedByte();
                                int extendedLength = buf.readUnsignedByte();
                                if (extendedType == 0x01) {
                                    long alarms = buf.readUnsignedInt();
                                    if (BitUtil.check(alarms, 0)) {
                                        position.addAlarm(Position.ALARM_ACCELERATION);
                                    }
                                    if (BitUtil.check(alarms, 1)) {
                                        position.addAlarm(Position.ALARM_BRAKING);
                                    }
                                    if (BitUtil.check(alarms, 2)) {
                                        position.addAlarm(Position.ALARM_CORNERING);
                                    }
                                    if (BitUtil.check(alarms, 3)) {
                                        position.addAlarm(Position.ALARM_ACCIDENT);
                                    }
                                    if (BitUtil.check(alarms, 4)) {
                                        position.addAlarm(Position.ALARM_TAMPERING);
                                    }
                                } else {
                                    buf.skipBytes(extendedLength);
                                }
                            }
                        }
                        position.set(Position.KEY_BATTERY_LEVEL, buf.readUnsignedByte());
                    }
                    break;
                default:
                    break;
            }
            } catch (Exception e) {
                System.getLogger(DC600ProtocolDecoder.class.getName()).log(System.Logger.Level.DEBUG,
                        "Error parsing extension 0x" + Integer.toHexString(subtype) + ": " + e.getMessage());
            }
            buf.readerIndex(endIndex);
        }

        if (network.getCellTowers() != null || network.getWifiAccessPoints() != null) {
            position.setNetwork(network);
        }
        return position;
    }
    private Position decodeLocation2(DeviceSession deviceSession, ByteBuf buf, int type) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        Jt600ProtocolDecoder.decodeBinaryLocation(buf, position);
        position.setValid(type != MSG_LOCATION_REPORT_BLIND);
        position.set(Position.KEY_RSSI, buf.readUnsignedByte());
        position.set(Position.KEY_SATELLITES, buf.readUnsignedByte());
        position.set(Position.KEY_ODOMETER, buf.readUnsignedInt() * 1000L);
        int battery = buf.readUnsignedByte();
        if (battery <= 100) {
            position.set(Position.KEY_BATTERY_LEVEL, battery);
        } else if (battery == 0xAA || battery == 0xAB) {
            position.set(Position.KEY_CHARGE, true);
        }
        long cid = buf.readUnsignedInt();
        int lac = buf.readUnsignedShort();
        if (cid > 0 && lac > 0) {
            position.setNetwork(new Network(CellTower.fromCidLac(getConfig(), cid, lac)));
        }
        int product = buf.readUnsignedByte();
        int status = buf.readUnsignedShort();
        int alarm = buf.readUnsignedShort();
        if (product == 1 || product == 2) {
            if (BitUtil.check(alarm, 0)) {
                position.addAlarm(Position.ALARM_LOW_POWER);
            }
        } else if (product == 3) {
            position.set(Position.KEY_BLOCKED, BitUtil.check(status, 5));
            if (BitUtil.check(alarm, 0)) {
                position.addAlarm(Position.ALARM_OVERSPEED);
            }
            if (BitUtil.check(alarm, 1)) {
                position.addAlarm(Position.ALARM_LOW_POWER);
            }
            if (BitUtil.check(alarm, 2)) {
                position.addAlarm(Position.ALARM_VIBRATION);
            }
            if (BitUtil.check(alarm, 3)) {
                position.addAlarm(Position.ALARM_LOW_BATTERY);
            }
            if (BitUtil.check(alarm, 5)) {
                position.addAlarm(Position.ALARM_GEOFENCE_ENTER);
            }
            if (BitUtil.check(alarm, 6)) {
                position.addAlarm(Position.ALARM_GEOFENCE_EXIT);
            }
        }
        position.set(Position.KEY_STATUS, status);
        while (buf.readableBytes() > 2) {
            int id = buf.readUnsignedByte();
            int length = buf.readUnsignedByte();
            switch (id) {
                case 0x02:
                    position.setAltitude(buf.readShort());
                    break;
                case 0x10:
                    position.set("wakeSource", buf.readUnsignedByte());
                    break;
                case 0x0A:
                    if (length == 3) {
                        buf.readUnsignedShort(); // mcc
                        buf.readUnsignedByte(); // mnc
                    } else {
                        buf.skipBytes(length);
                    }
                    break;
                case 0x0B:
                    position.set("lockCommand", buf.readUnsignedByte());
                    if (length >= 5 && length <= 6) {
                        position.set("lockCard", buf.readUnsignedInt());
                    } else if (length >= 7) {
                        position.set("lockPassword", buf.readCharSequence(6, StandardCharsets.US_ASCII).toString());
                    }
                    if (length % 2 == 0) {
                        position.set("unlockResult", buf.readUnsignedByte());
                    }
                    break;
                case 0x0C:
                    int x = buf.readUnsignedShort();
                    if (x > 0x8000) {
                        x -= 0x10000;
                    }
                    int y = buf.readUnsignedShort();
                    if (y > 0x8000) {
                        y -= 0x10000;
                    }
                    int z = buf.readUnsignedShort();
                    if (z > 0x8000) {
                        z -= 0x10000;
                    }
                    position.set("tilt", String.format("[%d,%d,%d]", x, y, z));
                    break;
                case 0xFC:
                    position.set(Position.KEY_GEOFENCE, buf.readUnsignedByte());
                    break;
                default:
                    buf.skipBytes(length);
                    break;
            }
        }
        return position;
    }
    private List<Position> decodeLocationBatch(DeviceSession deviceSession, ByteBuf buf, int type) {
        List<Position> positions = new LinkedList<>();
        int locationType = 0;
        if (type == MSG_LOCATION_BATCH) {
            buf.readUnsignedShort(); // count
            locationType = buf.readUnsignedByte();
        }
        while (buf.readableBytes() > 2) {
            int length = type == MSG_LOCATION_BATCH_2 ? buf.readUnsignedByte() : buf.readUnsignedShort();
            ByteBuf fragment = buf.readSlice(length);
            Position position = decodeLocation(deviceSession, fragment, null, null, null);
            if (locationType > 0) {
                position.set(Position.KEY_ARCHIVE, true);
            }
            positions.add(position);
        }
        return positions;
    }
    private Position decodeTransparent(DeviceSession deviceSession, ByteBuf buf) {
        int type = buf.readUnsignedByte();
        if (type == 0x41) {
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            String data = buf.readCharSequence(buf.readableBytes() - 2, StandardCharsets.US_ASCII).toString().trim();
            String[] values = data.split(",");
            int index = 1; // skip header
            if (!values[index++].isEmpty()) {
                position.set(Position.KEY_POWER, Double.parseDouble(values[index - 1]));
            }
            if (!values[index++].isEmpty()) {
                position.set(Position.KEY_RPM, Integer.parseInt(values[index - 1]));
            }
            if (!values[index++].isEmpty()) {
                position.set(Position.KEY_OBD_SPEED, Integer.parseInt(values[index - 1]));
            }
            if (!values[index++].isEmpty()) {
                position.set(Position.KEY_THROTTLE, Double.parseDouble(values[index - 1]));
            }
            if (!values[index++].isEmpty()) {
                position.set(Position.KEY_ENGINE_LOAD, Double.parseDouble(values[index - 1]));
            }
            if (!values[index++].isEmpty()) {
                position.set(Position.KEY_COOLANT_TEMP, Integer.parseInt(values[index - 1]));
            }
            if (!values[index++].isEmpty()) {
                position.set(Position.KEY_FUEL_CONSUMPTION, Double.parseDouble(values[index - 1])); // instant
            }
            if (!values[index++].isEmpty()) {
                position.set(Position.KEY_FUEL_CONSUMPTION, Double.parseDouble(values[index - 1])); // average
            }
            if (!values[index++].isEmpty()) {
                position.set(Position.KEY_ODOMETER_TRIP, Double.parseDouble(values[index - 1]));
            }
            if (!values[index++].isEmpty()) {
                position.set(Position.KEY_OBD_ODOMETER, Integer.parseInt(values[index - 1]));
            }
            if (!values[index++].isEmpty()) {
                position.set("tripFuelUsed", Double.parseDouble(values[index - 1]));
            }
            if (!values[index++].isEmpty()) {
                position.set(Position.KEY_FUEL_USED, Double.parseDouble(values[index - 1]));
            }
            return position;
        } else if (type == 0xF0) {
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            Date time = readDate(buf, deviceSession.get(DeviceSession.KEY_TIMEZONE));
            if (buf.readUnsignedByte() > 0) {
                position.set(Position.KEY_ARCHIVE, true);
            }
            buf.readUnsignedByte(); // vehicle type
            int count;
            int subtype = buf.readUnsignedByte();
            try {
            switch (subtype) {
                case 0x01:
                    count = buf.readUnsignedByte();
                    for (int i = 0; i < count; i++) {
                        int id = buf.readUnsignedShort();
                        int length = buf.readUnsignedByte();
                        switch (id) {
                            case 0x0102, 0x0528, 0x0546 -> {
                                position.set(Position.KEY_ODOMETER, buf.readUnsignedInt() * 100);
                            }
                            case 0x0103 -> position.set(Position.KEY_FUEL_LEVEL, buf.readUnsignedInt() * 0.01);
                            case 0x0111 -> position.set("fuelTemp", buf.readUnsignedByte() - 40);
                            case 0x012E -> position.set("oilLevel", buf.readUnsignedShort() * 0.1);
                            case 0x052A -> position.set(Position.KEY_FUEL_LEVEL, buf.readUnsignedShort() * 0.01);
                            case 0x0105, 0x052C -> position.set(Position.KEY_FUEL_USED, buf.readUnsignedInt() * 0.01);
                            case 0x014A, 0x0537, 0x0538, 0x0539 -> {
                                position.set(Position.KEY_FUEL_CONSUMPTION, buf.readUnsignedShort() * 0.01);
                            }
                            case 0x052B -> position.set(Position.KEY_FUEL_LEVEL, buf.readUnsignedByte());
                            case 0x052D -> position.set(Position.KEY_COOLANT_TEMP, buf.readUnsignedByte() - 40);
                            case 0x052E -> position.set("airTemp", buf.readUnsignedByte() - 40);
                            case 0x0530 -> position.set(Position.KEY_POWER, buf.readUnsignedShort() * 0.001);
                            case 0x0535 -> position.set(Position.KEY_OBD_SPEED, buf.readUnsignedShort() * 0.1);
                            case 0x0536 -> position.set(Position.KEY_RPM, buf.readUnsignedShort());
                            case 0x053D -> position.set("intakePressure", buf.readUnsignedShort() * 0.1);
                            case 0x0544 -> position.set("liquidLevel", buf.readUnsignedByte());
                            case 0x0547, 0x0548 -> position.set(Position.KEY_THROTTLE, buf.readUnsignedByte());
                            default -> {
                                switch (length) {
                                    case 1 -> position.set(Position.PREFIX_IO + id, buf.readUnsignedByte());
                                    case 2 -> position.set(Position.PREFIX_IO + id, buf.readUnsignedShort());
                                    case 4 -> position.set(Position.PREFIX_IO + id, buf.readUnsignedInt());
                                    default -> buf.skipBytes(length);
                                }
                            }
                        }
                    }
                    getLastLocation(position, time);
                    decodeCoordinates(position, deviceSession, buf);
                    position.setTime(time);
                    break;
                case 0x02:
                    List<String> codes = new LinkedList<>();
                    count = buf.readUnsignedShort();
                    for (int i = 0; i < count; i++) {
                        buf.readUnsignedInt(); // system id
                        int codeCount = buf.readUnsignedShort();
                        for (int j = 0; j < codeCount; j++) {
                            buf.readUnsignedInt(); // dtc
                            buf.readUnsignedInt(); // status
                            codes.add(buf.readCharSequence(
                                    buf.readUnsignedShort(), StandardCharsets.US_ASCII).toString().trim());
                        }
                    }
                    position.set(Position.KEY_DTCS, String.join(" ", codes));
                    getLastLocation(position, time);
                    decodeCoordinates(position, deviceSession, buf);
                    position.setTime(time);
                    break;
                case 0x03:
                    count = buf.readUnsignedByte();
                    for (int i = 0; i < count; i++) {
                        int id = buf.readUnsignedByte();
                        int length = buf.readUnsignedByte();
                        switch (id) {
                            case 0x01:
                                position.addAlarm(Position.ALARM_POWER_RESTORED);
                                break;
                            case 0x02:
                                position.addAlarm(Position.ALARM_POWER_CUT);
                                break;
                            case 0x1A:
                                position.addAlarm(Position.ALARM_ACCELERATION);
                                break;
                            case 0x1B:
                                position.addAlarm(Position.ALARM_BRAKING);
                                break;
                            case 0x1C:
                                position.addAlarm(Position.ALARM_CORNERING);
                                break;
                            case 0x1D:
                            case 0x1E:
                            case 0x1F:
                                position.addAlarm(Position.ALARM_LANE_CHANGE);
                                break;
                            case 0x23:
                                position.addAlarm(Position.ALARM_FATIGUE_DRIVING);
                                break;
                            case 0x26:
                            case 0x27:
                            case 0x28:
                                position.addAlarm(Position.ALARM_ACCIDENT);
                                break;
                            case 0x31:
                            case 0x32:
                                position.addAlarm(Position.ALARM_DOOR);
                                break;
                            default:
                                break;
                        }
                        buf.skipBytes(length);
                    }
                    getLastLocation(position, time);
                    decodeCoordinates(position, deviceSession, buf);
                    position.setTime(time);
                    break;
                case 0x0B:
                    if (buf.readUnsignedByte() > 0) {
                        position.set(Position.KEY_VIN, buf.readCharSequence(17, StandardCharsets.US_ASCII).toString());
                    }
                    getLastLocation(position, time);
                    break;
                case 0x15:
                    int event = buf.readInt();
                    switch (event) {
                        case 51 -> position.addAlarm(Position.ALARM_ACCELERATION);
                        case 52 -> position.addAlarm(Position.ALARM_BRAKING);
                        case 53 -> position.addAlarm(Position.ALARM_CORNERING);
                        case 54 -> position.addAlarm(Position.ALARM_LANE_CHANGE);
                        case 56 -> position.addAlarm(Position.ALARM_ACCIDENT);
                        default -> position.set(Position.KEY_EVENT, event);
                    }
                    getLastLocation(position, time);
                    break;
                default:
                    return null;
            }
            } catch (Exception e) {
                System.getLogger(DC600ProtocolDecoder.class.getName()).log(System.Logger.Level.DEBUG,
                        "Error parsing extension 0x" + Integer.toHexString(subtype) + ": " + e.getMessage());
            }
            return position;
        } else if (type == 0xFF) {
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            position.setValid(true);
            position.setTime(readDate(buf, deviceSession.get(DeviceSession.KEY_TIMEZONE)));
            position.setLatitude(buf.readInt() * 0.000001);
            position.setLongitude(buf.readInt() * 0.000001);
            position.setAltitude(buf.readShort());
            position.setSpeed(UnitsConverter.knotsFromKph(buf.readUnsignedShort() * 0.1));
            position.setCourse(buf.readUnsignedShort());
            return position;
        }
        return null;
    }
    // === ADD THESE NEW METHODS AT THE END OF THE CLASS ===
    private void decodeAdasAlarmType(Position position, int alarmType) {
        switch (alarmType) {
            case 0x01 -> position.addAlarm("forwardCollision");
            case 0x02 -> position.addAlarm("laneDeparture");
            case 0x03 -> position.addAlarm("vehicleTooClose");
            case 0x04 -> position.addAlarm("pedestrianCollision");
            case 0x05 -> position.addAlarm("frequentLaneChange");
            case 0x06 -> position.addAlarm("roadSignExceeded");
            case 0x07 -> position.addAlarm("obstacleDetection");
            case 0x10 -> position.set("roadSignRecognized", true);
            case 0x11 -> position.set("activeCapture", true);
            default -> position.set("adasEventType", alarmType);
        }
    }
    private void decodeDsmAlarmType(Position position, int alarmType) {
        LOGGER.debug("PROCESSING DSM ALARM TYPE: 0x{}", Integer.toHexString(alarmType));
        switch (alarmType) {
            case 0x01 -> position.addAlarm(Position.ALARM_FATIGUE_DRIVING);
            case 0x02 -> {
                position.addAlarm(Position.ALARM_PHONE_CALL);
                LOGGER.debug("Cellphone use alarm triggered");
            }
            case 0x03 -> position.addAlarm("smoking");
            case 0x04 -> position.addAlarm("distractedDriving");
            case 0x05 -> position.addAlarm("driverAbnormal");
            case 0x06 -> {
                position.addAlarm(Position.ALARM_SEAT_BELT);
                LOGGER.debug("Seatbelt alarm triggered");
            }
            case 0x10 -> position.set("autoCapture", true);
            case 0x11 -> position.set("driverChanged", true);
            default -> position.set("dsmEventType", alarmType);
        }
    }
    private Position decodeAlarmAttachmentUpload(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, null);
        // Decode attachment upload instruction (Table 4-21)
        int ipLength = buf.readUnsignedByte();
        String serverIp = buf.readCharSequence(ipLength, StandardCharsets.US_ASCII).toString();
        position.set("attachmentServerIp", serverIp);
        position.set("attachmentTcpPort", buf.readUnsignedShort());
        position.set("attachmentUdpPort", buf.readUnsignedShort());
        byte[] alarmFlag = new byte[16];
        buf.readBytes(alarmFlag);
        position.set("alarmFlag", ByteBufUtil.hexDump(Unpooled.wrappedBuffer(alarmFlag)));
        byte[] alarmNumber = new byte[32];
        buf.readBytes(alarmNumber);
        position.set("alarmNumber", ByteBufUtil.hexDump(Unpooled.wrappedBuffer(alarmNumber)));
        return position;
    }
    private Position decodeAlarmAttachmentInfo(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, null);
        // Decode alarm attachment info (Table 4-23)
        byte[] terminalId = new byte[7];
        buf.readBytes(terminalId);
        position.set("terminalId", ByteBufUtil.hexDump(Unpooled.wrappedBuffer(terminalId)));
        byte[] alarmFlag = new byte[16];
        buf.readBytes(alarmFlag);
        position.set("alarmFlag", ByteBufUtil.hexDump(Unpooled.wrappedBuffer(alarmFlag)));
        byte[] alarmNumber = new byte[32];
        buf.readBytes(alarmNumber);
        position.set("alarmNumber", ByteBufUtil.hexDump(Unpooled.wrappedBuffer(alarmNumber)));
        position.set("infoType", buf.readUnsignedByte());
        position.set("attachmentCount", buf.readUnsignedByte());
        // Decode attachment list (Table 4-24)
        List<String> attachments = new ArrayList<>();
        while (buf.readableBytes() > 0) {
            int nameLength = buf.readUnsignedByte();
            String fileName = buf.readCharSequence(nameLength, StandardCharsets.US_ASCII).toString();
            long fileSize = buf.readUnsignedInt();
            attachments.add(fileName + ":" + fileSize);
        }
        position.set("attachments", String.join(",", attachments));
        return position;
    }
    private Position decodeFileUploadComplete(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, null);
        // Decode file upload complete (Table 4-27)
        int nameLength = buf.readUnsignedByte();
        String fileName = buf.readCharSequence(nameLength, StandardCharsets.US_ASCII).toString();
        position.set("fileName", fileName);
        position.set("fileType", buf.readUnsignedByte());
        position.set("fileSize", buf.readUnsignedInt());
        return position;
    }

    private Position decodeParameterMessage(DeviceSession deviceSession, ByteBuf buf, int type) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, null);
        // Decode parameter query/response
        int paramCount = buf.readUnsignedByte();
        List<String> parameters = new ArrayList<>();
        for (int i = 0; i < paramCount; i++) {
            int paramId = buf.readUnsignedShort();
            int paramLength = buf.readUnsignedByte();
            switch (paramId) {
                case 0xF364 -> {
                    // ADAS parameters (Table 4-10)
                    position.set("adasAlarmStatus", buf.readUnsignedInt());
                    position.set("laneDepartureThreshold", buf.readUnsignedByte());
                    position.set("forwardCollisionThreshold", buf.readUnsignedByte());
                    position.set("pedestrianCollisionThreshold", buf.readUnsignedByte());
                    position.set("vehicleDistanceThreshold", buf.readUnsignedByte());
                }
                case 0xF365 -> {
                    // DSM parameters (Table 4-11)
                    position.set("dsmAlarmStatus", buf.readUnsignedInt());
                    position.set("fatigueDrivingThreshold", buf.readUnsignedByte());
                    position.set("callAlarmThreshold", buf.readUnsignedByte());
                    position.set("smokingAlarmThreshold", buf.readUnsignedByte());
                    position.set("distractedDrivingThreshold", buf.readUnsignedByte());
                    position.set("abnormalBehaviorThreshold", buf.readUnsignedByte());
                }
                default -> buf.skipBytes(paramLength);
            }
        }
        return position;
    }
    // === ADD THESE FINAL METHODS AT THE END ===
    private Position decodeFileDataUpload(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, null);
        // Decode file data upload (Table 4-26)
        long frameHeader = buf.readUnsignedInt();
        position.set("frameHeader", String.format("0x%08X", frameHeader));
        byte[] fileNameBytes = new byte[50];
        buf.readBytes(fileNameBytes);
        String fileName = new String(fileNameBytes, StandardCharsets.US_ASCII).trim();
        position.set("fileName", fileName);
        position.set("dataOffset", buf.readUnsignedInt());
        position.set("dataLength", buf.readUnsignedInt());
        // Store the actual file data
        ByteBuf fileData = buf.readSlice(buf.readableBytes());
        position.set("fileDataLength", fileData.readableBytes());
        return position;
    }
    private Position decodeFileUploadCompleteResponse(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, null);
        // Decode file upload complete response (Table 4-28)
        int nameLength = buf.readUnsignedByte();
        String fileName = buf.readCharSequence(nameLength, StandardCharsets.US_ASCII).toString();
        position.set("fileName", fileName);
        position.set("fileType", buf.readUnsignedByte());
        position.set("uploadResult", buf.readUnsignedByte());
        position.set("supplementaryPackets", buf.readUnsignedByte());
        // Decode supplementary packet list if present
        if (buf.readableBytes() > 0) {
            List<String> supplementaryPackets = new ArrayList<>();
            while (buf.readableBytes() >= 8) { // Each packet has 8 bytes (4+4)
                long dataOffset = buf.readUnsignedInt();
                long dataLength = buf.readUnsignedInt();
                supplementaryPackets.add(dataOffset + ":" + dataLength);
            }
            position.set("supplementaryPacketList", String.join(",", supplementaryPackets));
        }
        return position;
    }
    private void decodeVehicleStatusData(Position position, ByteBuf buf) {
        // Decode vehicle status data record file (Table 4-22)
        position.set("totalDataBlocks", buf.readUnsignedInt());
        position.set("currentBlockNumber", buf.readUnsignedInt());
        position.set("alarmFlag", buf.readUnsignedInt());
        position.set("vehicleStatus", buf.readUnsignedInt());

        position.setLatitude(buf.readUnsignedInt() * 0.000001);
        position.setLongitude(buf.readUnsignedInt() * 0.000001);
        position.setAltitude(buf.readUnsignedShort());
        position.setSpeed(buf.readUnsignedShort() * 0.1); // 1/10km/h
        position.setCourse(buf.readUnsignedShort());
        position.setTime(readDate(buf, TimeZone.getTimeZone("GMT+8")));
        // Acceleration data
        position.set("xAcceleration", buf.readShort() * 0.01); // in g
        position.set("yAcceleration", buf.readShort() * 0.01); // in g
        position.set("zAcceleration", buf.readShort() * 0.01); // in g
        // Angular velocity
        position.set("xAngularVelocity", buf.readShort() * 0.01); // deg/s
        position.set("yAngularVelocity", buf.readShort() * 0.01); // deg/s
        position.set("zAngularVelocity", buf.readShort() * 0.01); // deg/s
        position.set("pulseSpeed", buf.readUnsignedShort() * 0.1); // 1/10km/h
        position.set("obdSpeed", buf.readUnsignedShort() * 0.1); // 1/10km/h
        int gearStatus = buf.readUnsignedByte();
        position.set("gearStatus", gearStatus);
        position.set("acceleratorPedal", buf.readUnsignedByte()); // %
        position.set("brakePedal", buf.readUnsignedByte()); // %
        position.set("brakeStatus", buf.readUnsignedByte());
        position.set("engineRpm", buf.readUnsignedShort());
        position.set("steeringWheelAngle", readSignedWord(buf));
        position.set("turnSignalStatus", buf.readUnsignedByte());
        // Skip reserved bytes
        buf.skipBytes(2);
    }

    private Position handleVideoUpload(DeviceSession deviceSession, ByteBuf buf) {
        LOGGER.debug("ENTERED handleVideoUpload - readable bytes: {}", buf.readableBytes());
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, null);
        // Read video data from the buffer
        int dataLength = buf.readableBytes();
        LOGGER.debug("Video data length: {} bytes", dataLength);
        if (dataLength > 0) {
        ByteBuf videoData = buf.readSlice(dataLength);
        try {
            // Save the video file using your existing method
            String fileName = writeMediaFile(deviceSession.getUniqueId(), videoData, "mp4");
            position.set(Position.KEY_VIDEO, fileName);
            LOGGER.info("VIDEO STORED: {} ({} bytes)", fileName, dataLength);
        } catch (Exception e) {
            LOGGER.error("FAILED TO STORE VIDEO: {}", e.getMessage());
            position.set("videoStorageError", true);
        }
        } else {
            LOGGER.warn("No video data received - empty buffer");
        }

        return position;
    }
}
