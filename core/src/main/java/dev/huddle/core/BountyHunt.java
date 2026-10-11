package dev.huddle.core;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

// cacería en curso: el marcado gana si llega al final del tiempo o si no quedan cazadores. Si muere o abandona,
// gana el cazador que lo mató, o el último que le pegó dentro de hitWindow, o (si abandonó) el cazador más cercano.
// Si muere sin que lo atrapen y sigue en el evento, queda a la vista deathExposeTicks
public final class BountyHunt {
    public enum Outcome { RUNNING, SURVIVED, CAUGHT, NO_WINNER }

    // campTicks: tanto tiempo en el mismo chunk lo deja a la vista de los cazadores; deathExposeTicks: lo mismo
    // después de morir sin que lo atrapen
    public record Params(long maxTicks, long campTicks, long hitWindow, long deathExposeTicks) {
        public Params(long maxTicks, long campTicks, long hitWindow) {
            this(maxTicks, campTicks, hitWindow, 0);
        }
    }

    private static final int[] ROUNDING = {50, 25, 10, 1};

    private final UUID marked;
    private final Set<UUID> hunters = new LinkedHashSet<>();
    private final long start;
    private final Params params;
    private Outcome outcome = Outcome.RUNNING;
    private UUID winner;
    private UUID lastHitter;
    private long lastHit;
    private long chunk;
    private long chunkSince = -1;
    private long diedAt = Long.MIN_VALUE;

    public BountyHunt(UUID marked, Collection<UUID> hunters, long start, Params params) {
        this.marked = marked;
        this.hunters.addAll(hunters);
        this.hunters.remove(marked);
        this.start = start;
        this.params = params;
    }

    public static UUID pick(List<UUID> players, Random rnd) {
        if (players == null || players.isEmpty()) {
            return null;
        }
        return players.get(rnd.nextInt(players.size()));
    }

    public void hit(UUID attacker, long now) {
        if (hunters.contains(attacker)) {
            lastHitter = attacker;
            lastHit = now;
        }
    }

    // staysIn: la muerte no lo saca del evento; sin que lo atrapen, sigue la cacería
    public void markedDied(UUID killer, long now, boolean staysIn) {
        if (outcome(now) != Outcome.RUNNING) {
            return;
        }
        UUID w = catcher(hunters.contains(killer) ? killer : null, null, now);
        if (w == null && staysIn) {
            diedAt = now;
            return;
        }
        end(w == null ? Outcome.NO_WINNER : Outcome.CAUGHT, w);
    }

    public boolean diedRecently(long now) {
        return diedAt != Long.MIN_VALUE && now - diedAt < params.deathExposeTicks();
    }

    // nearestHunter solo cuenta si quien sale es el marcado
    public void leave(UUID player, UUID nearestHunter, long now) {
        if (marked.equals(player)) {
            caught(null, nearestHunter, now);
            return;
        }
        hunters.remove(player);
        if (outcome == Outcome.RUNNING && hunters.isEmpty()) {
            end(Outcome.SURVIVED, marked);
        }
    }

    private void caught(UUID killer, UUID fallback, long now) {
        if (outcome(now) != Outcome.RUNNING) {
            return;
        }
        UUID w = catcher(killer, fallback, now);
        end(w == null ? Outcome.NO_WINNER : Outcome.CAUGHT, w);
    }

    private UUID catcher(UUID killer, UUID fallback, long now) {
        UUID w = killer;
        if (w == null && lastHitter != null && hunters.contains(lastHitter) && now - lastHit <= params.hitWindow()) {
            w = lastHitter;
        }
        if (w == null && hunters.contains(fallback)) {
            w = fallback;
        }
        return w;
    }

    private void end(Outcome o, UUID w) {
        outcome = o;
        winner = w;
    }

    public void markedAt(int chunkX, int chunkZ, long now) {
        long c = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        if (chunkSince < 0 || c != chunk) {
            chunk = c;
            chunkSince = now;
        }
    }

    public boolean exposed(long now) {
        return diedRecently(now) || chunkSince >= 0 && now - chunkSince >= params.campTicks();
    }

    public int rounding(long now) {
        long elapsed = Math.max(0, now - start);
        int q = (int) Math.min(ROUNDING.length - 1, elapsed * ROUNDING.length / Math.max(1, params.maxTicks()));
        return ROUNDING[q];
    }

    public static int round(double distance, int step) {
        int s = Math.max(1, step);
        return (int) Math.max(s, Math.round(distance / s) * s);
    }

    // más cerca que medio paso: round diría 0 y sube al paso, así que se dice "a menos de un paso"
    public static boolean near(double distance, int step) {
        return step > 1 && distance < step / 2.0;
    }

    public Outcome outcome(long now) {
        if (outcome == Outcome.RUNNING && now >= start + params.maxTicks()) {
            end(Outcome.SURVIVED, marked);
        }
        return outcome;
    }

    public UUID winner() {
        return winner;
    }

    public UUID marked() {
        return marked;
    }

    public boolean hunter(UUID player) {
        return hunters.contains(player);
    }
}
