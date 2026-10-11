package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RaceTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private static final Pos ORIGIN = new Pos(0, 64, 0);

    private static Pos at(double x) {
        return new Pos(x, 64, 0);
    }

    private static Route.Checkpoint cp(double x) {
        return new Route.Checkpoint(at(x), 2);
    }

    private static Route line(int laps, double... xs) {
        List<Route.Checkpoint> cps = new ArrayList<>();
        for (double x : xs) {
            cps.add(cp(x));
        }
        return Route.of("t", null, "dev", Route.Mode.FOOT, laps, ORIGIN, cps).orElseThrow();
    }

    private static RaceState race(Route r, UUID... players) {
        Map<UUID, Pos> starts = new LinkedHashMap<>();
        for (UUID p : players) {
            starts.put(p, ORIGIN);
        }
        return new RaceState(r, starts, 100, 50);
    }

    private static List<RaceState.Kind> kinds(List<RaceState.Event> evs) {
        return evs.stream().map(RaceState.Event::kind).toList();
    }

    // con élitros o en bote se avanza más de un radio por tick: estar dentro al final del tick no alcanza
    @Test
    void fastMoveThroughCheckpointCounts() {
        RaceState s = race(line(1, 10, 40), a);
        s.move(a, at(5), 101);
        assertEquals(List.of(RaceState.Kind.CHECKPOINT), kinds(s.move(a, at(15), 102)));
        assertEquals(1, s.nextCheckpoint(a));
    }

    @Test
    void twoCheckpointsInOneTickCountInOrder() {
        RaceState s = race(line(1, 10, 20, 60), a);
        List<RaceState.Event> evs = s.move(a, at(30), 101);
        assertEquals(List.of(new RaceState.Event(RaceState.Kind.CHECKPOINT, 0), new RaceState.Event(RaceState.Kind.CHECKPOINT, 1)), evs);
        assertEquals(2, s.nextCheckpoint(a));
    }

    // atajo: pasar por el segundo sin el primero no cuenta
    @Test
    void checkpointOutOfOrderDoesNotCount() {
        Route r = Route.of("t", null, "dev", Route.Mode.FOOT, 1, ORIGIN,
                List.of(new Route.Checkpoint(new Pos(0, 64, 30), 2), cp(10), cp(40))).orElseThrow();
        RaceState s = race(r, a);
        assertTrue(s.move(a, at(12), 101).isEmpty());
        assertEquals(0, s.nextCheckpoint(a));
    }

    @Test
    void segmentThatPassesBesideDoesNotCount() {
        RaceState s = race(line(1, 10, 40), a);
        s.move(a, new Pos(0, 64, 3), 101);
        assertTrue(s.move(a, new Pos(20, 64, 3), 102).isEmpty());
    }

    // en el mismo tick, el segmento que cruza la meta y vuelve a entrar al primer punto no suma otra vuelta
    @Test
    void oneSegmentCannotChainLaps() {
        RaceState s = race(line(3, 10, 20), a);
        List<RaceState.Event> evs = s.move(a, at(25), 101);
        assertEquals(List.of(RaceState.Kind.CHECKPOINT, RaceState.Kind.CHECKPOINT, RaceState.Kind.LAP), kinds(evs));
        assertEquals(1, s.lapsDone(a));
        // vuelve hacia atrás y cruza el primero otra vez: recién ahí cuenta
        assertEquals(List.of(RaceState.Kind.CHECKPOINT), kinds(s.move(a, at(9), 102)));
    }

    // quedarse quieto dentro de un punto no suma puntos ni vueltas
    @Test
    void standingStillInsideFarmsNothing() {
        RaceState s = race(line(3, 10, 20), a);
        s.move(a, at(10), 101);
        for (long t = 102; t < 200; t++) {
            assertTrue(s.move(a, at(10), t).isEmpty());
        }
        assertEquals(0, s.lapsDone(a));
    }

    @Test
    void lapsThenFinish() {
        RaceState s = race(line(2, 10, 20), a);
        s.move(a, at(25), 101);
        s.move(a, at(5), 102);
        List<RaceState.Event> evs = s.move(a, at(25), 103);
        assertEquals(RaceState.Kind.FINISH, evs.get(evs.size() - 1).kind());
        assertEquals(RaceState.Status.FINISHED, s.status(a));
        assertTrue(s.over());
    }

    // perla de ender, waystone o /tp: descalifica y el salto no cuenta los puntos que cruza
    @Test
    void jumpDisqualifiesAndCountsNothing() {
        RaceState s = race(line(1, 10, 100), a);
        assertEquals(List.of(new RaceState.Event(RaceState.Kind.JUMP, 0)), s.move(a, at(100), 101));
        assertEquals(RaceState.Status.DISQUALIFIED, s.status(a));
        assertTrue(s.move(a, at(100), 102).isEmpty());
        assertTrue(s.results().isEmpty());
    }

    @Test
    void moveExactlyAtJumpLimitIsAllowed() {
        RaceState s = race(line(1, 10, 100), a);
        s.move(a, at(50), 101);
        assertEquals(RaceState.Status.RUNNING, s.status(a));
    }

    // posición rota de otro mod: se ignora sin descalificar ni avanzar
    @Test
    void nonFinitePositionIsIgnored() {
        RaceState s = race(line(1, 10, 40), a);
        assertTrue(s.move(a, new Pos(Double.NaN, 64, 0), 101).isEmpty());
        assertTrue(s.move(a, new Pos(Double.POSITIVE_INFINITY, 64, 0), 102).isEmpty());
        assertEquals(RaceState.Status.RUNNING, s.status(a));
        assertEquals(List.of(RaceState.Kind.CHECKPOINT), kinds(s.move(a, at(12), 103)));
    }

    // con la última posición en NaN la comparación del salto da siempre falso y un /tp pasaría
    @Test
    void jumpAfterNonFinitePositionStillDisqualifies() {
        RaceState s = race(line(1, 10, 400), a);
        s.move(a, new Pos(Double.NaN, 64, 0), 101);
        assertEquals(List.of(RaceState.Kind.JUMP), kinds(s.move(a, at(400), 102)));
    }

    // dos llegadas en el mismo tick: gana quien cruzó antes dentro del tick, no quien se procesó primero
    @Test
    void sameTickFinishOrderedByCrossingPoint() {
        RaceState s = race(line(1, 10), a, b);
        s.move(a, at(0), 101);
        s.move(b, at(6), 101);
        s.move(a, at(11), 102);
        s.move(b, at(9), 102);
        List<RaceState.Result> fin = s.newFinishes();
        assertEquals(List.of(b, a), fin.stream().map(RaceState.Result::player).toList());
        assertEquals(List.of(1, 2), fin.stream().map(RaceState.Result::place).toList());
        assertTrue(fin.get(0).ticks() < fin.get(1).ticks());
        assertTrue(fin.get(0).ticks() >= 1 && fin.get(1).ticks() <= 2);
        assertTrue(s.newFinishes().isEmpty());
        assertEquals(fin, s.results());
    }

    @Test
    void laterTickFinisherNeverBeatsEarlier() {
        RaceState s = race(line(1, 10), a, b);
        s.move(b, at(9), 101);
        s.newFinishes();
        s.move(a, at(9), 102);
        assertEquals(List.of(b, a), s.results().stream().map(RaceState.Result::player).toList());
        assertEquals(2, s.newFinishes().get(0).place());
    }

    // empate exacto entre la salida de un tick y la entrada del siguiente: la tabla final
    // respeta el puesto ya anunciado
    @Test
    void resultsKeepAnnouncedPlacesOnExactTie() {
        Map<UUID, Pos> starts = new LinkedHashMap<>();
        starts.put(a, at(9));
        starts.put(b, ORIGIN);
        RaceState s = new RaceState(line(1, 10), starts, 100, 50);
        s.move(b, at(8), 101);
        assertEquals(b, s.newFinishes().get(0).player());
        s.move(a, at(9), 102);
        s.newFinishes();
        List<RaceState.Result> res = s.results();
        assertEquals(res.get(0).ticks(), res.get(1).ticks());
        assertEquals(List.of(b, a), res.stream().map(RaceState.Result::player).toList());
    }

    @Test
    void leaverAndDisqualifiedCannotFinish() {
        RaceState s = race(line(1, 10), a, b);
        s.leave(a);
        s.disqualify(b);
        assertTrue(s.move(a, at(10), 101).isEmpty());
        assertTrue(s.move(b, at(10), 101).isEmpty());
        assertEquals(RaceState.Status.LEFT, s.status(a));
        assertTrue(s.over());
    }

    // quien ya llegó no pierde su puesto por un salto o un abandono posterior
    @Test
    void finishedRunnerKeepsResult() {
        RaceState s = race(line(1, 10), a);
        s.move(a, at(10), 101);
        s.move(a, at(500), 102);
        s.leave(a);
        s.disqualify(a);
        assertEquals(RaceState.Status.FINISHED, s.status(a));
        assertEquals(1, s.results().size());
    }

    @Test
    void standingsRankFinishedThenProgressThenOut() {
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        RaceState s = race(line(1, 10, 20, 30), a, b, c, d);
        s.move(a, at(12), 101);
        s.move(b, at(25), 101);
        s.move(c, at(8), 101);
        s.leave(d);
        assertEquals(List.of(b, a, c, d), s.standings());
        s.move(c, at(31), 102);
        assertEquals(List.of(c, b, a, d), s.standings());
    }

    @Test
    void unknownPlayerIsIgnored() {
        RaceState s = race(line(1, 10), a);
        assertTrue(s.move(b, at(10), 101).isEmpty());
        assertEquals(-1, s.nextCheckpoint(b));
    }

    // quien muere vuelve al último punto que pasó (la largada si no pasó ninguno) y el regreso no cuenta como salto
    @Test
    void respawnGoesBackToTheLastCheckpoint() {
        RaceState s = race(line(2, 10, 40, 70), a, b);
        assertEquals(ORIGIN, s.respawn(a));
        s.move(a, at(5), 101);
        s.move(a, at(12), 102);
        assertEquals(1, s.nextCheckpoint(a));
        assertEquals(at(10), s.respawn(a));
        // 30 bloques de vuelta en un tick: con el máximo de 50 no salta, pero desde el punto se sigue sin volver a contarlo
        assertTrue(kinds(s.move(a, at(11), 103)).isEmpty());
        for (double x = 15; x <= 70; x += 5) {
            s.move(a, at(x), 104 + (long) x);
        }
        // pasó la meta de la primera vuelta: vuelve a ella y sigue en la segunda
        assertEquals(1, s.lapsDone(a));
        assertEquals(at(70), s.respawn(a));
        assertEquals(RaceState.Status.RUNNING, s.status(a));
        s.leave(b);
        assertEquals(null, s.respawn(b));
    }

    // sin respawn, volver de lejos en un tick es un salto y descalifica: respawn tiene que mover el último lugar
    @Test
    void respawnFarAwayIsNotAJump() {
        RaceState s = race(line(1, 10, 40, 100), a);
        for (double x = 5; x <= 95; x += 5) {
            s.move(a, at(x), 100 + (long) x);
        }
        Pos back = s.respawn(a);
        assertEquals(at(40), back);
        assertFalse(kinds(s.move(a, back, 300)).contains(RaceState.Kind.JUMP));
        assertEquals(RaceState.Status.RUNNING, s.status(a));
    }
}
