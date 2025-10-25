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
 * Uses same framing as JT808:
 * - Delimiter: 0x7E
 * - Escape sequences:
 *   - 0x7D 0x01 → 0x7D
 *   - 0x7D 0x02 → 0x7E
 */
public class JT1078FrameDecoder extends BaseFrameDecoder {

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, ByteBuf buf) throws Exception {

        if (buf.readableBytes() < 2) {
            return null;
        }

        // JT1078 uses standard JT808 framing with 0x7E delimiter
        int delimiter = buf.getUnsignedByte(buf.readerIndex());

        if (delimiter != 0x7e) {
            // Not a valid JT808/JT1078 frame
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

}
