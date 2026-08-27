package org.traccar.handler.toll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.model.Position;
import org.traccar.tollroute.TollData;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Loads the 44-position field export for device 5964 (2026-08-25 01:44:43-01:47:32 UTC) and
 * exposes it both as a replayable position stream and as an Overpass oracle.
 *
 * <p><b>Where the oracle comes from.</b> The prompt asks for fixtures recorded by a probe
 * script's {@code --fixture-out}; no such script exists in this repository. The export itself
 * is the better fixture anyway: the nine enriched rows carry the attributes
 * {@code OverPassTollRouteProvider} actually produced at those coordinates in production, one
 * layer above the raw Overpass JSON. Replaying them exercises the handler-chain semantics
 * this test is about without pinning the response-parsing behaviour that stage 3 changes.
 *
 * <p>The export is written by the database's CSV dump, which does not escape the quotes inside
 * the {@code attributes} JSON column, so the row is split at the first brace rather than parsed
 * as RFC-4180 CSV. The file is kept byte-identical to the export so it stays auditable.
 */
public final class FieldDrive {

    public static final String RESOURCE = "/toll/field-drive-5964.csv";

    private final List<Position> positions = new ArrayList<>();
    private final Map<String, TollData> oracle = new HashMap<>();
    // Per-row, not per-coordinate: positions 41 and 42 are a duplicate pair sharing one
    // coordinate, and only 41 carries enrichment in the export.
    private final List<Boolean> enrichedInExport = new ArrayList<>();

    private FieldDrive() {
    }

    public static FieldDrive load() {
        FieldDrive drive = new FieldDrive();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream stream = FieldDrive.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + RESOURCE);
            }
            String[] lines = new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isBlank()) {
                    continue;
                }
                int open = line.indexOf('{');
                int close = line.lastIndexOf('}');
                String[] head = line.substring(0, open).split(",");
                JsonNode attributes = mapper.readTree(line.substring(open, close + 1));

                Position position = new Position();
                position.setId(Long.parseLong(unquote(head[0])));
                position.setDeviceId(Long.parseLong(unquote(head[1])));
                position.setProtocol(unquote(head[2]));
                position.setServerTime(format.parse(unquote(head[3])));
                position.setDeviceTime(format.parse(unquote(head[4])));
                position.setFixTime(format.parse(unquote(head[5])));
                position.setValid("1".equals(unquote(head[6])));
                position.setLatitude(Double.parseDouble(unquote(head[7])));
                position.setLongitude(Double.parseDouble(unquote(head[8])));
                position.setAltitude(Double.parseDouble(unquote(head[9])));
                position.setSpeed(Double.parseDouble(unquote(head[10])));
                position.setCourse(Double.parseDouble(unquote(head[11])));

                // Replay the position as the device sent it: only the decoder-supplied
                // attributes, never the enrichment the server later wrote onto the row.
                attributes.fields().forEachRemaining(entry -> {
                    if (!isEnrichment(entry.getKey())) {
                        position.getAttributes().put(entry.getKey(), asJava(entry.getValue()));
                    }
                });
                drive.positions.add(position);
                drive.enrichedInExport.add(attributes.has(Position.KEY_TOLL));

                if (attributes.has(Position.KEY_TOLL)) {
                    drive.oracle.put(key(position.getLatitude(), position.getLongitude()), new TollData(
                            attributes.get(Position.KEY_TOLL).asBoolean(),
                            text(attributes, Position.KEY_TOLL_REF),
                            text(attributes, Position.KEY_TOLL_NAME),
                            text(attributes, Position.KEY_SURFACE),
                            text(attributes, Position.KEY_HIGHWAY),
                            text(attributes, Position.KEY_ENFORCEMENT)));
                }
            }
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("cannot load " + RESOURCE, e);
        }
        return drive;
    }

    /** The 44 positions, in emission order, carrying only device-supplied attributes. */
    public List<Position> positions() {
        return positions;
    }

    /** 1-based index into the export, matching how the investigation documents number them. */
    public Position position(int oneBased) {
        return positions.get(oneBased - 1);
    }

    /**
     * Answers with the enrichment recorded at that exact coordinate. Coordinates the drive was
     * never enriched at answer {@code toll=false} with no tags, which is what
     * {@code OverPassTollRouteProvider.processApiResponse} returns for a response carrying no
     * {@code toll=yes} element ({@code OverPassTollRouteProvider.java:176-179}).
     */
    public TollChainHarness.TollOracle oracle() {
        return (latitude, longitude) -> oracle.getOrDefault(
                key(latitude, longitude), new TollData(false, null, null, null, null, null));
    }

    /** 1-based indexes of the rows the export recorded enrichment on. */
    public List<Integer> enrichedIndexes() {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < enrichedInExport.size(); i++) {
            if (enrichedInExport.get(i)) {
                indexes.add(i + 1);
            }
        }
        return indexes;
    }

    private static boolean isEnrichment(String key) {
        return Position.KEY_TOLL.equals(key)
                || Position.KEY_TOLL_REF.equals(key)
                || Position.KEY_TOLL_NAME.equals(key)
                || Position.KEY_SURFACE.equals(key)
                || Position.KEY_HIGHWAY.equals(key)
                || Position.KEY_ENFORCEMENT.equals(key)
                || Position.KEY_COUNTRY.equals(key)
                || Position.KEY_STATE.equals(key)
                || Position.KEY_CITY.equals(key);
    }

    private static Object asJava(JsonNode node) {
        if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isIntegralNumber()) {
            return node.asLong();
        } else if (node.isNumber()) {
            return node.asDouble();
        } else {
            return node.asText();
        }
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    private static String key(double latitude, double longitude) {
        return String.format(Locale.ROOT, "%.6f:%.6f", latitude, longitude);
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() > 1 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
