package org.traccar.tollroute;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(OverPassTollRouteProvider.class);
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
        //for region
//        final String baseurl = "https://overpass-api.de/api/interpreter";
        this.accuracy = config.getInteger(Keys.TOLL_ROUTE_ACCURACY);
        this.roundingDecimals = config.getInteger(Keys.TOLL_ROUTE_ROUNDING_DECIMALS);
//        this.url = baseurl + "?data=[out:json];way(around:" + accuracy + ",%f,%f);out%%20tags;";
        //for speeding camera
        this.url = baseurl + "?data=[out:json];(way(around:" + accuracy + ",%1$f,%2$f);"
                + "node(around:100,%1$f,%2$f););out%%20tags;";
       //for region
//        this.url = baseurl + "?data=[out:json];is_in(%f,%f);out%%20tags;";

    }

    @Override
    public void getTollRoute(double latitude, double longitude, TollRouteProviderCallback callback) {
        String cacheKey = generateCacheKey(latitude, longitude);
        LOGGER.debug("Getting toll route for coordinates: {}, {}", latitude, longitude);
        if (redisCache.isAvailable()) {
            try {
                String cachedData = redisCache.get(cacheKey);
                if (cachedData != null) {
                    LOGGER.debug("Cache hit for coordinates: " + latitude + ", " + longitude);
                    CachedTollData cached = objectMapper.readValue(cachedData, CachedTollData.class);
                    TollData tollData = new TollData(
                            cached.getToll(),
                            cached.getRef(),
                            cached.getName(),
                            cached.getSurface(),
                            cached.getCountry(),
                            cached.getState(),
                            cached.getCity(),
                            cached.getHighway(),
                            cached.getEnforcement()
                    );
                    LOGGER.debug("Cache hit. Restored region: country={}, state={}, city={}",
                            cached.getCountry(), cached.getState(), cached.getCity());
                    callback.onSuccess(tollData);
                    return;
                }
                LOGGER.debug("Cache miss for coordinates: " + latitude + ", " + longitude);
            } catch (Exception e) {
                LOGGER.debug("Error reading from cache: " + e.getMessage());
            }
        } else {
            LOGGER.debug(" Skipping Redis (unavailable), using API directly...");
        }

//  always fallback to API
        makeApiCall(latitude, longitude, cacheKey, callback);

    }
    private Boolean determineToll(String tollKey) {
        return tollKey != null && tollKey.equalsIgnoreCase("yes");
    }

    private void makeApiCall(double latitude, double longitude, String cacheKey, TollRouteProviderCallback callback) {
//        String formattedUrl = String.format(url, latitude, longitude);
        String formattedUrl = String.format(url, latitude, longitude, latitude, longitude);

        LOGGER.debug(" Overpass Query URL: " + formattedUrl);
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
                    LOGGER.debug("Error processing API response: " + e.getMessage());
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
        LOGGER.debug("Raw OverPass API response: {}", json.toString());
        JsonArray elements = json.getJsonArray("elements");
        LOGGER.debug("Number of elements in response: {}", elements.size());
        if (!elements.isEmpty()) {
            Boolean isToll = false;
            String ref = null;
            String name = null;
            String surface = null;
            String country = null;
            String state = null;
            String city = null;
            String highway = null;
            String enforcement = null;

            for (int i = 0; i < elements.size(); i++) {
                JsonObject element = elements.getJsonObject(i);
                LOGGER.info("Element {}: type={}", i, element.getString("type"));
                JsonObject tags = element.getJsonObject("tags");
                if (tags == null) {
                    LOGGER.debug("Element {} has NO tags", i);
                    continue;
                }
                LOGGER.debug("Tags found: {}", tags.toString());
                String elementName = tags.containsKey("name") ? tags.getString("name") : null;
                String adminLevel = tags.containsKey("admin_level") ? tags.getString("admin_level") : null;

                if ("2".equals(adminLevel) && country == null) {
                    country = elementName;
                    LOGGER.debug("Found country: {}", country);
                } else if ("4".equals(adminLevel) && state == null) {
                    state = elementName;
                    LOGGER.debug("Found state: {}", state);
                } else if (("8".equals(adminLevel)
                        || "city".equals(tags.containsKey("border_type")
                        ? tags.getString("border_type") : null)) && city == null) {
                    city = elementName;
                    LOGGER.debug("Found city: {}", city);
                }
                if (surface == null && tags.containsKey("surface")) {
                    surface = tags.getString("surface");
                    LOGGER.debug("Overpass returned surface: " + surface);
                }
                if (tags.containsKey("highway")) {
                    String currentHighway = tags.getString("highway");
                    if (highway == null || currentHighway.equalsIgnoreCase("speed_camera")) {
                        highway = currentHighway;
                        LOGGER.debug("Overpass returned highway tag: {}", highway);
                    }
                }
                if (tags.containsKey("enforcement")) {
                    String currentEnforcement = tags.getString("enforcement");
                    if (enforcement == null || currentEnforcement.equalsIgnoreCase("maxspeed")
                            || currentEnforcement.equalsIgnoreCase("speed")) {
                        enforcement = currentEnforcement;
                        LOGGER.debug("Overpass returned enforcement tag: {}", enforcement);
                    }
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


                if (!isToll && tags.containsKey("toll") && determineToll(tags.getString("toll"))) {
                    isToll = true;
                    LOGGER.debug(" Overpass returned toll: " + isToll);
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
            return new TollData(isToll, ref, name, surface, country, state, city, highway, enforcement);
        } else {
            return new TollData(false, null, null, null, null, null,
                    null, null, null);
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
                    tollData.getCity(),
                    tollData.getHighway(),
                    tollData.getEnforcement()
            );

            String jsonData = objectMapper.writeValueAsString(cached);
            redisCache.setWithTTL(cacheKey, jsonData, CACHE_TTL_SECONDS);

            LOGGER.debug("Cached toll data for key: " + cacheKey);
        } catch (Exception e) {
            LOGGER.debug("Failed to cache toll data: " + e.getMessage());
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

        @JsonProperty("highway")
        private String highway;

        @JsonProperty("enforcement")
        private String enforcement;

        // Default constructor for Jackson
        private CachedTollData() { }

        private CachedTollData(Boolean toll, String ref, String name, String surface,
                               String country, String state, String city, String highway, String enforcement) {
            this.toll = toll;
            this.ref = ref;
            this.name = name;
            this.surface = surface;
            this.country = country;
            this.state = state;
            this.city = city;
            this.highway = highway;
            this.enforcement = enforcement;
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
        String getHighway() {
            return highway;
        }
        String getEnforcement() {
            return enforcement;
        }

    }

}
