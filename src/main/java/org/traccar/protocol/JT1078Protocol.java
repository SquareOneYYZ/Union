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

import jakarta.inject.Inject;
import org.traccar.BaseProtocol;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;

/**
 * JT/T 1078-2016 Video Communication Protocol
 *
 * This protocol handles media uploads from JT808-compatible devices.
 * It receives 0x1210 (Alarm Attachment Info) messages in response to
 * 0x9208 (Alarm Attachment Upload Request) commands sent by DC600.
 *
 * Default port: 60001
 */
public class JT1078Protocol extends BaseProtocol {

    @Inject
    public JT1078Protocol(Config config) {
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new JT1078FrameDecoder());
                pipeline.addLast(new JT1078ProtocolDecoder(JT1078Protocol.this));
            }
        });
    }

}
