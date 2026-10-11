package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.huddle.core.TreasureHunt.Claim;
import dev.huddle.core.TreasureHunt.Heading;
import dev.huddle.core.TreasureHunt.Side;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TreasureHuntTest {
    private static final Pos SPOT = new Pos(100.5, 64, 0.5);
    private static final TreasureHunt.Params PARAMS = new TreasureHunt.Params(2400, 14400, 4.5);
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    private TreasureHunt hunt() {
        return new TreasureHunt(SPOT, List.of(a, b), 1000, PARAMS);
    }

    private static Pos near(double d) {
        return new Pos(SPOT.x() + d, SPOT.y(), SPOT.z());
    }

    @Test
    void cluesArriveOnTimeAndOnlyOnce() {
        TreasureHunt h = hunt();
        assertEquals(List.of(0), h.cluesDue(1000));
        assertEquals(List.of(), h.cluesDue(1000));
        assertEquals(List.of(), h.cluesDue(3399));
        assertEquals(List.of(1), h.cluesDue(3400));
        assertEquals(List.of(), h.cluesDue(3401));
        assertEquals(List.of(2), h.cluesDue(5800));
        assertEquals(List.of(3), h.cluesDue(8200));
        assertEquals(List.of(), h.cluesDue(20000));
    }

    // un servidor trabado varios minutos: las pistas atrasadas llegan juntas y en orden
    @Test
    void lateTickDeliversEveryMissedClue() {
        TreasureHunt h = hunt();
        assertEquals(List.of(0, 1, 2), h.cluesDue(6000));
        assertEquals(List.of(3), h.cluesDue(9000));
    }

    @Test
    void firstClickWinsAndTheSecondInTheSameTickDoesNot() {
        TreasureHunt h = hunt();
        assertEquals(Claim.WON, h.claim(a, near(1), 1200));
        assertEquals(Claim.OVER, h.claim(b, near(1), 1200));
        assertEquals(a, h.winner());
        assertTrue(h.over(1200));
    }

    // el clic lo manda el cliente: el servidor mide la distancia
    @Test
    void clickFromAfarDoesNotCount() {
        TreasureHunt h = hunt();
        assertEquals(Claim.TOO_FAR, h.claim(a, near(4.6), 1200));
        assertEquals(Claim.TOO_FAR, h.claim(a, new Pos(SPOT.x(), SPOT.y() + 0.5 + 4.6, SPOT.z()), 1200));
        assertNull(h.winner());
        assertEquals(Claim.WON, h.claim(a, near(4.4), 1200));
        // se mide al centro del cofre, medio bloque sobre el suelo
        assertEquals(Claim.WON, hunt().claim(a, new Pos(SPOT.x(), SPOT.y() + 0.5 + 4.4, SPOT.z()), 1200));
    }

    @Test
    void outsidersAndLeaversCannotWin() {
        TreasureHunt h = hunt();
        assertEquals(Claim.NOT_PARTICIPANT, h.claim(UUID.randomUUID(), near(0), 1200));
        h.leave(a);
        assertEquals(Claim.NOT_PARTICIPANT, h.claim(a, near(0), 1200));
        assertFalse(h.over(1200));
        h.leave(b);
        assertTrue(h.over(1200));
        assertNull(h.winner());
    }

    @Test
    void timeRunsOut() {
        TreasureHunt h = hunt();
        assertFalse(h.over(15399));
        assertTrue(h.over(15400));
        assertEquals(Claim.OVER, h.claim(a, near(0), 15400));
        assertNull(h.winner());
    }

    @Test
    void brokenPositionsNeverWin() {
        TreasureHunt h = hunt();
        assertEquals(Claim.TOO_FAR, h.claim(a, new Pos(Double.NaN, 64, 0), 1200));
        assertEquals(Claim.TOO_FAR, h.claim(a, null, 1200));
        assertEquals(Claim.TOO_FAR, h.claim(a, new Pos(Double.POSITIVE_INFINITY, 64, 0), 1200));
        assertNull(h.winner());
    }

    // en Minecraft el norte es z negativo y el este x positivo
    @Test
    void headingsFollowMinecraftAxes() {
        Pos o = new Pos(0, 64, 0);
        assertEquals(Heading.NORTH, TreasureHunt.heading(o, new Pos(0, 64, -50)));
        assertEquals(Heading.NORTHEAST, TreasureHunt.heading(o, new Pos(40, 64, -40)));
        assertEquals(Heading.EAST, TreasureHunt.heading(o, new Pos(50, 64, 0)));
        assertEquals(Heading.SOUTHEAST, TreasureHunt.heading(o, new Pos(40, 64, 40)));
        assertEquals(Heading.SOUTH, TreasureHunt.heading(o, new Pos(0, 64, 50)));
        assertEquals(Heading.SOUTHWEST, TreasureHunt.heading(o, new Pos(-40, 64, 40)));
        assertEquals(Heading.WEST, TreasureHunt.heading(o, new Pos(-50, 64, 0)));
        assertEquals(Heading.NORTHWEST, TreasureHunt.heading(o, new Pos(-40, 64, -40)));
        // a 20 grados del norte sigue siendo norte; a 25, noreste
        assertEquals(Heading.NORTH, TreasureHunt.heading(o, new Pos(Math.sin(Math.toRadians(20)) * 50, 64, -Math.cos(Math.toRadians(20)) * 50)));
        assertEquals(Heading.NORTHEAST, TreasureHunt.heading(o, new Pos(Math.sin(Math.toRadians(25)) * 50, 64, -Math.cos(Math.toRadians(25)) * 50)));
        assertEquals(Heading.NORTH, TreasureHunt.heading(o, new Pos(-Math.sin(Math.toRadians(20)) * 50, 64, -Math.cos(Math.toRadians(20)) * 50)));
    }

    // yaw de Minecraft: 0 mira al sur, 90 al oeste, 180 al norte, -90 al este; a la derecha de quien mira al sur está el oeste
    @Test
    void sideFollowsWhereThePlayerLooks() {
        Pos o = new Pos(0, 64, 0);
        Pos north = new Pos(0, 64, -50);
        assertEquals(Side.AHEAD, TreasureHunt.side(o, 180, north));
        assertEquals(Side.BEHIND, TreasureHunt.side(o, 0, north));
        assertEquals(Side.LEFT, TreasureHunt.side(o, -90, north));
        assertEquals(Side.RIGHT, TreasureHunt.side(o, 90, north));
        assertEquals(Side.RIGHT, TreasureHunt.side(o, 0, new Pos(-50, 64, 0)));
        assertEquals(Side.AHEAD_RIGHT, TreasureHunt.side(o, 0, new Pos(-40, 64, 40)));
        assertEquals(Side.AHEAD_LEFT, TreasureHunt.side(o, 0, new Pos(40, 64, 40)));
        assertEquals(Side.BEHIND_RIGHT, TreasureHunt.side(o, 0, new Pos(-40, 64, -40)));
        assertEquals(Side.BEHIND_LEFT, TreasureHunt.side(o, 0, new Pos(40, 64, -40)));
        // el yaw del jugador no se limita a una vuelta
        assertEquals(Side.AHEAD, TreasureHunt.side(o, 180 + 720, north));
        assertEquals(Side.AHEAD, TreasureHunt.side(o, -180 - 3600, north));
        // a 20 grados sigue adelante; a 25, adelante a un lado
        assertEquals(Side.AHEAD, TreasureHunt.side(o, 160, north));
        assertEquals(Side.AHEAD_RIGHT, TreasureHunt.side(o, 155, north));
        assertEquals(Side.AHEAD_LEFT, TreasureHunt.side(o, 205, north));
    }

    // distancia por el suelo, redondeada a 10 y nunca 0
    @Test
    void distanceIsRoundedToTens() {
        Pos o = new Pos(0, 64, 0);
        assertEquals(120, TreasureHunt.roundedDistance(o, new Pos(124, 30, 0)));
        assertEquals(130, TreasureHunt.roundedDistance(o, new Pos(0, 64, -125)));
        assertEquals(10, TreasureHunt.roundedDistance(o, new Pos(1, 64, 1)));
        assertEquals(50, TreasureHunt.roundedDistance(o, new Pos(30, 64, 40)));
    }

    // el círculo de la tercera pista siempre contiene el cofre, pero no está centrado en él
    @Test
    void zoneContainsTheSpotWithoutGivingItAway() {
        Set<Long> centers = new HashSet<>();
        for (long seed = 0; seed < 200; seed++) {
            Pos c = TreasureHunt.zoneCenter(SPOT, 24, new Random(seed));
            double d = Math.hypot(c.x() - SPOT.x(), c.z() - SPOT.z());
            assertTrue(d <= 24 * 0.6 + 1e-9, "cofre a " + d + " del centro");
            assertEquals(SPOT.y(), c.y());
            centers.add(Math.round(d));
        }
        assertTrue(centers.size() >= 10, "centros siempre a la misma distancia: " + centers);
    }
}
