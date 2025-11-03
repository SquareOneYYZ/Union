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
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.traccar.BaseFrameDecoder;

/**
 * JT1078 Frame Decoder
 *
 * Handles two types of packets:
 * 1. JT808/JT1078 messages (0x7E delimited with escape sequences)
 * 2. T/JSATL12-2017 code stream packets (0x30316364 header)
 *
 * Standard JT808 framing:
 * - Delimiter: 0x7E
 * - Escape sequences:
 *   - 0x7D 0x01 → 0x7D
 *   - 0x7D 0x02 → 0x7E
 *
 * Code stream packet format (Table 4-26):
 * - Frame header: 4 bytes (0x30316364 = "01cd")
 * - Filename: 50 bytes
 * - Data offset: 4 bytes
 * - Data length: 4 bytes
 * - File data: variable length
 */
public class JT1078FrameDecoder extends BaseFrameDecoder {

    private static final int CODE_STREAM_HEADER = 0x30316364;
    private static final int CODE_STREAM_HEADER_SIZE = 62; // 4 + 50 + 4 + 4

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, ByteBuf buf) throws Exception {

        if (buf.readableBytes() < 4) {
            return null;
        }

        // Check first 4 bytes to determine packet type
        int firstFourBytes = buf.getInt(buf.readerIndex());

        // Check if this is a code stream packet (T/JSATL12-2017 Table 4-26)
        if (firstFourBytes == CODE_STREAM_HEADER) {
            return decodeCodeStreamPacket(buf);
        }

        // Otherwise, decode as standard JT808/JT1078 message (0x7E delimited)
        int delimiter = buf.getUnsignedByte(buf.readerIndex());

        if (delimiter != 0x7e) {
            // Not a valid JT808/JT1078 frame or code stream packet
            // Skip this byte and try next
            buf.readByte();
            return null;
        }

        // Find end delimiter
        int index = buf.indexOf(buf.readerIndex() + 1, buf.writerIndex(), (byte) delimiter);
        if (index >= 0) {
            ByteBuf result = Unpooled.buffer(index + 1 - buf.readerIndex());

            // Process escape sequences
            while (buf.readerIndex() <= index) {
                int b = buf.readUnsignedByte();
                if (b == 0x7d) {
                    int ext = buf.readUnsignedByte();
                    if (ext == 0x01) {
                        result.writeByte(0x7d);
                    } else if (ext == 0x02) {
                        result.writeByte(0x7e);
                    }
                } else {
                    result.writeByte(b);
                }
            }

            return result;
        }

        return null;
    }

    /**
     * Decodes T/JSATL12-2017 code stream packet
     * Format: [4-byte header][50-byte filename][4-byte offset][4-byte length][data]
     */
    private Object decodeCodeStreamPacket(ByteBuf buf) {
        if (buf.readableBytes() < CODE_STREAM_HEADER_SIZE) {
            // Need at least the full header
            return null;
        }

        // Mark reader index in case we need to wait for more data
        buf.markReaderIndex();

        // Skip frame header (already verified it's 0x30316364)
        buf.skipBytes(4);

        // Skip filename (50 bytes)
        buf.skipBytes(50);

        // Skip data offset (4 bytes)
        buf.skipBytes(4);

        // Read data length (4 bytes)
        long dataLength = buf.readUnsignedInt();

        // Reset to marked position to read entire packet
        buf.resetReaderIndex();

        // Calculate total packet size
        long totalPacketSize = CODE_STREAM_HEADER_SIZE + dataLength;

        // Check if we have the complete packet
        if (buf.readableBytes() < totalPacketSize) {
            // Wait for more data
            return null;
        }

        // Read and return the complete packet
        ByteBuf result = buf.readRetainedSlice((int) totalPacketSize);
        return result;
    }

}
