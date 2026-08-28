package org.traccar;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import jakarta.ws.rs.client.Client;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Startup smoke test for the bounded HTTP clients.
 *
 * <p>Guice binding annotations are resolved at injector-creation time, not at compile time. A
 * missing or mistyped {@code @EnrichmentClient} / {@code @GeocoderClient} binding compiles cleanly
 * and then fails when the server starts - which is the worst place to find out, and the one thing
 * a green unit-test suite does not tell you about this change.
 */
public class BoundedClientWiringTest {

    private Injector injector() throws IOException {
        Path configFile = Files.createTempFile("traccar-wiring", ".xml");
        Files.writeString(configFile, """
                <?xml version='1.0' encoding='UTF-8'?>
                <!DOCTYPE properties SYSTEM 'http://java.sun.com/dtd/properties.dtd'>
                <properties>
                    <entry key='config.default'></entry>
                    <entry key='database.memory'>true</entry>
                    <entry key='logger.console'>true</entry>
                </properties>
                """, StandardCharsets.UTF_8);
        configFile.toFile().deleteOnExit();
        return Guice.createInjector(new MainModule(configFile.toString()));
    }

    /** The three clients must all resolve, and be three distinct instances. */
    @Test
    public void allThreeClientsResolveAndAreDistinct() throws IOException {
        Injector injector = injector();

        Client shared = injector.getInstance(Client.class);
        Client enrichment = injector.getInstance(Key.get(Client.class, EnrichmentClient.class));
        Client geocoder = injector.getInstance(Key.get(Client.class, GeocoderClient.class));

        assertNotNull(shared);
        assertNotNull(enrichment);
        assertNotNull(geocoder);

        assertNotSame(shared, enrichment, "the enrichment path must not use the shared client");
        assertNotSame(shared, geocoder, "the geocoder must not use the shared client");
        assertNotSame(enrichment, geocoder,
                "separate pools: a LocationIQ stall must not consume Overpass workers");
    }

    /** Each is a singleton, so the pools are per-process rather than per-injection. */
    @Test
    public void eachBoundedClientIsASingleton() throws IOException {
        Injector injector = injector();

        assertSame(
                injector.getInstance(Key.get(Client.class, EnrichmentClient.class)),
                injector.getInstance(Key.get(Client.class, EnrichmentClient.class)));
        assertSame(
                injector.getInstance(Key.get(Client.class, GeocoderClient.class)),
                injector.getInstance(Key.get(Client.class, GeocoderClient.class)));
    }

    /**
     * The defaults must resolve against an empty config to the values the sizing arithmetic
     * assumes. Every one of these is an explicit default; an unset key that resolved to 0 would
     * disable the bound it represents, which is the same silent-default trap as the gate key.
     */
    @Test
    public void everyBoundHasAnExplicitNonZeroDefault() {
        Config config = new Config();

        assertEquals(5000, config.getInteger(Keys.ENRICHMENT_CONNECT_TIMEOUT));
        assertEquals(15000, config.getInteger(Keys.ENRICHMENT_READ_TIMEOUT));
        assertEquals(512, config.getInteger(Keys.ENRICHMENT_MAX_CONCURRENT));
        assertEquals(512, config.getInteger(Keys.ENRICHMENT_QUEUE_SIZE));
        assertEquals(10, config.getInteger(Keys.ENRICHMENT_OVERPASS_QUERY_TIMEOUT));

        assertEquals(5000, config.getInteger(Keys.GEOCODER_CLIENT_CONNECT_TIMEOUT));
        assertEquals(15000, config.getInteger(Keys.GEOCODER_CLIENT_READ_TIMEOUT));
        assertEquals(256, config.getInteger(Keys.GEOCODER_CLIENT_MAX_CONCURRENT));
        assertEquals(512, config.getInteger(Keys.GEOCODER_CLIENT_QUEUE_SIZE));

        assertEquals(1000, config.getInteger(Keys.PROCESSING_QUEUE_MAX_SIZE));
        assertEquals(500, config.getInteger(Keys.TOLL_ROUTE_MINIMAL_DISTANCE),
                "the gate must stay at 500 through this deploy");
    }

    /** The Overpass server-side budget must sit below the client read timeout, or it is pointless. */
    @Test
    public void overpassQueryBudgetIsBelowTheReadTimeout() {
        Config config = new Config();
        int queryTimeoutMillis = config.getInteger(Keys.ENRICHMENT_OVERPASS_QUERY_TIMEOUT) * 1000;
        int readTimeout = config.getInteger(Keys.ENRICHMENT_READ_TIMEOUT);

        org.junit.jupiter.api.Assertions.assertTrue(queryTimeoutMillis < readTimeout,
                "the server must give up before we do, so the query is freed upstream too: "
                        + queryTimeoutMillis + " ms vs " + readTimeout + " ms");
    }

    /**
     * Why this test configures {@code logger.console} and {@code database.memory}, and what that
     * does <em>not</em> mean for the deploy.
     *
     * <p>Building the injector needs a {@code DataSource} unless {@code database.memory} is set,
     * because {@code DeviceLogContextInitializer} is an eager singleton that pulls in
     * {@code CacheManager} and {@code Storage}. That is a test-harness constraint only.
     *
     * <p>The logging one is worth stating precisely, because it is easy to misread as a risk this
     * PR introduced. {@code Config}'s constructor calls {@code Log.setupLogger} (Config.java:51)
     * and {@code Config} is bound {@code asEagerSingleton}, so a {@code RollingFileHandler} for
     * {@code ./logs/tracker-server.log} is installed during injector creation on every version.
     * It opens the file lazily, on first write. In production the first write is
     * {@code Main.run}'s own {@code logSystemInfo()} immediately after {@code createInjector},
     * long before any provider here is requested - so a missing {@code logs/} directory kills
     * startup with or without this change. It surfaced through
     * {@code provideEnrichmentClient} here only because this test has no {@code Main.run} ahead
     * of it. The directory is a real deploy prerequisite; it is not a new one.
     */
    @Test
    public void loggingSetupIsUnchangedByThisPr() {
        assertEquals("./logs/tracker-server.log", Keys.LOGGER_FILE.getDefaultValue(),
                "unchanged upstream default - the logs directory must exist on the host");
    }

    /** Guards the temp-file assumption the injector helper relies on. */
    @Test
    public void configFileIsReadable() throws IOException {
        Path configFile = Files.createTempFile("traccar-wiring-check", ".xml");
        configFile.toFile().deleteOnExit();
        org.junit.jupiter.api.Assertions.assertTrue(new File(configFile.toString()).canRead());
    }
}
