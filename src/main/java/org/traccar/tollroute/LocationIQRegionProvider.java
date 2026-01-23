package org.traccar.tollroute;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import jakarta.json.JsonObject;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.InvocationCallback;
import org.traccar.storage.localCache.RedisCache;

@Singleton
public class LocationIQRegionProvider implements RegionProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocationIQRegionProvider.class);
    private final Client client;
    private final String url;
    private final int roundingDecimals;
    private final RedisCache redisCache;
    private final ObjectMapper objectMapper;
    private static final int CACHE_TTL_SECONDS = 86400;
    private static final String CACHE_KEY_PREFIX = "region:";

    public LocationIQRegionProvider(Config config, Client client, String url, RedisCache redisCache) {
        this.client = client;
        this.redisCache = redisCache;
        this.objectMapper = new ObjectMapper();

        final String baseUrl = config.getString(Keys.REGION_PROVIDER_URL, url);
        final String apiKey = config.getString(Keys.GEOCODER_KEY);
        this.roundingDecimals = config.getInteger(Keys.REGION_CACHE_ROUNDING_DECIMALS, 4);
        this.url = baseUrl + "?key=" + apiKey + "&lat=%f&lon=%f&format=json";
        LOGGER.info("LocationIQRegionProvider initialized with URL: {}", baseUrl);
    }

    @Override
    public void getRegion(double latitude, double longitude, RegionProviderCallback callback) {
        String cacheKey = generateCacheKey(latitude, longitude);
        LOGGER.debug("Getting region for coordinates: {}, {}", latitude, longitude);
        if (redisCache.isAvailable()) {
            try {
                String cachedData = redisCache.get(cacheKey);
                if (cachedData != null) {
                    LOGGER.debug("Cache hit for coordinates: {}, {}", latitude, longitude);
                    CachedRegionData cached = objectMapper.readValue(cachedData, CachedRegionData.class);
                    RegionData regionData = new RegionData(
                            cached.getCountry(),
                            cached.getState(),
                            cached.getCity()
                    );
                    LOGGER.debug("Cache hit. Restored region: country={}, state={}, city={}",
                            cached.getCountry(), cached.getState(), cached.getCity());
                    callback.onSuccess(regionData);
                    return;
                }
                LOGGER.debug("Cache miss for coordinates: {}, {}", latitude, longitude);
            } catch (Exception e) {
                LOGGER.debug("Error reading from cache: {}", e.getMessage());
            }
        } else {
            LOGGER.debug("Skipping Redis (unavailable), using API directly...");
        }

        makeApiCall(latitude, longitude, cacheKey, callback);
    }

    private void makeApiCall(double latitude, double longitude, String cacheKey, RegionProviderCallback callback) {
        String formattedUrl = String.format(url, latitude, longitude);

        LOGGER.debug("LocationIQ Query URL: {}", formattedUrl);
        AsyncInvoker invoker = client.target(formattedUrl).request().async();
        invoker.get(new InvocationCallback<JsonObject>() {
            @Override
            public void completed(JsonObject json) {
                try {
                    RegionData regionData = processApiResponse(json);
                    cacheResult(cacheKey, regionData);

                    callback.onSuccess(regionData);
                } catch (Exception e) {
                    LOGGER.debug("Error processing API response: {}", e.getMessage());
                    callback.onFailure(e);
                }
            }

            @Override
            public void failed(Throwable throwable) {
                LOGGER.error("LocationIQ API call failed: {}", throwable.getMessage());
                callback.onFailure(throwable);
            }
        });
    }

    private RegionData processApiResponse(JsonObject json) {
        LOGGER.debug("Raw LocationIQ API response: {}", json.toString());
        String country = null;
        String state = null;
        String city = null;

        if (json.containsKey("address")) {
            JsonObject address = json.getJsonObject("address");
            if (address.containsKey("country")) {
                country = address.getString("country");
                LOGGER.debug("Found country: {}", country);
            }
            if (address.containsKey("state")) {
                state = address.getString("state");
                LOGGER.debug("Found state: {}", state);
            }
            if (address.containsKey("city")) {
                city = address.getString("city");
                LOGGER.debug("Found city: {}", city);
            } else if (address.containsKey("town")) {
                city = address.getString("town");
                LOGGER.debug("Found town (as city): {}", city);
            } else if (address.containsKey("village")) {
                city = address.getString("village");
                LOGGER.debug("Found village (as city): {}", city);
            }
        }

        return new RegionData(country, state, city);
    }

    private void cacheResult(String cacheKey, RegionData regionData) {
        try {
            CachedRegionData cached = new CachedRegionData(
                    regionData.getCountry(),
                    regionData.getState(),
                    regionData.getCity()
            );

            String jsonData = objectMapper.writeValueAsString(cached);
            redisCache.setWithTTL(cacheKey, jsonData, CACHE_TTL_SECONDS);

            LOGGER.debug("Cached region data for key: {}", cacheKey);
        } catch (Exception e) {
            LOGGER.debug("Failed to cache region data: {}", e.getMessage());
        }
    }

    private String generateCacheKey(double latitude, double longitude) {
        double scale = Math.pow(10, roundingDecimals);  // 10^decimals
        double roundedLat = Math.round(latitude * scale) / scale;
        double roundedLon = Math.round(longitude * scale) / scale;
        String format = "%." + roundingDecimals + "f:%." + roundingDecimals + "f";
        return CACHE_KEY_PREFIX + String.format(format, roundedLat, roundedLon);
    }

    private static final class CachedRegionData {
        @JsonProperty("country")
        private String country;

        @JsonProperty("state")
        private String state;

        @JsonProperty("city")
        private String city;

        private CachedRegionData() { }

        private CachedRegionData(String country, String state, String city) {
            this.country = country;
            this.state = state;
            this.city = city;
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
