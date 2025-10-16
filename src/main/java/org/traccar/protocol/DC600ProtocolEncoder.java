/*
 * Copyright 2017 - 2025 Anton Tananaev (anton@traccar.org)
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocolEncoder;
import org.traccar.Protocol;
import org.traccar.config.Keys;
import org.traccar.helper.DataConverter;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.model.Command;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DC600ProtocolEncoder extends BaseProtocolEncoder {
    private static final Logger LOGGER = LoggerFactory.getLogger(DC600ProtocolEncoder.class);

    public DC600ProtocolEncoder(Protocol protocol) {
        super(protocol);
    }

    @Override
    protected Object encodeCommand(Command command) {

        boolean alternative = AttributeUtil.lookup(
                getCacheManager(), Keys.PROTOCOL_ALTERNATIVE.withPrefix(getProtocolName()), command.getDeviceId());

        ByteBuf id = Unpooled.wrappedBuffer(
                DataConverter.parseHex(getUniqueId(command.getDeviceId())));
        try {
            ByteBuf data = Unpooled.buffer();
            byte[] time = DataConverter.parseHex(new SimpleDateFormat("yyMMddHHmmss").format(new Date()));

            switch (command.getType()) {
                case Command.TYPE_CUSTOM:
                    if ("BSJ".equals(getDeviceModel(command.getDeviceId()))) {
                        data.writeByte(1); // flag
                        var charset = Charset.isSupported("GBK") ? Charset.forName("GBK") : StandardCharsets.US_ASCII;
                        data.writeCharSequence(command.getString(Command.KEY_DATA), charset);
                        return HuabaoProtocolDecoder.formatMessage(
                                0x7e, HuabaoProtocolDecoder.MSG_SEND_TEXT_MESSAGE, id, false, data);
                    } else {
                        return Unpooled.wrappedBuffer(DataConverter.parseHex(command.getString(Command.KEY_DATA)));
                    }
                case Command.TYPE_REBOOT_DEVICE:
                    data.writeByte(1); // number of parameters
                    data.writeByte(0x23); // parameter id
                    data.writeByte(1); // parameter value length
                    data.writeByte(0x03); // restart
                    return HuabaoProtocolDecoder.formatMessage(
                            0x7e, HuabaoProtocolDecoder.MSG_PARAMETER_SETTING, id, false, data);
                case Command.TYPE_POSITION_PERIODIC:
                    data.writeByte(1); // number of parameters
                    data.writeByte(0x06); // parameter id
                    data.writeByte(4); // parameter value length
                    data.writeInt(command.getInteger(Command.KEY_FREQUENCY));
                    return HuabaoProtocolDecoder.formatMessage(
                            0x7e, HuabaoProtocolDecoder.MSG_PARAMETER_SETTING, id, false, data);
                case Command.TYPE_ALARM_ARM:
                case Command.TYPE_ALARM_DISARM:
                    data.writeByte(1); // number of parameters
                    data.writeByte(0x24); // parameter id
                    String username = "user";
                    data.writeByte(1 + username.length()); // parameter value length
                    data.writeByte(command.getType().equals(Command.TYPE_ALARM_ARM) ? 0x01 : 0x00);
                    data.writeCharSequence(username, StandardCharsets.US_ASCII);
                    return HuabaoProtocolDecoder.formatMessage(
                            0x7e, HuabaoProtocolDecoder.MSG_PARAMETER_SETTING, id, false, data);
                case Command.TYPE_ENGINE_STOP:
                case Command.TYPE_ENGINE_RESUME:
                    if (alternative) {
                        data.writeByte(command.getType().equals(Command.TYPE_ENGINE_STOP) ? 0x01 : 0x00);
                        data.writeBytes(time);
                        return HuabaoProtocolDecoder.formatMessage(
                                0x7e, HuabaoProtocolDecoder.MSG_OIL_CONTROL, id, false, data);
                    } else {
                        data.writeByte(command.getType().equals(Command.TYPE_ENGINE_STOP) ? 0xf0 : 0xf1);
                        return HuabaoProtocolDecoder.formatMessage(
                                0x7e, HuabaoProtocolDecoder.MSG_TERMINAL_CONTROL, id, false, data);
                    }

                case Command.TYPE_REQUEST_PHOTO:
                    data.writeByte(0x01); // channel number
                    data.writeByte(0x00); // capture command
                    data.writeByte(0x00); // timing enabled
                    data.writeShort(0x0000); // timing interval
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_IMAGE_CAPTURE_REQUEST, id, false, data);

                case Command.TYPE_LIVE_STREAM:
                    int channel = command.getInteger(Command.KEY_CHANNEL);
                    String serverIp = "143.198.33.215";
                    int serverPort = 9101;
                    int udpPort = 0;
                    LOGGER.info("LIVE STREAM START REQUEST - Device: {}, Channel: {}, Server: {}:{}(TCP)/{}(UDP)",
                            command.getDeviceId(), channel, serverIp, serverPort, udpPort);
                    data.writeByte(serverIp.length()); // Server IP address length
                    data.writeBytes(serverIp.getBytes(StandardCharsets.US_ASCII)); // Server IP address
                    data.writeShort(serverPort); // Server video channel listening port (TCP)
                    data.writeShort(udpPort); // Server video channel monitoring port (UDP)
                    data.writeByte(channel);
                    int dataType = 0; // Audio and video
                    if (command.hasAttribute(Command.KEY_DATA_TYPE)) {
                        dataType = command.getInteger(Command.KEY_DATA_TYPE);
                    }
                    data.writeByte(dataType);
                    int streamType = 0;
                    if (command.hasAttribute(Command.KEY_STREAM_TYPE)) {
                        streamType = command.getInteger(Command.KEY_STREAM_TYPE);
                    }
                    data.writeByte(streamType);
                    LOGGER.debug("Live stream request created - Channel: {}, DataType: {}, StreamType: {},"
                            + " Data length: {}", channel, dataType, streamType, data.readableBytes());
                    ByteBuf message = DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_VIDEO_LIVE_STREAM_REQUEST, id, false, data);
                    // Log the raw message for debugging
                    byte[] rawBytes = new byte[message.readableBytes()];
                    message.getBytes(message.readerIndex(), rawBytes);
                    String hexDump = DataConverter.printHex(rawBytes);
                    LOGGER.info("LIVE STREAM REQUEST - MsgID: 0x{}, Channel: {}, Raw: {}",
                            Integer.toHexString(DC600ProtocolDecoder.MSG_VIDEO_LIVE_STREAM_REQUEST).toUpperCase(),
                            channel, hexDump);
                    return message;


                case Command.TYPE_STOP_LIVE_STREAM:
                    int stopChannel = command.getInteger(Command.KEY_CHANNEL);
                    LOGGER.info("LIVE STREAM STOP REQUEST - Device: {}, Channel: {}",
                            command.getDeviceId(), stopChannel);
                    ByteBuf stopData = Unpooled.buffer();
                    stopData.writeByte(stopChannel);
                    stopData.writeByte(0);
                    stopData.writeByte(0);
                    stopData.writeByte(0);
                    LOGGER.debug("Live stream stop control created - Channel: {}, Data length: {}",
                            stopChannel, stopData.readableBytes());
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_VIDEO_LIVE_STREAM_CONTROL, id,
                            false, stopData);


                case Command.TYPE_VIDEO_PLAYBACK:
                    data.writeByte(command.getInteger(Command.KEY_CHANNEL));
                    data.writeByte(command.getInteger(Command.KEY_VIDEO_TYPE)); // Playback type
                    data.writeByte(command.getInteger(Command.KEY_STREAM_TYPE));
                    data.writeByte(command.getInteger(Command.KEY_STORAGE_TYPE));
                    // Use provided times or default to current time
                    String startTimeStr = command.getString(Command.KEY_START_TIME);
                    String endTimeStr = command.getString(Command.KEY_END_TIME);
                    byte[] startTimeBytes = startTimeStr != null
                            ? DataConverter.parseHex(startTimeStr) : time;
                    byte[] endTimeBytes = endTimeStr != null
                            ? DataConverter.parseHex(endTimeStr) : time;
                    data.writeBytes(startTimeBytes);
                    data.writeBytes(endTimeBytes);
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_VIDEO_PLAYBACK_REQUEST, id, false, data);

                case Command.TYPE_VIDEO_DOWNLOAD:
                    data.writeByte(0x01); // channel number
                    data.writeBytes(time); // start time
                    data.writeBytes(time); // end time
                    data.writeByte(0x01); // alarm flag
                    data.writeByte(0x00); // media type
                    data.writeByte(0x00); // stream type
                    data.writeByte(0x00); // storage type
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_VIDEO_DOWNLOAD_REQUEST, id, false, data);

                case Command.TYPE_AUDIO_STREAM:
                    data.writeByte(0x01); // channel number
                    data.writeByte(0x00); // audio command (start)
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_AUDIO_LIVE_STREAM_REQUEST, id, false, data);

                case Command.TYPE_PTZ_CONTROL:
                    int ptzCommand = command.getInteger(Command.KEY_COMMAND); // Get command parameter
                    data.writeByte(command.getInteger(Command.KEY_CHANNEL)); // Get channel, default to 1
                    data.writeByte(ptzCommand);
                    data.writeByte(command.getInteger(Command.KEY_PARAMETER)); // Get parameter, default to

                    int messageType;
                    switch (ptzCommand) {
                        case 0x01: case 0x02: // rotation
                            messageType = DC600ProtocolDecoder.MSG_PTZ_ROTATION;
                            break;
                        case 0x03: case 0x04: // focus
                            messageType = DC600ProtocolDecoder.MSG_PTZ_FOCUS;
                            break;
                        case 0x05: case 0x06: // aperture
                            messageType = DC600ProtocolDecoder.MSG_PTZ_APERTURE;
                            break;
                        case 0x07: case 0x08: // wiper
                            messageType = DC600ProtocolDecoder.MSG_PTZ_WIPER;
                            break;
                        case 0x09: case 0x0A: // infrared
                            messageType = DC600ProtocolDecoder.MSG_PTZ_INFRARED;
                            break;
                        case 0x0B: case 0x0C: // zoom
                            messageType = DC600ProtocolDecoder.MSG_PTZ_ZOOM;
                            break;
                        default:
                            return null;
                    }
                    return DC600ProtocolDecoder.formatMessage(0x7e, messageType, id, false, data);

                case Command.TYPE_VIDEO_ATTRIBUTES_QUERY:
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_VIDEO_ATTRIBUTES_QUERY, id, false, data);

                case Command.TYPE_VIDEO_RESOURCE_LIST_QUERY:
                    data.writeByte(0x00); // list type
                    data.writeBytes(time); // start time
                    data.writeBytes(time); // end time
                    data.writeByte(0xFF); // all channels
                    data.writeByte(0x00); // media type
                    data.writeByte(0x00); // event type
                    data.writeByte(0x00); // storage type
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_VIDEO_RESOURCE_LIST_QUERY, id, false, data);
                // TODO: add more commands for image, video and live stream
                default:
                    return null;
            }
        } finally {
            id.release();
        }
    }

}
