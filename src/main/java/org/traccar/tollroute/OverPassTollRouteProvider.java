package org.traccar.tollroute;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.InvocationCallback;
import org.traccar.storage.localCache.RedisCache;

@Singleton
public class OverPassTollRouteProvider implements TollRouteProvider {
    private final Client client;
    private final String url;
    private final int accuracy;
    private final int roundingDecimals;
    private final RedisCache redisCache;
    private final ObjectMapper objectMapper;
    private static final int CACHE_TTL_SECONDS = 86400;
    private static final String CACHE_KEY_PREFIX = "toll_route:";

    public OverPassTollRouteProvider(Config config, Client client, String url, RedisCache redisCache) {
        this.client = client;
        this.redisCache = redisCache;
        this.objectMapper = new ObjectMapper();
        //! got the url from the config , and set it using the base url , hope this works :)
        final String baseurl = config.getString(Keys.TOLL_ROUTE_URL, url);
        this.accuracy = config.getInteger(Keys.TOLL_ROUTE_ACCURACY);
        this.roundingDecimals = config.getInteger(Keys.TOLL_ROUTE_ROUNDING_DECIMALS);
        //  this.url = baseurl + "?data=[out:json];way[toll=yes](around:" + accuracy + ",%f,%f);out%%20tags;";
//        this.url = baseurl + "?data=[out:json];way(around:" + accuracy + ",%f,%f);out%%20tags;";
     this.url = baseurl + "?data=[out:json];is_in(%f,%f);out%%20tags;way(around:" + accuracy + ",%f,%f);out%%20tags;";


    }

    @Override
    public void getTollRoute(double latitude, double longitude, TollRouteProviderCallback callback) {
        String cacheKey = generateCacheKey(latitude, longitude);
        if (redisCache.isAvailable()) {
            try {
                String cachedData = redisCache.get(cacheKey);
                if (cachedData != null) {
                    System.out.println("Cache hit for coordinates: " + latitude + ", " + longitude);
                    CachedTollData cached = objectMapper.readValue(cachedData, CachedTollData.class);
                    TollData tollData = new TollData(
                            cached.getToll(),
                            cached.getRef(),
                            cached.getName(),
                            cached.getSurface(),
                            cached.getCountry(),
                            cached.getState(),
                            cached.getCity()
                    );
                    callback.onSuccess(tollData);
                    return;
                }
                System.out.println("Cache miss for coordinates: " + latitude + ", " + longitude);
            } catch (Exception e) {
                System.err.println("Error reading from cache: " + e.getMessage());
            }
        } else {
            System.out.println(" Skipping Redis (unavailable), using API directly...");
        }

//  always fallback to API
        makeApiCall(latitude, longitude, cacheKey, callback);

    }
    private Boolean determineToll(String tollKey) {
        return tollKey != null && tollKey.equalsIgnoreCase("yes");
    }

    private void makeApiCall(double latitude, double longitude, String cacheKey, TollRouteProviderCallback callback) {
        String formattedUrl = String.format(url, latitude, longitude);
        System.out.println(" Overpass Query URL: " + formattedUrl);
        AsyncInvoker invoker = client.target(formattedUrl).request().async();
        invoker.get(new InvocationCallback<JsonObject>() {
            @Override
            public void completed(JsonObject json) {
                try {
                    TollData tollData = processApiResponse(json);
                    // Cache the result with TTL
                    cacheResult(cacheKey, tollData);

                    callback.onSuccess(tollData);
                } catch (Exception e) {
                    System.err.println("Error processing API response: " + e.getMessage());
                    callback.onFailure(e);
                }
            }
            @Override
            public void failed(Throwable throwable) {
                System.err.println("API call failed: " + throwable.getMessage());
                callback.onFailure(throwable);
            }
        });
    }

    private TollData processApiResponse(JsonObject json) {
        JsonArray elements = json.getJsonArray("elements");
        if (!elements.isEmpty()) {
            Boolean isToll = false;
            String ref = null;
            String name = null;
            String surface = null;
            String country = null;
            String state = null;
            String city = null;

            for (int i = 0; i < elements.size(); i++) {
                JsonObject element = elements.getJsonObject(i);
                JsonObject tags = element.getJsonObject("tags");
                if (tags == null) {
                    continue;
                }
                // Collect surface if available
                if (surface == null && tags.containsKey("surface")) {
                    surface = tags.getString("surface");
                    System.out.println("Overpass returned surface: " + surface);
                }
                if (tags.containsKey("addr:country")) {
                    country = tags.getString("addr:country");
                }
                if (tags.containsKey("addr:state")) {
                    state = tags.getString("addr:state");
                }
                if (tags.containsKey("addr:city")) {
                    city = tags.getString("addr:city");
                }
                if (country == null && tags.containsKey("is_in:country")) {
                    country = tags.getString("is_in:country");
                }
                if (state == null && tags.containsKey("is_in:state")) {
                    state = tags.getString("is_in:state");
                }
                if (city == null && tags.containsKey("is_in:city")) {
                    city = tags.getString("is_in:city");
                }


                // Toll-specific check
                if (!isToll && tags.containsKey("toll") && determineToll(tags.getString("toll"))) {
                    isToll = true;
                    System.out.println(" Overpass returned toll: " + isToll);
                }
                if (ref == null && tags.containsKey("ref")) {
                    ref = tags.getString("ref");
                }
                if (ref == null && tags.containsKey("destination:ref")) {
                    ref = tags.getString("destination:ref");
                }
                if (name == null && tags.containsKey("name")) {
                    name = tags.getString("name");
                }
                if (name == null && tags.containsKey("destination:name")) {
                    name = tags.getString("destination:name");
                }
                if (isToll && ref != null && name != null && surface != null) {
                    if (country == null && tags.containsKey("addr:country")) {
                        country = tags.getString("addr:country");
                    }
                    if (state == null && tags.containsKey("addr:state")) {
                        state = tags.getString("addr:state");
                    }
                    if (city == null && tags.containsKey("addr:city")) {
                        city = tags.getString("addr:city");
                    }
                    if (country == null && tags.containsKey("is_in:country")) {
                        country = tags.getString("is_in:country");
                    }
                    if (state == null && tags.containsKey("is_in:state")) {
                        state = tags.getString("is_in:state");
                    }
                    if (city == null && tags.containsKey("is_in:city")) {
                        city = tags.getString("is_in:city");
                    }
                    break;
                }
            }
            return new TollData(isToll, ref, name, surface, country, state, city);
        } else {
            return new TollData(false, null, null, null, null, null, null);
        }
    }

    private void cacheResult(String cacheKey, TollData tollData) {
        try {
            CachedTollData cached = new CachedTollData(
                    tollData.getToll(),
                    tollData.getRef(),
                    tollData.getName(),
                    tollData.getSurface(),
                    tollData.getCountry(),
                    tollData.getState(),
                    tollData.getCity()
            );

            String jsonData = objectMapper.writeValueAsString(cached);
            // Set with TTL (24 hours)
            redisCache.setWithTTL(cacheKey, jsonData, CACHE_TTL_SECONDS);

            System.out.println("Cached toll data for key: " + cacheKey);
        } catch (Exception e) {
            System.err.println("Failed to cache toll data: " + e.getMessage());
            // Don't fail the whole operation if caching fails
        }
    }

    private String generateCacheKey(double latitude, double longitude) {
        double scale = Math.pow(10, roundingDecimals);  // 10^decimals
        double roundedLat = Math.round(latitude * scale) / scale;
        double roundedLon = Math.round(longitude * scale) / scale;
        String format = "%." + roundingDecimals + "f:%.";
        format += roundingDecimals + "f";
        return CACHE_KEY_PREFIX + String.format(format, roundedLat, roundedLon);
    }
    // Inner class for JSON serialization
    private static final class CachedTollData {
        @JsonProperty("toll")
        private Boolean toll;

        @JsonProperty("ref")
        private String ref;

        @JsonProperty("name")
        private String name;

        @JsonProperty("surface")
        private String surface;

        @JsonProperty("country")
        private String country;

        @JsonProperty("state")
        private String state;

        @JsonProperty("city")
        private String city;

        // Default constructor for Jackson
        private CachedTollData() { }

        private CachedTollData(Boolean toll, String ref, String name, String surface,
                               String country, String state, String city) {
            this.toll = toll;
            this.ref = ref;
            this.name = name;
            this.surface = surface;
            this.country = country;
            this.state = state;
            this.city = city;
        }

        Boolean getToll() {
            return toll;
        }
        String getRef() {
            return ref;
        }
        String getName() {
            return name;
        }
        String getSurface() {
            return surface;
        }
        String getCountry() {
            return country;
        }
        String getState() {
            return state;
        }
        String getCity() {
            return city;
        }

    }

}
