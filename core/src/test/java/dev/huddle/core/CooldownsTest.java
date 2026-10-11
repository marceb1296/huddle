package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CooldownsTest {
    private static final long MIN = 60_000;
    private final String key = Cooldowns.playerType(UUID.randomUUID(), "race");

    @Test
    void expiresExactlyAtTheEnd() {
        Cooldowns cd = new Cooldowns();
        cd.start(key, 1000, 5 * MIN);
        assertTrue(cd.active(key, 1000 + 5 * MIN - 1));
        assertFalse(cd.active(key, 1000 + 5 * MIN));
    }

    @Test
    void typesAndPlayersAreSeparate() {
        Cooldowns cd = new Cooldowns();
        UUID p = UUID.randomUUID();
        cd.start(Cooldowns.playerType(p, "race"), 0, MIN);
        assertFalse(cd.active(Cooldowns.playerType(p, "bounty"), 1));
        assertFalse(cd.active(Cooldowns.playerType(UUID.randomUUID(), "race"), 1));
    }

    // el reloj del sistema retrocede un año: el enfriamiento no puede durar un año
    @Test
    void clockGoingBackDoesNotStretchCooldown() {
        Cooldowns cd = new Cooldowns();
        long now = 1_800_000_000_000L;
        cd.start(key, now, 5 * MIN);
        long back = now - 365L * 24 * 60 * MIN;
        assertEquals(5 * MIN, cd.remaining(key, back));
        assertFalse(cd.active(key, back + 5 * MIN));
    }

    @Test
    void hugeLengthDoesNotOverflowIntoThePast() {
        Cooldowns cd = new Cooldowns();
        cd.start(key, 1_800_000_000_000L, Long.MAX_VALUE);
        cd.prune(1_800_000_000_001L);
        assertTrue(cd.active(key, 1_800_000_000_001L));
    }

    @Test
    void zeroOrNegativeLengthClearsCooldown() {
        Cooldowns cd = new Cooldowns();
        cd.start(key, 0, MIN);
        cd.start(key, 10, 0);
        assertFalse(cd.active(key, 11));
        cd.start(key, 10, -5);
        assertFalse(cd.active(key, 11));
    }

    @Test
    void survivesSnapshotAndRestore() {
        Cooldowns cd = new Cooldowns();
        cd.start(key, 0, MIN);
        Map<String, Cooldowns.Entry> saved = cd.snapshot();
        Cooldowns again = new Cooldowns();
        again.restore(saved);
        assertTrue(again.active(key, MIN - 1));
        assertFalse(again.active(key, MIN));
    }

    // datos guardados corruptos no dejan entradas eternas ni fallan
    @Test
    void restoreSkipsBrokenEntries() {
        Cooldowns cd = new Cooldowns();
        java.util.HashMap<String, Cooldowns.Entry> saved = new java.util.HashMap<>();
        saved.put("bad", new Cooldowns.Entry(Long.MAX_VALUE, 0));
        saved.put("null", null);
        cd.restore(saved);
        assertFalse(cd.active("bad", 0));
        assertTrue(cd.snapshot().isEmpty());
    }

    @Test
    void pruneDropsOnlyExpired() {
        Cooldowns cd = new Cooldowns();
        cd.start("x", 0, 10);
        cd.start("y", 0, 100);
        cd.prune(50);
        assertEquals(1, cd.snapshot().size());
        assertTrue(cd.active("y", 50));
    }
}
