/*
 * Copyright 2024 Anton Tananaev (anton@traccar.org)
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
package org.traccar;

import com.google.inject.Injector;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.BufferingManager;
import org.traccar.database.NotificationManager;
import org.traccar.handler.*;
import org.traccar.handler.events.*;
import org.traccar.handler.network.AcknowledgementHandler;
import org.traccar.helper.DeviceLogContext;
import org.traccar.helper.PositionLogger;
import org.traccar.model.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.session.cache.CacheManager;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Singleton
@ChannelHandler.Sharable
public class ProcessingHandler extends ChannelInboundHandlerAdapter implements BufferingManager.Callback {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingHandler.class);

    /** Log the first drop for a device, then every this-many, to bound log volume under a stall. */
    private static final int DROP_LOG_INTERVAL = 100;

    private final CacheManager cacheManager;
    private final NotificationManager notificationManager;
    private final PositionLogger positionLogger;
    private final BufferingManager bufferingManager;
    private final List<BasePositionHandler> positionHandlers;
    private final List<BaseEventHandler> eventHandlers;
    private final PostProcessHandler postProcessHandler;

    private final Map<Long, Queue<Position>> queues = new HashMap<>();
    private final int queueMaxSize;

    /**
     * Every shed position is counted, not just logged.
     *
     * <p>A dropped position is never stored: DatabaseHandler is the last handler in the chain, so
     * a position that never enters the chain never reaches it. That is real telemetry loss, and it
     * is the same shape as the defect this workstream exists to remove - something that vanishes
     * without a trace. A bound that sheds invisibly is only a better failure than an OutOfMemory
     * if someone finds out it happened.
     *
     * <p>So: a total, a per-device count, and a WARN carrying both. The per-device map is keyed
     * only by devices that have actually shed, which is bounded by the number of stalled devices
     * rather than by fleet size.
     */
    private final AtomicLong droppedTotal = new AtomicLong();
    private final Map<Long, AtomicLong> droppedByDevice = new ConcurrentHashMap<>();

    private synchronized Queue<Position> getQueue(long deviceId) {
        return queues.computeIfAbsent(deviceId, k -> new LinkedList<>());
    }

    /**
     * Discards a device's queue entry once it is empty.
     *
     * <p>{@code processNextPosition} already removes the device from the cache when its queue
     * drains, but left the entry itself in {@code queues} - so the map grew by one
     * {@code LinkedList} for every device ever seen and never shrank.
     */
    private synchronized void discardQueueIfEmpty(long deviceId) {
        Queue<Position> queue = queues.get(deviceId);
        if (queue != null && queue.isEmpty()) {
            queues.remove(deviceId);
        }
    }

    @Inject
    public ProcessingHandler(
            Injector injector, Config config,
            CacheManager cacheManager, NotificationManager notificationManager, PositionLogger positionLogger) {
        this.cacheManager = cacheManager;
        this.notificationManager = notificationManager;
        this.positionLogger = positionLogger;
        bufferingManager = new BufferingManager(config, this);
        this.queueMaxSize = config.getInteger(Keys.PROCESSING_QUEUE_MAX_SIZE);

        positionHandlers = Stream.of(
                ComputedAttributesHandler.Early.class,
                OutdatedHandler.class,
                TimeHandler.class,
                GeolocationHandler.class,
                HemisphereHandler.class,
                DistanceHandler.class,
                FilterHandler.class,
                GeofenceHandler.class,
                GeocoderHandler.class,
                SpeedLimitHandler.class,
               // TollRouteHandler.class,
                PositionInfoHandler.class,
                MotionHandler.class,
                ComputedAttributesHandler.Late.class,
                EngineHoursHandler.class,
                DriverHandler.class,
                CopyAttributesHandler.class,
                PositionForwardingHandler.class,
                DatabaseHandler.class)
                .map((clazz) -> (BasePositionHandler) injector.getInstance(clazz))
                .filter(Objects::nonNull)
                .toList();

        eventHandlers = Stream.of(
                MediaEventHandler.class,
                CommandResultEventHandler.class,
                OverspeedEventHandler.class,
                TollEventHandler.class,
                SurfaceEventHandler.class,
                RegionEventHandler.class,
                SpeedCameraEventHandler.class,
                BehaviorEventHandler.class,
                FuelEventHandler.class,
                MotionEventHandler.class,
                GeofenceEventHandler.class,
                AlarmEventHandler.class,
                IgnitionEventHandler.class,
                MaintenanceEventHandler.class,
                DriverEventHandler.class)
                .map((clazz) -> (BaseEventHandler) injector.getInstance(clazz))
                .filter(Objects::nonNull)
                .toList();

        postProcessHandler = injector.getInstance(PostProcessHandler.class);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Position position) {
            bufferingManager.accept(ctx, position);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void onReleased(ChannelHandlerContext context, Position position) {
        DeviceLogContext.setDeviceId(position.getDeviceId());
        Queue<Position> queue = getQueue(position.getDeviceId());
        boolean queued;
        boolean dropped = false;
        synchronized (queue) {
            queued = !queue.isEmpty();
            // The queue drains only as each position completes the handler chain, and that chain
            // makes external calls. A stalled provider therefore holds it open indefinitely while
            // the device keeps reporting, and before this bound it grew without limit. Dropping
            // the newest position is the explicit policy: the alternative, dropping the oldest,
            // would reorder a stream that PositionUtil.isLatest and every windowed detector
            // assume is monotonic in fixTime.
            if (queueMaxSize > 0 && queue.size() >= queueMaxSize) {
                dropped = true;
            } else {
                queue.offer(position);
            }
        }
        if (dropped) {
            long deviceTotal = droppedByDevice
                    .computeIfAbsent(position.getDeviceId(), k -> new AtomicLong())
                    .incrementAndGet();
            long total = droppedTotal.incrementAndGet();
            // Every drop is counted; the log is rate-limited so a sustained stall cannot drown the
            // file, but the running totals ride along on each line so nothing is unaccounted for.
            if (deviceTotal == 1 || deviceTotal % DROP_LOG_INTERVAL == 0) {
                LOGGER.warn("Device {} queue is at its {} limit - DROPPING position at {} "
                                + "(never stored). {} dropped for this device, {} process-wide. "
                                + "The handler chain is not draining; check enrichment provider health",
                        position.getDeviceId(), queueMaxSize, position.getFixTime(), deviceTotal, total);
            }
            context.writeAndFlush(new AcknowledgementHandler.EventHandled(position));
            DeviceLogContext.clear();
            return;
        }
        if (!queued) {
            try {
                cacheManager.addDevice(position.getDeviceId(), position.getDeviceId());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            try {
                processPositionHandlers(context, position);
            } finally {
                DeviceLogContext.clear();
            }
        } else {
            DeviceLogContext.clear();
        }
    }

    private void processPositionHandlers(ChannelHandlerContext ctx, Position position) {
        var iterator = positionHandlers.iterator();
        iterator.next().handlePosition(position, new BasePositionHandler.Callback() {
            @Override
            public void processed(boolean filtered) {
                if (!filtered) {
                    if (iterator.hasNext()) {
                        iterator.next().handlePosition(position, this);
                    } else {
                        processEventHandlers(ctx, position);
                    }
                } else {
                    finishedProcessing(ctx, position, true);
                }
            }
        });
    }

    private void processEventHandlers(ChannelHandlerContext ctx, Position position) {
        eventHandlers.forEach(handler -> handler.analyzePosition(
                position, (event) -> notificationManager.updateEvents(Map.of(event, position))));
        finishedProcessing(ctx, position, false);
    }

    private void finishedProcessing(ChannelHandlerContext ctx, Position position, boolean filtered) {
        if (!filtered) {
            postProcessHandler.handlePosition(position, ignore -> {
                positionLogger.log(ctx, position);
                ctx.writeAndFlush(new AcknowledgementHandler.EventHandled(position));
                processNextPosition(ctx, position.getDeviceId());
            });
        } else {
            ctx.writeAndFlush(new AcknowledgementHandler.EventHandled(position));
            processNextPosition(ctx, position.getDeviceId());
        }
    }

    private void processNextPosition(ChannelHandlerContext ctx, long deviceId) {
        DeviceLogContext.setDeviceId(deviceId);
        Queue<Position> queue = getQueue(deviceId);
        Position nextPosition;
        synchronized (queue) {
            queue.poll();
            nextPosition = queue.peek();
        }
        if (nextPosition != null) {
            try {
                processPositionHandlers(ctx, nextPosition);
            } finally {
                DeviceLogContext.clear();
            }
        } else {
            cacheManager.removeDevice(deviceId, deviceId);
            discardQueueIfEmpty(deviceId);
            AtomicLong deviceDrops = droppedByDevice.remove(deviceId);
            if (deviceDrops != null) {
                LOGGER.warn("Device {} queue drained after dropping {} position(s); "
                        + "{} dropped process-wide so far", deviceId, deviceDrops.get(), droppedTotal.get());
            }
            DeviceLogContext.clear();
        }
    }

}
