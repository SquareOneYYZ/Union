/*
 * Copyright 2014 - 2017 Anton Tananaev (anton@traccar.org)
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
package org.traccar.geocoder;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.storage.localCache.RedisCache;

import java.io.StringReader;

public class NominatimGeocoder extends JsonGeocoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(NominatimGeocoder.class);

    private static String formatUrl(String url, String key, String language) {
        if (url == null) {
            url = "https://nominatim.openstreetmap.org/reverse";
        }
        url += "?format=json&lat=%f&lon=%f&zoom=18&addressdetails=1";
        if (key != null) {
            url += "&key=" + key;
        }
        if (language != null) {
            url += "&accept-language=" + language;
        }
        return url;
    }

    private final RedisCache redisManager;
    private final Client client;

//    public NominatimGeocoder(
//            Client client, String url, String key, String language, int cacheSize, AddressFormat addressFormat) {
//        super(client, formatUrl(url, key, language), cacheSize, addressFormat);
//    }

    @Inject
    public NominatimGeocoder(
            Client client,
            RedisCache redisManager,
            @Named("geocoder.url") String url,
            @Named("geocoder.key") String key,
            @Named("geocoder.language") String language,
            @Named("geocoder.cacheSize") int cacheSize,
            AddressFormat addressFormat) {

        super(client, formatUrl(url, key, language), cacheSize, addressFormat);
        this.client = client;
        this.redisManager = redisManager;
    }

    @Override
    public Address parseAddress(JsonObject json) {
        JsonObject result = json.getJsonObject("address");

        if (result != null) {
            Address address = new Address();

            if (json.containsKey("display_name")) {
                address.setFormattedAddress(json.getString("display_name"));
            }

            if (result.containsKey("house_number")) {
                address.setHouse(result.getString("house_number"));
            }
            if (result.containsKey("road")) {
                address.setStreet(result.getString("road"));
            }
            if (result.containsKey("suburb")) {
                address.setSuburb(result.getString("suburb"));
            }

            if (result.containsKey("village")) {
                address.setSettlement(result.getString("village"));
            } else if (result.containsKey("town")) {
                address.setSettlement(result.getString("town"));
            } else if (result.containsKey("city")) {
                address.setSettlement(result.getString("city"));
            }

            if (result.containsKey("state_district")) {
                address.setDistrict(result.getString("state_district"));
            } else if (result.containsKey("region")) {
                address.setDistrict(result.getString("region"));
            }

            if (result.containsKey("state")) {
                address.setState(result.getString("state"));
            }
            if (result.containsKey("country_code")) {
                address.setCountry(result.getString("country_code").toUpperCase());
            }
            if (result.containsKey("postcode")) {
                address.setPostcode(result.getString("postcode"));
            }

            return address;
        }

        return null;
    }

    //  Override reverse geocoding to use Redis cache
    @Override
    public String getAddress(double latitude, double longitude, ReverseGeocoderCallback callback) {
        String key = String.format("geocode:reverse:%.5f:%.5f", latitude, longitude);

        // Try Redis first
        String cached = redisManager.get(key);
        if (cached != null) {
            LOGGER.debug("[Geocoder] Cache HIT for key={} (lat={}, lon={})", key, latitude, longitude);
            if (callback != null) {
                callback.onSuccess(cached);
            }
            return cached;
        }

        LOGGER.debug("[Geocoder] Cache MISS for key={} (lat={}, lon={}), calling Nominatim API",
                key, latitude, longitude);
        // Fall back to normal JsonGeocoder logic
        String result = super.getAddress(latitude, longitude, callback);
        if (result != null) {
            redisManager.setWithTTL(key, result, 86400);
            LOGGER.debug("[Geocoder] Stored result in Redis with TTL=86400s, key={}", key);
        } else {
            LOGGER.warn("[Geocoder] API returned NULL for lat={}, lon={}", latitude, longitude);
        }
        return result;
    }

    //  Add forward geocoding support
    public Address getCoordinates(String query) {
        String key = "geocode:forward:" + query.toLowerCase();

        // Try Redis
        String cached = redisManager.get(key);
        if (cached != null) {
            LOGGER.debug("[Geocoder] Forward cache HIT for query='{}'", query);
            try {
                JsonArray results = Json.createReader(new StringReader(cached)).readArray();
                if (!results.isEmpty()) {
                    return parseForward(results.getJsonObject(0));
                }
            } catch (Exception e) {
                LOGGER.warn("[Geocoder] Failed to parse cached forward geocode for '{}'", query, e);
            }
        }
        LOGGER.debug("[Geocoder] Forward cache MISS for query='{}', calling Nominatim API", query);
        try {
            JsonArray results = client.target("https://nominatim.openstreetmap.org/search")
                    .queryParam("format", "json")
                    .queryParam("limit", 1)
                    .queryParam("q", query)
                    .request()
                    .get(JsonArray.class);

            if (!results.isEmpty()) {
                JsonObject json = results.getJsonObject(0);
                redisManager.setWithTTL(key, results.toString(), 86400);
                LOGGER.debug("[Geocoder] Forward geocode result stored in Redis with TTL=86400s, query='{}'", query);
                return parseForward(json);
            }
        } catch (Exception e) {
            LOGGER.error("[Geocoder] Forward geocoding error for '{}'", query, e);
        }

        return null;
    }

    private Address parseForward(JsonObject json) {
        Address address = new Address();
        if (json.containsKey("display_name")) {
            address.setFormattedAddress(json.getString("display_name"));
        }
        if (json.containsKey("lat") && json.containsKey("lon")) {
            LOGGER.trace("Coordinates present but not stored in Address");
        }
        return address;
    }



}
