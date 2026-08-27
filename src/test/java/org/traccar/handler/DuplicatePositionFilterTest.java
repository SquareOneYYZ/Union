package org.traccar.handler;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a duplicate position is, and what stops it.
 *
 * <p>Deliberately not a toll test. {@code filter.duplicate} is a {@code FilterHandler} setting
 * that happens to affect the toll window, along with every other windowed detector and the
 * ungated speed-limit query. Filing it under toll would hide that.
 *
 * <p>Field evidence for errata item 28: duplicates are not theoretical. Adjacent pairs sharing a
 * timestamp <em>and</em> a coordinate appear in every dataset to hand - 20 in
 * {@code tollRouteSample.py}, 14 in the 284-position Ontario route, 10 in the 1000-position
 * Illinois route, and 3 in the 44-position field export for device 5964.
 */
public class DuplicatePositionFilterTest {

    /**
     * The key has no default, so filtering is off unless someone sets it.
     *
     * <p>{@code Keys.FILTER_DUPLICATE} is a two-argument {@code BooleanConfigKey}, and
     * {@code Config.getBoolean} returns {@code Objects.requireNonNullElse(defaultValue, false)}.
     * {@code FilterHandler} is already in the chain, four slots ahead of
     * {@code PositionInfoHandler} - so this is a config-only mitigation that is available today
     * and switched off.
     */
    @Test
    public void duplicateFilteringIsOffByDefault() {
        assertFalse(new Config().getBoolean(Keys.FILTER_DUPLICATE),
                "filter.duplicate has no declared default, so it resolves to false");
        assertEquals("filter.duplicate", Keys.FILTER_DUPLICATE.getKey());
    }

    /**
     * The predicate, stated precisely, because it is narrower than "same position".
     *
     * <p>{@code FilterHandler.java:109-119} drops a position only when its {@code fixTime} equals
     * the previous one's <b>and</b> every attribute on the new position is already present on the
     * last. Two consequences worth knowing before enabling it:
     *
     * <ul>
     *   <li>It compares attribute <em>keys</em>, not values, and it does not compare coordinates
     *       at all. A position at a different coordinate with the same fixTime and no new
     *       attribute keys is still dropped.</li>
     *   <li>It is asymmetric: a duplicate carrying one extra key survives, even if every other
     *       field is identical. That is what makes it conservative - a re-sent fix that has since
     *       been enriched is kept.</li>
     * </ul>
     */
    @Test
    public void theDropPredicateIsSameFixTimeAndNoNewAttributeKeys() {
        Date fixTime = new Date(1_756_000_000_000L);

        Position last = position(fixTime, 43.65, -79.71);
        last.set(Position.KEY_DISTANCE, 0.0);
        last.set(Position.KEY_TOTAL_DISTANCE, 1000.0);

        Position exactDuplicate = position(fixTime, 43.65, -79.71);
        exactDuplicate.set(Position.KEY_DISTANCE, 0.0);
        exactDuplicate.set(Position.KEY_TOTAL_DISTANCE, 1000.0);
        assertTrue(wouldDrop(exactDuplicate, last), "same fixTime, no new keys - dropped");

        Position laterFix = position(new Date(fixTime.getTime() + 1000), 43.65, -79.71);
        laterFix.set(Position.KEY_DISTANCE, 0.0);
        assertFalse(wouldDrop(laterFix, last), "a different fixTime is never a duplicate");

        Position withNewAttribute = position(fixTime, 43.65, -79.71);
        withNewAttribute.set(Position.KEY_DISTANCE, 0.0);
        withNewAttribute.set(Position.KEY_TOLL, true);
        assertFalse(wouldDrop(withNewAttribute, last),
                "one new attribute key is enough to keep it - the predicate is conservative");

        Position movedButSameFixTime = position(fixTime, 43.99, -79.11);
        movedButSameFixTime.set(Position.KEY_DISTANCE, 0.0);
        assertTrue(wouldDrop(movedButSameFixTime, last),
                "coordinates are not part of the predicate - worth knowing before enabling it");
    }

    /**
     * Why it matters beyond duplicate rows in a table: with filtering off, a duplicate reaches
     * every windowed detector and is counted twice.
     *
     * <p>Modelled here on the confirmation window's own shape rather than by running the toll
     * handler, so the fact stays where it belongs. Six readings are needed; a stream of six
     * distinct positions where one arrives twice delivers seven entries, and the seventh displaces
     * the oldest. The window still decides correctly when every reading agrees - which is why this
     * is a double-feed rather than a defect on its own - but the evidence count is inflated, and
     * for any detector whose window is not homogeneous that changes the answer.
     */
    @Test
    public void anUnfilteredDuplicateDoubleFeedsAWindow() {
        List<Boolean> withoutDuplicate = window(List.of(true, true, true, true, true, true), 6);
        List<Boolean> withDuplicate = window(List.of(true, true, true, true, true, true, true), 6);

        assertEquals(6, withoutDuplicate.size());
        assertEquals(6, withDuplicate.size());
        assertEquals(withoutDuplicate, withDuplicate,
                "with homogeneous readings the outcome is unchanged - this is a double-feed, "
                        + "not a wrong answer");

        // The same stream where the duplicated reading disagrees with its neighbours.
        List<Boolean> honest = window(List.of(true, true, false, true, true, true), 6);
        List<Boolean> doubled = window(List.of(true, true, false, false, true, true, true), 6);
        assertFalse(honest.equals(doubled),
                "one re-sent fix carrying a contrary reading shifts the window by a whole slot");
    }

    /** Mirrors {@code FilterHandler.filterDuplicate} exactly - see the citation above. */
    private static boolean wouldDrop(Position position, Position last) {
        if (last != null && position.getFixTime().equals(last.getFixTime())) {
            for (String key : position.getAttributes().keySet()) {
                if (!last.hasAttribute(key)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** Mirrors the trimming half of {@code TollRouteState.addOnToll}. */
    private static List<Boolean> window(List<Boolean> readings, int duration) {
        List<Boolean> result = new ArrayList<>();
        for (Boolean reading : readings) {
            result.add(reading);
            while (result.size() > duration) {
                result.remove(0);
            }
        }
        return result;
    }

    private static Position position(Date fixTime, double latitude, double longitude) {
        Position position = new Position();
        position.setDeviceId(1);
        position.setValid(true);
        position.setTime(fixTime);
        position.setLatitude(latitude);
        position.setLongitude(longitude);
        return position;
    }
}
