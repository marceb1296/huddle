package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModeGraceTest {
    private static boolean run(ModeGrace g, boolean inMode, int ticks) {
        boolean out = false;
        for (int i = 0; i < ticks; i++) {
            out |= g.update(inMode);
        }
        return out;
    }

    @Test
    void shortDismountIsAllowed() {
        ModeGrace g = new ModeGrace(80);
        assertFalse(run(g, false, 80));
        assertTrue(g.update(false));
    }

    // bajarse 3 s, subirse un tick y repetir: con una cuenta que se reinicia se haría la pista a pie
    @Test
    void flickeringBackIntoModeDoesNotResetTheGrace() {
        ModeGrace g = new ModeGrace(80);
        boolean caught = false;
        for (int i = 0; i < 10 && !caught; i++) {
            caught = run(g, false, 60) | run(g, true, 1);
        }
        assertTrue(caught);
    }

    @Test
    void timeInModeEarnsTheGraceBack() {
        ModeGrace g = new ModeGrace(80);
        run(g, false, 70);
        run(g, true, 70);
        assertEquals(80, g.remaining());
        assertFalse(run(g, false, 80));
    }

    @Test
    void negativeGraceMeansNoMargin() {
        ModeGrace g = new ModeGrace(-5);
        assertTrue(g.update(false));
    }
}
