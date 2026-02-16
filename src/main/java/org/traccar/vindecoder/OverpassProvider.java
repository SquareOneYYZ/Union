package org.traccar.vindecoder;

import java.util.List;

public interface OverpassProvider {
    interface Callback {
        void onSuccess(List<TollWay> tollWays);
        void onFailure(Throwable e);
    }

    void fetchTollWays(String query, Callback callback);
}
