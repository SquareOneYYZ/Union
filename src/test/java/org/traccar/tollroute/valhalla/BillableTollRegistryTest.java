package org.traccar.tollroute.valhalla;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BillableTollRegistryTest {


    @Test
    void emptyListIsOpenMode() {
        BillableTollRegistry r = new BillableTollRegistry(List.of());
        assertTrue(r.isOpenMode());
    }

    @Test
    void openModeAcceptsNull() {
        BillableTollRegistry r = new BillableTollRegistry(List.of());
        assertTrue(r.isBillable(null));
    }

    @Test
    void openModeAcceptsEmptyNames() {
        BillableTollRegistry r = new BillableTollRegistry(List.of());
        assertTrue(r.isBillable(List.of()));
    }

    @Test
    void openModeAcceptsAnyName() {
        BillableTollRegistry r = new BillableTollRegistry(List.of());
        assertTrue(r.isBillable(List.of("407 ETR")));
        assertTrue(r.isBillable(List.of("Park Road Tollway")));
    }


    @Test
    void whitelistModeIsNotOpenMode() {
        BillableTollRegistry r = new BillableTollRegistry(List.of("407 ETR"));
        assertFalse(r.isOpenMode());
    }

    @Test
    void exactMatchAccepted() {
        BillableTollRegistry r = new BillableTollRegistry(List.of("407 ETR"));
        assertTrue(r.isBillable(List.of("407 ETR")));
    }

    @Test
    void caseInsensitiveMatch() {
        BillableTollRegistry r = new BillableTollRegistry(List.of("407 ETR"));
        assertTrue(r.isBillable(List.of("407 etr")));
        assertTrue(r.isBillable(List.of("407 Etr")));
    }

    @Test
    void matchInMultipleNames() {
        BillableTollRegistry r = new BillableTollRegistry(List.of("407 ETR", "Florida's Turnpike"));
        assertTrue(r.isBillable(List.of("some local name", "Florida's Turnpike")));
    }

    @Test
    void noMatchReturnsFalse() {
        BillableTollRegistry r = new BillableTollRegistry(List.of("407 ETR"));
        assertFalse(r.isBillable(List.of("Scenic Park Road")));
    }

    @Test
    void nullNamesReturnsFalseInWhitelistMode() {
        BillableTollRegistry r = new BillableTollRegistry(List.of("407 ETR"));
        assertFalse(r.isBillable(null));
    }

    @Test
    void emptyNamesReturnsFalseInWhitelistMode() {
        BillableTollRegistry r = new BillableTollRegistry(List.of("407 ETR"));
        assertFalse(r.isBillable(List.of()));
    }

    @Test
    void multipleWhitelistEntries() {
        BillableTollRegistry r = new BillableTollRegistry(
                List.of("407 ETR", "Florida's Turnpike", "I-90"));
        assertEquals(3, r.getWhitelist().size());
        assertTrue(r.isBillable(List.of("I-90")));
        assertFalse(r.isBillable(List.of("Highway 401")));
    }

    @Test
    void whitelistEntriesAreTrimmed() {
        BillableTollRegistry r = new BillableTollRegistry(List.of("  407 ETR  "));
        assertTrue(r.isBillable(List.of("407 ETR")));
    }
}
