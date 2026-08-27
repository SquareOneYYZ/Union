package org.traccar;

import com.google.inject.BindingAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the JAX-RS client used by the per-position enrichment providers - toll, region and speed
 * limit - as distinct from the client every other subsystem shares.
 *
 * <p>Those three are the only consumers on the hot path: they run once per gate-passing position,
 * against third-party services, inside the handler chain that must finish before the next position
 * for that device is processed. The shared client is used by SMS, notificators, event and position
 * forwarding, ~20 geocoders, 4 geolocation providers, statistics, the health check and the VIN
 * decoder - all of which want different timeouts and none of which want their concurrency bounded
 * by enrichment traffic.
 *
 * <p>Separating the two is what makes it possible to bound the enrichment path without choosing
 * timeout values on behalf of an SMS gateway.
 */
@BindingAnnotation
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface EnrichmentClient {
}
