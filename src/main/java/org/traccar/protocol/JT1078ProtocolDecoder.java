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
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * JT1078 Protocol Decoder
 *
 * Handles multimedia data upload from JT808/JT1078-compatible devices.
 * Implements multi-packet file transfer similar to DualcamProtocolDecoder.
 *
 * Supported message types:
 * - 0x0801: Multimedia Data Upload (Terminal → Platform) - NEW: Handles actual media data
 * - 0x1210: Alarm Attachment Info (Terminal → Platform) - Response to 0x9208
 * - 0x1211: File Info Upload (Terminal → Platform)
 * - 0x1212: File Upload Complete (Terminal → Platform) - NEW: Completes transfer and writes file
 *
 * Media transfer flow:
 * 1. Device sends 0x0801 packets with multimedia data chunks
 * 2. Server accumulates data in MediaTransfer state
 * 3. Device sends 0x1212 when upload complete
 * 4. Server writes complete media file to storage
 * 5. Position object created with media reference
 *
 * This implementation follows the pattern established by DualcamProtocolDecoder
 * for handling multi-packet file transfers over TCP connections.
 */
public class JT1078ProtocolDecoder extends BaseProtocolDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(JT1078ProtocolDecoder.class);

    // Message types
    public static final int MSG_MULTIMEDIA_DATA_UPLOAD = 0x0801;    // Terminal → Platform
    public static final int MSG_ALARM_ATTACHMENT_INFO = 0x1210;     // Terminal → Platform
    public static final int MSG_FILE_INFO_UPLOAD = 0x1211;          // Terminal → Platform
    public static final int MSG_FILE_UPLOAD_COMPLETE = 0x1212;      // Terminal → Platform
    public static final int MSG_GENERAL_RESPONSE = 0x8001;          // Platform → Terminal

    // Response codes
    public static final int RESULT_SUCCESS = 0x00;

    // Media transfer state tracking
    private static class MediaTransfer {
        private long multimediaId;
        private int totalPackets;
        private int receivedPackets;
        private int mediaType;          // 0=image, 1=audio, 2=video
        private int mediaFormat;        // 0=JPEG, 1=TIF, 2=MP3, 3=WAV, 4=WMV
        private int eventCode;
        private ByteBuf data;
        private String deviceId;

        MediaTransfer(long multimediaId, int mediaType, int mediaFormat, int eventCode, String deviceId) {
            this.multimediaId = multimediaId;
            this.mediaType = mediaType;
            this.mediaFormat = mediaFormat;
            this.eventCode = eventCode;
            this.deviceId = deviceId;
            this.data = Unpooled.buffer();
            this.receivedPackets = 0;
        }

        long getMultimediaId() {
            return multimediaId;
        }

        int getTotalPackets() {
            return totalPackets;
        }

        int getReceivedPackets() {
            return receivedPackets;
        }

        void incrementReceivedPackets() {
            receivedPackets++;
        }

        int getMediaType() {
            return mediaType;
        }

        int getMediaFormat() {
            return mediaFormat;
        }

        int getEventCode() {
            return eventCode;
        }

        ByteBuf getData() {
            return data;
        }

        String getDeviceId() {
            return deviceId;
        }

        void release() {
            if (data != null) {
                data.release();
                data = null;
            }
        }
    }

    // Active transfers per device
    private final Map<Long, MediaTransfer> activeTransfers = new HashMap<>();

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
            LOGGER.warn("JT1078 message too short: {} bytes (minimum 12)", buf.readableBytes());
            return null;
        }

        // LOG RAW MESSAGE for debugging
        int startReaderIndex = buf.readerIndex();
        byte[] rawMessage = new byte[buf.readableBytes()];
        buf.getBytes(startReaderIndex, rawMessage);
        LOGGER.info("=".repeat(70));
        LOGGER.info("JT1078 RAW MESSAGE ({} bytes): {}", rawMessage.length, ByteBufUtil.hexDump(rawMessage));
        LOGGER.info("=".repeat(70));

        // Verify checksum
        int checksumIndex = buf.writerIndex() - 2;
        byte calculatedChecksum = (byte) Checksum.xor(buf.nioBuffer(1, checksumIndex - 1));
        byte receivedChecksum = buf.getByte(checksumIndex);

        if (calculatedChecksum != receivedChecksum) {
            LOGGER.warn("JT1078 CHECKSUM MISMATCH - calculated: 0x{}, received: 0x{}",
                    Integer.toHexString(calculatedChecksum & 0xFF),
                    Integer.toHexString(receivedChecksum & 0xFF));
            LOGGER.warn("  Message may be corrupted - discarding");
            return null;
        }

        LOGGER.info("JT1078 Checksum verified: 0x{}", Integer.toHexString(receivedChecksum & 0xFF));

        // Parse JT808 header
        int delimiter = buf.readUnsignedByte();
        int type = buf.readUnsignedShort();
        int attribute = buf.readUnsignedShort();
        int bodyLength = attribute & 0x3FF;  // Bits 0-9
        boolean isSubPackage = BitUtil.check(attribute, 13);
        int encryption = (attribute >> 10) & 0x07;

        LOGGER.info("JT1078 HEADER PARSED:");
        LOGGER.info("  Delimiter: 0x{}", Integer.toHexString(delimiter));
        LOGGER.info("  Message Type: 0x{}", Integer.toHexString(type));
        LOGGER.info("  Attribute: 0x{} (binary: {})",
                Integer.toHexString(attribute),
                String.format("%16s", Integer.toBinaryString(attribute)).replace(' ', '0'));
        LOGGER.info("  Body Length: {} bytes", bodyLength);
        LOGGER.info("  Is Sub-package: {}", isSubPackage);
        LOGGER.info("  Encryption: {} (0=none)", encryption);

        if (encryption != 0) {
            LOGGER.warn("JT1078 ENCRYPTED MESSAGE NOT SUPPORTED - encryption: {}", encryption);
            LOGGER.warn("  Cannot process encrypted messages - discarding");
            return null;
        }

        // Handle sub-packages
        int totalPackages = 0;
        int packageNo = 0;
        if (isSubPackage) {
            totalPackages = buf.readUnsignedShort();
            packageNo = buf.readUnsignedShort();
            LOGGER.info("  Sub-package info: {} of {}", packageNo, totalPackages);
        }

        ByteBuf id = buf.readSlice(6);
        String deviceId = decodeDeviceId(id);
        id.resetReaderIndex();

        int index = buf.readUnsignedShort();

        LOGGER.info("  Device ID: {}", deviceId);
        LOGGER.info("  Sequence Number: {}", index);

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, deviceId);
        if (deviceSession == null) {
            LOGGER.warn("JT1078 UNKNOWN DEVICE - deviceId: {}", deviceId);
            LOGGER.warn("  Device not registered - check device configuration");
            return null;
        }

        LOGGER.info("JT1078 MESSAGE IDENTIFIED:");
        LOGGER.info("  Device: {} (ID: {})", deviceSession.getDeviceId(), deviceId);
        LOGGER.info("  Type: 0x{}", Integer.toHexString(type));
        LOGGER.info("  Length: {} bytes", bodyLength);
        LOGGER.info("  Seq: {}", index);

        // Process message based on type
        switch (type) {
            case MSG_MULTIMEDIA_DATA_UPLOAD:
                return decodeMultimediaDataUpload(channel, remoteAddress, id, index, buf, deviceSession, bodyLength);

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

    private String getMediaFileExtension(int mediaType, int mediaFormat) {
        // Media type: 0=image, 1=audio, 2=video
        // Media format: 0=JPEG, 1=TIF, 2=MP3, 3=WAV, 4=WMV
        if (mediaType == 0) {
            // Image
            return mediaFormat == 1 ? "tif" : "jpg";
        } else if (mediaType == 1) {
            // Audio
            return mediaFormat == 3 ? "wav" : "mp3";
        } else {
            // Video
            return "wmv";
        }
    }

    private String getMediaTypeName(int mediaType) {
        switch (mediaType) {
            case 0: return "Image";
            case 1: return "Audio";
            case 2: return "Video";
            default: return "Unknown(" + mediaType + ")";
        }
    }

    private String getMediaFormatName(int mediaFormat) {
        switch (mediaFormat) {
            case 0: return "JPEG";
            case 1: return "TIF";
            case 2: return "MP3";
            case 3: return "WAV";
            case 4: return "WMV";
            default: return "Unknown(" + mediaFormat + ")";
        }
    }

    private void logActiveTransfers() {
        if (activeTransfers.isEmpty()) {
            LOGGER.info("  Active Transfers: NONE");
        } else {
            LOGGER.info("  Active Transfers: {} in progress", activeTransfers.size());
            for (Map.Entry<Long, MediaTransfer> entry : activeTransfers.entrySet()) {
                MediaTransfer t = entry.getValue();
                LOGGER.info("    - ID {}: {} packets, {} bytes, {} ({})",
                        t.getMultimediaId(),
                        t.getReceivedPackets(),
                        t.getData().readableBytes(),
                        getMediaTypeName(t.getMediaType()),
                        getMediaFormatName(t.getMediaFormat()));
            }
        }
    }

    private Object decodeMultimediaDataUpload(Channel channel, SocketAddress remoteAddress,
                                             ByteBuf id, int index, ByteBuf buf,
                                             DeviceSession deviceSession, int bodyLength) {
        LOGGER.info("-".repeat(70));
        LOGGER.info("RECEIVED: MULTIMEDIA DATA UPLOAD (0x0801)");
        LOGGER.info("  Device: {}", deviceSession.getDeviceId());
        LOGGER.info("  Sequence: {}", index);
        LOGGER.info("  Body Length: {} bytes", bodyLength);

        // Parse 0x0801 message header (8 bytes)
        // Format:
        // - Multimedia ID (4 bytes, DWORD)
        // - Multimedia Type (1 byte): 0=Image, 1=Audio, 2=Video
        // - Multimedia Format (1 byte): 0=JPEG, 1=TIF, 2=MP3, 3=WAV, 4=WMV
        // - Event Code (1 byte)
        // - Channel ID (1 byte)
        // - Multimedia data packet (remaining bytes)

        long multimediaId = buf.readUnsignedInt();
        int mediaType = buf.readUnsignedByte();
        int mediaFormat = buf.readUnsignedByte();
        int eventCode = buf.readUnsignedByte();
        int channelId = buf.readUnsignedByte();

        LOGGER.info("0x0801 MULTIMEDIA HEADER PARSED:");
        LOGGER.info("  Multimedia ID: {}", multimediaId);
        LOGGER.info("  Media Type: {} ({})", mediaType, getMediaTypeName(mediaType));
        LOGGER.info("  Media Format: {} ({})", mediaFormat, getMediaFormatName(mediaFormat));
        LOGGER.info("  Event Code: 0x{}", Integer.toHexString(eventCode));
        LOGGER.info("  Channel ID: {}", channelId);

        // Read multimedia data chunk
        int dataLength = buf.readableBytes();
        byte[] dataChunk = new byte[dataLength];
        buf.readBytes(dataChunk);

        LOGGER.info("0x0801 DATA CHUNK:");
        LOGGER.info("  Size: {} bytes", dataLength);
        // Log first 32 bytes of data as hex for debugging
        int previewLength = Math.min(32, dataLength);
        LOGGER.info("  Preview (first {} bytes): {}", previewLength,
                ByteBufUtil.hexDump(dataChunk, 0, previewLength));

        // Get or create transfer state
        MediaTransfer transfer = activeTransfers.get(multimediaId);
        boolean isNewTransfer = (transfer == null);

        if (isNewTransfer) {
            // First packet - create new transfer
            LOGGER.info("0x0801 TRANSFER STATE: NEW");
            LOGGER.info("  Creating new MediaTransfer for ID: {}", multimediaId);
            transfer = new MediaTransfer(multimediaId, mediaType, mediaFormat, eventCode,
                    deviceSession.getUniqueId());
            activeTransfers.put(multimediaId, transfer);
            LOGGER.info("  [SUCCESS] Media transfer created");
            LOGGER.info("  Device: {}", deviceSession.getDeviceId());
            LOGGER.info("  Type: {} ({})", mediaType, getMediaTypeName(mediaType));
            LOGGER.info("  Format: {} (.{})", getMediaFormatName(mediaFormat),
                    getMediaFileExtension(mediaType, mediaFormat));
        } else {
            // Continuing existing transfer
            LOGGER.info("0x0801 TRANSFER STATE: CONTINUING");
            LOGGER.info("  Appending to existing transfer ID: {}", multimediaId);
            LOGGER.info("  Previous size: {} bytes, {} packets",
                    transfer.getData().readableBytes(), transfer.getReceivedPackets());
        }

        // Append data to transfer buffer
        int previousSize = transfer.getData().readableBytes();
        transfer.getData().writeBytes(dataChunk);
        transfer.incrementReceivedPackets();
        int newSize = transfer.getData().readableBytes();

        LOGGER.info("0x0801 TRANSFER PROGRESS:");
        LOGGER.info("  Multimedia ID: {}", multimediaId);
        LOGGER.info("  Packets received: {}", transfer.getReceivedPackets());
        LOGGER.info("  Previous size: {} bytes", previousSize);
        LOGGER.info("  Chunk size: {} bytes", dataLength);
        LOGGER.info("  New total size: {} bytes", newSize);
        LOGGER.info("  Size increase: +{} bytes", (newSize - previousSize));

        // Log current active transfers
        LOGGER.info("0x0801 ACTIVE TRANSFERS SUMMARY:");
        logActiveTransfers();

        // Send acknowledgment (0x8001)
        LOGGER.info("0x0801 SENDING ACK (0x8001):");
        LOGGER.info("  Seq: {}", index);
        LOGGER.info("  Result: {} (SUCCESS)", RESULT_SUCCESS);
        sendGeneralResponse(channel, remoteAddress, id, MSG_MULTIMEDIA_DATA_UPLOAD, index, RESULT_SUCCESS);
        LOGGER.info("  [ACK SENT]");

        // Note on completion
        LOGGER.info("0x0801 COMPLETION INFO:");
        LOGGER.info("  Transfer ID {} will complete when 0x1212 message received", multimediaId);
        LOGGER.info("  Current state: Waiting for more packets or completion signal");

        LOGGER.info("-".repeat(70));

        return null;  // JT1078 doesn't create position objects for data packets
    }

    private Object decodeAlarmAttachmentInfo(Channel channel, SocketAddress remoteAddress,
                                            ByteBuf id, int index, ByteBuf buf,
                                            DeviceSession deviceSession) {
        LOGGER.info("-".repeat(70));
        LOGGER.info("RECEIVED: ALARM ATTACHMENT INFO (0x1210)");
        LOGGER.info("  Device: {}", deviceSession.getDeviceId());
        LOGGER.info("  Sequence: {}", index);
        LOGGER.info("  Remaining bytes: {}", buf.readableBytes());

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

        LOGGER.info("0x1210 ATTACHMENT SERVER INFO:");
        LOGGER.info("  Server IP: {} (length: {} bytes)", serverIp, serverIpLength);
        LOGGER.info("  TCP Port: {}", tcpPort);
        LOGGER.info("  UDP Port: {}", udpPort);

        // Read alarm identifier (16 bytes - could be 4-byte serial + padding or full alarm flag)
        ByteBuf alarmData = buf.readSlice(16);
        String alarmFlag = ByteBufUtil.hexDump(alarmData);
        LOGGER.info("0x1210 ALARM IDENTIFICATION:");
        LOGGER.info("  Alarm Flag (16 bytes): {}", alarmFlag);
        LOGGER.info("  Structure breakdown:");
        byte[] alarmBytes = new byte[16];
        alarmData.resetReaderIndex();
        alarmData.readBytes(alarmBytes);
        LOGGER.info("    Terminal ID (7): {}", ByteBufUtil.hexDump(alarmBytes, 0, 7));
        LOGGER.info("    Time BCD (6): {}", ByteBufUtil.hexDump(alarmBytes, 7, 6));
        LOGGER.info("    Serial (1): 0x{}", Integer.toHexString(alarmBytes[13] & 0xFF));
        LOGGER.info("    Attachments (1): {}", alarmBytes[14] & 0xFF);
        LOGGER.info("    Reserved (1): 0x{}", Integer.toHexString(alarmBytes[15] & 0xFF));

        // Read alarm number (32 bytes)
        byte[] alarmNumberBytes = new byte[32];
        buf.readBytes(alarmNumberBytes);
        String alarmNumber = new String(alarmNumberBytes, StandardCharsets.US_ASCII).trim();
        LOGGER.info("0x1210 ALARM NUMBER:");
        LOGGER.info("  Alarm Number: '{}'", alarmNumber);
        LOGGER.info("  Raw (32 bytes): {}", ByteBufUtil.hexDump(alarmNumberBytes));

        // Skip reserved (16 bytes)
        byte[] reserved = new byte[16];
        buf.readBytes(reserved);
        LOGGER.info("0x1210 RESERVED BYTES:");
        LOGGER.info("  Reserved (16 bytes): {}", ByteBufUtil.hexDump(reserved));

        // Read attachment count
        int attachmentCount = buf.readUnsignedByte();
        LOGGER.info("0x1210 ATTACHMENT INFO:");
        LOGGER.info("  Attachment Count: {}", attachmentCount);

        // Parse file info list
        if (attachmentCount > 0) {
            LOGGER.info("0x1210 FILE LIST:");
            for (int i = 0; i < attachmentCount && buf.readableBytes() >= 1; i++) {
                int fileNameLength = buf.readUnsignedByte();
                if (buf.readableBytes() < fileNameLength + 4) {
                    LOGGER.warn("  [WARNING] Not enough bytes for file #{} - expected {}, remaining {}",
                            i + 1, fileNameLength + 4, buf.readableBytes());
                    break;
                }
                String fileName = buf.readCharSequence(fileNameLength, StandardCharsets.US_ASCII).toString();
                long fileSize = buf.readUnsignedInt();

                LOGGER.info("  File #{}: {} ({} bytes)", i + 1, fileName, fileSize);
            }
        } else {
            LOGGER.info("  [NO FILES] Attachment count is 0");
        }

        // Send acknowledgment (0x8001)
        LOGGER.info("0x1210 SENDING ACK (0x8001):");
        LOGGER.info("  Seq: {}", index);
        LOGGER.info("  Result: {} (SUCCESS)", RESULT_SUCCESS);
        sendGeneralResponse(channel, remoteAddress, id, MSG_ALARM_ATTACHMENT_INFO, index, RESULT_SUCCESS);
        LOGGER.info("  [ACK SENT]");

        LOGGER.info("0x1210 PROCESSING COMPLETE:");
        LOGGER.info("  [SUCCESS] Alarm attachment info received");
        LOGGER.info("  Device: {}", deviceSession.getDeviceId());
        LOGGER.info("  Alarm: {}", alarmNumber);
        LOGGER.info("  Files: {}", attachmentCount);
        LOGGER.info("  Next: Device should send 0x0801 data packets for each file");

        LOGGER.info("-".repeat(70));

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
        LOGGER.info("=".repeat(70));
        LOGGER.info("RECEIVED: FILE UPLOAD COMPLETE (0x1212)");
        LOGGER.info("  Device: {}", deviceSession.getDeviceId());
        LOGGER.info("  Sequence: {}", index);
        LOGGER.info("  Remaining bytes: {}", buf.readableBytes());

        // Parse 0x1212 message
        // Format (based on JT808-2013 Annex B):
        // - Result (1 byte): 0=success, others=failure
        // - Multimedia ID count (2 bytes, WORD)
        // - Multimedia ID list (4 bytes each, DWORD)

        int result = buf.readUnsignedByte();
        int multimediaIdCount = buf.readUnsignedShort();

        LOGGER.info("0x1212 COMPLETION HEADER:");
        LOGGER.info("  Result Code: {} (0=success, other=failure)", result);
        LOGGER.info("  Multimedia ID Count: {}", multimediaIdCount);

        if (result != 0) {
            LOGGER.warn("0x1212 [WARNING] Upload result indicates FAILURE: {}", result);
            LOGGER.warn("  Device reported upload failure - files may not have been sent correctly");
        } else {
            LOGGER.info("0x1212 [SUCCESS] Upload result indicates SUCCESS");
        }

        Position position = null;

        LOGGER.info("0x1212 PROCESSING {} MULTIMEDIA FILE(S):", multimediaIdCount);
        logActiveTransfers();

        // Process each multimedia ID
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < multimediaIdCount && buf.readableBytes() >= 4; i++) {
            long multimediaId = buf.readUnsignedInt();

            LOGGER.info("-".repeat(70));
            LOGGER.info("0x1212 MULTIMEDIA FILE #{} OF {}", i + 1, multimediaIdCount);
            LOGGER.info("  Multimedia ID: {}", multimediaId);

            // Get transfer state
            MediaTransfer transfer = activeTransfers.get(multimediaId);

            if (transfer == null) {
                LOGGER.warn("0x1212 [ERROR] NO TRANSFER FOUND");
                LOGGER.warn("  Multimedia ID {} not in active transfers", multimediaId);
                LOGGER.warn("  This could mean:");
                LOGGER.warn("    - No 0x0801 packets were received for this ID");
                LOGGER.warn("    - Transfer was already completed or cleaned up");
                LOGGER.warn("    - Multimedia ID mismatch between device and server");
                failureCount++;
                continue;
            }

            LOGGER.info("0x1212 TRANSFER FOUND:");
            LOGGER.info("  Media Type: {} ({})",
                    transfer.getMediaType(), getMediaTypeName(transfer.getMediaType()));
            LOGGER.info("  Media Format: {} ({})",
                    transfer.getMediaFormat(), getMediaFormatName(transfer.getMediaFormat()));
            LOGGER.info("  Total Packets: {}", transfer.getReceivedPackets());
            LOGGER.info("  Total Size: {} bytes", transfer.getData().readableBytes());
            LOGGER.info("  Event Code: 0x{}", Integer.toHexString(transfer.getEventCode()));

            if (result != 0) {
                LOGGER.warn("0x1212 [SKIPPED] Device reported failure (result={})", result);
                LOGGER.warn("  Not writing file due to device-reported upload failure");
                transfer.release();
                activeTransfers.remove(multimediaId);
                failureCount++;
                continue;
            }

            try {
                // Write media file
                String extension = getMediaFileExtension(transfer.getMediaType(), transfer.getMediaFormat());

                LOGGER.info("0x1212 WRITING MEDIA FILE:");
                LOGGER.info("  Extension: .{}", extension);
                LOGGER.info("  Size: {} bytes", transfer.getData().readableBytes());

                String filename = writeMediaFile(transfer.getDeviceId(), transfer.getData(), extension);

                LOGGER.info("0x1212 [SUCCESS] MEDIA FILE SAVED:");
                LOGGER.info("  Multimedia ID: {}", multimediaId);
                LOGGER.info("  Filename: {}", filename);
                LOGGER.info("  Size: {} bytes", transfer.getData().readableBytes());
                LOGGER.info("  Packets: {}", transfer.getReceivedPackets());
                LOGGER.info("  Type: {} ({})", getMediaTypeName(transfer.getMediaType()), extension);

                // Create position object to record media event
                if (position == null) {
                    LOGGER.info("0x1212 CREATING POSITION OBJECT:");
                    position = new Position(getProtocolName());
                    position.setDeviceId(deviceSession.getDeviceId());
                    getLastLocation(position, null);
                    LOGGER.info("  Device ID: {}", deviceSession.getDeviceId());
                    LOGGER.info("  Protocol: {}", getProtocolName());
                } else {
                    LOGGER.info("0x1212 ADDING TO EXISTING POSITION OBJECT");
                }

                // Store media reference based on type
                String attributeKey;
                if (transfer.getMediaType() == 0) {
                    // Image
                    attributeKey = Position.KEY_IMAGE;
                    position.set(Position.KEY_IMAGE, filename);
                } else if (transfer.getMediaType() == 1) {
                    // Audio
                    attributeKey = Position.KEY_AUDIO;
                    position.set(Position.KEY_AUDIO, filename);
                } else if (transfer.getMediaType() == 2) {
                    // Video
                    attributeKey = Position.KEY_VIDEO;
                    position.set(Position.KEY_VIDEO, filename);
                } else {
                    attributeKey = "media";
                    position.set("media", filename);
                }

                LOGGER.info("0x1212 POSITION ATTRIBUTES SET:");
                LOGGER.info("  Attribute: {} = {}", attributeKey, filename);

                // Store additional metadata
                position.set("multimediaId", multimediaId);
                position.set("mediaType", transfer.getMediaType());
                position.set("mediaFormat", transfer.getMediaFormat());
                position.set("eventCode", transfer.getEventCode());
                position.set("mediaPackets", transfer.getReceivedPackets());

                LOGGER.info("  Metadata: multimediaId={}, mediaType={}, mediaFormat={}, eventCode=0x{}, packets={}",
                        multimediaId, transfer.getMediaType(), transfer.getMediaFormat(),
                        Integer.toHexString(transfer.getEventCode()), transfer.getReceivedPackets());

                successCount++;

            } catch (Exception e) {
                LOGGER.error("0x1212 [ERROR] Exception while writing media file:", e);
                LOGGER.error("  Multimedia ID: {}", multimediaId);
                LOGGER.error("  Error: {}", e.getMessage());
                failureCount++;
            } finally {
                // Clean up transfer state
                LOGGER.info("0x1212 CLEANING UP TRANSFER:");
                LOGGER.info("  Releasing ByteBuf for multimedia ID: {}", multimediaId);
                transfer.release();
                activeTransfers.remove(multimediaId);
                LOGGER.info("  [CLEANED] Transfer removed from active list");
            }
        }

        // Log final summary
        LOGGER.info("=".repeat(70));
        LOGGER.info("0x1212 PROCESSING SUMMARY:");
        LOGGER.info("  Total files requested: {}", multimediaIdCount);
        LOGGER.info("  Successfully saved: {} files", successCount);
        LOGGER.info("  Failed: {} files", failureCount);
        LOGGER.info("  Active transfers remaining: {}", activeTransfers.size());

        if (successCount > 0) {
            LOGGER.info("  [SUCCESS] {} multimedia file(s) saved successfully", successCount);
        }
        if (failureCount > 0) {
            LOGGER.warn("  [WARNING] {} multimedia file(s) failed", failureCount);
        }

        // Log remaining active transfers (shouldn't be any if all completed)
        if (!activeTransfers.isEmpty()) {
            LOGGER.warn("0x1212 [WARNING] ORPHANED TRANSFERS DETECTED:");
            LOGGER.warn("  {} transfer(s) remain in memory after completion", activeTransfers.size());
            LOGGER.warn("  These may indicate:");
            LOGGER.warn("    - Device sent 0x1212 for IDs not in 0x0801 packets");
            LOGGER.warn("    - Missing 0x1212 for some multimedia IDs");
            LOGGER.warn("  Orphaned transfers:");
            for (Map.Entry<Long, MediaTransfer> entry : activeTransfers.entrySet()) {
                MediaTransfer t = entry.getValue();
                LOGGER.warn("    - ID {}: {} bytes, {} packets, waiting for completion",
                        t.getMultimediaId(), t.getData().readableBytes(), t.getReceivedPackets());
            }
        }

        // Send acknowledgment (0x8001)
        LOGGER.info("0x1212 SENDING ACK (0x8001):");
        LOGGER.info("  Seq: {}", index);
        LOGGER.info("  Result: {} (SUCCESS)", RESULT_SUCCESS);
        sendGeneralResponse(channel, remoteAddress, id, MSG_FILE_UPLOAD_COMPLETE, index, RESULT_SUCCESS);
        LOGGER.info("  [ACK SENT]");

        // Final status
        if (position != null) {
            LOGGER.info("0x1212 [SUCCESS] RETURNING POSITION OBJECT:");
            LOGGER.info("  Device: {}", deviceSession.getDeviceId());
            LOGGER.info("  Position will be saved to database with media references");
        } else if (multimediaIdCount > 0) {
            LOGGER.warn("0x1212 [WARNING] NO POSITION OBJECT CREATED:");
            LOGGER.warn("  {} multimedia files were requested but none saved successfully", multimediaIdCount);
        }

        LOGGER.info("0x1212 COMPLETE");
        LOGGER.info("=".repeat(70));

        return position;
    }

}
