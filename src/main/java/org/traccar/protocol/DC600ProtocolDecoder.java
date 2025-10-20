/*
 * Copyright 2015 - 2025 Anton Tananaev (anton@traccar.org)
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
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * DC600 GPRS Communication Protocol Decoder
 * Based on: DC600 GPRS Communication protocol v1.0 (2024-05-15)
 * Ministry of transport of the People's Republic of China (January 2013)
 */
public class DC600ProtocolDecoder extends BaseProtocolDecoder {
    private static final Logger LOGGER = LoggerFactory.getLogger(DC600ProtocolDecoder.class);

    public DC600ProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    // Section 8: Message IDs from specification
    public static final int MSG_TERMINAL_GENERAL_RESPONSE = 0x0001;  // 8.1
    public static final int MSG_PLATFORM_GENERAL_RESPONSE = 0x8001;  // 8.2
    public static final int MSG_TERMINAL_HEARTBEAT = 0x0002;         // 8.3
    public static final int MSG_TERMINAL_REGISTER = 0x0100;          // 8.4
    public static final int MSG_TERMINAL_REGISTER_RESPONSE = 0x8100; // 8.5
    public static final int MSG_TERMINAL_AUTH = 0x0102;              // 8.6
    public static final int MSG_CHECK_TERMINAL_PARAMETER_RESPONSE = 0x0104; // 8.10
    public static final int MSG_CHECK_TERMINAL_ATTRIBUTE_RESPONSE = 0x0107; // 8.12
    public static final int MSG_PARAMETER_SETTING = 0x8103;          // 8.7
    public static final int MSG_CHECK_TERMINAL_PARAMETER = 0x8104;   // 8.8
    public static final int MSG_CHECK_SPECIFIED_PARAMETERS = 0x8106; // 8.9
    public static final int MSG_CHECK_TERMINAL_ATTRIBUTE = 0x8107;   // 8.11
    public static final int MSG_LOCATION_REPORT = 0x0200;            // 8.13
    public static final int MSG_MANUALLY_CONFIRM_ALARM = 0x8203;     // 8.14
    public static final int MSG_SEND_TEXT_INFO = 0x8300;             // 8.15
    public static final int MSG_TERMINAL_UPDATE_PACKET = 0x8108;     // 8.16
    public static final int MSG_TERMINAL_UPGRADE_RESULT = 0x0108;    // 8.17
    public static final int MSG_SETTING_CIRCLE_AREA = 0x8600;        // 8.18
    public static final int MSG_DELETE_CIRCLE_AREA = 0x8601;         // 8.19
    public static final int MSG_SETTING_RECTANGLE_AREA = 0x8602;     // 8.20
    public static final int MSG_DELETE_RECTANGLE_AREA = 0x8603;      // 8.21
    public static final int MSG_SETTING_POLYGON_AREA = 0x8604;       // 8.22
    public static final int MSG_DELETE_POLYGON_AREA = 0x8605;        // 8.23
    public static final int MSG_SETTING_ROUTE = 0x8606;              // 8.24
    public static final int MSG_DELETE_ROUTE = 0x8607;               // 8.25
    public static final int MSG_LOCATION_BATCH_UPLOAD = 0x0704;      // 8.26
    public static final int MSG_MULTIMEDIA_EVENT_INFO = 0x0800;      // 8.27
    public static final int MSG_MULTIMEDIA_DATA_UPLOAD = 0x0801;     // 8.28
    public static final int MSG_MULTIMEDIA_UPLOAD_RESPONSE = 0x8800; // 8.29
    public static final int MSG_CAMERA_COMMAND = 0x8801;             // 8.30
    public static final int MSG_CAMERA_RESPONSE = 0x0805;            // 8.31
    public static final int MSG_RETRIEVE_MULTIMEDIA = 0x8802;        // 8.32
    public static final int MSG_RETRIEVE_MULTIMEDIA_RESPONSE = 0x0802; // 8.33
    public static final int MSG_STORE_MULTIMEDIA_UPLOAD = 0x8803;    // 8.34
    public static final int MSG_SINGLE_MULTIMEDIA_UPLOAD = 0x8805;   // 8.35
    public static final int MSG_SEND_TEXT_MESSAGE = 0x8300;          // 8.15 (duplicate for BSJ devices)
    public static final int MSG_OIL_CONTROL = 0x8500;                // Oil/fuel control
    public static final int MSG_TERMINAL_CONTROL = 0x8105;           // Terminal control

    // JT/T 1078-2016: Video protocol message IDs
    public static final int MSG_VIDEO_LIVE_STREAM_REQUEST = 0x9101;      // Platform → Terminal
    public static final int MSG_VIDEO_LIVE_STREAM_RESPONSE = 0x1001;     // Terminal → Platform
    public static final int MSG_VIDEO_LIVE_STREAM_CONTROL = 0x9102;      // Platform → Terminal
    public static final int MSG_VIDEO_PLAYBACK_REQUEST = 0x9201;         // Platform → Terminal
    public static final int MSG_VIDEO_PLAYBACK_RESPONSE = 0x1201;        // Terminal → Platform
    public static final int MSG_VIDEO_PLAYBACK_CONTROL = 0x9202;         // Platform → Terminal
    public static final int MSG_VIDEO_RESOURCE_LIST_RESPONSE = 0x1205;   // Terminal → Platform
    public static final int MSG_VIDEO_DOWNLOAD_REQUEST = 0x9206;         // Platform → Terminal
    public static final int MSG_IMAGE_CAPTURE_REQUEST = 0x8801;          // Platform → Terminal (JT/T 808)
    public static final int MSG_IMAGE_CAPTURE_RESPONSE = 0x0805;         // Terminal → Platform

    // T/JSATL12-2017: ADAS/DSM alarm attachment protocol
    public static final int MSG_ALARM_ATTACHMENT_UPLOAD_REQUEST = 0x9208; // Platform → Terminal
    public static final int MSG_ALARM_ATTACHMENT_INFO = 0x1210;           // Terminal → Platform
    public static final int MSG_FILE_INFO_UPLOAD = 0x1211;                // Terminal → Platform
    public static final int MSG_FILE_UPLOAD_COMPLETE = 0x1212;            // Terminal → Platform

    private static final int RESULT_SUCCESS = 0;
    private static final int RESULT_FAILURE = 1;
    private static final int RESULT_INCORRECT_INFO = 2;
    private static final int RESULT_NOT_SUPPORTED = 3;

    private int delimiter = 0x7e;

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

    private void sendGeneralResponse(Channel channel, SocketAddress remoteAddress, ByteBuf id, int type, int index) {
        if (channel != null) {
            ByteBuf response = Unpooled.buffer();
            response.writeShort(index);
            response.writeShort(type);
            response.writeByte(RESULT_SUCCESS);
            channel.writeAndFlush(new NetworkMessage(
                    formatMessage(delimiter, MSG_PLATFORM_GENERAL_RESPONSE, id, false, response), remoteAddress));
        }
    }

    private void sendAlarmAttachmentRequest(Channel channel, SocketAddress remoteAddress,
                                             ByteBuf id, int alarmId, int alarmType, Position position) {
        if (channel != null) {
            LOGGER.info("SENDING ALARM ATTACHMENT REQUEST (0x9208) - AlarmId: {}, AlarmType: 0x{}",
                    alarmId, Integer.toHexString(alarmType).toUpperCase());
            ByteBuf data = Unpooled.buffer();
//            data.writeByte(alarmId);           // Alarm serial number
//            data.writeByte(alarmType);         // Alarm type (ADAS or DSM)
//            data.writeByte(0x00);              // Alarm terminal ID length (0 = all terminals)
//            data.writeByte(0x00);              // Reserved
            String serverIp = "165.22.228.97";
            data.writeByte(serverIp.length());
            data.writeBytes(serverIp.getBytes(StandardCharsets.US_ASCII));
            data.writeShort(5999);
            data.writeShort(0);
            byte[] alarmFlag = new byte[16];
            if (position.hasAttribute("adasAlarmId") || position.hasAttribute("dsmAlarmId")) {
                String deviceId = String.format("%07d", position.getDeviceId());
                System.arraycopy(deviceId.getBytes(StandardCharsets.US_ASCII), 0, alarmFlag, 0, 7);
                Date alarmTime = position.getDeviceTime() != null ? position.getDeviceTime() : new Date();
                SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmss");
                byte[] timeBytes = DataConverter.parseHex(sdf.format(alarmTime));
                System.arraycopy(timeBytes, 0, alarmFlag, 7, 6);
                alarmFlag[13] = (byte) alarmId;
                alarmFlag[14] = 0x01;
                alarmFlag[15] = 0x00;
            }
            data.writeBytes(alarmFlag);
            byte[] alarmNumber = new byte[32];
            String uniqueAlarmNumber = String.format("ALM-%d-%d-%d",
                    position.getDeviceId(), alarmId, System.currentTimeMillis());
            byte[] alarmNumBytes = uniqueAlarmNumber.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(alarmNumBytes, 0, alarmNumber, 0, Math.min(alarmNumBytes.length, 32));
            data.writeBytes(alarmNumber);
            data.writeBytes(new byte[16]);
            ByteBuf message = formatMessage(delimiter, MSG_ALARM_ATTACHMENT_UPLOAD_REQUEST, id, false, data);
            byte[] rawBytes = new byte[message.readableBytes()];
            message.getBytes(message.readerIndex(), rawBytes);
            LOGGER.info("ALARM ATTACHMENT REQUEST RAW DATA - AlarmId: {}, Length: {}, Hex: {}",
                    alarmId, rawBytes.length, DataConverter.printHex(rawBytes));
            channel.writeAndFlush(new NetworkMessage(message, remoteAddress));
            LOGGER.info("ALARM ATTACHMENT REQUEST SENT - AlarmId: {}, Server: {}:{}",
                    alarmId, serverIp, 5999);
        } else {
            LOGGER.error("Cannot send alarm attachment request - channel is null");
        }

    }

    private void sendImageCaptureRequest(Channel channel, SocketAddress remoteAddress, ByteBuf id) {
        if (channel != null) {
            LOGGER.info("SENDING IMAGE CAPTURE REQUEST (0x8801) - Requesting immediate capture");
            ByteBuf data = Unpooled.buffer();
            data.writeByte(0x01);              // Channel number (channel 1)
            data.writeByte(0x00);              // Capture command (0 = capture immediately)
            data.writeByte(0x00);              // Timing enabled (0 = disabled)
            data.writeShort(0x0000);           // Timing interval (0 = not applicable)
            data.writeByte(0x01);              // Save flag (1 = save to storage)
            data.writeByte(0x01);              // Resolution (1 = standard)
            data.writeByte(0x01);              // Image quality (1 = standard)
            data.writeByte(0x55);              // Brightness (default)
            data.writeByte(0x55);              // Contrast (default)
            data.writeByte(0x55);              // Saturation (default)
            data.writeByte(0x55);              // Chroma (default)

//            channel.writeAndFlush(new NetworkMessage(
//                    formatMessage(delimiter, MSG_IMAGE_CAPTURE_REQUEST, id, false, data),
//                    remoteAddress));
            ByteBuf message = formatMessage(delimiter, MSG_IMAGE_CAPTURE_REQUEST, id, false, data);
            byte[] rawBytes = new byte[message.readableBytes()];
            message.getBytes(message.readerIndex(), rawBytes);
            LOGGER.debug("IMAGE CAPTURE REQUEST RAW DATA - Hex: {}", DataConverter.printHex(rawBytes));
            channel.writeAndFlush(new NetworkMessage(message, remoteAddress));
            LOGGER.info("IMAGE CAPTURE REQUEST SENT - Channel: 1, Mode: Immediate");
        } else {
            LOGGER.error("Cannot send image capture request - channel is null");
        }

    }

    private String decodeDeviceId(ByteBuf id) {
        StringBuilder deviceId = new StringBuilder();
        for (int i = 0; i < id.readableBytes(); i++) {
            int b = id.getUnsignedByte(i);
            deviceId.append((b & 0xF0) >> 4).append(b & 0x0F);
        }
        return deviceId.toString();
    }

    private Date readDate(ByteBuf buf, TimeZone timeZone) {
        return new DateBuilder(timeZone)
                .setYear(BcdUtil.readInteger(buf, 2))
                .setMonth(BcdUtil.readInteger(buf, 2))
                .setDay(BcdUtil.readInteger(buf, 2))
                .setHour(BcdUtil.readInteger(buf, 2))
                .setMinute(BcdUtil.readInteger(buf, 2))
                .setSecond(BcdUtil.readInteger(buf, 2))
                .getDate();
    }

    private void decodeAlarmSigns(Position position, long alarmSign) {
        if (BitUtil.check(alarmSign, 0)) {
            position.set(Position.KEY_ALARM, Position.ALARM_SOS);
        }
        if (BitUtil.check(alarmSign, 1)) {
            position.set(Position.KEY_ALARM, Position.ALARM_OVERSPEED);
        }
        if (BitUtil.check(alarmSign, 2)) {
            position.set(Position.KEY_ALARM, Position.ALARM_FAULT);
        }
        if (BitUtil.check(alarmSign, 3)) {
            position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
        }
        if (BitUtil.check(alarmSign, 13)) {
            position.set(Position.KEY_ALARM, Position.ALARM_OVERSPEED);
        }
        if (BitUtil.check(alarmSign, 14)) {
            position.set(Position.KEY_ALARM, Position.ALARM_FATIGUE_DRIVING);
        }
        if (BitUtil.check(alarmSign, 18)) {
            position.set(Position.KEY_ALARM, Position.ALARM_OVERSPEED);
        }
        if (BitUtil.check(alarmSign, 19)) {
            position.set(Position.KEY_ALARM, Position.ALARM_IDLE);
        }
        if (BitUtil.check(alarmSign, 20)) {
            position.set(Position.KEY_ALARM, Position.ALARM_GEOFENCE_ENTER);
        }
        if (BitUtil.check(alarmSign, 21)) {
            position.set(Position.KEY_ALARM, Position.ALARM_GEOFENCE_EXIT);
        }
        if (BitUtil.check(alarmSign, 22)) {
            position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
        }
        if (BitUtil.check(alarmSign, 23)) {
            position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
        }
    }

    private Position decodeLocationBasicInfo(DeviceSession deviceSession, ByteBuf buf, TimeZone timeZone) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        long alarmSign = buf.readUnsignedInt();
        decodeAlarmSigns(position, alarmSign);
        long status = buf.readUnsignedInt();
        position.setValid(BitUtil.check(status, 1));
        position.set(Position.KEY_IGNITION, BitUtil.check(status, 0));
        double latitude = buf.readUnsignedInt() / 1000000.0;
        if (BitUtil.check(status, 2)) {
            latitude = -latitude; // South latitude
        }
        position.setLatitude(latitude);
        double longitude = buf.readUnsignedInt() / 1000000.0;
        if (BitUtil.check(status, 3)) {
            longitude = -longitude; // West longitude
        }
        position.setLongitude(longitude);
        position.setAltitude(buf.readUnsignedShort());
        position.setSpeed(buf.readUnsignedShort() * 0.1 * 0.539957); // Convert to knots
        position.setCourse(buf.readUnsignedShort());
        position.setTime(readDate(buf, timeZone));

        return position;
    }

    private void decodeLocationAdditionalInfo(Position position, ByteBuf buf, Channel channel,
                                               SocketAddress remoteAddress, ByteBuf id) {
        while (buf.readableBytes() > 2) {
            int infoId = buf.readUnsignedByte();
            int length = buf.readUnsignedByte();

            if (buf.readableBytes() < length) {
                break;
            }

            switch (infoId) {
                case 0x01: // Mileage (DWORD, 1/10km)
                    position.set(Position.KEY_ODOMETER, buf.readUnsignedInt() * 100);
                    break;
                case 0x30: // Signal strength
                    position.set(Position.KEY_RSSI, buf.readUnsignedByte());
                    break;
                case 0x31: // Satellite count
                    position.set(Position.KEY_SATELLITES, buf.readUnsignedByte());
                    break;

                case 0x64: // T/JSATL12-2017 Table 4-15: ADAS alarm information
                    if (length >= 4) {
                        int alarmId = buf.readUnsignedByte();
                        int alarmStatus = buf.readUnsignedByte();
                        int alarmType = buf.readUnsignedByte();
                        int alarmLevel = buf.readUnsignedByte();
                        LOGGER.info("ADAS ALARM DETECTED - Device: {}, AlarmId: {}, Type: 0x{}, Status: {}, Level: {}",
                                position.getDeviceId(), alarmId,
                                Integer.toHexString(alarmType).toUpperCase(),
                                alarmStatus, alarmLevel);

                        position.set("adasAlarmId", alarmId);
                        position.set("adasStatus", alarmStatus);
                        position.set("adasType", alarmType);
                        position.set("adasLevel", alarmLevel);

                        switch (alarmType) {
                            case 0x01: // Forward collision warning
                                position.set(Position.KEY_ALARM, Position.ALARM_ACCIDENT);
                                position.set("adasAlarmName", "forwardCollision");
                                LOGGER.warn("ADAS ALARM TYPE: Forward Collision Warning - Device: {}, AlarmId: {}",
                                        position.getDeviceId(), alarmId);
                                break;
                            case 0x02: // Lane departure warning
                                position.set(Position.KEY_ALARM, Position.ALARM_LANE_CHANGE);
                                position.set("adasAlarmName", "laneDeparture");
                                break;
                            case 0x03: // Vehicle distance monitoring warning
                                position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
                                position.set("adasAlarmName", "vehicleTooClose");
                                break;
                            case 0x04: // Pedestrian collision warning
                                position.set(Position.KEY_ALARM, Position.ALARM_ACCIDENT);
                                position.set("adasAlarmName", "pedestrianCollision");
                                break;
                            case 0x05: // Frequent lane change warning
                                position.set(Position.KEY_ALARM, Position.ALARM_LANE_CHANGE);
                                position.set("adasAlarmName", "frequentLaneChange");
                                break;
                            case 0x06: // Road sign out of limit warning
                                position.set(Position.KEY_ALARM, Position.ALARM_OVERSPEED);
                                position.set("adasAlarmName", "roadSignViolation");
                                break;
                            case 0x07: // Obstacle warning
                                position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
                                position.set("adasAlarmName", "obstacleDetection");
                                break;
                            default:
                                position.set("adasAlarmName", "unknown_" + alarmType);
                                break;
                        }

                        if (length >= 32) {
                            position.set("adasSpeed", buf.readUnsignedByte()); // Vehicle speed (1 km/h)
                            position.set("adasAltitude", buf.readUnsignedShort() / 10.0); // Altitude (1/10 m)
                            position.set("adasLatitude", buf.readInt() / 1000000.0); // Latitude (degree * 10^6)
                            position.set("adasLongitude", buf.readInt() / 1000000.0); // Longitude (degree * 10^6)
                            // BCD timestamp (6 bytes): YY-MM-DD-hh-mm-ss
                            buf.skipBytes(6);
                            // Vehicle status (2 bytes)
                            buf.skipBytes(2);
                            // Alarm identification (16 bytes)
                            buf.skipBytes(Math.min(16, buf.readableBytes()));
                        } else {
                            buf.skipBytes(length - 4);
                        }
                        LOGGER.info("Triggering alarm attachment request for ADAS alarm - Device: {}, AlarmId: {},"
                                        + " Type: 0x{}", position.getDeviceId(), alarmId,
                                           Integer.toHexString(alarmType).toUpperCase());

                        sendAlarmAttachmentRequest(channel, remoteAddress, id, alarmId, alarmType, position);
                    } else {
                        buf.skipBytes(length);
                    }
                    break;

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

                        // Map DSM alarm types to Traccar alarm constants
                        switch (alarmType) {
                            case 0x01: // Fatigue driving alarm
                                position.set(Position.KEY_ALARM, Position.ALARM_FATIGUE_DRIVING);
                                position.set("dsmAlarmName", "fatigueDriving");
                                LOGGER.warn("DSM ALARM TYPE: Fatigue Driving - Device: {}, AlarmId: {}",
                                        position.getDeviceId(), alarmId);
                                break;
                            case 0x02: // Calling/phone use alarm - CRITICAL FOR REQUIREMENT
                                position.set(Position.KEY_ALARM, Position.ALARM_PHONE_CALL);
                                position.set("dsmAlarmName", "phoneUse");
                                position.set("phoneUseDetected", true);
                                LOGGER.warn("DSM ALARM TYPE: PHONE USE DETECTED - Device: {}, AlarmId: {}",
                                        position.getDeviceId(), alarmId);
                                break;
                            case 0x03: // Smoking alarm
                                position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
                                position.set("dsmAlarmName", "smoking");
                                LOGGER.warn("DSM ALARM TYPE: Smoking Detected - Device: {}, AlarmId: {}",
                                        position.getDeviceId(), alarmId);
                                break;
                            case 0x04: // Distracted driving alarm
                                position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
                                position.set("dsmAlarmName", "distractedDriving");
                                LOGGER.warn("DSM ALARM TYPE: Distracted Driving - Device: {}, AlarmId: {}",
                                        position.getDeviceId(), alarmId);
                                break;
                            case 0x05: // Driver abnormal alarm
                                position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);
                                position.set("dsmAlarmName", "driverAbnormal");
                                break;
                            default:
                                position.set("dsmAlarmName", "unknown_" + alarmType);
                                break;
                        }

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
                        LOGGER.info("Triggering alarm attachment request for DSM alarm - Device: {}, AlarmId: {},"
                                        + " Type: 0x{}", position.getDeviceId(), alarmId,
                                               Integer.toHexString(alarmType).toUpperCase());

                        // Trigger automatic alarm attachment request
                        sendAlarmAttachmentRequest(channel, remoteAddress, id, alarmId, alarmType, position);
                    } else {
                        buf.skipBytes(length);
                    }
                    break;

                default:
                    buf.skipBytes(length);
                    break;
            }
        }
    }

    private Position decodeLocationReport(DeviceSession deviceSession, ByteBuf buf, TimeZone timeZone,
                                           Channel channel, SocketAddress remoteAddress, ByteBuf id, int index) {
        Position position = decodeLocationBasicInfo(deviceSession, buf, timeZone);
        decodeLocationAdditionalInfo(position, buf, channel, remoteAddress, id);

        if (position.hasAttribute(Position.KEY_ALARM)) {
            String alarmType = position.getString(Position.KEY_ALARM);
            LOGGER.info("ALARM DETECTED IN LOCATION REPORT - Device: {}, AlarmType: {}, Triggering image capture",
                    deviceSession.getDeviceId(), alarmType);
            sendImageCaptureRequest(channel, remoteAddress, id);
        }

        return position;
    }

    private List<Position> decodeLocationBatch(DeviceSession deviceSession, ByteBuf buf, TimeZone timeZone,
                                                Channel channel, SocketAddress remoteAddress, ByteBuf id, int index) {
        List<Position> positions = new ArrayList<>();

        int count = buf.readUnsignedShort();
        int type = buf.readUnsignedByte(); // 0: Normal, 1: Blind area

        for (int i = 0; i < count; i++) {
            int length = buf.readUnsignedShort();
            ByteBuf locationBuf = buf.readSlice(length);
            Position position = decodeLocationBasicInfo(deviceSession, locationBuf, timeZone);
            decodeLocationAdditionalInfo(position, locationBuf, channel, remoteAddress, id);
            if (position.hasAttribute(Position.KEY_ALARM)) {
                sendImageCaptureRequest(channel, remoteAddress, id);
            }
            positions.add(position);
        }
        return positions;
    }

    private String generateAuthCode(String deviceId) throws Exception {
        String secret = "asfdasdfeasdfve3435445"; // Store in config
        long timestamp = System.currentTimeMillis();
        String data = deviceId + timestamp + secret;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder authCode = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            authCode.append(String.format("%02X", hash[i] & 0xFF));
        }
        return authCode.toString();
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {
        ByteBuf buf = (ByteBuf) msg;
        if (buf.readableBytes() < 12) {
            return null;
        }
        int checksumIndex = buf.writerIndex() - 2;
        byte calculatedChecksum = (byte) Checksum.xor(
                buf.nioBuffer(1, checksumIndex - 1));
        byte receivedChecksum = buf.getByte(checksumIndex);
        if (calculatedChecksum != receivedChecksum) {
            // Invalid checksum - reject message
            return null;
        }
        delimiter = buf.readUnsignedByte();
        int type = buf.readUnsignedShort();
        int attribute = buf.readUnsignedShort();
        int bodyLength = attribute & 0x3FF; // Bits 0-9
        boolean isSubPackage = BitUtil.check(attribute, 13);
        int encryption = (attribute >> 10) & 0x07;

        if (isSubPackage) {
            int totalPackages = buf.readUnsignedShort();
            int packageNo = buf.readUnsignedShort();
        }
        if (encryption != 0) {
            return null;
        }

        ByteBuf id = buf.readSlice(6);
        String deviceId = decodeDeviceId(id);
        id.resetReaderIndex();
        int index = buf.readUnsignedShort();

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, deviceId);
        if (deviceSession == null && type != MSG_TERMINAL_REGISTER) {
            return null;
        }

        TimeZone timeZone = TimeZone.getTimeZone("GMT+8");

        // Section 7: Protocol classification - message processing
        switch (type) {
            case MSG_TERMINAL_REGISTER:
                // Section 8.5: Terminal registration response
                if (channel != null) {
                    ByteBuf response = Unpooled.buffer();
                    response.writeShort(index);
                    response.writeByte(RESULT_SUCCESS);
                    response.writeBytes(deviceId.getBytes(StandardCharsets.US_ASCII));
                    channel.writeAndFlush(new NetworkMessage(
                            formatMessage(delimiter, MSG_TERMINAL_REGISTER_RESPONSE, id, false, response),
                            remoteAddress));
                }

                ByteBuf response = Unpooled.buffer();
                response.writeShort(index);        // Response serial number
                response.writeByte(RESULT_SUCCESS); // Result: 0=success
                // Authentication code (STRING) - only if success
                String authCode = generateAuthCode(deviceId); // Generate unique auth code
                response.writeBytes(authCode.getBytes(StandardCharsets.US_ASCII));
                return null;

            case MSG_TERMINAL_AUTH:
                // Section 8.6: Terminal authentication - send general response
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return null;

            case MSG_TERMINAL_HEARTBEAT:
                // Section 8.3: Terminal heartbeat - send general response
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return null;

            case MSG_LOCATION_REPORT:
                // Section 8.13: Location information report
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeLocationReport(deviceSession, buf, timeZone, channel, remoteAddress, id, index);

            case MSG_LOCATION_BATCH_UPLOAD:
                // Section 8.26: Positioning data batch upload
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeLocationBatch(deviceSession, buf, timeZone, channel, remoteAddress, id, index);

            case MSG_TERMINAL_GENERAL_RESPONSE:
                // Section 8.1: Terminal general response - acknowledgment from device
                return null;

            case MSG_ALARM_ATTACHMENT_INFO:
                // T/JSATL12-2017 Section 4.6.2: Alarm attachment information message (0x1210)
                LOGGER.info("RECEIVED ALARM ATTACHMENT INFO (0x1210) - Device: {}", deviceSession.getUniqueId());
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                Position attachmentPos = decodeAlarmAttachmentInfo(deviceSession, buf);
                if (attachmentPos != null) {
                    LOGGER.info("ALARM ATTACHMENT INFO DECODED - AlarmId: {}, AttachmentCount: {}",
                            attachmentPos.getAttributes().get("alarmId"),
                            attachmentPos.getAttributes().get("attachmentCount"));
                }
                return attachmentPos;

            case MSG_VIDEO_LIVE_STREAM_RESPONSE:
                // JT/T 1078-2016 Section 5.1: Video live stream response (0x1001)
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeVideoLiveStreamResponse(deviceSession, buf);

            case MSG_VIDEO_PLAYBACK_RESPONSE:
                // JT/T 1078-2016: Video playback response (0x1201)
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeVideoPlaybackResponse(deviceSession, buf);

            case MSG_VIDEO_RESOURCE_LIST_RESPONSE:
                // JT/T 1078-2016: Video resource list response (0x1205)
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeVideoResourceListResponse(deviceSession, buf);

            case MSG_IMAGE_CAPTURE_RESPONSE:
                // Section 8.31: Image/video capture response (0x0805)
                LOGGER.info("RECEIVED IMAGE CAPTURE RESPONSE (0x0805) - Device: {}", deviceSession.getUniqueId());
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                Position capturePos = decodeImageCaptureResponse(deviceSession, buf);
                if (capturePos != null) {
                    LOGGER.info("IMAGE CAPTURE RESPONSE DECODED - Result: {}, MediaIds: {}, Count: {}",
                            capturePos.getString("imageCaptureStatus"),
                            capturePos.getString("mediaIds"),
                            capturePos.getInteger("mediaIdCount"));
                }
                return capturePos;

            case MSG_CHECK_TERMINAL_PARAMETER_RESPONSE:
                // Section 8.10: Check terminal parameter response (0x0104)
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeTerminalParameterResponse(deviceSession, buf);

            case MSG_CHECK_TERMINAL_ATTRIBUTE_RESPONSE:
                // Section 8.12: Check terminal attribute response (0x0107)
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeTerminalAttributeResponse(deviceSession, buf);

            case MSG_MULTIMEDIA_EVENT_INFO:
                // Section 8.27: Multimedia event information uploading (0x0800)
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeMultimediaEventInfo(deviceSession, buf);

            case MSG_MULTIMEDIA_DATA_UPLOAD:
                // Section 8.28: Multimedia data upload (0x0801)
                LOGGER.info("RECEIVED MULTIMEDIA DATA UPLOAD (0x0801) - Device: {}", deviceSession.getUniqueId());
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                Position uploadPos = decodeMultimediaDataUpload(deviceSession, buf, channel, remoteAddress, id);
                if (uploadPos != null) {
                    LOGGER.info("MULTIMEDIA DATA DECODED - MultimediaId: {}, Type: {}, PacketSize: {},"
                                    + " TotalReceived: {}",
                            uploadPos.getInteger("multimediaId"),
                            uploadPos.getString("multimediaType"),
                            uploadPos.getInteger("packetSize"),
                            uploadPos.getInteger("totalReceived"));

                    if ("multimediaUploadComplete".equals(uploadPos.getString("event"))) {
                        LOGGER.info("MULTIMEDIA FILE SAVED - Device: {}, MultimediaId: {}, Type: {}, File: {}",
                                deviceSession.getUniqueId(),
                                uploadPos.getInteger("multimediaId"),
                                uploadPos.getString("multimediaType"),
                                uploadPos.getString("multimediaFile"));
                    }
                }
                return uploadPos;

            case MSG_RETRIEVE_MULTIMEDIA_RESPONSE:
                // Section 8.33: Response of store multimedia data retrieves (0x0802)
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeMultimediaRetrieveResponse(deviceSession, buf);

            case MSG_FILE_INFO_UPLOAD:
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeFileInfoUpload(deviceSession, buf);

            case MSG_FILE_UPLOAD_COMPLETE:
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeFileUploadComplete(deviceSession, buf);
            default:
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return null;
        }
    }

    private Position decodeAlarmAttachmentInfo(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        int alarmId = buf.readUnsignedByte();           // Alarm serial number
        int alarmType = buf.readUnsignedByte();         // Alarm type
        int attachmentCount = buf.readUnsignedByte();   // Number of attachments
        position.set("alarmId", alarmId);
        position.set("alarmType", alarmType);
        position.set("attachmentCount", attachmentCount);
        position.set("event", "alarmAttachmentInfo");

        StringBuilder attachmentList = new StringBuilder();
        for (int i = 0; i < attachmentCount && buf.readableBytes() >= 3; i++) {
            int fileType = buf.readUnsignedByte();      // 0=image, 1=audio, 2=video, 3=text
            int fileSize = buf.readInt();                // File size in bytes
            int fileNameLength = buf.readUnsignedByte(); // File name length

            if (buf.readableBytes() >= fileNameLength) {
                String fileName = buf.readCharSequence(fileNameLength, StandardCharsets.UTF_8).toString();
                String fileTypeStr;
                switch (fileType) {
                    case 0: fileTypeStr = "image"; break;
                    case 1: fileTypeStr = "audio"; break;
                    case 2: fileTypeStr = "video"; break;
                    case 3: fileTypeStr = "text"; break;
                    default: fileTypeStr = "unknown"; break;
                }
                if (attachmentList.length() > 0) {
                    attachmentList.append(";");
                }
                attachmentList.append(fileTypeStr).append(":").append(fileName).append(":").append(fileSize);
                position.set("attachment" + i + "Type", fileTypeStr);
                position.set("attachment" + i + "Name", fileName);
                position.set("attachment" + i + "Size", fileSize);
            }
        }
        position.set("attachmentList", attachmentList.toString());
        position.setValid(false); // This is an event, not a location update
        position.setTime(new Date());
        return position;
    }

    private Position decodeVideoLiveStreamResponse(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        if (buf.readableBytes() >= 1) {
            int result = buf.readUnsignedByte(); // 0=success, 1=failure, 2=channel not supported
            position.set("liveStreamResult", result);
            String resultStr;
            switch (result) {
                case 0:
                    resultStr = "success";
                    position.set("event", "liveStreamStarted");
                    break;
                case 1:
                    resultStr = "failure";
                    position.set("event", "liveStreamFailed");
                    break;
                case 2:
                    resultStr = "channelNotSupported";
                    position.set("event", "liveStreamFailed");
                    break;
                default:
                    resultStr = "unknown";
                    position.set("event", "liveStreamUnknown");
                    break;
            }
            position.set("liveStreamStatus", resultStr);
        }
        position.setValid(false); // This is an event, not a location update
        position.setTime(new Date());
        return position;
    }
    private Position decodeVideoPlaybackResponse(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        if (buf.readableBytes() >= 1) {
            int result = buf.readUnsignedByte();
            position.set("playbackResult", result);
            position.set("event", result == 0 ? "playbackStarted" : "playbackFailed");
        }
        position.setValid(false);
        position.setTime(new Date());
        return position;
    }

    private Position decodeVideoResourceListResponse(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        if (buf.readableBytes() >= 3) {
            int sequenceNumber = buf.readUnsignedShort();
            int itemCount = buf.readUnsignedByte();
            position.set("sequenceNumber", sequenceNumber);
            position.set("videoResourceCount", itemCount);
            position.set("event", "videoResourceList");

            for (int i = 0; i < itemCount && buf.readableBytes() >= 28; i++) {
                position.set("video" + i + "Channel", buf.readUnsignedByte());
                buf.skipBytes(6);
                buf.skipBytes(6);
                position.set("video" + i + "AlarmType", buf.readLong());
                position.set("video" + i + "MediaType", buf.readUnsignedByte());
                position.set("video" + i + "StreamType", buf.readUnsignedByte());
                position.set("video" + i + "StorageType", buf.readUnsignedByte());
                position.set("video" + i + "Size", buf.readInt());
            }
        }
        position.setValid(false);
        position.setTime(new Date());
        return position;
    }

    private Position decodeImageCaptureResponse(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        if (buf.readableBytes() >= 2) {
            int result = buf.readUnsignedByte();     // 0=success, 1=failure, 2=channel not supported
            int mediaIdCount = buf.readUnsignedByte(); // Number of media IDs
            position.set("imageCaptureResult", result);
            position.set("mediaIdCount", mediaIdCount);
            String resultStr;
            switch (result) {
                case 0:
                    resultStr = "success";
                    position.set("event", "imageCaptureSuccess");
                    break;
                case 1:
                    resultStr = "failure";
                    position.set("event", "imageCaptureFailed");
                    break;
                case 2:
                    resultStr = "channelNotSupported";
                    position.set("event", "imageCaptureFailed");
                    break;
                default:
                    resultStr = "unknown";
                    position.set("event", "imageCaptureUnknown");
                    break;
            }
            position.set("imageCaptureStatus", resultStr);
            StringBuilder mediaIds = new StringBuilder();
            for (int i = 0; i < mediaIdCount && buf.readableBytes() >= 4; i++) {
                int mediaId = buf.readInt();
                if (mediaIds.length() > 0) {
                    mediaIds.append(",");
                }
                mediaIds.append(mediaId);
                position.set("mediaId" + i, mediaId);
            }
            if (mediaIds.length() > 0) {
                position.set("mediaIds", mediaIds.toString());
            }
        }
        position.setValid(false); // This is an event, not a location update
        position.setTime(new Date());
        return position;
    }

    private Position decodeTerminalParameterResponse(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        int responseSerial = buf.readUnsignedShort();
        int paramCount = buf.readUnsignedByte();
        position.set("responseSerial", responseSerial);
        position.set("parameterCount", paramCount);

        for (int i = 0; i < paramCount && buf.readableBytes() >= 5; i++) {
            int paramId = buf.readInt();           // DWORD
            int paramLength = buf.readUnsignedByte();

            if (buf.readableBytes() < paramLength) {
                break;
            }
            switch (paramId) {
                case 0x0001: // Heartbeat interval (DWORD)
                    if (paramLength == 4) {
                        position.set("param_heartbeat", buf.readInt());
                    }
                    break;
                case 0x0027: // Dormancy report interval (DWORD)
                    if (paramLength == 4) {
                        position.set("param_dormancyInterval", buf.readInt());
                    }
                    break;
                case 0x0029: // Default report interval (DWORD)
                    if (paramLength == 4) {
                        position.set("param_defaultInterval", buf.readInt());
                    }
                    break;
                case 0x0030: // Angle of inflection point (DWORD)
                    if (paramLength == 4) {
                        position.set("param_inflectionAngle", buf.readInt());
                    }
                    break;
                case 0x0050: // Alarm blocked field (DWORD)
                    if (paramLength == 4) {
                        position.set("param_alarmBlocked", buf.readInt());
                    }
                    break;
                case 0x0055: // Maximum speed (DWORD)
                    if (paramLength == 4) {
                        position.set("param_maxSpeed", buf.readInt());
                    }
                    break;
                case 0x0056: // Overspeed duration (DWORD)
                    if (paramLength == 4) {
                        position.set("param_overspeedDuration", buf.readInt());
                    }
                    break;
                case 0x0057: // Continuous driving time limit (DWORD)
                    if (paramLength == 4) {
                        position.set("param_continuousDrivingLimit", buf.readInt());
                    }
                    break;
                case 0x0058: // Accumulated driving time (DWORD)
                    if (paramLength == 4) {
                        position.set("param_accumulatedDrivingTime", buf.readInt());
                    }
                    break;
                case 0x0059: // Minimum rest time (DWORD)
                    if (paramLength == 4) {
                        position.set("param_minRestTime", buf.readInt());
                    }
                    break;
                case 0x005A: // Maximum parking time (DWORD)
                    if (paramLength == 4) {
                        position.set("param_maxParkingTime", buf.readInt());
                    }
                    break;
                case 0x005B: // Overspeed alarm difference (WORD)
                    if (paramLength == 2) {
                        position.set("param_overspeedDifference", buf.readUnsignedShort());
                    }
                    break;
                case 0x005C: // Fatigue driving difference (WORD)
                    if (paramLength == 2) {
                        position.set("param_fatigueDifference", buf.readUnsignedShort());
                    }
                    break;
                default:
                    buf.skipBytes(paramLength); // Unknown parameter
                    break;
            }
        }
        position.set("event", "parameterResponse");
        position.setValid(false);
        position.setTime(new Date());
        return position;
    }
    private Position decodeTerminalAttributeResponse(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        if (buf.readableBytes() < 55) {
            return null;
        }
        int terminalType = buf.readUnsignedShort();
        byte[] manufacturerId = new byte[5];
        buf.readBytes(manufacturerId);
        byte[] terminalModel = new byte[20];
        buf.readBytes(terminalModel);
        byte[] terminalId = new byte[7];
        buf.readBytes(terminalId);
        byte[] iccid = new byte[10];
        buf.readBytes(iccid);
        int hwVersionLen = buf.readUnsignedByte();
        String hwVersion = buf.readCharSequence(hwVersionLen, StandardCharsets.UTF_8).toString();

        int fwVersionLen = buf.readUnsignedByte();
        String fwVersion = buf.readCharSequence(fwVersionLen, StandardCharsets.UTF_8).toString();
        int gnssAttr = buf.readUnsignedByte();
        int commAttr = buf.readUnsignedByte();
        position.set("terminalType", terminalType);
        position.set("manufacturer", new String(manufacturerId, StandardCharsets.UTF_8).trim());
        position.set("terminalModel", new String(terminalModel, StandardCharsets.UTF_8).trim());
        position.set("terminalId", new String(terminalId, StandardCharsets.UTF_8).trim());
        position.set(Position.KEY_ICCID, ByteBufUtil.hexDump(iccid).replaceAll("f", ""));
        position.set("hwVersion", hwVersion);
        position.set("fwVersion", fwVersion);
        position.set("gnssModules", gnssAttr);
        position.set("commModules", commAttr);
        position.set("event", "terminalAttributes");
        position.setValid(false);
        position.setTime(new Date());
        return position;
    }

    private Position decodeMultimediaEventInfo(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        if (buf.readableBytes() < 8) {
            return null;
        }
        int multimediaId = buf.readInt();
        int multimediaType = buf.readUnsignedByte(); // 0=Image, 1=Audio, 2=Video
        int formatCode = buf.readUnsignedByte();     // 0=JPEG, 1=TIF, 2=MP3, 3=WAV, 4=WMV
        int eventCode = buf.readUnsignedByte();      // 0-7 per Table 80
        int channelId = buf.readUnsignedByte();
        position.set("multimediaId", multimediaId);
        position.set("multimediaType", multimediaType == 0 ? "image" : (multimediaType == 1 ? "audio" : "video"));
        position.set("multimediaFormat", formatCode);
        position.set("eventCode", eventCode);
        position.set("channelId", channelId);
        String eventDesc;
        switch (eventCode) {
            case 0:
                eventDesc = "platformCommand";
                break;
            case 1:
                eventDesc = "timedAction";
                break;
            case 2:
                eventDesc = "robberyAlarm";
                break;
            case 3:
                eventDesc = "collisionAlarm";
                break;
            case 4:
                eventDesc = "doorOpen";
                break;
            case 5:
                eventDesc = "doorClose";
                break;
            case 6:
                eventDesc = "speedIncrease";
                break;
            case 7:
                eventDesc = "fixedDistance";
                break;
            default:
                eventDesc = "unknown";
                break;
        }
        position.set("eventDescription", eventDesc);
        position.set("event", "multimediaEvent");
        position.setValid(false);
        position.setTime(new Date());
        return position;
    }
    private static final class MultimediaFile {
        private int multimediaId;
        private int totalSize;
        private int receivedSize;
        private ByteBuf data;
        private int multimediaType;
        private int formatCode;
        private String deviceId;
    }

    private final Map<String, MultimediaFile> multimediaFiles = new HashMap<>();

    private Position decodeMultimediaDataUpload(DeviceSession deviceSession, ByteBuf buf,
                                                Channel channel, SocketAddress remoteAddress, ByteBuf id) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        if (buf.readableBytes() < 36) {
            return null;
        }
        int multimediaId = buf.readInt();
        int multimediaType = buf.readUnsignedByte(); // 0=Image, 1=Audio, 2=Video
        int formatCode = buf.readUnsignedByte();     // 0=JPEG, 1=TIF, 2=MP3, 3=WAV, 4=WMV
        int eventCode = buf.readUnsignedByte();
        int channelId = buf.readUnsignedByte();
        LOGGER.debug("Multimedia Upload Details - ID: {}, Type: {}, Format: {}, Event: {}, Channel: {}",
                multimediaId, multimediaType, formatCode, eventCode, channelId);
        buf.skipBytes(28);
        byte[] packetData = new byte[buf.readableBytes()];
        buf.readBytes(packetData);
        LOGGER.debug("Multimedia Packet Received - ID: {}, PacketSize: {} bytes",
                multimediaId, packetData.length);
        String fileKey = deviceSession.getUniqueId() + "_" + multimediaId;
        MultimediaFile file = multimediaFiles.get(fileKey);

        if (file == null) {
            LOGGER.info("NEW MULTIMEDIA FILE STARTED - Device: {}, MultimediaId: {}, Type: {}",
                    deviceSession.getUniqueId(), multimediaId, multimediaType);
            file = new MultimediaFile();
            file.multimediaId = multimediaId;
            file.deviceId = deviceSession.getUniqueId();
            file.multimediaType = multimediaType;
            file.formatCode = formatCode;
            file.data = Unpooled.buffer();
            file.receivedSize = 0;
            multimediaFiles.put(fileKey, file);
        }
        file.data.writeBytes(packetData);
        file.receivedSize += packetData.length;
        position.set("multimediaId", multimediaId);
        position.set("multimediaType", multimediaType);
        position.set("packetSize", packetData.length);
        position.set("totalReceived", file.receivedSize);
        position.set("event", "multimediaDataReceived");
        if (buf.readableBytes() == 0) {
            try {
                // Determine file extension
                String extension;
                switch (formatCode) {
                    case 0:
                        extension = "jpg";
                        break;
                    case 1:
                        extension = "tif";
                        break;
                    case 2:
                        extension = "mp3";
                        break;
                    case 3:
                        extension = "wav";
                        break;
                    case 4:
                        extension = "wmv";
                        break;
                    default:
                        extension = "bin";
                        break;
                }
                LOGGER.info("SAVING MULTIMEDIA FILE - Device: {}, MultimediaId: {}, Type: {}, Size: {} bytes,"
                                + " Extension: {}",
                        file.deviceId, multimediaId, multimediaType, file.receivedSize, extension);
                // Save file using Traccar's storage system (like DualcamProtocolDecoder)
                String filePath = writeMediaFile(file.deviceId, file.data, extension);
                LOGGER.info("MULTIMEDIA FILE SAVED SUCCESSFULLY - Path: {}, Size: {} bytes",
                        filePath, file.receivedSize);
                if (multimediaType == 0) {
                    position.set(Position.KEY_IMAGE, filePath);
                } else if (multimediaType == 1) {
                    position.set(Position.KEY_AUDIO, filePath);
                } else if (multimediaType == 2) {
                    position.set(Position.KEY_VIDEO, filePath);
                }
                position.set("multimediaFile", filePath);
                position.set("event", "multimediaUploadComplete");
            } catch (Exception e) {
                LOGGER.error("FAILED TO SAVE MULTIMEDIA FILE - Device: {}, MultimediaId: {}, Error: {}",
                        file.deviceId, multimediaId, e.getMessage(), e);
            } finally {
                file.data.release();
                multimediaFiles.remove(fileKey);
            }
            if (channel != null) {
                LOGGER.debug("Sending multimedia upload response (0x8800) - MultimediaId: {}", multimediaId);
                ByteBuf response = Unpooled.buffer();
                response.writeInt(multimediaId);
                // No additional fields = all packets received
                channel.writeAndFlush(new NetworkMessage(
                        formatMessage(delimiter, MSG_MULTIMEDIA_UPLOAD_RESPONSE, id, false, response),
                        remoteAddress));
            }
        }
        position.setValid(false);
        position.setTime(new Date());
        return position;
    }

    private Position decodeMultimediaRetrieveResponse(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        if (buf.readableBytes() < 4) {
            return null;
        }
        int responseSerial = buf.readUnsignedShort();
        int totalCount = buf.readUnsignedShort();
        position.set("responseSerial", responseSerial);
        position.set("multimediaCount", totalCount);
        int itemCount = 0;
        while (buf.readableBytes() >= 35) { // Minimum item size: 4+1+1+1+28
            int multimediaId = buf.readInt();
            int multimediaType = buf.readUnsignedByte(); // 0=Image, 1=Audio, 2=Video
            int channelId = buf.readUnsignedByte();
            int eventCode = buf.readUnsignedByte();
            buf.skipBytes(28);
            position.set("media" + itemCount + "Id", multimediaId);
            position.set("media" + itemCount + "Type", multimediaType == 0 ? "image"
                    : (multimediaType == 1 ? "audio" : "video"));
            position.set("media" + itemCount + "Channel", channelId);
            position.set("media" + itemCount + "Event", eventCode);

            itemCount++;
        }
        position.set("retrievedItems", itemCount);
        position.set("event", "multimediaRetrieveResponse");
        position.setValid(false);
        position.setTime(new Date());
        return position;
    }

    private Position decodeFileInfoUpload(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        if (buf.readableBytes() < 2) {
            return null;
        }
        int fileNameLen = buf.readUnsignedByte();
        if (buf.readableBytes() < fileNameLen + 5) {
            return null;
        }
        String fileName = buf.readCharSequence(fileNameLen, StandardCharsets.UTF_8).toString();
        int fileType = buf.readUnsignedByte(); // 0=image, 1=audio, 2=video, 3=text
        int fileSize = buf.readInt();
        position.set("fileName", fileName);
        position.set("fileType", fileType == 0 ? "image" : (fileType == 1 ? "audio"
                : (fileType == 2 ? "video" : "text")));
        position.set("fileSize", fileSize);
        position.set("event", "fileInfoUpload");
        position.setValid(false);
        position.setTime(new Date());
        return position;
    }
    private Position decodeFileUploadComplete(DeviceSession deviceSession, ByteBuf buf) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        if (buf.readableBytes() >= 1) {
            int result = buf.readUnsignedByte(); // 0=success, 1=failure
            position.set("uploadResult", result);
            position.set("event", result == 0 ? "fileUploadSuccess" : "fileUploadFailed");
        }

        position.setValid(false);
        position.setTime(new Date());
        return position;
    }

}
