package org.traccar.tollroute;

public interface RegionProvider {
    interface RegionProviderCallback {
        void onSuccess(RegionData regionData);

        void onFailure(Throwable e);
    }

    void getRegion(double latitude, double longitude, RegionProviderCallback callback);
}
