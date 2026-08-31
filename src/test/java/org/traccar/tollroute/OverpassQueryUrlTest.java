package org.traccar.tollroute;

import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.speedlimit.OverpassSpeedLimitProvider;
import org.traccar.storage.localCache.RedisCache;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Captures the URL the two Overpass providers actually request.
 *
 * <p>Both query strings are assembled by concatenation and then run through {@code String.format},
 * which makes them easy to break silently: a stray {@code %}, a lost {@code %%20}, a positional
 * index that no longer matches the argument count. A malformed query does not fail loudly - it
 * comes back as an Overpass error for <em>every</em> lookup, which after stage 1 reads as
 * "unknown" and simply stops toll detection working.
 *
 * <p>Added because {@code [timeout:N]} was inserted into both without any test asserting the
 * result.
 */
public class OverpassQueryUrlTest {

    private static final double LATITUDE = 43.638320;
    private static final double LONGITUDE = -79.729848;

    /** Mocks the {@code client.target(url).request().async()} chain and captures the URL. */
    private String captureUrl(java.util.function.Consumer<Client> invocation) {
        Client client = mock(Client.class);
        WebTarget target = mock(WebTarget.class);
        Invocation.Builder builder = mock(Invocation.Builder.class);
        AsyncInvoker async = mock(AsyncInvoker.class);

        when(client.target(anyString())).thenReturn(target);
        when(target.request()).thenReturn(builder);
        when(builder.async()).thenReturn(async);

        invocation.accept(client);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(client).target(captor.capture());
        return captor.getValue();
    }

    private Config config() {
        Config config = mock(Config.class);
        when(config.getString(Keys.TOLL_ROUTE_URL, "http://overpass.test/api/interpreter"))
                .thenReturn("http://overpass.test/api/interpreter");
        when(config.getInteger(Keys.TOLL_ROUTE_ACCURACY)).thenReturn(10);
        when(config.getInteger(Keys.TOLL_ROUTE_ROUNDING_DECIMALS)).thenReturn(3);
        when(config.getInteger(Keys.SPEED_LIMIT_ACCURACY)).thenReturn(100);
        when(config.getInteger(Keys.ENRICHMENT_OVERPASS_QUERY_TIMEOUT)).thenReturn(10);
        return config;
    }

    private String tollUrl() {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.isAvailable()).thenReturn(false);
        return captureUrl(client -> new OverPassTollRouteProvider(
                config(), client, "http://overpass.test/api/interpreter", redisCache)
                .getTollRoute(LATITUDE, LONGITUDE, mock(TollRouteProvider.TollRouteProviderCallback.class)));
    }

    private String speedLimitUrl() {
        return captureUrl(client -> new OverpassSpeedLimitProvider(
                config(), client, "http://overpass.test/api/interpreter")
                .getSpeedLimit(LATITUDE, LONGITUDE,
                        mock(org.traccar.speedlimit.SpeedLimitProvider.SpeedLimitProviderCallback.class)));
    }

    /**
     * The whole toll query, asserted literally. A diff on this string is the point: any change to
     * the query is a change to what the feature detects, and should be a deliberate edit here too.
     */
    @Test
    public void tollQueryUrlIsExactlyAsExpected() {
        assertEquals(
                "http://overpass.test/api/interpreter?data=[out:json][timeout:10];"
                        + "(way(around:10,43.638320,-79.729848);"
                        + "node(around:100,43.638320,-79.729848););out%20tags;",
                tollUrl());
    }

    /** The same for the ungated speed-limit query, which is the larger share of Overpass traffic. */
    @Test
    public void speedLimitQueryUrlIsExactlyAsExpected() {
        assertEquals(
                "http://overpass.test/api/interpreter?data=[out:json][timeout:10];"
                        + "way[maxspeed](around:100,43.638320,-79.729848);out%20tags;",
                speedLimitUrl());
    }

    /**
     * {@code [timeout:N]} must be a global setting - chained with {@code [out:json]} before the
     * first semicolon - not a statement. Overpass rejects it anywhere else.
     */
    @Test
    public void timeoutIsAGlobalSettingBeforeTheFirstStatement() {
        for (String url : new String[]{tollUrl(), speedLimitUrl()}) {
            String settings = url.substring(url.indexOf("?data=") + 6, url.indexOf(';'));
            assertEquals("[out:json][timeout:10]", settings,
                    "settings block must carry both directives and nothing else");
        }
    }

    /**
     * The format string must be fully consumed. A surviving {@code %f}, {@code %1$f} or a literal
     * {@code %%} means the substitution silently did not happen, and the request would go out with
     * placeholders in place of coordinates.
     */
    @Test
    public void noFormatSpecifiersSurviveIntoTheRequest() {
        for (String url : new String[]{tollUrl(), speedLimitUrl()}) {
            assertFalse(url.contains("%f"), "unsubstituted %f in " + url);
            assertFalse(url.contains("%1$"), "unsubstituted positional specifier in " + url);
            assertFalse(url.contains("%%"), "literal %% survived into " + url);
            assertTrue(url.contains("%20"), "the encoded space in 'out tags' must survive: " + url);
        }
    }

    /** Both coordinates must appear, and the toll query needs each of them twice - way and node. */
    @Test
    public void coordinatesAreSubstitutedTheRightNumberOfTimes() {
        String toll = tollUrl();
        assertEquals(2, countOccurrences(toll, "43.638320"), "latitude once per around() clause");
        assertEquals(2, countOccurrences(toll, "-79.729848"), "longitude once per around() clause");

        String speedLimit = speedLimitUrl();
        assertEquals(1, countOccurrences(speedLimit, "43.638320"));
        assertEquals(1, countOccurrences(speedLimit, "-79.729848"));
    }

    /**
     * Pre-existing defect, pinned rather than fixed: {@code String.format} is called without a
     * {@code Locale}, so a JVM running under a comma-decimal locale emits {@code 43,638320} and
     * every query becomes malformed.
     *
     * <p>This is errata's locale item and belongs to stage 3, not here. The assertion documents
     * that the tests above pass only because the build locale happens to use a decimal point - so
     * a green suite is not evidence the deployed JVM is safe.
     */
    @Test
    public void queryCorrectnessCurrentlyDependsOnTheDefaultLocale() {
        assertEquals("43.638320", String.format("%f", LATITUDE).substring(0, 9),
                "this test suite runs under a decimal-point locale: " + Locale.getDefault());
        assertEquals("43,638320", String.format(Locale.GERMANY, "%f", LATITUDE).substring(0, 9),
                "under a comma-decimal locale the same code emits an unparseable query");
    }

    /**
     * Closes the gap the mock above leaves: {@code client.target(String)} is stubbed there, so
     * Jersey's own {@code UriBuilder} never parses the string. This feeds both URLs to a real
     * client and asserts the target builds.
     *
     * <p>It matters because the query carries unencoded {@code [}, {@code ]}, {@code (}, {@code )}
     * and {@code ;}, and this change adds a second bracket pair. {@code target()} does not open a
     * connection, so this stays offline.
     */
    @Test
    public void bothUrlsAreAcceptedByARealJaxRsClient() {
        Client client = jakarta.ws.rs.client.ClientBuilder.newClient();
        try {
            for (String url : new String[]{tollUrl(), speedLimitUrl()}) {
                WebTarget target = client.target(url);
                assertEquals(url, target.getUri().toString(),
                        "Jersey must round-trip the query without mangling it");
            }
        } finally {
            client.close();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
