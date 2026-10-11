package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrackRecorderTest {
    private static final Pos START = new Pos(0, 64, 0);

    private static Pos at(double x) {
        return new Pos(x, 64, 0);
    }

    private static TrackRecorder rec(double spacing) {
        return new TrackRecorder(Route.Mode.FOOT, START, spacing, 3, 20);
    }

    @Test
    void addsAPointEveryNBlocks() {
        TrackRecorder r = rec(10);
        int added = 0;
        for (int x = 1; x <= 35; x++) {
            added += r.sample(at(x)) == TrackRecorder.Outcome.ADDED ? 1 : 0;
        }
        assertEquals(3, added);
        Route route = r.save(at(37), "p", UUID.randomUUID(), "Steve", 1).orElseThrow();
        assertEquals(4, route.checkpoints().size());
        assertEquals(at(37), route.checkpoints().get(3).center());
    }

    // una separación menor que dos radios haría puntos que se pisan y se perderían al guardar
    @Test
    void spacingSmallerThanTwoRadiiIsRaised() {
        TrackRecorder r = rec(1);
        int added = 0;
        for (int x = 1; x <= 30; x++) {
            TrackRecorder.Outcome o = r.sample(at(x));
            // el aviso de punto muy cercano es para los puntos a mano, no para cada tick
            assertTrue(o == TrackRecorder.Outcome.ADDED || o == TrackRecorder.Outcome.NONE, o.name());
            added += o == TrackRecorder.Outcome.ADDED ? 1 : 0;
        }
        assertEquals(5, added);
        assertEquals(5, r.save(at(30), "p", null, "dev", 1).orElseThrow().checkpoints().size());
    }

    // grabar con una perla o un /tp en el medio dejaría una pista imposible a pie
    @Test
    void jumpWhileRecordingBreaksTheRecording() {
        TrackRecorder r = rec(10);
        r.sample(at(5));
        assertEquals(TrackRecorder.Outcome.JUMP, r.sample(at(100)));
        assertEquals(TrackRecorder.Outcome.BROKEN, r.sample(at(101)));
        assertEquals(TrackRecorder.Outcome.BROKEN, r.point(at(102)));
        assertTrue(r.save(at(103), "p", null, "dev", 1).isEmpty());
    }

    @Test
    void jumpAtSaveIsRejected() {
        TrackRecorder r = rec(10);
        r.sample(at(5));
        assertTrue(r.save(at(500), "p", null, "dev", 1).isEmpty());
    }

    @Test
    void manualPointTooCloseIsReported() {
        TrackRecorder r = rec(0);
        assertEquals(TrackRecorder.Outcome.ADDED, r.point(at(10)));
        assertEquals(TrackRecorder.Outcome.TOO_CLOSE, r.point(at(12)));
        assertEquals(TrackRecorder.Outcome.ADDED, r.point(at(16)));
        assertEquals(TrackRecorder.Outcome.NONE, r.sample(at(30)));
    }

    // terminar encima del último punto: ese punto pasa a ser la meta en vez de perderse la meta
    @Test
    void finishOnTopOfLastPointReplacesIt() {
        TrackRecorder r = rec(0);
        r.point(at(10));
        r.point(at(20));
        Route route = r.save(at(21), "p", null, "dev", 1).orElseThrow();
        assertEquals(2, route.checkpoints().size());
        assertEquals(at(21), route.checkpoints().get(1).center());
    }

    @Test
    void stopsAtTheLimit() {
        TrackRecorder r = new TrackRecorder(Route.Mode.ELYTRA, START, 0, 1, 1e9);
        for (int i = 1; i <= Route.MAX_CHECKPOINTS; i++) {
            assertEquals(TrackRecorder.Outcome.ADDED, r.point(at(i * 5)));
        }
        assertEquals(TrackRecorder.Outcome.FULL, r.point(at(100_000)));
        assertEquals(Route.MAX_CHECKPOINTS, r.save(at(200_000), "p", null, "dev", 1).orElseThrow().checkpoints().size());
    }

    @Test
    void brokenPositionBreaksTheRecording() {
        TrackRecorder r = rec(10);
        assertEquals(TrackRecorder.Outcome.BROKEN, r.sample(new Pos(Double.NaN, 0, 0)));
    }
}
