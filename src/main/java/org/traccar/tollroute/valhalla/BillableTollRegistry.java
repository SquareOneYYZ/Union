package org.traccar.tollroute.valhalla;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


public final class BillableTollRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(BillableTollRegistry.class);

    private final Set<String> whitelist;
    private final boolean     openMode;

    public BillableTollRegistry(Config config) {
        String raw = config.getString(Keys.VALHALLA_BILLABLE_REFS, "");
        if (raw == null || raw.isBlank()) {
            this.whitelist = Collections.emptySet();
            this.openMode  = true;
            LOGGER.info("BillableTollRegistry: open mode (no whitelist configured — "
                    + "all OSM toll=yes edges accepted)");
        } else {
            this.whitelist = Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.toUnmodifiableSet());
            this.openMode  = false;
            LOGGER.info("BillableTollRegistry: whitelist mode — {} entries: {}",
                    whitelist.size(), whitelist);
        }
    }

    public BillableTollRegistry(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            this.whitelist = Collections.emptySet();
            this.openMode  = true;
        } else {
            this.whitelist = refs.stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.toUnmodifiableSet());
            this.openMode  = false;
        }
    }


    public boolean isBillable(List<String> names) {
        if (openMode) {
            return true;
        }
        if (names == null || names.isEmpty()) {
            return false;
        }
        return names.stream()
                .map(String::toLowerCase)
                .anyMatch(whitelist::contains);
    }

    public boolean isOpenMode() {
        return openMode;
    }

    public Set<String> getWhitelist() {
        return whitelist;
    }
}
