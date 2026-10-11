package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouteTest {
    private static final Pos START = new Pos(0, 64, 0);

    private static Route.Checkpoint cp(double x, double r) {
        return new Route.Checkpoint(new Pos(x, 64, 0), r);
    }

    private static Optional<Route> of(int laps, List<Route.Checkpoint> cps) {
        return Route.of("pista", UUID.randomUUID(), "Steve", Route.Mode.BOAT, laps, START, cps);
    }

    // datos guardados rotos: puntos NaN, nulos o infinitos se descartan sin romper la carga
    @Test
    void brokenPointsAreDropped() {
        List<Route.Checkpoint> cps = Arrays.asList(cp(10, 2), null, new Route.Checkpoint(new Pos(Double.NaN, 0, 0), 2),
                new Route.Checkpoint(null, 2), new Route.Checkpoint(new Pos(30, Double.NEGATIVE_INFINITY, 0), 2),
                new Route.Checkpoint(new Pos(40, 64, 0), Double.NaN), cp(50, 2));
        assertEquals(List.of(cp(10, 2), cp(50, 2)), of(1, cps).orElseThrow().checkpoints());
    }

    // un punto repetido o pegado al anterior: quedarse quieto en la zona compartida contaría los dos
    @Test
    void overlappingConsecutivePointsAreDropped() {
        Route r = of(1, List.of(cp(10, 2), cp(10, 2), cp(13, 2), cp(14, 2), cp(30, 2))).orElseThrow();
        assertEquals(List.of(cp(10, 2), cp(14, 2), cp(30, 2)), r.checkpoints());
    }

    @Test
    void emptyOrUselessRouteIsRejected() {
        assertTrue(of(1, List.of()).isEmpty());
        assertTrue(of(1, null).isEmpty());
        assertTrue(of(1, Arrays.asList((Route.Checkpoint) null)).isEmpty());
        assertTrue(Route.of("x", null, null, Route.Mode.FOOT, 1, new Pos(0, Double.NaN, 0), List.of(cp(10, 2))).isEmpty());
        assertTrue(Route.of("x", null, null, null, 1, START, List.of(cp(10, 2))).isEmpty());
    }

    // radio 0 haría la pista imposible; uno enorme la ganaría quien no se mueve
    @Test
    void radiusIsClamped() {
        Route r = of(1, List.of(cp(10, 0), cp(100, 1e9), cp(200, -5))).orElseThrow();
        assertEquals(List.of(Route.MIN_RADIUS, Route.MAX_RADIUS, Route.MIN_RADIUS),
                r.checkpoints().stream().map(Route.Checkpoint::radius).toList());
    }

    @Test
    void hugeListIsCapped() {
        List<Route.Checkpoint> cps = new ArrayList<>();
        for (int i = 1; i <= 100_000; i++) {
            cps.add(cp(i * 10, 2));
        }
        assertEquals(Route.MAX_CHECKPOINTS, of(1, cps).orElseThrow().checkpoints().size());
    }

    @Test
    void lapsAreClamped() {
        List<Route.Checkpoint> cps = List.of(cp(10, 2), cp(30, 2));
        assertEquals(1, of(0, cps).orElseThrow().laps());
        assertEquals(1, of(Integer.MIN_VALUE, cps).orElseThrow().laps());
        assertEquals(Route.MAX_LAPS, of(Integer.MAX_VALUE, cps).orElseThrow().laps());
    }

    // circuito de un solo punto: cada entrada al punto sería una vuelta
    @Test
    void circuitNeedsTwoPointsThatDoNotTouchAcrossTheLoop() {
        assertEquals(1, of(5, List.of(cp(10, 2))).orElseThrow().laps());
        Route r = of(5, List.of(cp(10, 2), cp(30, 2), cp(12, 2))).orElseThrow();
        assertEquals(List.of(cp(10, 2), cp(30, 2)), r.checkpoints());
        assertEquals(5, r.laps());
    }

    @Test
    void namesAreSingleCommandWords() {
        assertEquals("lago_norte", Route.cleanName("  Lago_Norte "));
        assertNull(Route.cleanName("dos palabras"));
        assertNull(Route.cleanName(""));
        assertNull(Route.cleanName("x".repeat(Route.MAX_NAME + 1)));
        assertNull(Route.cleanName("pista\"; drop"));
        assertNull(Route.cleanName("§cpista"));
        assertFalse(Route.of("dos palabras", null, null, Route.Mode.FOOT, 1, START, List.of(cp(10, 2))).isPresent());
    }

    @Test
    void modeParsingRejectsUnknown() {
        assertEquals(Optional.of(Route.Mode.ELYTRA), Route.parseMode(" elytra "));
        assertTrue(Route.parseMode("rocket").isEmpty());
        assertTrue(Route.parseMode(null).isEmpty());
    }

    @Test
    void missingAuthorNameGetsPlaceholder() {
        Route r = Route.of("x", null, "  ", Route.Mode.FOOT, 1, START, List.of(cp(10, 2))).orElseThrow();
        assertEquals("?", r.authorName());
        assertEquals(64, Route.of("x", null, "n".repeat(1000), Route.Mode.FOOT, 1, START, List.of(cp(10, 2))).orElseThrow().authorName().length());
    }

    // cerca del borde del mundo los cuadrados de coordenadas no pierden la detección
    @Test
    void entryWorksFarFromOrigin() {
        Route.Checkpoint c = new Route.Checkpoint(new Pos(29_999_000, 64, -29_999_000), 2);
        assertTrue(c.entry(new Pos(29_998_990, 64, -29_999_000), new Pos(29_999_010, 64, -29_999_000)) > 0);
        assertEquals(-1, c.entry(new Pos(29_998_990, 64, -29_998_990), new Pos(29_999_010, 64, -29_998_990)));
    }

    @Test
    void entryPointIsWhereTheSegmentTouchesTheSphere() {
        Route.Checkpoint c = new Route.Checkpoint(new Pos(10, 0, 0), 2);
        assertEquals(0.8, c.entry(new Pos(0, 0, 0), new Pos(10, 0, 0)), 1e-9);
        assertEquals(-1, c.entry(new Pos(0, 0, 0), new Pos(7.9, 0, 0)));
        assertEquals(-1, c.entry(new Pos(20, 0, 0), new Pos(30, 0, 0)));
        assertEquals(0, c.entry(new Pos(11, 0, 0), new Pos(30, 0, 0)));
    }
}
