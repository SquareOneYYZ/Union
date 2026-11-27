package org.traccar.tollpricing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the owner's constraint from {@code docs/toll-pricing/23}: "I just don't want false
 * entry logic to be a new issue." Pricing is read-only over detection output, so nothing in
 * {@code org.traccar.tollpricing} may create or emit events, or touch detection state or its
 * Redis keys. This is the enforcing function the doc names.
 */
public class TollPricingIsolationTest {

    private static final String[] FORBIDDEN = {
        "new Event(",
        "eventDetected",
        "TollRouteState",
        "TollRouteProcessor",
        "TollEventHandler",
        "RedisCache",
        "setTollStartDistance",
        "setTollrouteTime",
    };

    @Test
    public void pricingNeverTouchesDetection() throws IOException {
        Path dir = Path.of("src", "main", "java", "org", "traccar", "tollpricing");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String source = Files.readString(file);
                for (String token : FORBIDDEN) {
                    if (source.contains(token)) {
                        hits.add(file.getFileName() + ": " + token);
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "pricing must not touch detection: " + hits);
    }
}
