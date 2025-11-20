package org.traccar.handler;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Position;
import org.traccar.tollroute.TollData;
import org.traccar.tollroute.TollRouteProvider;

@Singleton
public class PositionInfoHandler extends BasePositionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PositionInfoHandler.class);
    private final TollRouteProvider tollRouteProvider;

    @Inject
    public PositionInfoHandler(TollRouteProvider tollRouteProvider) {
        this.tollRouteProvider = tollRouteProvider;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (position.getValid()) {
            tollRouteProvider.getTollRoute(position.getLatitude(), position.getLongitude(),
                    new TollRouteProvider.TollRouteProviderCallback() {
                        @Override
                        public void onSuccess(TollData data) {
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
                            if (data.getHighway() != null) {
                                position.set(Position.KEY_HIGHWAY, data.getHighway());
                                LOGGER.info("Setting highway: {}", data.getHighway());
                            }

                            callback.processed(false);
                        }

                        @Override
                        public void onFailure(Throwable e) {
                            LOGGER.warn("Overpass query failed", e);
                            callback.processed(false);
                        }
                    });
        } else {
            callback.processed(false);
        }
    }
}
