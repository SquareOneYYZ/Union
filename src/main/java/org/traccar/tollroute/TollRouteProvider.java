package org.traccar.tollroute;

import org.traccar.model.Position;

public interface TollRouteProvider {

    interface TollRouteProviderCallback {
        void onSuccess(TollData tollCost);
        void onFailure(Throwable e);
    }


    void getTollRoute(double latitude, double longitude, TollRouteProviderCallback callback);


    default void getTollRoute(Position position, TollRouteProviderCallback callback) {
        getTollRoute(position.getLatitude(), position.getLongitude(), callback);
    }
}
