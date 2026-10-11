package dev.huddle.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

// una búsqueda del tesoro en curso. La primera pista llega al empezar y después una cada clueEvery ticks;
// gana el primer participante que reclama el cofre desde cerca
public final class TreasureHunt {
    public static final int CLUES = 4;

    public enum Claim { WON, TOO_FAR, NOT_PARTICIPANT, OVER }

    public enum Heading { NORTH, NORTHEAST, EAST, SOUTHEAST, SOUTH, SOUTHWEST, WEST, NORTHWEST }

    public enum Side { AHEAD, AHEAD_RIGHT, RIGHT, BEHIND_RIGHT, BEHIND, BEHIND_LEFT, LEFT, AHEAD_LEFT }

    // reach: distancia máxima del jugador al centro del cofre para que el clic cuente
    public record Params(long clueEvery, long maxTicks, double reach) {
    }

    private final Pos spot;
    private final Set<UUID> hunters = new LinkedHashSet<>();
    private final long start;
    private final Params params;
    private int cluesGiven;
    private UUID winner;

    public TreasureHunt(Pos spot, Collection<UUID> participants, long start, Params params) {
        this.spot = spot;
        this.hunters.addAll(participants);
        this.start = start;
        this.params = params;
    }

    // pistas que tocan desde la última llamada, en orden
    public List<Integer> cluesDue(long now) {
        List<Integer> out = new ArrayList<>();
        while (cluesGiven < CLUES && now >= start + cluesGiven * params.clueEvery()) {
            out.add(cluesGiven++);
        }
        return out;
    }

    public Claim claim(UUID player, Pos at, long now) {
        if (over(now)) {
            return Claim.OVER;
        }
        if (!hunters.contains(player)) {
            return Claim.NOT_PARTICIPANT;
        }
        // una posición rota da NaN y la comparación es falsa
        if (at == null || !(at.distSq(center()) <= params.reach() * params.reach())) {
            return Claim.TOO_FAR;
        }
        winner = player;
        return Claim.WON;
    }

    private Pos center() {
        return new Pos(spot.x(), spot.y() + 0.5, spot.z());
    }

    public void leave(UUID player) {
        hunters.remove(player);
    }

    public boolean over(long now) {
        return winner != null || hunters.isEmpty() || now >= start + params.maxTicks();
    }

    public UUID winner() {
        return winner;
    }

    public Pos spot() {
        return spot;
    }

    public static Heading heading(Pos from, Pos to) {
        double deg = Math.toDegrees(Math.atan2(to.x() - from.x(), from.z() - to.z()));
        int k = (int) Math.round(deg / 45);
        return Heading.values()[Math.floorMod(k, 8)];
    }

    // yaw de Minecraft: 0 mira al sur (z positivo) y crece al girar a la derecha
    public static Side side(Pos from, double yaw, Pos to) {
        double bearing = Math.toDegrees(Math.atan2(from.x() - to.x(), to.z() - from.z()));
        long k = Math.round((bearing - yaw) / 45);
        return Side.values()[(int) Math.floorMod(k, 8L)];
    }

    public static int roundedDistance(Pos from, Pos to) {
        double d = Math.hypot(to.x() - from.x(), to.z() - from.z());
        return (int) Math.max(10, Math.round(d / 10) * 10);
    }

    // centro de un círculo de ese radio que contiene el lugar, corrido al azar hasta 0,6 del radio
    public static Pos zoneCenter(Pos spot, double radius, Random rnd) {
        double a = rnd.nextDouble() * Math.PI * 2;
        double d = rnd.nextDouble() * 0.6 * radius;
        return new Pos(spot.x() + Math.cos(a) * d, spot.y(), spot.z() + Math.sin(a) * d);
    }
}
