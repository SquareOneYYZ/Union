package org.traccar.tollpricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
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
                List<String> cardNames = new ArrayList<>();
                if (f.has("cards")) {
                    f.path("cards").forEach(c -> cardNames.add(c.asText()));
                } else {
                    cardNames.add(f.path("card").asText());
                }
                List<InputStream> cards = new ArrayList<>();
                try (InputStream gantries = resource("gantries/" + f.path("gantries").asText())) {
                    for (String name : cardNames) {
                        cards.add(resource("cards/" + name));
                    }
                    gantrySets.add(GantrySet.load(id, cards, gantries,
                            f.path("payment_column").asText(), f.path("vehicle_column").asText(),
                            !"exclude".equals(f.path("ramp_rule").asText()),
                            ZoneId.of(f.path("timezone").asText())));
                } finally {
                    for (InputStream card : cards) {
                        card.close();
                    }
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
            DistanceZonedPricer.Continuation continuation = null;
            JsonNode c = f.path("continuation");
            if (!c.isMissingNode()) {
                List<double[][]> lines = new ArrayList<>();
                try (InputStream geometry = resource("corridors/" + c.path("geometry").asText())) {
                    for (JsonNode line : new ObjectMapper().readTree(geometry).path("lines")) {
                        double[][] points = new double[line.size()][];
                        for (int i = 0; i < line.size(); i++) {
                            points[i] = new double[] {line.get(i).get(0).asDouble(), line.get(i).get(1).asDouble()};
                        }
                        lines.add(points);
                    }
                }
                if (lines.isEmpty()) {
                    throw new IOException("facility " + id + ": continuation geometry has no lines");
                }
                try {
                    continuation = new DistanceZonedPricer.Continuation(c.path("facility").asText(),
                            LocalDate.parse(c.path("toll_free_since").asText()), List.copyOf(lines));
                } catch (DateTimeParseException e) {
                    throw new IOException("facility " + id + ": bad continuation.toll_free_since", e);
                }
                if (continuation.facilityId().isEmpty()) {
                    throw new IOException("facility " + id + ": continuation without a facility id");
                }
            }
            DistanceZonedPricer pricer = new DistanceZonedPricer(
                    card, corridor, ZoneId.of(f.path("timezone").asText()), holidays, continuation);
            facilities.add(new Facility(id, card.getCurrency(), pricer, corridor));
        }
        GantrySet.linkShared(gantrySets);
        return new FacilityRegistry(facilities, gantrySets, skipped);
    }

    private static InputStream resource(String path) throws IOException {
        InputStream in = FacilityRegistry.class.getResourceAsStream("/toll-pricing/" + path);
        if (in == null) {
            throw new IOException("toll-pricing/" + path + " not on classpath");
        }
        return in;
    }

    /**
     * Flat facilities whose toll points lie near either edge, plus every facility sharing a gantry with
     * one of them; their toll points are tested against the trail. Either edge, not both: a trip that
     * enters Florida's Turnpike at Leesburg and leaves on CFX's SR 528 must test CFX's gantries too, or its
     * CFX portion is silently dropped. Testing an extra facility only finds passages the trail made.
     */
    public List<GantrySet> flatNear(double entryLat, double entryLon, double exitLat, double exitLon) {
        Set<String> wanted = new HashSet<>();
        for (GantrySet s : gantrySets) {
            if (s.near(entryLat, entryLon, FLAT_MARGIN_DEG) || s.near(exitLat, exitLon, FLAT_MARGIN_DEG)) {
                wanted.add(s.getFacilityId());
                wanted.addAll(s.partnerFacilities());
            }
        }
        List<GantrySet> near = new ArrayList<>();
        for (GantrySet s : gantrySets) {
            if (wanted.contains(s.getFacilityId())) {
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
