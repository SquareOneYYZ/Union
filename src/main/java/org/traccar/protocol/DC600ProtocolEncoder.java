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
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocolEncoder;
import org.traccar.Protocol;
import org.traccar.config.Config;
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

    private Config config;

    public DC600ProtocolEncoder(Protocol protocol) {
        super(protocol);
    }

    @Inject
    public void setConfig(Config config) {
        this.config = config;
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
                        return DC600ProtocolDecoder.formatMessage(
                                0x7e, DC600ProtocolDecoder.MSG_SEND_TEXT_MESSAGE, id, false, data);
                    } else {
                        return Unpooled.wrappedBuffer(DataConverter.parseHex(command.getString(Command.KEY_DATA)));
                    }
                case Command.TYPE_REBOOT_DEVICE:
                    data.writeByte(1); // number of parameters
                    data.writeByte(0x23); // parameter id
                    data.writeByte(1); // parameter value length
                    data.writeByte(0x03); // restart
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_PARAMETER_SETTING, id, false, data);
                case Command.TYPE_POSITION_PERIODIC:
                    data.writeByte(1); // number of parameters
                    data.writeByte(0x06); // parameter id
                    data.writeByte(4); // parameter value length
                    data.writeInt(command.getInteger(Command.KEY_FREQUENCY));
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_PARAMETER_SETTING, id, false, data);
                case Command.TYPE_ALARM_ARM:
                case Command.TYPE_ALARM_DISARM:
                    data.writeByte(1); // number of parameters
                    data.writeByte(0x24); // parameter id
                    String username = "user";
                    data.writeByte(1 + username.length()); // parameter value length
                    data.writeByte(command.getType().equals(Command.TYPE_ALARM_ARM) ? 0x01 : 0x00);
                    data.writeCharSequence(username, StandardCharsets.US_ASCII);
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_PARAMETER_SETTING, id, false, data);
                case Command.TYPE_ENGINE_STOP:
                case Command.TYPE_ENGINE_RESUME:
                    if (alternative) {
                        data.writeByte(command.getType().equals(Command.TYPE_ENGINE_STOP) ? 0x01 : 0x00);
                        data.writeBytes(time);
                        return DC600ProtocolDecoder.formatMessage(
                                0x7e, DC600ProtocolDecoder.MSG_OIL_CONTROL, id, false, data);
                    } else {
                        data.writeByte(command.getType().equals(Command.TYPE_ENGINE_STOP) ? 0xf0 : 0xf1);
                        return DC600ProtocolDecoder.formatMessage(
                                0x7e, DC600ProtocolDecoder.MSG_TERMINAL_CONTROL, id, false, data);
                    }

                case Command.TYPE_REQUEST_PHOTO:
                    data.writeByte(0x01); // channel number
                    data.writeByte(0x00); // capture command
                    data.writeByte(0x00); // timing enabled
                    data.writeShort(0x0000); // timing interval
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_IMAGE_CAPTURE_REQUEST, id, false, data);

                case Command.TYPE_LIVE_STREAM:
                    LOGGER.info("LIVE STREAM - Received command attributes: {}", command.getAttributes());
                    int channel = command.getInteger(Command.KEY_CHANNEL);
                    // JT/T 1076-2016 channel numbering starts from 1, not 0. Ensure minimum channel is 1.
                    if (channel == 0) {
                        channel = 1;
                        LOGGER.warn("Channel 0 is invalid per JT/T 1076, using channel 1 for device {}",
                                command.getDeviceId());
                    }
                    // Read from config file with defaults
                    String serverIp = config.getString("dc600.livestream.ip", "143.198.33.215");
                    int serverPort = config.getInteger("dc600.livestream.port", 9101);
                    int udpPort = 0;
                    LOGGER.info("LIVE STREAM START REQUEST - Device: {}, Channel: {}, Server: {}:{}(TCP)/{}(UDP)",
                            command.getDeviceId(), channel, serverIp, serverPort, udpPort);
                    LOGGER.debug("LIVE STREAM ATTR CHECK - hasChannel: {}, hasDataType: {}, hasStreamType: {}",
                            command.hasAttribute(Command.KEY_CHANNEL),
                            command.hasAttribute(Command.KEY_DATA_TYPE),
                            command.hasAttribute(Command.KEY_STREAM_TYPE));
                    data.writeByte(serverIp.length() + 1); // Server IP address length (+1 for NULL terminator)
                    data.writeBytes(serverIp.getBytes(StandardCharsets.US_ASCII)); // Server IP address
                    data.writeByte(0x00); // NULL terminator (required by DC600 protocol)
                    data.writeShort(serverPort); // Server video channel listening port (TCP)
                    data.writeShort(udpPort); // Server video channel monitoring port (UDP)
                    data.writeByte(channel); // Channel number (1-based per JT/T 1076-2016)
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
                    // Read from config file with defaults
                    String playbackServerIp = config.getString("dc600.playback.ip", "165.22.228.97");
                    int playbackTcpPort = config.getInteger("dc600.playback.port", 5999);
                    int playbackUdpPort = config.getInteger("dc600.playback.udpPort", 0);
                    data.writeByte(playbackServerIp.length() + 1); // +1 for NULL terminator
                    data.writeBytes(playbackServerIp.getBytes(StandardCharsets.US_ASCII));
                    data.writeByte(0x00); // NULL terminator (required by DC600 protocol)
                    data.writeShort(playbackTcpPort); // TCP port
                    data.writeShort(playbackUdpPort); // UDP port
                    data.writeByte(command.getInteger(Command.KEY_CHANNEL));
                    data.writeByte(0x00);
                    data.writeByte(0x01);
                    data.writeByte(0x01);
                    byte[] startTimeBytes = command.hasAttribute(Command.KEY_START_TIME)
                            ? DataConverter.parseHex(command.getString(Command.KEY_START_TIME)) : time;
                    byte[] endTimeBytes = command.hasAttribute(Command.KEY_END_TIME)
                            ? DataConverter.parseHex(command.getString(Command.KEY_END_TIME)) : time;
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

                case Command.TYPE_GET_DEVICE_STATUS:
                    // Section 8.11: Query terminal hardware/software attributes (0x8107)
                    // Message body is null per spec
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_CHECK_TERMINAL_ATTRIBUTE, id, false,
                            Unpooled.buffer(0));

                case "getTerminalParameters":
                    // Section 8.8: Query terminal parameters (0x8104)
                    // Can query all parameters or specific ones
                    if (command.hasAttribute("parameterIds")) {
                        // Section 8.9: Query specific parameters (0x8106)
                        String[] ids = command.getString("parameterIds").split(",");
                        data.writeByte(ids.length);
                        for (String paramId : ids) {
                            data.writeInt(Integer.parseInt(paramId.trim(), 16));
                        }
                        return DC600ProtocolDecoder.formatMessage(
                                0x7e, DC600ProtocolDecoder.MSG_CHECK_SPECIFIED_PARAMETERS, id, false, data);
                    } else {
                        // Query all parameters - message body is null per spec
                        return DC600ProtocolDecoder.formatMessage(
                                0x7e, DC600ProtocolDecoder.MSG_CHECK_TERMINAL_PARAMETER, id, false,
                                Unpooled.buffer(0));
                    }

                case "retrieveMultimedia":
                    // Section 8.32: Query multimedia files stored on terminal (0x8802)
                    data.writeByte(command.getInteger("multimediaType")); // 0=Image, 1=Audio, 2=Video
                    data.writeByte(command.getInteger("channelId"));      // 0 = all channels
                    data.writeByte(command.getInteger("eventCode"));      // Event code
                    // Start time (BCD[6] - yyMMddHHmmss)
                    String startTime = command.getString("startTime");
                    data.writeBytes(DataConverter.parseHex(startTime));
                    // End time (BCD[6] - yyMMddHHmmss)
                    String endTime = command.getString("endTime");
                    data.writeBytes(DataConverter.parseHex(endTime));
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_RETRIEVE_MULTIMEDIA, id, false, data);

                case "uploadMultimediaByTime":
                    // Section 8.34: Command terminal to upload multimedia files by time range (0x8803)
                    data.writeByte(command.getInteger("multimediaType")); // 0=Image, 1=Audio, 2=Video
                    data.writeByte(command.getInteger("channelId"));      // Channel ID
                    data.writeByte(command.getInteger("eventCode"));      // Event code
                    // Start time (BCD[6])
                    String uploadStartTime = command.getString("startTime");
                    data.writeBytes(DataConverter.parseHex(uploadStartTime));
                    // End time (BCD[6])
                    String uploadEndTime = command.getString("endTime");
                    data.writeBytes(DataConverter.parseHex(uploadEndTime));
                    data.writeByte(command.getInteger("deleteAfter")); // 0=reserve, 1=delete after upload
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_STORE_MULTIMEDIA_UPLOAD, id, false, data);

                case "uploadMultimediaById":
                    // Section 8.35: Request single multimedia file by ID (0x8805)
                    data.writeInt(command.getInteger("multimediaId"));
                    data.writeByte(command.getInteger("deleteAfter")); // 0=reserve, 1=delete after upload
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_SINGLE_MULTIMEDIA_UPLOAD, id, false, data);

                case "confirmAlarm":
                    // Section 8.14: Manually confirm alarm (0x8203)
                    data.writeShort(command.getInteger("alarmSerial")); // Alarm message serial number
                    data.writeInt(command.getInteger("alarmType"));     // Alarm type bits (Table 36)
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_MANUALLY_CONFIRM_ALARM, id, false, data);

                case "videoPlaybackControl":
                    // JT/T 1078-2016: Video playback control (0x9202)
                    int playbackChannel = command.getInteger(Command.KEY_CHANNEL);
                    int controlCode = command.getInteger("controlCode");
                    // 0=start, 1=pause, 2=resume, 3=stop, 4=fast forward, 5=slow playback
                    data.writeByte(playbackChannel);
                    data.writeByte(controlCode);
                    data.writeByte(command.getInteger("playbackSpeed")); // Fast forward/slow speed
                    data.writeByte(command.getInteger("playbackMultiplier")); // Speed multiplier
                    return DC600ProtocolDecoder.formatMessage(
                            0x7e, DC600ProtocolDecoder.MSG_VIDEO_PLAYBACK_CONTROL, id, false, data);

                default:
                    return null;
            }
        } finally {
            id.release();
        }
    }

}
