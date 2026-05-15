package org.traccar.geocoder;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.traccar.storage.localCache.RedisCache;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
public class NominatimGeocoderTest {
    private RedisCache redisCache;
    private Client client;
    private NominatimGeocoder geocoder;

    @BeforeEach
    void setup() {
        redisCache = Mockito.mock(RedisCache.class);
        client = Mockito.mock(Client.class);

        geocoder = new NominatimGeocoder(
                client,
                redisCache,
                "https://nominatim.openstreetmap.org/reverse",
                null,
                "en",
                2000,
                new AddressFormat()
        );
    }

    @Test
    void testReverseGeocode_Cached() {
        String key = "geocode:reverse:12.34567:76.54321";
        String cachedValue = "Test Address From Cache";

        when(redisCache.get(eq(key))).thenReturn(cachedValue);

        String result = geocoder.getAddress(12.34567, 76.54321, null);

        assertEquals(cachedValue, result);
        verify(redisCache, times(1)).get(eq(key));
        verifyNoMoreInteractions(client); // should not hit HTTP
    }

    @Test
    void testForwardGeocode_Cached() {
        String query = "Berlin";
        String key = "geocode:forward:berlin";

        String json = "[{\"display_name\": \"Berlin, Germany\", \"lat\":\"52.5200\", \"lon\":\"13.4050\"}]";

        when(redisCache.get(eq(key))).thenReturn(json);

        Address address = geocoder.getCoordinates(query);

        assertNotNull(address);
        assertEquals("Berlin, Germany", address.getFormattedAddress());
    }

    @Test
    void testForwardGeocode_HttpFetch() {
        String query = "Paris";
        String key = "geocode:forward:paris";

        JsonArray jsonArray = Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                        .add("display_name", "Paris, France")
                        .add("lat", "48.8566")
                        .add("lon", "2.3522"))
                .build();

        when(redisCache.get(eq(key))).thenReturn(null);

        // --- Mock JAX-RS chain ---
        WebTarget target = mock(WebTarget.class);
        WebTarget target2 = mock(WebTarget.class);
        WebTarget target3 = mock(WebTarget.class);
        Invocation.Builder builder = mock(Invocation.Builder.class);

        when(client.target(anyString())).thenReturn(target);
        when(target.queryParam(anyString(), any())).thenReturn(target2);
        when(target2.queryParam(anyString(), any())).thenReturn(target3);
        when(target3.queryParam(anyString(), any())).thenReturn(target3); // limit + q
        when(target3.request()).thenReturn(builder);
        when(builder.get(eq(JsonArray.class))).thenReturn(jsonArray);

        // --- Call method ---
        Address address = geocoder.getCoordinates(query);

        assertNotNull(address);
        assertEquals("Paris, France", address.getFormattedAddress());

        verify(redisCache, times(1)).setWithTTL(eq(key), anyString(), eq(86400));
    }

}
