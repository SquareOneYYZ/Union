package org.traccar.tollpricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The priced facilities, loaded from {@code toll-pricing/facilities.json} in the jar. Adding a
 * facility is a data change: a registry entry, a card, and its geometry.
 *
 * <p>{@link #match} is the enforcing function for "geometry first": a traversal belongs to the
 * facility whose geometry both edges lie on. The detector's {@code tollName}/{@code tollRef} are
 * never used to choose a facility - they changed across map extracts (decision record, 2026-09-07).
 */
public final class FacilityRegistry {

    public record Facility(String id, String currency, DistanceZonedPricer pricer, ZonedCorridor corridor) {
    }

    /** How far beyond a flat facility's outermost toll points an edge may lie and still be "near" it. */
    static final double FLAT_MARGIN_DEG = 0.05;

    private final List<Facility> facilities;
    private final List<GantrySet> gantrySets;
    private final List<String> skipped;

    private FacilityRegistry(List<Facility> facilities, List<GantrySet> gantrySets, List<String> skipped) {
        this.facilities = List.copyOf(facilities);
        this.gantrySets = List.copyOf(gantrySets);
        this.skipped = List.copyOf(skipped);
    }

    public static FacilityRegistry loadResource() throws IOException {
        try (InputStream in = FacilityRegistry.class.getResourceAsStream("/toll-pricing/facilities.json")) {
            if (in == null) {
                throw new IOException("toll-pricing/facilities.json not on classpath");
            }
            return load(in);
        }
    }

    public static FacilityRegistry load(InputStream in) throws IOException {
        JsonNode root = new ObjectMapper().readTree(in);
        List<Facility> facilities = new ArrayList<>();
        List<GantrySet> gantrySets = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (JsonNode f : root.path("facilities")) {
            String id = f.path("id").asText();
            String model = f.path("rate_model").asText();
            if ("flat".equals(model)) {
                try (InputStream card = resource("cards/" + f.path("card").asText());
                     InputStream gantries = resource("gantries/" + f.path("gantries").asText())) {
                    gantrySets.add(GantrySet.load(id, card, gantries,
                            f.path("payment_column").asText(), f.path("vehicle_column").asText(),
                            !"exclude".equals(f.path("ramp_rule").asText())));
                }
                continue;
            }
            if (!"distance_zoned".equals(model)) {
                skipped.add(id + " (" + model + ")");
                continue;
            }
            JsonNode h = f.path("holidays");
            Set<String> keys = new HashSet<>();
            h.path("keys").forEach(k -> keys.add(k.asText()));
            OperatorHolidays holidays;
            try {
                holidays = new OperatorHolidays(h.path("calendar").asText(), h.path("subdivision").asText(), keys);
            } catch (IllegalArgumentException e) {
                throw new IOException("facility " + id + ": " + e.getMessage(), e);
            }
            DistanceZonedCard card = DistanceZonedCard.loadResource(f.path("card").asText());
            ZonedCorridor corridor = ZonedCorridor.loadResource(f.path("corridor").asText());
            DistanceZonedPricer pricer = new DistanceZonedPricer(
                    card, corridor, ZoneId.of(f.path("timezone").asText()), holidays);
            facilities.add(new Facility(id, card.getCurrency(), pricer, corridor));
        }
        return new FacilityRegistry(facilities, gantrySets, skipped);
    }

    private static InputStream resource(String path) throws IOException {
        InputStream in = FacilityRegistry.class.getResourceAsStream("/toll-pricing/" + path);
        if (in == null) {
            throw new IOException("toll-pricing/" + path + " not on classpath");
        }
        return in;
    }

    /** Flat facilities whose toll points lie near both edges; their gantries are tested against the trail. */
    public List<GantrySet> flatNear(double entryLat, double entryLon, double exitLat, double exitLon) {
        List<GantrySet> near = new ArrayList<>();
        for (GantrySet s : gantrySets) {
            if (s.near(entryLat, entryLon, FLAT_MARGIN_DEG) && s.near(exitLat, exitLon, FLAT_MARGIN_DEG)) {
                near.add(s);
            }
        }
        return near;
    }

    public List<GantrySet> getGantrySets() {
        return gantrySets;
    }

    /** The facility both edges lie on, preferring the one they lie closest to; empty when none. */
    public Optional<Facility> match(double entryLat, double entryLon, double exitLat, double exitLon) {
        Facility best = null;
        double bestOffset = Double.MAX_VALUE;
        for (Facility f : facilities) {
            ZonedCorridor.Projection in = f.corridor().project(entryLat, entryLon);
            ZonedCorridor.Projection out = f.corridor().project(exitLat, exitLon);
            if (in != null && out != null && in.offsetMeters() + out.offsetMeters() < bestOffset) {
                best = f;
                bestOffset = in.offsetMeters() + out.offsetMeters();
            }
        }
        return Optional.ofNullable(best);
    }

    public List<Facility> getFacilities() {
        return facilities;
    }

    /** Registry entries with a rate model this build cannot price yet. */
    public List<String> getSkipped() {
        return skipped;
    }
}
