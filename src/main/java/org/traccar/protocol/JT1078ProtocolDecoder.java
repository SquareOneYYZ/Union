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
import org.traccar.helper.BitUtil;
import org.traccar.helper.Checksum;
import org.traccar.session.DeviceSession;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * JT1078 Protocol Decoder
 *
 * Handles media-related messages from JT808-compatible devices:
 * - 0x1210: Alarm Attachment Info (response to 0x9208)
 * - 0x1211: File Info Upload
 * - 0x1212: File Upload Complete
 *
 * This decoder primarily logs media file information and acknowledges messages.
 * Actual file upload happens via separate FTP/HTTP connection or data packets.
 */
public class JT1078ProtocolDecoder extends BaseProtocolDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(JT1078ProtocolDecoder.class);

    // Message types
    public static final int MSG_ALARM_ATTACHMENT_INFO = 0x1210;     // Terminal → Platform
    public static final int MSG_FILE_INFO_UPLOAD = 0x1211;          // Terminal → Platform
    public static final int MSG_FILE_UPLOAD_COMPLETE = 0x1212;      // Terminal → Platform
    public static final int MSG_GENERAL_RESPONSE = 0x8001;          // Platform → Terminal

    // Response codes
    public static final int RESULT_SUCCESS = 0x00;

    public JT1078ProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private String decodeDeviceId(ByteBuf buf) {
        StringBuilder deviceId = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            deviceId.append(String.format("%02X", buf.readUnsignedByte()));
        }
        return deviceId.toString();
    }

    private ByteBuf formatMessage(int delimiter, int type, ByteBuf id, ByteBuf data) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(delimiter);
        buf.writeShort(type);

        int bodyLength = data.readableBytes();
        buf.writeShort(bodyLength);  // Message attributes (just length for now)

        id.resetReaderIndex();
        buf.writeBytes(id);  // Device ID (6 bytes)
        buf.writeShort(0);   // Message sequence number (0 for responses)

        buf.writeBytes(data);

        // Calculate XOR checksum (from message type to end of data)
        byte checksum = (byte) Checksum.xor(buf.nioBuffer(1, buf.readableBytes() - 1));
        buf.writeByte(checksum);
        buf.writeByte(delimiter);

        return buf;
    }

    private void sendGeneralResponse(Channel channel, SocketAddress remoteAddress,
                                     ByteBuf id, int type, int index, int result) {
        if (channel != null) {
            ByteBuf data = Unpooled.buffer();
            data.writeShort(index);  // Response serial number
            data.writeShort(type);   // Message ID being acknowledged
            data.writeByte(result);  // Result code

            ByteBuf response = formatMessage(0x7e, MSG_GENERAL_RESPONSE, id, data);
            channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
        }
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {
        ByteBuf buf = (ByteBuf) msg;

        if (buf.readableBytes() < 12) {
            return null;
        }

        // Verify checksum
        int checksumIndex = buf.writerIndex() - 2;
        byte calculatedChecksum = (byte) Checksum.xor(buf.nioBuffer(1, checksumIndex - 1));
        byte receivedChecksum = buf.getByte(checksumIndex);

        if (calculatedChecksum != receivedChecksum) {
            LOGGER.warn("JT1078 checksum mismatch - calculated: 0x{}, received: 0x{}",
                    Integer.toHexString(calculatedChecksum & 0xFF),
                    Integer.toHexString(receivedChecksum & 0xFF));
            return null;
        }

        // Parse JT808 header
        int delimiter = buf.readUnsignedByte();
        int type = buf.readUnsignedShort();
        int attribute = buf.readUnsignedShort();
        int bodyLength = attribute & 0x3FF;  // Bits 0-9
        boolean isSubPackage = BitUtil.check(attribute, 13);
        int encryption = (attribute >> 10) & 0x07;

        if (encryption != 0) {
            LOGGER.warn("JT1078 encrypted message not supported - encryption: {}", encryption);
            return null;
        }

        // Handle sub-packages (not implemented yet)
        if (isSubPackage) {
            buf.skipBytes(4);  // Skip totalPackages (2) and packageNo (2)
        }

        ByteBuf id = buf.readSlice(6);
        String deviceId = decodeDeviceId(id);
        id.resetReaderIndex();

        int index = buf.readUnsignedShort();

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, deviceId);
        if (deviceSession == null) {
            LOGGER.warn("JT1078 unknown device - deviceId: {}", deviceId);
            return null;
        }

        LOGGER.info("JT1078 message received - Device: {}, Type: 0x{}, Length: {}, Seq: {}",
                deviceSession.getDeviceId(),
                Integer.toHexString(type),
                bodyLength,
                index);

        // Process message based on type
        switch (type) {
            case MSG_ALARM_ATTACHMENT_INFO:
                return decodeAlarmAttachmentInfo(channel, remoteAddress, id, index, buf, deviceSession);

            case MSG_FILE_INFO_UPLOAD:
                return decodeFileInfoUpload(channel, remoteAddress, id, index, buf, deviceSession);

            case MSG_FILE_UPLOAD_COMPLETE:
                return decodeFileUploadComplete(channel, remoteAddress, id, index, buf, deviceSession);

            default:
                LOGGER.warn("JT1078 unknown message type: 0x{}", Integer.toHexString(type));
                return null;
        }
    }

    private Object decodeAlarmAttachmentInfo(Channel channel, SocketAddress remoteAddress,
                                            ByteBuf id, int index, ByteBuf buf,
                                            DeviceSession deviceSession) {
        LOGGER.info("RECEIVED ALARM ATTACHMENT INFO (0x1210) - Device: {}, Seq: {}",
                deviceSession.getDeviceId(), index);

        // Parse 0x1210 message
        // Format:
        // - Server IP Length (1 byte)
        // - Server IP (variable, ASCII)
        // - TCP Port (2 bytes)
        // - UDP Port (2 bytes)
        // - Alarm Serial Number (4 bytes) or Alarm Flag (16 bytes)
        // - Alarm Number (32 bytes, ASCII)
        // - Reserved (16 bytes)
        // - Attachment Count (1 byte)
        // - File Info List (variable)

        int serverIpLength = buf.readUnsignedByte();
        String serverIp = buf.readCharSequence(serverIpLength, StandardCharsets.US_ASCII).toString();
        int tcpPort = buf.readUnsignedShort();
        int udpPort = buf.readUnsignedShort();

        LOGGER.info("0x1210 Attachment Server - IP: {}, TCP: {}, UDP: {}",
                serverIp, tcpPort, udpPort);

        // Read alarm identifier (could be 4-byte serial or 16-byte flag)
        ByteBuf alarmData = buf.readSlice(16);
        String alarmFlag = ByteBufUtil.hexDump(alarmData);
        LOGGER.info("0x1210 Alarm Flag: {}", alarmFlag);

        // Read alarm number (32 bytes)
        byte[] alarmNumberBytes = new byte[32];
        buf.readBytes(alarmNumberBytes);
        String alarmNumber = new String(alarmNumberBytes, StandardCharsets.US_ASCII).trim();
        LOGGER.info("0x1210 Alarm Number: {}", alarmNumber);

        // Skip reserved (16 bytes)
        buf.skipBytes(16);

        // Read attachment count
        int attachmentCount = buf.readUnsignedByte();
        LOGGER.info("0x1210 Attachment Count: {}", attachmentCount);

        // Parse file info list
        for (int i = 0; i < attachmentCount && buf.readableBytes() >= 28; i++) {
            int fileNameLength = buf.readUnsignedByte();
            String fileName = buf.readCharSequence(fileNameLength, StandardCharsets.US_ASCII).toString();
            long fileSize = buf.readUnsignedInt();

            LOGGER.info("0x1210 File #{}: Name: {}, Size: {} bytes",
                    i + 1, fileName, fileSize);
        }

        // Send acknowledgment (0x8001)
        sendGeneralResponse(channel, remoteAddress, id, MSG_ALARM_ATTACHMENT_INFO, index, RESULT_SUCCESS);

        LOGGER.info("0x1210 PROCESSED - Device: {}, AlarmNumber: {}, Files: {}",
                deviceSession.getDeviceId(), alarmNumber, attachmentCount);

        // TODO: Store file info in database for correlation with alarm events
        // TODO: Initiate FTP/HTTP download if configured
        // TODO: Create media entries linked to alarm events

        return null;  // JT1078 doesn't create position objects
    }

    private Object decodeFileInfoUpload(Channel channel, SocketAddress remoteAddress,
                                       ByteBuf id, int index, ByteBuf buf,
                                       DeviceSession deviceSession) {
        LOGGER.info("RECEIVED FILE INFO UPLOAD (0x1211) - Device: {}, Seq: {}",
                deviceSession.getDeviceId(), index);

        // TODO: Parse file metadata
        // Send acknowledgment
        sendGeneralResponse(channel, remoteAddress, id, MSG_FILE_INFO_UPLOAD, index, RESULT_SUCCESS);

        return null;
    }

    private Object decodeFileUploadComplete(Channel channel, SocketAddress remoteAddress,
                                           ByteBuf id, int index, ByteBuf buf,
                                           DeviceSession deviceSession) {
        LOGGER.info("RECEIVED FILE UPLOAD COMPLETE (0x1212) - Device: {}, Seq: {}",
                deviceSession.getDeviceId(), index);

        // TODO: Verify file integrity, update database
        // Send acknowledgment
        sendGeneralResponse(channel, remoteAddress, id, MSG_FILE_UPLOAD_COMPLETE, index, RESULT_SUCCESS);

        return null;
    }

}
