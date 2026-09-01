package org.traccar;

import com.google.inject.BindingAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the JAX-RS client used by {@code GeocoderHandler}, separate from both the shared client
 * and the enrichment one.
 *
 * <p>{@code GeocoderHandler} is in the position handler chain, so a hung geocode stalls that
 * device's chain, fills its queue in {@code ProcessingHandler} and ends in dropped positions. It
 * therefore needs the same bounds as the enrichment path.
 *
 * <p>It gets its own pool rather than sharing the enrichment one because the two call different
 * third parties - LocationIQ here, Overpass for toll and speed limit. Sharing would couple their
 * failure modes: a LocationIQ stall would consume workers that toll detection needs, for reasons
 * that have nothing to do with Overpass. Separate pools mean each degrades alone.
 */
@BindingAnnotation
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface GeocoderClient {
}
