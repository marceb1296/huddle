package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LastManTest {
    private static final Pos CENTER = new Pos(100.5, 64, -50.5);
    // 100, 50 y 10 de medio lado; cada etapa quieta 600 ticks y 400 achicándose; 200 para volver; 1200 al final
    private static final LastMan.Params PARAMS = new LastMan.Params(List.of(100.0, 50.0, 10.0), 600, 400, 200, 1200);
    private static final long START = 1000;
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    private LastMan game() {
        return new LastMan(CENTER, List.of(a, b, c), START, PARAMS);
    }

    private static Pos off(double dx, double dz) {
        return new Pos(CENTER.x() + dx, CENTER.y(), CENTER.z() + dz);
    }

    private static Map<UUID, Pos> at(Object... pairs) {
        Map<UUID, Pos> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((UUID) pairs[i], (Pos) pairs[i + 1]);
        }
        return m;
    }

    @Test
    void shrinksInStagesAndHoldsBetween() {
        LastMan g = game();
        assertEquals(100, g.halfSize(0), 1e-9);
        assertEquals(100, g.halfSize(START + 599), 1e-9);
        assertEquals(75, g.halfSize(START + 800), 1e-9);
        assertEquals(50, g.halfSize(START + 1000), 1e-9);
        assertEquals(50, g.halfSize(START + 1599), 1e-9);
        assertEquals(30, g.halfSize(START + 1800), 1e-9);
        assertEquals(10, g.halfSize(START + 2000), 1e-9);
        assertEquals(10, g.halfSize(START + 99999), 1e-9);
        assertFalse(g.shrinking(START + 100));
        assertTrue(g.shrinking(START + 700));
        assertFalse(g.shrinking(START + 2500));
    }

    @Test
    void nextChangeIsTheEndOfTheCurrentPhase() {
        LastMan g = game();
        assertEquals(START, g.nextChange(10));
        assertEquals(START, g.nextChange(START - 1));
        assertEquals(START + 600, g.nextChange(START));
        assertEquals(START + 1000, g.nextChange(START + 600));
        assertEquals(START + 1600, g.nextChange(START + 1200));
        assertEquals(START + 2000 + 1200, g.nextChange(START + 2000));
        assertEquals(START + 3200, g.endTick());
    }

    // cuadrada como el borde del mundo que ve el jugador: la esquina está dentro aunque quede lejos del centro
    @Test
    void zoneIsSquareAndBrokenPositionsAreOutside() {
        LastMan g = game();
        assertTrue(g.inside(off(100, -100), START));
        assertFalse(g.inside(off(100.01, 0), START));
        assertFalse(g.inside(off(0, -100.01), START));
        assertFalse(g.inside(new Pos(Double.NaN, 64, CENTER.z()), START));
        assertFalse(g.inside(new Pos(CENTER.x(), 64, Double.NaN), START));
        assertFalse(g.inside(null, START));
        // la altura no cuenta
        assertTrue(g.inside(new Pos(CENTER.x(), 9999, CENTER.z()), START));
    }

    @Test
    void outsideTooLongIsDisqualifiedButReturningResets() {
        LastMan g = game();
        Map<UUID, Pos> out = at(a, off(150, 0), b, off(0, 0), c, off(0, 0));
        assertEquals(List.of(), g.tick(out, START));
        assertEquals(200, g.outsideLeft(a, START));
        assertEquals(List.of(), g.tick(out, START + 199));
        assertEquals(1, g.outsideLeft(a, START + 199));
        // vuelve justo antes y la cuenta se borra
        assertEquals(List.of(), g.tick(at(a, off(0, 0)), START + 199));
        assertEquals(-1, g.outsideLeft(a, START + 199));
        assertEquals(List.of(), g.tick(out, START + 300));
        assertEquals(List.of(), g.tick(out, START + 499));
        assertEquals(List.of(a), g.tick(out, START + 500));
        assertFalse(g.alive().contains(a));
        assertEquals(-1, g.outsideLeft(a, START + 500));
    }

    // la zona que se achica deja afuera al que estaba quieto
    @Test
    void shrinkingZoneCatchesWhoStaysPut() {
        LastMan g = game();
        Map<UUID, Pos> where = at(a, off(60, 0), b, off(0, 0), c, off(0, 0));
        assertEquals(List.of(), g.tick(where, START + 700));
        assertEquals(-1, g.outsideLeft(a, START + 700));
        // a los 900 ticks el medio lado es 62,5: todavía adentro
        assertEquals(List.of(), g.tick(where, START + 900));
        assertEquals(-1, g.outsideLeft(a, START + 900));
        assertEquals(List.of(), g.tick(where, START + 1000));
        assertEquals(200, g.outsideLeft(a, START + 1000));
        assertEquals(List.of(a), g.tick(where, START + 1200));
    }

    // desconectado: ni avanza ni se borra su cuenta; la sala decide aparte si lo saca
    @Test
    void missingPlayersAreNotJudged() {
        LastMan g = game();
        g.tick(at(a, off(500, 0)), START);
        assertEquals(List.of(), g.tick(at(b, off(0, 0)), START + 1000));
        assertTrue(g.alive().contains(a));
        assertEquals(List.of(a), g.tick(at(a, off(500, 0)), START + 1000));
    }

    @Test
    void lastOneStandingWins() {
        LastMan g = game();
        assertNull(g.winner());
        g.eliminate(a);
        assertFalse(g.over(START));
        // un eliminado ya no cuenta aunque siga mandando posición
        g.tick(at(a, off(500, 0)), START);
        assertEquals(List.of(), g.tick(at(a, off(500, 0)), START + 500));
        g.eliminate(b);
        assertTrue(g.over(START));
        assertEquals(c, g.winner());
        assertEquals(List.of(), g.tick(at(c, off(500, 0)), START + 1000));
        assertEquals(c, g.winner());
    }

    // los dos últimos quedan fuera el mismo tick: no gana ninguno
    @Test
    void lastTwoOutTogetherMeansNoWinner() {
        LastMan g = new LastMan(CENTER, List.of(a, b), START, PARAMS);
        Map<UUID, Pos> out = at(a, off(150, 0), b, off(-150, 0));
        g.tick(out, START);
        assertEquals(2, g.tick(out, START + 200).size());
        assertTrue(g.over(START + 200));
        assertNull(g.winner());
    }

    // solo (el admin bajó minPlayers a 1): nadie gana sin rivales, si no sería botín gratis; dura hasta el final o hasta salir
    @Test
    void aloneNobodyWins() {
        LastMan g = new LastMan(CENTER, List.of(a), START, PARAMS);
        assertFalse(g.over(START));
        assertNull(g.winner());
        assertFalse(g.over(START + 3199));
        assertTrue(g.over(START + 3200));
        assertNull(g.winner());
        LastMan h = new LastMan(CENTER, List.of(a), START, PARAMS);
        h.eliminate(a);
        assertTrue(h.over(START));
    }

    @Test
    void timeRunsOutWithoutWinner() {
        LastMan g = game();
        assertFalse(g.over(START + 3199));
        assertTrue(g.over(START + 3200));
        assertNull(g.winner());
        assertEquals(List.of(), g.tick(at(a, off(500, 0)), START + 3200));
    }

    // terminado por tiempo, ya no se descalifica a nadie: si no, aparecería un ganador después del final
    @Test
    void nobodyIsJudgedAfterTheEnd() {
        LastMan g = game();
        Map<UUID, Pos> out = at(a, off(500, 0), b, off(500, 0));
        g.tick(out, START + 3000);
        assertEquals(List.of(), g.tick(out, START + 3200));
        assertEquals(3, g.alive().size());
        assertNull(g.winner());
    }

    @Test
    void hostileSizesAreCleanedOrRejected() {
        LastMan.Params p = new LastMan.Params(Arrays.asList(10.0, Double.NaN, -5.0, null, 80.0, Double.POSITIVE_INFINITY, 0.0, 40.0), 1, 1, 1, 1);
        assertEquals(List.of(80.0, 40.0, 10.0), p.sizes());
        assertThrows(IllegalArgumentException.class, () -> new LastMan.Params(List.of(-1.0, Double.NaN), 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LastMan.Params(List.of(), 1, 1, 1, 1));
    }

    // sin tiempos no divide por cero: va directo a la última etapa
    @Test
    void zeroTimesGoStraightToTheLastStage() {
        LastMan g = new LastMan(CENTER, List.of(a, b), START, new LastMan.Params(List.of(100.0, 20.0), 0, 0, 0, 50));
        assertEquals(20, g.halfSize(START), 1e-9);
        assertEquals(START + 50, g.nextChange(START));
        g.tick(at(a, off(30, 0)), START);
        assertFalse(g.alive().contains(a));
    }

    @Test
    void hugeTimesDoNotOverflow() {
        LastMan g = new LastMan(CENTER, List.of(a, b), START, new LastMan.Params(List.of(100.0, 50.0, 10.0), Long.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        assertTrue(g.endTick() > START);
        assertFalse(g.over(START + 1_000_000_000L));
        assertEquals(100, g.halfSize(START + 1_000_000_000L), 1e-9);
        assertTrue(g.nextChange(START + 5) > START + 5);
    }

    @Test
    void tooManyStagesAreCut() {
        Double[] many = new Double[500];
        for (int i = 0; i < many.length; i++) {
            many[i] = 1000.0 - i;
        }
        assertEquals(64, new LastMan.Params(List.of(many), 1, 1, 1, 1).sizes().size());
    }
}
