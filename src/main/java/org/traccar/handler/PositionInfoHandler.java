package org.traccar.handler;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Position;
import org.traccar.tollroute.TollData;
import org.traccar.tollroute.TollRouteProvider;
import org.traccar.tollroute.ValhallaTraceAttributesProvider;
import org.traccar.tollroute.RegionData;
import org.traccar.tollroute.RegionProvider;

import java.util.concurrent.atomic.AtomicInteger;

@Singleton
public class PositionInfoHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PositionInfoHandler.class);
    private final TollRouteProvider tollRouteProvider;
    private final RegionProvider regionProvider;

    @Inject
    public PositionInfoHandler(TollRouteProvider tollRouteProvider, RegionProvider regionProvider) {
        this.tollRouteProvider = tollRouteProvider;
        this.regionProvider = regionProvider;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (!position.getValid()) {
            callback.processed(false);
            return;
        }

        AtomicInteger pendingCallbacks = new AtomicInteger(2);

        regionProvider.getRegion(position.getLatitude(), position.getLongitude(),
                new RegionProvider.RegionProviderCallback() {
                    @Override
                    public void onSuccess(RegionData data) {
                        if (data.getCountry() != null) {
                            position.set(Position.KEY_COUNTRY, data.getCountry());
                            LOGGER.info("Setting country: {}", data.getCountry());
                        }
                        if (data.getState() != null) {
                            position.set(Position.KEY_STATE, data.getState());
                            LOGGER.info("Setting state: {}", data.getState());
                        }
                        if (data.getCity() != null) {
                            position.set(Position.KEY_CITY, data.getCity());
                            LOGGER.info("Setting city: {}", data.getCity());
                        }
                        if (pendingCallbacks.decrementAndGet() == 0) {
                            callback.processed(false);
                        }
                    }

                    @Override
                    public void onFailure(Throwable e) {
                        LOGGER.warn("LocationIQ region query failed", e);
                        position.set("regionLookupFailed", true);
                        if (pendingCallbacks.decrementAndGet() == 0) {
                            callback.processed(false);
                        }
                    }
                });

        if (tollRouteProvider instanceof ValhallaTraceAttributesProvider valhalla) {
            long unixTimeSec = position.getFixTime() != null
                    ? position.getFixTime().getTime() / 1000L
                    : System.currentTimeMillis() / 1000L;

            valhalla.bufferPoint(
                    position.getDeviceId(),
                    position.getLatitude(),
                    position.getLongitude(),
                    unixTimeSec);

            valhalla.getTollRouteForDevice(position.getDeviceId(),
                    new TollRouteProvider.TollRouteProviderCallback() {
                        @Override
                        public void onSuccess(TollData data) {
                            applyTollData(position, data);
                            if (pendingCallbacks.decrementAndGet() == 0) {
                                callback.processed(false);
                            }
                        }

                        @Override
                        public void onFailure(Throwable e) {
                            LOGGER.warn("Valhalla toll lookup failed", e);
                            if (pendingCallbacks.decrementAndGet() == 0) {
                                callback.processed(false);
                            }
                        }
                    });

        } else {
            // Overpass and any other point-based provider
            tollRouteProvider.getTollRoute(position.getLatitude(), position.getLongitude(),
                    new TollRouteProvider.TollRouteProviderCallback() {
                        @Override
                        public void onSuccess(TollData data) {
                            applyTollData(position, data);
                            if (data.getHighway() != null) {
                                position.set(Position.KEY_HIGHWAY, data.getHighway());
                                LOGGER.info("Setting highway: {}", data.getHighway());
                            }
                            if (data.getEnforcement() != null) {
                                position.set(Position.KEY_ENFORCEMENT, data.getEnforcement());
                                LOGGER.info("Setting enforcement: {}", data.getEnforcement());
                            }
                            if (pendingCallbacks.decrementAndGet() == 0) {
                                callback.processed(false);
                            }
                        }

                        @Override
                        public void onFailure(Throwable e) {
                            LOGGER.warn("Overpass query failed", e);
                            if (pendingCallbacks.decrementAndGet() == 0) {
                                callback.processed(false);
                            }
                        }
                    });
        }
    }

    private static void applyTollData(Position position, TollData data) {
        if (data.getToll() != null) {
            position.set(Position.KEY_TOLL, data.getToll());
        }
        if (data.getRef() != null) {
            position.set(Position.KEY_TOLL_REF, data.getRef());
        }
        if (data.getName() != null) {
            position.set(Position.KEY_TOLL_NAME, data.getName());
        }
        if (data.getSurface() != null) {
            position.set(Position.KEY_SURFACE, data.getSurface());
        }
    }
}
