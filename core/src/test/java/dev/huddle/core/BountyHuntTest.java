package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.huddle.core.BountyHunt.Outcome;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BountyHuntTest {
    private static final BountyHunt.Params PARAMS = new BountyHunt.Params(12000, 2400, 200);
    private final UUID target = UUID.randomUUID();
    private final UUID h1 = UUID.randomUUID();
    private final UUID h2 = UUID.randomUUID();

    private BountyHunt hunt() {
        return new BountyHunt(target, List.of(h1, h2), 1000, PARAMS);
    }

    @Test
    void survivingTheTimeWins() {
        BountyHunt b = hunt();
        assertEquals(Outcome.RUNNING, b.outcome(12999));
        assertEquals(Outcome.SURVIVED, b.outcome(13000));
        assertEquals(target, b.winner());
        // decidido: una muerte después no cambia nada
        b.markedDied(h1, 13001, false);
        assertEquals(target, b.winner());
        // ni que salga después
        b.leave(target, h1, 13002);
        assertEquals(Outcome.SURVIVED, b.outcome(13002));
        assertEquals(target, b.winner());
    }

    @Test
    void hunterKillWins() {
        BountyHunt b = hunt();
        b.markedDied(h2, 5000, false);
        assertEquals(Outcome.CAUGHT, b.outcome(5000));
        assertEquals(h2, b.winner());
        assertEquals(Outcome.CAUGHT, b.outcome(20000));
        // los cazadores se van después de la captura: el ganador no cambia
        b.leave(h1, null, 5001);
        b.leave(h2, null, 5002);
        assertEquals(Outcome.CAUGHT, b.outcome(5002));
        assertEquals(h2, b.winner());
    }

    // lava, caída o un zombi: gana el último cazador que le pegó, si fue hace poco
    @Test
    void otherDeathCreditsTheLastRecentHit() {
        BountyHunt b = hunt();
        b.hit(h1, 4000);
        b.hit(h2, 4100);
        b.markedDied(null, 4300, false);
        assertEquals(h2, b.winner());
        BountyHunt late = hunt();
        late.hit(h1, 4000);
        late.markedDied(null, 4201, false);
        assertEquals(Outcome.NO_WINNER, late.outcome(4201));
        assertNull(late.winner());
    }

    // un golpe de alguien que no caza (otro jugador, el propio marcado) no cuenta
    @Test
    void onlyHuntersEarnCredit() {
        BountyHunt b = hunt();
        b.hit(h1, 4000);
        b.hit(UUID.randomUUID(), 4100);
        b.hit(target, 4150);
        b.markedDied(UUID.randomUUID(), 4200, false);
        assertEquals(h1, b.winner());
    }

    // abandonar nunca le conviene al marcado: gana quien le pegó hace poco o el cazador más cercano
    @Test
    void markedLeavingHandsTheWin() {
        BountyHunt b = hunt();
        b.hit(h1, 4000);
        b.leave(target, h2, 4100);
        assertEquals(h1, b.winner());
        BountyHunt n = hunt();
        n.leave(target, h2, 4100);
        assertEquals(Outcome.CAUGHT, n.outcome(4100));
        assertEquals(h2, n.winner());
        BountyHunt none = hunt();
        none.leave(target, null, 4100);
        assertEquals(Outcome.NO_WINNER, none.outcome(4100));
        BountyHunt outsider = hunt();
        outsider.leave(target, UUID.randomUUID(), 4100);
        assertEquals(Outcome.NO_WINNER, outsider.outcome(4100));
    }

    // un cazador que ya salió no cobra, aunque haya pegado último
    @Test
    void leftHunterGetsNothing() {
        BountyHunt b = hunt();
        b.hit(h1, 4000);
        b.leave(h1, null, 4050);
        assertFalse(b.hunter(h1));
        b.markedDied(null, 4100, false);
        assertEquals(Outcome.NO_WINNER, b.outcome(4100));
        BountyHunt k = hunt();
        k.leave(h1, null, 4050);
        k.markedDied(h1, 4100, false);
        assertEquals(Outcome.NO_WINNER, k.outcome(4100));
    }

    // decidido: morir sin que un cazador lo atrape no termina la cacería; queda a la vista un rato
    @Test
    void lonelyDeathExposesAndGoesOn() {
        BountyHunt b = new BountyHunt(target, List.of(h1, h2), 1000, new BountyHunt.Params(12000, 2400, 200, 600));
        b.hit(h1, 4000);
        b.markedDied(null, 4300, true);
        assertEquals(Outcome.RUNNING, b.outcome(4300));
        assertNull(b.winner());
        assertTrue(b.exposed(4300));
        assertTrue(b.diedRecently(4899));
        assertTrue(b.exposed(4899));
        assertFalse(b.exposed(4900));
        assertFalse(b.diedRecently(4900));
        // atraparlo después sigue valiendo
        b.markedDied(h2, 5000, true);
        assertEquals(Outcome.CAUGHT, b.outcome(5000));
        assertEquals(h2, b.winner());
    }

    // quien lo mató o le pegó hace poco lo atrapa igual aunque siga en el evento
    @Test
    void stayingInStillCountsTheCatch() {
        BountyHunt b = new BountyHunt(target, List.of(h1, h2), 1000, new BountyHunt.Params(12000, 2400, 200, 600));
        b.hit(h1, 4000);
        b.markedDied(null, 4200, true);
        assertEquals(Outcome.CAUGHT, b.outcome(4200));
        assertEquals(h1, b.winner());
        assertFalse(b.diedRecently(4200));
    }

    @Test
    void noHuntersLeftMeansTheMarkedWins() {
        BountyHunt b = hunt();
        b.leave(h1, null, 3000);
        assertEquals(Outcome.RUNNING, b.outcome(3000));
        b.leave(h2, null, 3001);
        assertEquals(Outcome.SURVIVED, b.outcome(3001));
        assertEquals(target, b.winner());
    }

    @Test
    void stayingInOneChunkGivesTheMarkedAway() {
        BountyHunt b = hunt();
        b.markedAt(5, 5, 1000);
        b.markedAt(5, 5, 3399);
        assertFalse(b.exposed(3399));
        b.markedAt(5, 5, 3400);
        assertTrue(b.exposed(3400));
        // al cambiar de chunk se esconde otra vez y el tiempo vuelve a contar
        b.markedAt(6, 5, 3500);
        assertFalse(b.exposed(3500));
        assertFalse(b.exposed(5899));
        assertTrue(b.exposed(5900));
        // ir y volver no reinicia nada raro: es otro chunk distinto del actual
        b.markedAt(5, 5, 5950);
        assertFalse(b.exposed(5950));
        // otro chunk aunque las coordenadas sumen lo mismo
        b.markedAt(5, 5, 9000);
        assertTrue(b.exposed(9000));
        b.markedAt(6, 4, 9001);
        assertFalse(b.exposed(9001));
        assertFalse(new BountyHunt(target, List.of(h1), 0, PARAMS).exposed(100000));
    }

    @Test
    void precisionGrowsByQuarters() {
        BountyHunt b = hunt();
        assertEquals(50, b.rounding(1000));
        assertEquals(50, b.rounding(3999));
        assertEquals(25, b.rounding(4000));
        assertEquals(10, b.rounding(7000));
        assertEquals(1, b.rounding(10000));
        assertEquals(1, b.rounding(99999));
        assertEquals(50, b.rounding(0));
        assertEquals(50, b.rounding(-100000));
    }

    @Test
    void roundingNeverSaysZero() {
        assertEquals(150, BountyHunt.round(160, 50));
        assertEquals(200, BountyHunt.round(175, 50));
        assertEquals(50, BountyHunt.round(3, 50));
        assertEquals(30, BountyHunt.round(26, 10));
        assertEquals(3, BountyHunt.round(3.4, 1));
        assertEquals(1, BountyHunt.round(0.2, 1));
        assertEquals(1, BountyHunt.round(Double.NaN, 1));
    }

    // con el blanco a 6 bloques y pasos de 50, "about 50 blocks" engaña: se dice que está a menos de un paso
    @Test
    void closerThanHalfAStepIsNear() {
        assertTrue(BountyHunt.near(6, 50));
        assertTrue(BountyHunt.near(24.9, 50));
        assertFalse(BountyHunt.near(25, 50));
        assertFalse(BountyHunt.near(160, 50));
        assertTrue(BountyHunt.near(4, 10));
        assertFalse(BountyHunt.near(0.2, 1));
        assertFalse(BountyHunt.near(Double.NaN, 50));
    }

    @Test
    void pickIsFairAndHandlesNothing() {
        List<UUID> players = List.of(target, h1, h2);
        Set<UUID> chosen = new HashSet<>();
        for (long seed = 0; seed < 30; seed++) {
            UUID u = BountyHunt.pick(players, new Random(seed));
            assertTrue(players.contains(u));
            chosen.add(u);
        }
        assertEquals(3, chosen.size());
        assertNull(BountyHunt.pick(List.of(), new Random(1)));
        assertNull(BountyHunt.pick(null, new Random(1)));
    }

    @Test
    void markedIsNeverAHunter() {
        BountyHunt b = new BountyHunt(target, List.of(target, h1), 0, PARAMS);
        assertFalse(b.hunter(target));
        assertTrue(b.hunter(h1));
        assertEquals(target, b.marked());
        b.leave(h1, null, 10);
        assertEquals(Outcome.SURVIVED, b.outcome(10));
    }
}
