package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AirdropTest {
    private static final Pos LAND = new Pos(10.5, 70, 10.5);
    // cae 1200 ticks desde 60 bloques; se abre en 100; dura 6000 después de caer; 3 bloques al centro
    private static final Airdrop.Params PARAMS = new Airdrop.Params(1200, 60, 100, 6000, 3);
    private static final long LANDED = 2200;
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    private Airdrop drop() {
        return new Airdrop(LAND, List.of(a, b), 1000, PARAMS);
    }

    private static Pos near(double dx) {
        return new Pos(LAND.x() + dx, LAND.y(), LAND.z());
    }

    private static Map<UUID, Pos> at(Object... pairs) {
        Map<UUID, Pos> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((UUID) pairs[i], (Pos) pairs[i + 1]);
        }
        return m;
    }

    // ticks seguidos con las mismas posiciones; devuelve el siguiente tick
    private static long run(Airdrop d, long from, int ticks, Map<UUID, Pos> where) {
        for (int i = 0; i < ticks; i++) {
            d.tick(where, Set.of(), from + i);
        }
        return from + ticks;
    }

    @Test
    void fallsSlowlyAndStaysOnTheGround() {
        Airdrop d = drop();
        assertEquals(130, d.position(1000).y(), 1e-9);
        assertEquals(100, d.position(1600).y(), 1e-9);
        assertFalse(d.landed(2199));
        assertTrue(d.landed(LANDED));
        assertEquals(70, d.position(LANDED).y(), 1e-9);
        assertEquals(70, d.position(9000).y(), 1e-9);
        assertEquals(LAND.x(), d.position(1500).x(), 1e-9);
        assertEquals(130, d.position(0).y(), 1e-9);
    }

    @Test
    void staysNearUntilItOpens() {
        Airdrop d = drop();
        long t = run(d, LANDED, 99, at(a, near(1)));
        assertNull(d.winner());
        assertEquals(a, d.holder());
        assertEquals(0.99, d.progress(), 1e-9);
        d.tick(at(a, near(1)), Set.of(), t);
        assertEquals(a, d.winner());
        assertTrue(d.over(t));
    }

    // esperar debajo mientras cae no adelanta nada
    @Test
    void nothingCountsBeforeLanding() {
        Airdrop d = drop();
        run(d, 1000, 1200, at(a, near(0)));
        assertEquals(0, d.progress(), 1e-9);
        assertNull(d.winner());
    }

    @Test
    void aHitStartsOver() {
        Airdrop d = drop();
        long t = run(d, LANDED, 80, at(a, near(1)));
        d.tick(at(a, near(1)), Set.of(a), t++);
        assertEquals(0, d.progress(), 1e-9);
        run(d, t, 99, at(a, near(1)));
        assertNull(d.winner());
    }

    @Test
    void steppingAwayStartsOver() {
        Airdrop d = drop();
        long t = run(d, LANDED, 80, at(a, near(1)));
        t = run(d, t, 1, at(a, near(3.6)));
        assertEquals(0, d.progress(), 1e-9);
        assertNull(d.holder());
        run(d, t, 99, at(a, near(1)));
        assertNull(d.winner());
    }

    // dos cerca: nadie avanza; cuando uno se va, el otro sigue desde donde estaba
    @Test
    void contestedFreezes() {
        Airdrop d = drop();
        long t = run(d, LANDED, 50, at(a, near(1)));
        t = run(d, t, 200, at(a, near(1), b, near(-1)));
        assertNull(d.winner());
        assertEquals(0.5, d.progress(), 1e-9);
        t = run(d, t, 49, at(a, near(1), b, near(10)));
        assertNull(d.winner());
        d.tick(at(a, near(1), b, near(10)), Set.of(), t);
        assertEquals(a, d.winner());
    }

    // el que llega mientras otro está solo y lo saca a golpes empieza de cero
    @Test
    void newHolderStartsFromZero() {
        Airdrop d = drop();
        long t = run(d, LANDED, 60, at(a, near(1)));
        t = run(d, t, 1, at(b, near(1)));
        assertEquals(b, d.holder());
        assertEquals(0.01, d.progress(), 1e-9);
    }

    @Test
    void outsidersAndLeaversDoNotCount() {
        Airdrop d = drop();
        UUID stranger = UUID.randomUUID();
        long t = run(d, LANDED, 100, at(stranger, near(0)));
        assertNull(d.winner());
        // alguien de afuera parado al lado no traba al participante
        run(d, t, 100, at(stranger, near(0), a, near(1)));
        assertEquals(a, d.winner());
        Airdrop e = drop();
        e.leave(a);
        run(e, LANDED, 100, at(a, near(1)));
        assertNull(e.winner());
    }

    @Test
    void reachIsMeasuredToTheBoxCenter() {
        Airdrop d = drop();
        // el centro de la caja está medio bloque sobre el suelo
        run(d, LANDED, 100, at(a, new Pos(LAND.x(), LAND.y() + 0.5 + 2.9, LAND.z())));
        assertEquals(a, d.winner());
        Airdrop e = drop();
        run(e, LANDED, 100, at(a, new Pos(LAND.x(), LAND.y() + 0.5 + 3.1, LAND.z())));
        assertNull(e.winner());
        // uno apenas fuera del radio no traba al que está al lado
        Airdrop f = drop();
        run(f, LANDED, 100, at(a, new Pos(LAND.x() + 3.1, LAND.y() + 0.5, LAND.z()), b, near(1)));
        assertEquals(b, f.winner());
    }

    @Test
    void endsWithoutWinnerWhenTimeRunsOut() {
        Airdrop d = drop();
        assertFalse(d.over(LANDED + 5999));
        assertTrue(d.over(LANDED + 6000));
        run(d, LANDED + 6000, 100, at(a, near(0)));
        assertNull(d.winner());
    }

    @Test
    void everyoneLeavingEndsIt() {
        Airdrop d = drop();
        d.leave(a);
        assertFalse(d.over(1000));
        d.leave(b);
        assertTrue(d.over(1000));
    }

    @Test
    void brokenPositionsAreIgnored() {
        Airdrop d = drop();
        run(d, LANDED, 100, at(a, new Pos(Double.NaN, 70, 10)));
        Map<UUID, Pos> nul = new HashMap<>();
        nul.put(a, null);
        run(d, LANDED + 100, 100, nul);
        assertNull(d.winner());
    }
}
