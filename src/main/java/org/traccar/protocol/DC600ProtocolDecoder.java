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
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.BcdUtil;
import org.traccar.helper.BitUtil;
import org.traccar.helper.Checksum;
import org.traccar.helper.DateBuilder;
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * DC600 GPRS Communication Protocol Decoder
 * Based on: DC600 GPRS Communication protocol v1.0 (2024-05-15)
 * Ministry of transport of the People's Republic of China (January 2013)
 */
public class DC600ProtocolDecoder extends BaseProtocolDecoder {

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

    private static final int RESULT_SUCCESS = 0;
    private static final int RESULT_FAILURE = 1;
    private static final int RESULT_INCORRECT_INFO = 2;
    private static final int RESULT_NOT_SUPPORTED = 3;

    // Section 4.4.2: Flag bit
    private int delimiter = 0x7e;

    /**
     * Section 4.4.3: Format message according to specification
     */
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

    /**
     * Section 8.2: Platform general response
     */
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

    /**
     * Section 4.4.3: Decode BCD phone number from header
     */
    private String decodeDeviceId(ByteBuf id) {
        StringBuilder deviceId = new StringBuilder();
        for (int i = 0; i < id.readableBytes(); i++) {
            int b = id.getUnsignedByte(i);
            deviceId.append((b & 0xF0) >> 4).append(b & 0x0F);
        }
        return deviceId.toString();
    }

    /**
     * Section 8.13: Read BCD date (Table 23)
     */
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

    /**
     * Section 8.13: Decode alarm signs (Table 24)
     */
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

    /**
     * Section 8.13: Decode location basic information (Table 23)
     */
    private Position decodeLocationBasicInfo(DeviceSession deviceSession, ByteBuf buf, TimeZone timeZone) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        // Alarm sign (DWORD)
        long alarmSign = buf.readUnsignedInt();
        decodeAlarmSigns(position, alarmSign);

        // Status (DWORD)
        long status = buf.readUnsignedInt();
        position.setValid(BitUtil.check(status, 1));
        position.set(Position.KEY_IGNITION, BitUtil.check(status, 0));

        // Latitude (DWORD) - unit is degree * 10^6
        double latitude = buf.readUnsignedInt() / 1000000.0;
        if (BitUtil.check(status, 2)) {
            latitude = -latitude; // South latitude
        }
        position.setLatitude(latitude);

        // Longitude (DWORD) - unit is degree * 10^6
        double longitude = buf.readUnsignedInt() / 1000000.0;
        if (BitUtil.check(status, 3)) {
            longitude = -longitude; // West longitude
        }
        position.setLongitude(longitude);

        // Altitude (WORD) - meters
        position.setAltitude(buf.readUnsignedShort());

        // Speed (WORD) - 1/10 km/h
        position.setSpeed(buf.readUnsignedShort() * 0.1 * 0.539957); // Convert to knots

        // Direction (WORD) - 0-359 degrees
        position.setCourse(buf.readUnsignedShort());

        // Time (BCD[6]) - YY-MM-DD-hh-mm-ss (GMT+8)
        position.setTime(readDate(buf, timeZone));

        return position;
    }

    /**
     * Section 8.13: Decode location additional information (Table 26, 27)
     */
    private void decodeLocationAdditionalInfo(Position position, ByteBuf buf) {
        while (buf.readableBytes() > 2) {
            int id = buf.readUnsignedByte();
            int length = buf.readUnsignedByte();

            if (buf.readableBytes() < length) {
                break;
            }

            switch (id) {
                case 0x01: // Mileage (DWORD, 1/10km)
                    position.set(Position.KEY_ODOMETER, buf.readUnsignedInt() * 100);
                    break;
                case 0x30: // Signal strength
                    position.set(Position.KEY_RSSI, buf.readUnsignedByte());
                    break;
                case 0x31: // Satellite count
                    position.set(Position.KEY_SATELLITES, buf.readUnsignedByte());
                    break;
                default:
                    buf.skipBytes(length);
                    break;
            }
        }
    }

    /**
     * Section 8.13: Location information report (0x0200)
     */
    private Position decodeLocationReport(DeviceSession deviceSession, ByteBuf buf, TimeZone timeZone) {
        Position position = decodeLocationBasicInfo(deviceSession, buf, timeZone);
        decodeLocationAdditionalInfo(position, buf);
        return position;
    }

    /**
     * Section 8.26: Positioning data batch upload (0x0704)
     */
    private List<Position> decodeLocationBatch(DeviceSession deviceSession, ByteBuf buf, TimeZone timeZone) {
        List<Position> positions = new ArrayList<>();

        int count = buf.readUnsignedShort();
        int type = buf.readUnsignedByte(); // 0: Normal, 1: Blind area

        for (int i = 0; i < count; i++) {
            int length = buf.readUnsignedShort();
            ByteBuf locationBuf = buf.readSlice(length);
            Position position = decodeLocationBasicInfo(deviceSession, locationBuf, timeZone);
            decodeLocationAdditionalInfo(position, locationBuf);
            positions.add(position);
        }

        return positions;
    }


    private String generateAuthCode(String deviceId) throws Exception {
        // Create hash from device ID + timestamp + secret
        String secret = "asfdasdfeasdfve3435445"; // Store in config
        long timestamp = System.currentTimeMillis();
        String data = deviceId + timestamp + secret;

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));

        // Take first 8 bytes and convert to hex (16 characters)
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

        // Read everything except last 2 bytes (checksum + delimiter)
        int checksumIndex = buf.writerIndex() - 2;

        // Calculate checksum on header + body (after delimiter, before checksum)
        byte calculatedChecksum = (byte) Checksum.xor(
                buf.nioBuffer(1, checksumIndex - 1));
        byte receivedChecksum = buf.getByte(checksumIndex);

        if (calculatedChecksum != receivedChecksum) {
            // Invalid checksum - reject message
            return null;
        }


        // Section 4.4.2: Flag bit
        delimiter = buf.readUnsignedByte();

        // Section 4.4.3: Header
        int type = buf.readUnsignedShort();
        int attribute = buf.readUnsignedShort();
        int bodyLength = attribute & 0x3FF; // Bits 0-9
        boolean isSubPackage = BitUtil.check(attribute, 13);
        int encryption = (attribute >> 10) & 0x07;

        if (isSubPackage) {
            int totalPackages = buf.readUnsignedShort();
            int packageNo = buf.readUnsignedShort();
            // TODO: Reassemble multi-packet messages
            // For now, you could skip or log unsupported multi-packet
        }

        if (encryption != 0) {
            // Handle RSA encryption if encryption == 1 (bit 10 set)
            // For now, reject encrypted messages
        }

        // Terminal phone number (BCD[6])
        ByteBuf id = buf.readSlice(6);
        String deviceId = decodeDeviceId(id);
        id.resetReaderIndex();

        // Message serial number (WORD)
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
                return decodeLocationReport(deviceSession, buf, timeZone);

            case MSG_LOCATION_BATCH_UPLOAD:
                // Section 8.26: Positioning data batch upload
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeLocationBatch(deviceSession, buf, timeZone);

            case MSG_TERMINAL_GENERAL_RESPONSE:
                // Section 8.1: Terminal general response - acknowledgment from device
                return null;

            default:
                // Unsupported message type
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return null;
        }
    }

}
