/*
 * Copyright 2018 - 2023 Anton Tananaev (anton@traccar.org)
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
package org.traccar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsonp.JSONPModule;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.name.Names;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.apache.velocity.app.VelocityEngine;
import org.traccar.broadcast.BroadcastService;
import org.traccar.broadcast.MulticastBroadcastService;
import org.traccar.broadcast.RedisBroadcastService;
import org.traccar.broadcast.NullBroadcastService;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.LdapProvider;
import org.traccar.database.OpenIdProvider;
import org.traccar.database.StatisticsManager;
import org.traccar.forward.EventForwarder;
import org.traccar.forward.EventForwarderJson;
import org.traccar.forward.EventForwarderAmqp;
import org.traccar.forward.EventForwarderKafka;
import org.traccar.forward.EventForwarderMqtt;
import org.traccar.forward.PositionForwarder;
import org.traccar.forward.PositionForwarderJson;
import org.traccar.forward.PositionForwarderAmqp;
import org.traccar.forward.PositionForwarderKafka;
import org.traccar.forward.PositionForwarderRedis;
import org.traccar.forward.PositionForwarderUrl;
import org.traccar.forward.PositionForwarderMqtt;
import org.traccar.forward.PositionForwarderWialon;
import org.traccar.geocoder.AddressFormat;
import org.traccar.geocoder.BanGeocoder;
import org.traccar.geocoder.BingMapsGeocoder;
import org.traccar.geocoder.FactualGeocoder;
import org.traccar.geocoder.GeoapifyGeocoder;
import org.traccar.geocoder.GeocodeFarmGeocoder;
import org.traccar.geocoder.GeocodeXyzGeocoder;
import org.traccar.geocoder.Geocoder;
import org.traccar.geocoder.GisgraphyGeocoder;
import org.traccar.geocoder.GoogleGeocoder;
import org.traccar.geocoder.HereGeocoder;
import org.traccar.geocoder.LocationIqGeocoder;
import org.traccar.geocoder.MapQuestGeocoder;
import org.traccar.geocoder.MapTilerGeocoder;
import org.traccar.geocoder.MapboxGeocoder;
import org.traccar.geocoder.MapmyIndiaGeocoder;
import org.traccar.geocoder.NominatimGeocoder;
import org.traccar.geocoder.OpenCageGeocoder;
import org.traccar.geocoder.PositionStackGeocoder;
import org.traccar.geocoder.PlusCodesGeocoder;
import org.traccar.geocoder.TomTomGeocoder;
import org.traccar.geocoder.GeocodeJsonGeocoder;
import org.traccar.geolocation.GeolocationProvider;
import org.traccar.geolocation.GoogleGeolocationProvider;
import org.traccar.geolocation.OpenCellIdGeolocationProvider;
import org.traccar.geolocation.UnwiredGeolocationProvider;
import org.traccar.handler.CopyAttributesHandler;
import org.traccar.handler.FilterHandler;
import org.traccar.handler.GeocoderHandler;
import org.traccar.handler.GeolocationHandler;
import org.traccar.handler.SpeedLimitHandler;
import org.traccar.handler.TimeHandler;
import org.traccar.handler.TollRouteHandler;
import org.traccar.helper.DeviceLogContextInitializer;
import org.traccar.helper.ObjectMapperContextResolver;
import org.traccar.helper.WebHelper;
import org.traccar.mail.LogMailManager;
import org.traccar.mail.MailManager;
import org.traccar.mail.SmtpMailManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.sms.HttpSmsClient;
import org.traccar.sms.SmsManager;
import org.traccar.sms.SnsSmsClient;
import org.traccar.speedlimit.OverpassSpeedLimitProvider;
import org.traccar.speedlimit.SpeedLimitProvider;
import org.traccar.storage.DatabaseStorage;
import org.traccar.storage.MemoryStorage;
import org.traccar.storage.Storage;
import org.traccar.storage.localCache.RedisCache;
import org.traccar.tollroute.OverPassTollRouteProvider;
import org.traccar.tollroute.TollRouteProvider;
import org.traccar.vindecoder.NHTSAVinDecoderProvider;
import org.traccar.vindecoder.OverpassApiProvider;
import org.traccar.vindecoder.OverpassProvider;
import org.traccar.vindecoder.VinDecoderProvider;
import org.traccar.tollroute.RegionProvider;
import org.traccar.tollroute.LocationIQRegionProvider;
import org.traccar.web.WebServer;
import org.traccar.api.security.LoginService;

import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MainModule extends AbstractModule {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(MainModule.class);

    private final String configFile;

    public MainModule(String configFile) {
        this.configFile = configFile;
    }

    @Override
    protected void configure() {
        bindConstant().annotatedWith(Names.named("configFile")).to(configFile);
        bind(Config.class).asEagerSingleton();
        bind(DeviceLogContextInitializer.class).asEagerSingleton();
        bind(Timer.class).to(HashedWheelTimer.class).in(Scopes.SINGLETON);
    }

    @Singleton
    @Provides
    public static ExecutorService provideExecutorService() {
        return Executors.newCachedThreadPool();
    }

    @Singleton
    @Provides
    public static Storage provideStorage(Injector injector, Config config) {
        if (config.getBoolean(Keys.DATABASE_MEMORY)) {
            return injector.getInstance(MemoryStorage.class);
        } else {
            return injector.getInstance(DatabaseStorage.class);
        }
    }

    @Singleton
    @Provides
    public static ObjectMapper provideObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JSONPModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }

    @Singleton
    @Provides
    public static Client provideClient(ObjectMapperContextResolver objectMapperContextResolver) {
        return ClientBuilder.newClient().register(objectMapperContextResolver);
    }

    /**
     * Builds a bounded JAX-RS client for one of the position-chain call sites.
     *
     * <p>Three bounds, and they interlock:
     *
     * <ul>
     *   <li>connect and read timeouts, so a single hung request cannot occupy a worker forever;</li>
     *   <li>a fixed worker pool, so concurrent in-flight requests are capped - Jersey's default
     *       client async executor is {@code DefaultClientAsyncExecutorProvider}, which sizes from
     *       {@code ClientProperties.ASYNC_THREADPOOL_SIZE} and defaults to 0, i.e. an unbounded
     *       cached pool with one platform thread per in-flight request;</li>
     *   <li>a bounded queue with {@code AbortPolicy}, so work beyond it is rejected immediately and
     *       surfaces to the caller as a lookup failure instead of accumulating in memory.</li>
     * </ul>
     *
     * <p>Together they put a ceiling on outbound volume that holds even when a caller retries
     * freely: at most {@code maxConcurrent} requests in flight, each for at most the read timeout.
     */
    private static Client boundedClient(
            String name, ObjectMapperContextResolver objectMapperContextResolver,
            int connectTimeout, int readTimeout, int maxConcurrent, int queueSize) {

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                maxConcurrent, maxConcurrent,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize),
                runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);

        LOGGER.info("{} HTTP client: connect {} ms, read {} ms, {} workers, queue {}",
                name, connectTimeout, readTimeout, maxConcurrent, queueSize);

        return ClientBuilder.newBuilder()
                .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .executorService(executor)
                .build()
                .register(objectMapperContextResolver);
    }

    /**
     * The client for the toll, region and speed-limit providers. See {@link EnrichmentClient} for
     * why these are separated from the client every other subsystem shares, and
     * {@code Keys.ENRICHMENT_MAX_CONCURRENT} for the sizing arithmetic.
     */
    @Singleton
    @Provides
    @EnrichmentClient
    public static Client provideEnrichmentClient(
            Config config, ObjectMapperContextResolver objectMapperContextResolver) {
        return boundedClient("enrichment", objectMapperContextResolver,
                config.getInteger(Keys.ENRICHMENT_CONNECT_TIMEOUT),
                config.getInteger(Keys.ENRICHMENT_READ_TIMEOUT),
                config.getInteger(Keys.ENRICHMENT_MAX_CONCURRENT),
                config.getInteger(Keys.ENRICHMENT_QUEUE_SIZE));
    }

    /**
     * The client for {@code GeocoderHandler}. Bounded for the same reason as the enrichment one -
     * it is in the position chain, so a hang there stalls a device and costs positions - but with
     * its own pool, so a LocationIQ stall cannot consume Overpass workers. See
     * {@link GeocoderClient}.
     */
    @Singleton
    @Provides
    @GeocoderClient
    public static Client provideGeocoderClient(
            Config config, ObjectMapperContextResolver objectMapperContextResolver) {
        return boundedClient("geocoder", objectMapperContextResolver,
                config.getInteger(Keys.GEOCODER_CLIENT_CONNECT_TIMEOUT),
                config.getInteger(Keys.GEOCODER_CLIENT_READ_TIMEOUT),
                config.getInteger(Keys.GEOCODER_CLIENT_MAX_CONCURRENT),
                config.getInteger(Keys.GEOCODER_CLIENT_QUEUE_SIZE));
    }

    @Singleton
    @Provides
    public static SmsManager provideSmsManager(Config config, Client client) {
        if (config.hasKey(Keys.SMS_HTTP_URL)) {
            return new HttpSmsClient(config, client);
        } else if (config.hasKey(Keys.SMS_AWS_REGION)) {
            return new SnsSmsClient(config);
        }
        return null;
    }

    @Singleton
    @Provides
    public static MailManager provideMailManager(Config config, StatisticsManager statisticsManager) {
        if (config.getBoolean(Keys.MAIL_DEBUG)) {
            return new LogMailManager();
        } else {
            return new SmtpMailManager(config, statisticsManager);
        }
    }

    @Singleton
    @Provides
    public static LdapProvider provideLdapProvider(Config config) {
        if (config.hasKey(Keys.LDAP_URL)) {
            return new LdapProvider(config);
        }
        return null;
    }

    @Singleton
    @Provides
    public static OpenIdProvider provideOpenIDProvider(
        Config config, LoginService loginService, ObjectMapper objectMapper
        ) throws InterruptedException, IOException, URISyntaxException {
        if (config.hasKey(Keys.OPENID_CLIENT_ID)) {
            return new OpenIdProvider(config, loginService, HttpClient.newHttpClient(), objectMapper);
        }
        return null;
    }

    @Provides
    public static WebServer provideWebServer(Injector injector, Config config) {
        if (config.getInteger(Keys.WEB_PORT) > 0) {
            return new WebServer(injector, config);
        }
        return null;
    }

    @Singleton
    @Provides
    public static Geocoder provideGeocoder(
            Config config, @GeocoderClient Client client, StatisticsManager statisticsManager) {
        if (config.getBoolean(Keys.GEOCODER_ENABLE)) {
            String type = config.getString(Keys.GEOCODER_TYPE);
            String url = config.getString(Keys.GEOCODER_URL);
            String key = config.getString(Keys.GEOCODER_KEY);
            String language = config.getString(Keys.GEOCODER_LANGUAGE);
            String formatString = config.getString(Keys.GEOCODER_FORMAT);
            AddressFormat addressFormat = formatString != null ? new AddressFormat(formatString) : new AddressFormat();

            int cacheSize = config.getInteger(Keys.GEOCODER_CACHE_SIZE);
            Geocoder geocoder = switch (type) {
                case "pluscodes" -> new PlusCodesGeocoder();
                case "nominatim" -> new NominatimGeocoder(client, url, key, language, cacheSize, addressFormat);
                case "locationiq" -> new LocationIqGeocoder(client, url, key, language, cacheSize, addressFormat);
                case "gisgraphy" -> new GisgraphyGeocoder(client, url, cacheSize, addressFormat);
                case "mapquest" -> new MapQuestGeocoder(client, url, key, cacheSize, addressFormat);
                case "opencage" -> new OpenCageGeocoder(client, url, key, language, cacheSize, addressFormat);
                case "bingmaps" -> new BingMapsGeocoder(client, url, key, cacheSize, addressFormat);
                case "factual" -> new FactualGeocoder(client, url, key, cacheSize, addressFormat);
                case "geocodefarm" -> new GeocodeFarmGeocoder(client, key, language, cacheSize, addressFormat);
                case "geocodexyz" -> new GeocodeXyzGeocoder(client, key, cacheSize, addressFormat);
                case "ban" -> new BanGeocoder(client, cacheSize, addressFormat);
                case "here" -> new HereGeocoder(client, url, key, language, cacheSize, addressFormat);
                case "mapmyindia" -> new MapmyIndiaGeocoder(client, url, key, cacheSize, addressFormat);
                case "tomtom" -> new TomTomGeocoder(client, url, key, cacheSize, addressFormat);
                case "positionstack" -> new PositionStackGeocoder(client, key, cacheSize, addressFormat);
                case "mapbox" -> new MapboxGeocoder(client, key, cacheSize, addressFormat);
                case "maptiler" -> new MapTilerGeocoder(client, key, cacheSize, addressFormat);
                case "geoapify" -> new GeoapifyGeocoder(client, key, language, cacheSize, addressFormat);
                case "geocodejson" -> new GeocodeJsonGeocoder(client, url, key, language, cacheSize, addressFormat);
                default -> new GoogleGeocoder(client, url, key, language, cacheSize, addressFormat);
            };
            geocoder.setStatisticsManager(statisticsManager);
            return geocoder;
        }
        return null;
    }

    @Singleton
    @Provides
    public static GeolocationProvider provideGeolocationProvider(Config config, Client client) {
        if (config.getBoolean(Keys.GEOLOCATION_ENABLE)) {
            String type = config.getString(Keys.GEOLOCATION_TYPE, "google");
            String url = config.getString(Keys.GEOLOCATION_URL);
            String key = config.getString(Keys.GEOLOCATION_KEY);
            return switch (type) {
                case "opencellid" -> new OpenCellIdGeolocationProvider(client, url, key);
                case "unwired" -> new UnwiredGeolocationProvider(client, url, key);
                default -> new GoogleGeolocationProvider(client, key);
            };
        }
        return null;
    }

    @Singleton
    @Provides
    public static SpeedLimitProvider provideSpeedLimitProvider(
            Config config, @EnrichmentClient Client client) {
        if (config.getBoolean(Keys.SPEED_LIMIT_ENABLE)) {
            String type = config.getString(Keys.SPEED_LIMIT_TYPE, "overpass");
            String url = config.getString(Keys.SPEED_LIMIT_URL);
            return switch (type) {
                case "overpass" -> new OverpassSpeedLimitProvider(config, client, url);
                default -> throw new IllegalArgumentException("Unknown speed limit provider");
            };
        }
        return null;
    }

    @Singleton
    @Provides
    public static TollRouteProvider provideTollRouteProvider(
            Config config, @EnrichmentClient Client client, RedisCache redisCache) {
        if (config.getBoolean(Keys.TOLL_ROUTE_ENABLE)) {
            String type = config.getString(Keys.TOLL_ROUTE_TYPE);
            String url = config.getString(Keys.TOLL_ROUTE_URL);
            if (url != null) {
                return switch (type) {
                    case "overpass" -> new OverPassTollRouteProvider(config, client, url, redisCache);
                    default -> throw new IllegalArgumentException("Unknown Toll Route provider");
                };
            }
        }
        return null;
    }

    @Singleton
    @Provides
    public static TollRouteHandler provideTollRouteHandler(@Nullable TollRouteProvider  tollRouteProvider) {
        if (tollRouteProvider != null) {
            return new TollRouteHandler(tollRouteProvider);
        }
        return null;
    }

    @Singleton
    @Provides
    public static VinDecoderProvider provideVinDecoderProvider(Client client) {
        return new NHTSAVinDecoderProvider(client);
    }

    @Singleton
    @Provides
    public static OverpassProvider provideOverpassProvider(Config config, Client client) {
        return new OverpassApiProvider(config, client);
    }

    @Singleton
    @Provides
    public static RegionProvider provideRegionProvider(
            Config config, @EnrichmentClient Client client, RedisCache redisCache) {
        String type = config.getString(Keys.REGION_PROVIDER_TYPE, "locationiq");
        String url = config.getString(Keys.REGION_PROVIDER_URL);
        return switch (type) {
            case "locationiq" -> new LocationIQRegionProvider(config, client, url, redisCache);
            default -> throw new IllegalArgumentException("Unknown Region provider: " + type);
        };
    }


    @Singleton
    @Provides
    public static GeolocationHandler provideGeolocationHandler(
            Config config, @Nullable GeolocationProvider geolocationProvider, CacheManager cacheManager,
            StatisticsManager statisticsManager) {
        if (geolocationProvider != null) {
            return new GeolocationHandler(config, geolocationProvider, cacheManager, statisticsManager);
        }
        return null;
    }

    @Singleton
    @Provides
    public static GeocoderHandler provideGeocoderHandler(
            Config config, @Nullable Geocoder geocoder, CacheManager cacheManager) {
        if (geocoder != null) {
            return new GeocoderHandler(config, geocoder, cacheManager);
        }
        return null;
    }

    @Singleton
    @Provides
    public static SpeedLimitHandler provideSpeedLimitHandler(@Nullable SpeedLimitProvider speedLimitProvider) {
        if (speedLimitProvider != null) {
            return new SpeedLimitHandler(speedLimitProvider);
        }
        return null;
    }

    @Singleton
    @Provides
    public static CopyAttributesHandler provideCopyAttributesHandler(Config config, CacheManager cacheManager) {
        if (config.getBoolean(Keys.PROCESSING_COPY_ATTRIBUTES_ENABLE)) {
            return new CopyAttributesHandler(config, cacheManager);
        }
        return null;
    }

    @Singleton
    @Provides
    public static FilterHandler provideFilterHandler(
            Config config, CacheManager cacheManager, Storage storage, StatisticsManager statisticsManager) {
        if (config.getBoolean(Keys.FILTER_ENABLE)) {
            return new FilterHandler(config, cacheManager, storage, statisticsManager);
        }
        return null;
    }

    @Singleton
    @Provides
    public static TimeHandler provideTimeHandler(Config config) {
        if (config.hasKey(Keys.TIME_OVERRIDE)) {
            return new TimeHandler(config);
        }
        return null;
    }

    @Singleton
    @Provides
    public static BroadcastService provideBroadcastService(
            Config config, ExecutorService executorService, ObjectMapper objectMapper) throws IOException {
        if (config.hasKey(Keys.BROADCAST_TYPE)) {
            return switch (config.getString(Keys.BROADCAST_TYPE)) {
                case "multicast" -> new MulticastBroadcastService(config, executorService, objectMapper);
                case "redis" -> new RedisBroadcastService(config, executorService, objectMapper);
                default -> new NullBroadcastService();
            };
        }
        return new NullBroadcastService();
    }

    @Singleton
    @Provides
    public static EventForwarder provideEventForwarder(Config config, Client client, ObjectMapper objectMapper) {
        if (config.hasKey(Keys.EVENT_FORWARD_URL)) {
            String forwardType = config.getString(Keys.EVENT_FORWARD_TYPE);
            return switch (forwardType) {
                case "amqp" -> new EventForwarderAmqp(config, objectMapper);
                case "kafka" -> new EventForwarderKafka(config, objectMapper);
                case "mqtt" -> new EventForwarderMqtt(config, objectMapper);
                default -> new EventForwarderJson(config, client);
            };
        }
        return null;
    }

    @Singleton
    @Provides
    public static PositionForwarder providePositionForwarder(
            Config config, Client client, ExecutorService executorService,
            ObjectMapper objectMapper, CacheManager cacheManager) {
        if (config.hasKey(Keys.FORWARD_URL)) {
            return switch (config.getString(Keys.FORWARD_TYPE)) {
                case "json" -> new PositionForwarderJson(config, client, objectMapper, cacheManager);
                case "amqp" -> new PositionForwarderAmqp(config, objectMapper);
                case "kafka" -> new PositionForwarderKafka(config, objectMapper);
                case "mqtt" -> new PositionForwarderMqtt(config, objectMapper);
                case "redis" -> new PositionForwarderRedis(config, objectMapper);
                case "wialon" -> new PositionForwarderWialon(config, executorService, "1.0", false);
                default -> new PositionForwarderUrl(config, client, objectMapper);
            };
        }
        return null;
    }

    @Singleton
    @Provides
    public static VelocityEngine provideVelocityEngine(Config config) {
        Properties properties = new Properties();
        properties.setProperty("resource.loader.file.path", config.getString(Keys.TEMPLATES_ROOT) + "/");
        properties.setProperty("web.url", WebHelper.retrieveWebUrl(config));

        VelocityEngine velocityEngine = new VelocityEngine();
        velocityEngine.init(properties);
        return velocityEngine;
    }

}
