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
    public static final int MSG_FILE_UPLOAD_COMPLETE_RESPONSE = 0x9212;  // Platform → Terminal
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
        private String filename;        // T/JSATL12-2017 filename from code stream packet
        private long declaredFileSize;  // File size declared in 0x1211 message

        MediaTransfer(long multimediaId, int mediaType, int mediaFormat, int eventCode, String deviceId) {
            this.multimediaId = multimediaId;
            this.mediaType = mediaType;
            this.mediaFormat = mediaFormat;
            this.eventCode = eventCode;
            this.deviceId = deviceId;
            this.data = Unpooled.buffer();
            this.receivedPackets = 0;
            this.declaredFileSize = 0;
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

        String getFilename() {
            return filename;
        }

        void setFilename(String filename) {
            this.filename = filename;
        }

        long getDeclaredFileSize() {
            return declaredFileSize;
        }

        void setDeclaredFileSize(long declaredFileSize) {
            this.declaredFileSize = declaredFileSize;
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

        // Check if this is a T/JSATL12-2017 code stream packet (Table 4-26)
        // These start with 0x30316364 instead of 0x7E delimiter
        if (isCodeStreamPacket(buf)) {
            // This is a raw TCP code stream packet, NOT a JT/T 808 message

            // CODE STREAM FIX #1: Extract device ID from filename
            // Code stream packets don't have device ID in header, but it's in the filename!
            // Peek at filename (bytes 4-54) without consuming the buffer
            if (buf.readableBytes() >= 54) {
                buf.markReaderIndex();
                buf.skipBytes(4); // Skip frame header (0x30316364)

                // Read filename (50 bytes)
                byte[] filenameBytes = new byte[50];
                buf.readBytes(filenameBytes);
                String filename = new String(filenameBytes, StandardCharsets.US_ASCII).trim();

                // Reset buffer to beginning
                buf.resetReaderIndex();

                // Extract device ID from filename
                String deviceId = extractDeviceIdFromFilename(filename);

                DeviceSession deviceSession;
                if (deviceId != null) {
                    // Look up session by device ID (more reliable)
                    deviceSession = getDeviceSession(channel, remoteAddress, deviceId);
                    LOGGER.debug("Code stream packet - looked up session by device ID: {}", deviceId);
                } else {
                    // Fallback to channel/address lookup
                    deviceSession = getDeviceSession(channel, remoteAddress);
                    LOGGER.debug("Code stream packet - using channel/address lookup (no device ID extracted)");
                }

                if (deviceSession == null) {
                    LOGGER.warn("Code stream packet received but no device session found");
                    LOGGER.warn("  Filename: '{}', Device ID: {}", filename, deviceId);
                    return null;
                }

                // CODE STREAM FIX #2: Refresh session activity to prevent timeout
                // Large file uploads can take 30+ seconds, need to keep session alive
                updateDeviceSession(deviceSession, channel, remoteAddress);

                return decodeCodeStreamPacket(channel, remoteAddress, buf, deviceSession);
            } else {
                LOGGER.warn("Code stream packet too small to extract filename: {} bytes", buf.readableBytes());
                return null;
            }
        }

        // Otherwise, proceed with normal JT/T 808 message parsing
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

    /**
     * Detects if the buffer contains a T/JSATL12-2017 code stream packet
     * (Table 4-26 format with 0x30316364 header)
     */
    private boolean isCodeStreamPacket(ByteBuf buf) {
        if (buf.readableBytes() < 4) {
            return false;
        }

        int readerIndex = buf.readerIndex();

        // Check for frame header: 0x30 0x31 0x63 0x64 ("01cd" in ASCII)
        byte b0 = buf.getByte(readerIndex);
        byte b1 = buf.getByte(readerIndex + 1);
        byte b2 = buf.getByte(readerIndex + 2);
        byte b3 = buf.getByte(readerIndex + 3);

        return b0 == 0x30 && b1 == 0x31 && b2 == 0x63 && b3 == 0x64;
    }

    /**
     * Extracts multimedia ID from T/JSATL12-2017 filename format
     * Format: <type>_<channel>_<alarm_type>_<serial>_<alarm_number>.<ext>
     * Example: "00_64_6402_0_ALM-3906-0-1762021442036.jpg"
     */
    private long extractMultimediaIdFromFilename(String filename) {
        try {
            // Extract alarm number from filename (last part before extension)
            String withoutExt = filename.substring(0, filename.lastIndexOf('.'));
            String[] parts = withoutExt.split("_");

            if (parts.length >= 5) {
                // Last part is alarm number (e.g., "ALM-3906-0-1762021442036")
                String alarmPart = parts[parts.length - 1];

                // Extract just the numeric ID at the end
                String[] alarmParts = alarmPart.split("-");
                if (alarmParts.length >= 4) {
                    return Long.parseLong(alarmParts[alarmParts.length - 1]);
                }
            }

            // Fallback: use hash of filename
            return Math.abs(filename.hashCode());

        } catch (Exception e) {
            LOGGER.warn("Failed to extract multimedia ID from filename '{}': {}",
                    filename, e.getMessage());
            return Math.abs(filename.hashCode());
        }
    }

    /**
     * Extracts device ID from DC600 filename format
     * Filename format: XX_YY_ZZZZ_N_ALM-DDDD-M-TTTTTTTTTTTTT.ext
     * Where DDDD is the device ID (e.g., "3906")
     * Example: "02_65_6502_3_ALM-3906-0-1762130012739.mp4" -> "3906"
     */
    private String extractDeviceIdFromFilename(String filename) {
        try {
            // Extract alarm part from filename
            String withoutExt = filename.contains(".")
                ? filename.substring(0, filename.lastIndexOf('.'))
                : filename;
            String[] parts = withoutExt.split("_");

            if (parts.length >= 5) {
                // Last part is alarm (e.g., "ALM-3906-0-1762021442036")
                String alarmPart = parts[parts.length - 1];

                // Split by dash and extract device ID (second element)
                String[] alarmParts = alarmPart.split("-");
                if (alarmParts.length >= 4 && alarmPart.startsWith("ALM-")) {
                    String deviceId = alarmParts[1]; // Extract "3906"
                    LOGGER.debug("Extracted device ID '{}' from filename '{}'", deviceId, filename);
                    return deviceId;
                }
            }

            LOGGER.warn("Could not extract device ID from filename '{}' - invalid format", filename);
            return null;

        } catch (Exception e) {
            LOGGER.warn("Failed to extract device ID from filename '{}': {}",
                    filename, e.getMessage());
            return null;
        }
    }

    /**
     * Parses T/JSATL12-2017 code stream data packet (Table 4-26)
     * This is sent as raw TCP data, NOT as JT/T 808 signaling message
     */
    private Object decodeCodeStreamPacket(Channel channel, SocketAddress remoteAddress,
                                         ByteBuf buf, DeviceSession deviceSession) {
        LOGGER.info("-".repeat(70));
        LOGGER.info("RECEIVED: CODE STREAM DATA PACKET (T/JSATL12-2017 Table 4-26)");
        LOGGER.info("  Device: {}", deviceSession.getDeviceId());
        LOGGER.info("  Packet Size: {} bytes", buf.readableBytes());

        if (buf.readableBytes() < 62) {
            LOGGER.warn("  [ERROR] Packet too small: {} bytes (minimum 62)", buf.readableBytes());
            return null;
        }

        // Read frame header (4 bytes) - should be 0x30 0x31 0x63 0x64
        int frameHeader = buf.readInt();
        LOGGER.info("  Frame Header: 0x{}", Integer.toHexString(frameHeader));

        if (frameHeader != 0x30316364) {
            LOGGER.warn("  [ERROR] Invalid frame header: expected 0x30316364, got 0x{}",
                    Integer.toHexString(frameHeader));
            return null;
        }

        // Read filename (50 bytes, zero-padded)
        byte[] filenameBytes = new byte[50];
        buf.readBytes(filenameBytes);
        String filename = new String(filenameBytes, StandardCharsets.US_ASCII).trim();
        LOGGER.info("  Filename: '{}'", filename);

        // Read data offset (4 bytes)
        long dataOffset = buf.readUnsignedInt();
        LOGGER.info("  Data Offset: {} bytes", dataOffset);

        // Read data length (4 bytes)
        long dataLength = buf.readUnsignedInt();
        LOGGER.info("  Data Length: {} bytes", dataLength);

        // Read data body (remaining bytes)
        int actualDataLength = buf.readableBytes();
        LOGGER.info("  Actual Data: {} bytes", actualDataLength);

        if (actualDataLength != dataLength) {
            LOGGER.warn("  [WARNING] Data length mismatch: declared={}, actual={}",
                    dataLength, actualDataLength);
        }

        byte[] fileData = new byte[actualDataLength];
        buf.readBytes(fileData);

        // Log first 32 bytes as hex for debugging
        int previewLength = Math.min(32, actualDataLength);
        LOGGER.info("  Data Preview (first {} bytes): {}",
                previewLength, ByteBufUtil.hexDump(fileData, 0, previewLength));

        // Extract multimedia ID from filename
        long multimediaId = extractMultimediaIdFromFilename(filename);
        LOGGER.info("  Extracted Multimedia ID: {}", multimediaId);

        // Determine file type from filename extension
        int fileType = 0; // Default to image
        int fileFormat = 0; // Default to JPEG

        if (filename.toLowerCase().endsWith(".jpg") || filename.toLowerCase().endsWith(".jpeg")) {
            fileType = 0; // Image
            fileFormat = 0; // JPEG
        } else if (filename.toLowerCase().endsWith(".png")) {
            fileType = 0; // Image
            fileFormat = 1; // TIF/PNG
        } else if (filename.toLowerCase().endsWith(".mp4") || filename.toLowerCase().endsWith(".wmv")) {
            fileType = 2; // Video
            fileFormat = 4; // WMV/MP4
        } else if (filename.toLowerCase().endsWith(".wav")) {
            fileType = 1; // Audio
            fileFormat = 3; // WAV
        } else if (filename.toLowerCase().endsWith(".mp3")) {
            fileType = 1; // Audio
            fileFormat = 2; // MP3
        }

        LOGGER.info("  File Type: {} ({})", fileType, getMediaTypeName(fileType));
        LOGGER.info("  File Format: {} ({})", fileFormat, getMediaFormatName(fileFormat));

        // Get or create MediaTransfer for this file
        MediaTransfer transfer = activeTransfers.get(multimediaId);

        if (transfer == null) {
            // First packet for this file
            LOGGER.info("  [NEW TRANSFER] Creating MediaTransfer for ID: {}", multimediaId);
            transfer = new MediaTransfer(multimediaId, fileType, fileFormat, 0,
                    deviceSession.getUniqueId());
            transfer.setFilename(filename);
            activeTransfers.put(multimediaId, transfer);
        } else {
            LOGGER.info("  [CONTINUING TRANSFER] Appending to existing transfer");
            LOGGER.info("    Previous size: {} bytes", transfer.getData().readableBytes());
        }

        // Append data at the specified offset
        if (dataOffset == 0 || dataOffset == transfer.getData().readableBytes()) {
            // Sequential write - just append
            transfer.getData().writeBytes(fileData);
            transfer.incrementReceivedPackets();
            LOGGER.info("  [APPENDED] Sequential write at offset {}", dataOffset);
        } else {
            LOGGER.warn("  [WARNING] Non-sequential write: offset={}, current_size={}",
                    dataOffset, transfer.getData().readableBytes());
            // For now, still append (could implement random-access in future)
            transfer.getData().writeBytes(fileData);
            transfer.incrementReceivedPackets();
        }

        int newSize = transfer.getData().readableBytes();
        LOGGER.info("  [SUCCESS] Transfer updated:");
        LOGGER.info("    Multimedia ID: {}", multimediaId);
        LOGGER.info("    Packets received: {}", transfer.getReceivedPackets());
        LOGGER.info("    Total size: {} bytes", newSize);
        LOGGER.info("    Filename: {}", filename);

        logActiveTransfers();

        LOGGER.info("-".repeat(70));

        // No response needed for code stream packets (per T/JSATL12-2017)
        return null;
    }

    /**
     * Sends 0x9212 File Upload Complete Response (T/JSATL12-2017 Section 4.10, Table 4-28)
     * DC600 format: Fixed 50-byte filename (null-padded)
     * This is MANDATORY per the specification - device waits 10 seconds for this response
     */
    private void sendFileUploadCompleteResponse(Channel channel, SocketAddress remoteAddress,
                                               ByteBuf deviceId, int sequenceNumber,
                                               String filename, int fileType, boolean success) {
        LOGGER.info("-".repeat(70));
        LOGGER.info("SENDING: FILE UPLOAD COMPLETE RESPONSE (0x9212)");
        LOGGER.info("  Filename: '{}'", filename);
        LOGGER.info("  File Type: {} ({})", fileType, getMediaTypeName(fileType));
        LOGGER.info("  Upload Result: {} ({})", success ? "0x00" : "0x01",
                success ? "SUCCESS" : "FAILURE");

        if (channel == null) {
            LOGGER.error("  [ERROR] Channel is null - cannot send response");
            return;
        }

        try {
            // Build message body per T/JSATL12-2017 Table 4-28 (DC600 format)
            ByteBuf body = Unpooled.buffer();

            // Filename (50 bytes, null-padded for DC600)
            // DC600 uses FIXED 50-byte format, not variable-length
            byte[] filenameBytes = new byte[50];
            byte[] fileNameSrc = filename.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(fileNameSrc, 0, filenameBytes, 0,
                             Math.min(fileNameSrc.length, 50));
            body.writeBytes(filenameBytes);

            // File type (1 byte): 0=picture, 1=audio, 2=video, 3=text, 4=other
            body.writeByte(fileType);

            // Upload result (1 byte): 0x00 = success, 0x01 = failure
            body.writeByte(success ? 0x00 : 0x01);

            // Reserved (1 byte)
            body.writeByte(0x00);

            // Format message using existing helper
            ByteBuf response = formatMessage(0x7E, MSG_FILE_UPLOAD_COMPLETE_RESPONSE, deviceId, body);

            LOGGER.info("  Message Size: {} bytes", response.readableBytes());
            LOGGER.info("  Body Size: 53 bytes (50 filename + 1 type + 1 result + 1 reserved)");
            LOGGER.info("  Hex: {}", ByteBufUtil.hexDump(response));

            // Send to device
            channel.writeAndFlush(new NetworkMessage(response, remoteAddress));

            LOGGER.info("  [SUCCESS] 0x9212 response sent to device");
            LOGGER.info("  Device should now log: 'ai upload ... scusess'");

            body.release();

        } catch (Exception e) {
            LOGGER.error("  [ERROR] Failed to send 0x9212 response:", e);
        }

        LOGGER.info("-".repeat(70));
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

        // Parse 0x1210 message per T/JSATL12-2017 Table 4-23
        // Format:
        // - Terminal ID (7 bytes)
        // - Alarm Flag (16 bytes)
        // - Alarm Number (32 bytes, ASCII)
        // - Information Type (1 byte): 0x00=Normal, 0x01=Update
        // - Attachment Count (1 byte)
        // - File Info List (variable, Table 4-24 format per file)

        // Read Terminal ID (7 bytes)
        byte[] terminalIdBytes = new byte[7];
        buf.readBytes(terminalIdBytes);
        String terminalId = new String(terminalIdBytes, StandardCharsets.US_ASCII).trim();
        LOGGER.info("0x1210 HEADER:");
        LOGGER.info("  Terminal ID (7 bytes): '{}' (hex: {})",
                terminalId, ByteBufUtil.hexDump(terminalIdBytes));

        // Read Alarm Flag (16 bytes) - See Table 4-16 for structure
        byte[] alarmFlagBytes = new byte[16];
        buf.readBytes(alarmFlagBytes);
        LOGGER.info("  Alarm Flag (16 bytes): {}", ByteBufUtil.hexDump(alarmFlagBytes));

        // Read Alarm Number (32 bytes, ASCII)
        byte[] alarmNumberBytes = new byte[32];
        buf.readBytes(alarmNumberBytes);
        String alarmNumber = new String(alarmNumberBytes, StandardCharsets.US_ASCII).trim();
        LOGGER.info("  Alarm Number (32 bytes): '{}'", alarmNumber);

        // Read Information Type (1 byte)
        int informationType = buf.readUnsignedByte();
        LOGGER.info("  Information Type: {} ({})",
                informationType, informationType == 0 ? "Normal" : "Update");

        // Read Attachment Count (1 byte)
        int attachmentCount = buf.readUnsignedByte();
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
        LOGGER.info("-".repeat(70));
        LOGGER.info("RECEIVED: FILE INFO UPLOAD (0x1211)");
        LOGGER.info("  Device: {}", deviceSession.getDeviceId());
        LOGGER.info("  Sequence: {}", index);
        LOGGER.info("  Remaining bytes: {}", buf.readableBytes());

        // Parse 0x1211 message per T/JSATL12-2017 Table 4-25
        // Format:
        // - File name length (1 byte)
        // - File name (variable, STRING)
        // - File type (1 byte): 0=picture, 1=audio, 2=video, 3=text, 4=other
        // - File size (4 bytes, DWORD)

        if (buf.readableBytes() < 6) {
            LOGGER.warn("0x1211 [ERROR] Insufficient data: {} bytes (minimum 6)", buf.readableBytes());
            sendGeneralResponse(channel, remoteAddress, id, MSG_FILE_INFO_UPLOAD, index, RESULT_SUCCESS);
            return null;
        }

        // Read file name length (1 byte)
        int fileNameLength = buf.readUnsignedByte();
        LOGGER.info("0x1211 FILE INFO:");
        LOGGER.info("  File Name Length: {} bytes", fileNameLength);

        if (buf.readableBytes() < fileNameLength + 5) {
            LOGGER.warn("0x1211 [ERROR] Insufficient data for filename: expected {}, remaining {}",
                    fileNameLength + 5, buf.readableBytes());
            sendGeneralResponse(channel, remoteAddress, id, MSG_FILE_INFO_UPLOAD, index, RESULT_SUCCESS);
            return null;
        }

        // Read file name (variable)
        String fileName = buf.readCharSequence(fileNameLength, StandardCharsets.US_ASCII).toString();
        LOGGER.info("  File Name: '{}'", fileName);

        // Read file type (1 byte)
        int fileType = buf.readUnsignedByte();
        String fileTypeStr;
        switch (fileType) {
            case 0: fileTypeStr = "Picture"; break;
            case 1: fileTypeStr = "Audio"; break;
            case 2: fileTypeStr = "Video"; break;
            case 3: fileTypeStr = "Text"; break;
            case 4: fileTypeStr = "Other"; break;
            default: fileTypeStr = "Unknown"; break;
        }
        LOGGER.info("  File Type: {} ({})", fileType, fileTypeStr);

        // Read file size (4 bytes, DWORD)
        long fileSize = buf.readUnsignedInt();
        LOGGER.info("  File Size: {} bytes ({} KB)", fileSize, fileSize / 1024);

        // Extract multimedia ID from filename for validation
        long multimediaId = extractMultimediaIdFromFilename(fileName);
        LOGGER.info("  Extracted Multimedia ID: {}", multimediaId);

        // Check if MediaTransfer already exists from code stream packets
        MediaTransfer transfer = activeTransfers.get(multimediaId);
        if (transfer != null) {
            LOGGER.info("0x1211 [VALIDATION] MediaTransfer found for ID: {}", multimediaId);
            LOGGER.info("  Current transfer size: {} bytes", transfer.getData().readableBytes());

            // CODE STREAM FIX #3a: Store declared file size for validation
            transfer.setDeclaredFileSize(fileSize);
            LOGGER.info("  [STORED] Declared file size: {} bytes", fileSize);

            // Validate file size if data already received
            if (transfer.getData().readableBytes() > 0 && transfer.getData().readableBytes() != fileSize) {
                LOGGER.warn("  [WARNING] Size mismatch: declared={}, received={}",
                        fileSize, transfer.getData().readableBytes());
            } else {
                LOGGER.info("  [OK] File metadata validated");
            }
        } else {
            LOGGER.info("0x1211 [INFO] No MediaTransfer found yet (will be created by code stream packets)");
            // This is normal - code stream packets may arrive after 0x1211
        }

        LOGGER.info("0x1211 PROCESSING COMPLETE:");
        LOGGER.info("  File: {}", fileName);
        LOGGER.info("  Type: {}", fileTypeStr);
        LOGGER.info("  Size: {} bytes", fileSize);
        LOGGER.info("  Next: Expecting code stream packets with file data");

        // Send acknowledgment (0x8001)
        LOGGER.info("0x1211 SENDING ACK (0x8001):");
        LOGGER.info("  Seq: {}", index);
        sendGeneralResponse(channel, remoteAddress, id, MSG_FILE_INFO_UPLOAD, index, RESULT_SUCCESS);
        LOGGER.info("  [ACK SENT]");

        LOGGER.info("-".repeat(70));

        return null;  // File data comes via code stream packets
    }

    private Object decodeFileUploadComplete(Channel channel, SocketAddress remoteAddress,
                                           ByteBuf id, int index, ByteBuf buf,
                                           DeviceSession deviceSession) {
        LOGGER.info("=".repeat(70));
        LOGGER.info("RECEIVED: FILE UPLOAD COMPLETE (0x1212)");
        LOGGER.info("  Device: {}", deviceSession.getDeviceId());
        LOGGER.info("  Sequence: {}", index);
        LOGGER.info("  Remaining bytes: {}", buf.readableBytes());

        // Parse 0x1212 message per T/JSATL12-2017 Table 4-27
        // Format:
        // - File name length (1 byte)
        // - File name (variable, STRING)
        // - File type (1 byte): 0=picture, 1=audio, 2=video, 3=text, 4=other
        // - File size (4 bytes, DWORD)

        if (buf.readableBytes() < 6) {
            LOGGER.warn("0x1212 [ERROR] Insufficient data: {} bytes (minimum 6)", buf.readableBytes());
            sendGeneralResponse(channel, remoteAddress, id, MSG_FILE_UPLOAD_COMPLETE, index, RESULT_SUCCESS);
            return null;
        }

        // Read file name length (1 byte)
        int fileNameLength = buf.readUnsignedByte();
        LOGGER.info("0x1212 FILE INFO:");
        LOGGER.info("  File Name Length: {} bytes", fileNameLength);

        if (buf.readableBytes() < fileNameLength + 5) {
            LOGGER.warn("0x1212 [ERROR] Insufficient data for filename: expected {}, remaining {}",
                    fileNameLength + 5, buf.readableBytes());
            sendGeneralResponse(channel, remoteAddress, id, MSG_FILE_UPLOAD_COMPLETE, index, RESULT_SUCCESS);
            return null;
        }

        // Read file name (variable)
        String fileName = buf.readCharSequence(fileNameLength, StandardCharsets.US_ASCII).toString();
        LOGGER.info("  File Name: '{}'", fileName);

        // Read file type (1 byte)
        int fileType = buf.readUnsignedByte();
        String fileTypeStr;
        switch (fileType) {
            case 0: fileTypeStr = "Picture"; break;
            case 1: fileTypeStr = "Audio"; break;
            case 2: fileTypeStr = "Video"; break;
            case 3: fileTypeStr = "Text"; break;
            case 4: fileTypeStr = "Other"; break;
            default: fileTypeStr = "Unknown"; break;
        }
        LOGGER.info("  File Type: {} ({})", fileType, fileTypeStr);

        // Read file size (4 bytes, DWORD)
        long fileSize = buf.readUnsignedInt();
        LOGGER.info("  File Size: {} bytes ({} KB)", fileSize, fileSize / 1024);

        LOGGER.info("-".repeat(70));

        // Extract multimedia ID from filename
        long multimediaId = extractMultimediaIdFromFilename(fileName);
        LOGGER.info("0x1212 LOCATING TRANSFER:");
        LOGGER.info("  Extracted Multimedia ID: {}", multimediaId);
        LOGGER.info("  Filename: '{}'", fileName);

        logActiveTransfers();

        // Get MediaTransfer created by code stream packets
        MediaTransfer transfer = activeTransfers.get(multimediaId);

        if (transfer == null) {
            LOGGER.error("0x1212 [ERROR] NO TRANSFER FOUND");
            LOGGER.error("  Multimedia ID {} not in active transfers", multimediaId);
            LOGGER.error("  This means:");
            LOGGER.error("    - No code stream packets (0x30316364) were received for this file");
            LOGGER.error("    - MediaTransfer was not created by decodeCodeStreamPacket()");
            LOGGER.error("    - Possible filename mismatch or ID extraction failure");
            LOGGER.error("  Cannot save file - no data available");

            // Send ACK anyway
            sendGeneralResponse(channel, remoteAddress, id, MSG_FILE_UPLOAD_COMPLETE, index, RESULT_SUCCESS);
            LOGGER.info("0x1212 COMPLETE (FAILED - no data)");
            LOGGER.info("=".repeat(70));
            return null;
        }

        LOGGER.info("0x1212 [SUCCESS] TRANSFER FOUND:");
        LOGGER.info("  Media Type: {} ({})",
                transfer.getMediaType(), getMediaTypeName(transfer.getMediaType()));
        LOGGER.info("  Media Format: {} ({})",
                transfer.getMediaFormat(), getMediaFormatName(transfer.getMediaFormat()));
        LOGGER.info("  Total Packets: {}", transfer.getReceivedPackets());
        LOGGER.info("  Total Size: {} bytes", transfer.getData().readableBytes());
        LOGGER.info("  Filename: '{}'", transfer.getFilename());

        // Validate file size
        if (transfer.getData().readableBytes() != fileSize) {
            LOGGER.warn("0x1212 [WARNING] Size mismatch:");
            LOGGER.warn("  Declared in 0x1212: {} bytes", fileSize);
            LOGGER.warn("  Actually received: {} bytes", transfer.getData().readableBytes());
            LOGGER.warn("  Using actual received size");
        } else {
            LOGGER.info("  [OK] File size validated: {} bytes", fileSize);
        }

        Position position = null;

        try {
            // Write media file to storage
            String extension = getMediaFileExtension(transfer.getMediaType(), transfer.getMediaFormat());

            LOGGER.info("0x1212 SAVING MEDIA FILE:");
            LOGGER.info("  Extension: .{}", extension);
            LOGGER.info("  Size: {} bytes", transfer.getData().readableBytes());

            String savedPath = writeMediaFile(transfer.getDeviceId(), transfer.getData(), extension);

            LOGGER.info("0x1212 [SUCCESS] MEDIA FILE SAVED:");
            LOGGER.info("  Multimedia ID: {}", multimediaId);
            LOGGER.info("  Original Filename: {}", fileName);
            LOGGER.info("  Saved Path: {}", savedPath);
            LOGGER.info("  Size: {} bytes", transfer.getData().readableBytes());
            LOGGER.info("  Packets: {}", transfer.getReceivedPackets());
            LOGGER.info("  Type: {} (.{})", fileTypeStr, extension);

            // Create Position object to record media event
            LOGGER.info("0x1212 CREATING POSITION OBJECT:");
            position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            LOGGER.info("  Device ID: {}", deviceSession.getDeviceId());
            LOGGER.info("  Protocol: {}", getProtocolName());

            // Store media reference based on type
            String attributeKey;
            if (fileType == 0) {
                // Image
                attributeKey = Position.KEY_IMAGE;
                position.set(Position.KEY_IMAGE, savedPath);
            } else if (fileType == 1) {
                // Audio
                attributeKey = Position.KEY_AUDIO;
                position.set(Position.KEY_AUDIO, savedPath);
            } else if (fileType == 2) {
                // Video
                attributeKey = Position.KEY_VIDEO;
                position.set(Position.KEY_VIDEO, savedPath);
            } else {
                attributeKey = "media";
                position.set("media", savedPath);
            }

            LOGGER.info("0x1212 POSITION ATTRIBUTES SET:");
            LOGGER.info("  Attribute: {} = {}", attributeKey, savedPath);

            // Store additional metadata
            position.set("multimediaId", multimediaId);
            position.set("mediaType", fileType);
            position.set("mediaFormat", transfer.getMediaFormat());
            position.set("originalFilename", fileName);
            position.set("fileSize", fileSize);
            position.set("mediaPackets", transfer.getReceivedPackets());

            LOGGER.info("  Metadata: multimediaId={}, mediaType={}, originalFilename='{}', fileSize={}, packets={}",
                    multimediaId, fileType, fileName, fileSize, transfer.getReceivedPackets());

        } catch (Exception e) {
            LOGGER.error("0x1212 [ERROR] Exception while saving media file:", e);
            LOGGER.error("  Multimedia ID: {}", multimediaId);
            LOGGER.error("  Filename: {}", fileName);
            LOGGER.error("  Error: {}", e.getMessage());

            // Clean up and send ACK
            transfer.release();
            activeTransfers.remove(multimediaId);
            sendGeneralResponse(channel, remoteAddress, id, MSG_FILE_UPLOAD_COMPLETE, index, RESULT_SUCCESS);
            LOGGER.info("0x1212 COMPLETE (FAILED - exception)");
            LOGGER.info("=".repeat(70));
            return null;
        } finally {
            // Clean up transfer state (whether success or failure)
            if (transfer != null) {
                LOGGER.info("0x1212 CLEANING UP TRANSFER:");
                LOGGER.info("  Releasing ByteBuf for multimedia ID: {}", multimediaId);
                transfer.release();
                activeTransfers.remove(multimediaId);
                LOGGER.info("  [CLEANED] Transfer removed from active list");
            }
        }

        // Send acknowledgment (0x8001)
        LOGGER.info("-".repeat(70));
        LOGGER.info("0x1212 SENDING ACK (0x8001):");
        LOGGER.info("  Seq: {}", index);
        LOGGER.info("  Result: {} (SUCCESS)", RESULT_SUCCESS);
        sendGeneralResponse(channel, remoteAddress, id, MSG_FILE_UPLOAD_COMPLETE, index, RESULT_SUCCESS);
        LOGGER.info("  [ACK SENT]");

        // CODE STREAM FIX #3b: Validate data completeness before sending 0x9212
        boolean uploadSuccess = true;
        if (transfer != null && transfer.getDeclaredFileSize() > 0) {
            long declaredSize = transfer.getDeclaredFileSize();
            long actualSize = transfer.getData().readableBytes();

            if (actualSize < declaredSize) {
                uploadSuccess = false;
                long percentReceived = (actualSize * 100) / declaredSize;
                LOGGER.error("-".repeat(70));
                LOGGER.error("FILE UPLOAD INCOMPLETE!");
                LOGGER.error("  File: {}", fileName);
                LOGGER.error("  Declared size: {} bytes ({} KB)", declaredSize, declaredSize / 1024);
                LOGGER.error("  Actual received: {} bytes ({} KB)", actualSize, actualSize / 1024);
                LOGGER.error("  Missing: {} bytes ({} KB)", declaredSize - actualSize, (declaredSize - actualSize) / 1024);
                LOGGER.error("  Completeness: {}%", percentReceived);
                LOGGER.error("  This means:");
                LOGGER.error("    - Device session likely expired during upload");
                LOGGER.error("    - Some code stream packets were rejected");
                LOGGER.error("    - Video will be corrupted/truncated");
                LOGGER.error("  Fix: Increase session timeout or fix device ID extraction");
            } else {
                LOGGER.info("0x1212 [VALIDATION] File transfer complete:");
                LOGGER.info("  Declared: {} bytes", declaredSize);
                LOGGER.info("  Received: {} bytes", actualSize);
                LOGGER.info("  Status: 100% complete ✓");
            }
        }

        // Send 0x9212 File Upload Complete Response (MANDATORY per T/JSATL12-2017 Section 4.10)
        LOGGER.info("-".repeat(70));
        LOGGER.info("0x1212 SENDING 0x9212 RESPONSE:");
        LOGGER.info("  Upload Success: {}", uploadSuccess);
        sendFileUploadCompleteResponse(channel, remoteAddress, id, index, fileName, fileType, uploadSuccess);

        // Log final status
        LOGGER.info("=".repeat(70));
        if (position != null) {
            LOGGER.info("0x1212 [SUCCESS] COMPLETE:");
            LOGGER.info("  Device: {}", deviceSession.getDeviceId());
            LOGGER.info("  File: {}", fileName);
            LOGGER.info("  Type: {}", fileTypeStr);
            LOGGER.info("  Size: {} bytes", fileSize);
            LOGGER.info("  Position will be saved to database with media reference");
        } else {
            LOGGER.warn("0x1212 [FAILED] COMPLETE:");
            LOGGER.warn("  File was not saved successfully");
        }

        logActiveTransfers();

        LOGGER.info("0x1212 COMPLETE");
        LOGGER.info("=".repeat(70));

        return position;
    }

}
