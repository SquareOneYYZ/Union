package org.traccar.handler.toll;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 1d - the enrichment gate becomes {@code tollRoute.minimalDistance}.
 */
public class TollGateConfigTest {

    private static final int MINIMAL_DURATION = 6;
    private static final long DEVICE_ID = 5964L;

    /**
     * The declaration must carry an explicit default, and it must be 500 so an unset key
     * preserves today's behaviour exactly.
     *
     * <p>This is not a style preference. {@code Config.getInteger} returns
     * {@code Objects.requireNonNullElse(defaultValue, 0)}, so a two-argument declaration
     * resolves to 0 when the key is absent, {@code distanceMoved < 0} is never true, and the
     * gate disappears with no error and no log line. The gate is the only bound on Overpass and
     * region-provider load, so that failure is fail-open: discovered by the upstream service
     * rather than by a user.
     */
    @Test
    public void gateKeyDeclaresAnExplicitFiveHundredMetreDefault() {
        assertEquals(500, Keys.TOLL_ROUTE_MINIMAL_DISTANCE.getDefaultValue(),
                "an unset key must preserve the shipped behaviour, not disable the gate");
        assertEquals("tollRoute.minimalDistance", Keys.TOLL_ROUTE_MINIMAL_DISTANCE.getKey());
        assertEquals(500, new Config().getInteger(Keys.TOLL_ROUTE_MINIMAL_DISTANCE),
                "resolving the key against an empty config must give 500, not 0");
    }

    /**
     * The key must be added, never a rename of an existing one. {@code setup/setup.sh} preserves
     * {@code conf/traccar.xml} across every upgrade and {@code Config.getInteger} returns 0 for
     * an unset key rather than throwing, so a rename would leave the old entry sitting unread
     * with the feature silently dead - the same failure shape as the defect being fixed.
     */
    @Test
    public void existingTollKeysAreUntouched() {
        assertEquals("tollRoute.accuracy", Keys.TOLL_ROUTE_ACCURACY.getKey());
        assertEquals("event.tollRoute.minimalDuration", Keys.EVENT_TOLL_ROUTE_MINIMAL_DURATION.getKey());
        assertEquals("tollRoute.roundingDecimals", Keys.TOLL_ROUTE_ROUNDING_DECIMALS.getKey());
    }

    /** The configured value is what the gate actually uses. */
    @Test
    public void gateHonoursTheConfiguredDistance() {
        // 130 km/h at 3.9 s is 141 m per fix: gated out at 500 m, every position through at 100.
        List<Position> atFiveHundred = SyntheticDrive.straightLine(
                DEVICE_ID, 43.65, -79.71, 45.0, 130.0, 3.9, 40);
        List<Position> atOneHundred = SyntheticDrive.straightLine(
                DEVICE_ID, 43.65, -79.71, 45.0, 130.0, 3.9, 40);

        TollChainHarness shipped = new TollChainHarness(MINIMAL_DURATION,
                TollChainHarness.constant(true, "407 ETR", "407 Express Toll Route"), 500);
        shipped.acceptAll(atFiveHundred);

        TollChainHarness loosened = new TollChainHarness(MINIMAL_DURATION,
                TollChainHarness.constant(true, "407 ETR", "407 Express Toll Route"), 100);
        loosened.acceptAll(atOneHundred);

        assertTrue(loosened.lookupCount() > shipped.lookupCount(),
                "a smaller gate must issue more lookups over the same route");
        // 140.8 m per fix, so the gate clears every fourth position: the first (no reference
        // point yet) plus floor(39/4) more.
        assertEquals(10, shipped.lookupCount(), "5,492 m of travel at a 500 m gate");
        assertEquals(40, loosened.lookupCount(), "at a 100 m gate every 140.8 m fix clears it");
        assertEquals(1, shipped.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size());
        assertEquals(1, loosened.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size());
    }

    /**
     * A zero gate is the fail-open state the explicit default exists to prevent. If it is ever
     * configured deliberately, every valid position issues a lookup - which is why stage 2 has
     * to land before the gate is loosened, not after.
     */
    @Test
    public void aZeroGateEnrichesEveryPosition() {
        List<Position> stream = SyntheticDrive.straightLine(
                DEVICE_ID, 43.65, -79.71, 45.0, 130.0, 3.9, 40);

        TollChainHarness harness = new TollChainHarness(MINIMAL_DURATION,
                TollChainHarness.constant(true, "407 ETR", "407 Express Toll Route"), 0);
        harness.acceptAll(stream);

        assertEquals(stream.size(), harness.lookupCount(),
                "a zero gate is no gate: 40 positions, 40 Overpass calls and 40 region calls");
        for (Position position : stream) {
            assertTrue(position.hasAttribute(Position.KEY_TOLL));
        }
    }
}
