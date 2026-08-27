package org.traccar.handler.toll;

import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.handler.PositionInfoHandler;
import org.traccar.handler.events.TollEventHandler;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Group;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.localCache.RedisCache;
import org.traccar.tollroute.RegionData;
import org.traccar.tollroute.RegionProvider;
import org.traccar.tollroute.TollData;
import org.traccar.tollroute.TollRouteProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives positions through the <em>real</em> {@link PositionInfoHandler} and
 * {@link TollEventHandler}, with the Overpass and region providers stubbed.
 *
 * <p>Nothing here touches the network, Redis or a database. {@code RedisCache.isAvailable()}
 * is stubbed to {@code false} so {@link TollEventHandler} persists its per-device
 * {@code TollRouteState} through its in-process {@code localCache}, which keeps the window
 * alive across positions exactly as Redis would in production.
 *
 * <p>The gate in {@code PositionInfoHandler} is real and unmocked: whether a position is
 * enriched is decided by the same haversine displacement test that runs in production.
 */
public final class TollChainHarness {

    /** What the stubbed Overpass provider should do at a given coordinate. */
    public interface TollOracle {
        /** Return the {@link TollData} to answer with, or {@code null} to fail the lookup. */
        TollData lookup(double latitude, double longitude);
    }

    private final PositionInfoHandler positionInfoHandler;
    private final TollEventHandler tollEventHandler;
    private final List<Event> events = new ArrayList<>();
    private final List<Position> enriched = new ArrayList<>();

    private int lookupCount;
    private int failureCount;

    public TollChainHarness(int minimalDuration, TollOracle oracle) {
        this(minimalDuration, oracle, new Device());
    }

    public TollChainHarness(int minimalDuration, TollOracle oracle, Device device) {
        TollRouteProvider tollRouteProvider = (latitude, longitude, callback) -> {
            TollData data = oracle.lookup(latitude, longitude);
            if (data == null) {
                failureCount++;
                callback.onFailure(new IllegalStateException("stubbed Overpass failure"));
            } else {
                lookupCount++;
                callback.onSuccess(data);
            }
        };
        // The region provider is not under test; answer successfully with nothing to set so
        // PositionInfoHandler's two-callback latch completes.
        RegionProvider regionProvider = (latitude, longitude, callback) ->
                callback.onSuccess(new RegionData(null, null, null));

        this.positionInfoHandler = new PositionInfoHandler(tollRouteProvider, regionProvider);

        Config config = mock(Config.class);
        when(config.getInteger(eq(Keys.EVENT_TOLL_ROUTE_MINIMAL_DURATION))).thenReturn(minimalDuration);

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);
        when(cacheManager.getObject(eq(Group.class), anyLong())).thenReturn(null);
        // null last position => PositionUtil.isLatest is always true for the replayed stream
        when(cacheManager.getPosition(anyLong())).thenReturn(null);

        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.isAvailable()).thenReturn(false);

        this.tollEventHandler = new TollEventHandler(config, cacheManager, mock(Storage.class), redisCache);
    }

    /**
     * Seeds the gate's reference point for a device, so a replay can start mid-stream with the
     * same gate phase production had. The priming position never reaches the event handler and
     * its lookup is not counted.
     *
     * <p>Needed because {@code PositionInfoHandler.lastProcessedPositions} is empty on a fresh
     * process, which makes the first position of any replay unconditionally enriched — an
     * artefact of the replay, not of production, where the map already held an entry.
     */
    public void primeGate(long deviceId, double latitude, double longitude) {
        Position primer = new Position();
        primer.setDeviceId(deviceId);
        primer.setValid(true);
        primer.setLatitude(latitude);
        primer.setLongitude(longitude);
        primer.setTime(new java.util.Date(0));
        positionInfoHandler.handlePosition(primer, filtered -> { });
        lookupCount = 0;
        failureCount = 0;
    }

    /**
     * Runs one position through the enrichment handler and then the toll event handler,
     * mirroring {@code ProcessingHandler}'s ordering (position handlers, then event handlers).
     */
    public void accept(Position position) {
        positionInfoHandler.handlePosition(position, filtered -> { });
        if (position.hasAttribute(Position.KEY_TOLL)) {
            enriched.add(position);
        }
        tollEventHandler.analyzePosition(position, events::add);
    }

    public void acceptAll(List<Position> positions) {
        positions.forEach(this::accept);
    }

    public List<Event> events() {
        return events;
    }

    public List<Event> eventsOfType(String type) {
        return events.stream().filter(e -> type.equals(e.getType())).toList();
    }

    /** Positions that came out of the gate carrying an {@code isToll} attribute. */
    public List<Position> enrichedPositions() {
        return enriched;
    }

    /** Number of successful stubbed Overpass lookups, i.e. positions that cleared the gate. */
    public int lookupCount() {
        return lookupCount;
    }

    public int failureCount() {
        return failureCount;
    }

    /** Convenience oracle: constant answer everywhere. */
    public static TollOracle constant(boolean toll, String ref, String name) {
        return (lat, lon) -> new TollData(toll, ref, name, null, null, null);
    }

    /** Convenience oracle: answer decided by a predicate on the coordinate. */
    public static TollOracle predicate(Function<double[], Boolean> onToll, String ref, String name) {
        return (lat, lon) -> {
            Boolean toll = onToll.apply(new double[]{lat, lon});
            return new TollData(toll, Boolean.TRUE.equals(toll) ? ref : null,
                    Boolean.TRUE.equals(toll) ? name : null, null, null, null);
        };
    }
}
