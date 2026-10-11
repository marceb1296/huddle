package dev.huddle.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// premios que esperan al admin; cada uno se aprueba o rechaza una sola vez
public final class Approvals {
    public record Pending(int id, UUID winner, String winnerIdentity, String eventType, int units, long time) {
    }

    private final Map<Integer, Pending> pending = new LinkedHashMap<>();
    private int nextId = 1;

    public Pending add(UUID winner, String winnerIdentity, String eventType, int units, long time) {
        Pending p = new Pending(nextId++, winner, winnerIdentity, eventType, units, time);
        pending.put(p.id(), p);
        return p;
    }

    // null si ya se resolvió o no existe
    public Pending resolve(int id) {
        return pending.remove(id);
    }

    public List<Pending> list() {
        return new ArrayList<>(pending.values());
    }

    public static Approvals restore(List<Pending> saved) {
        Approvals a = new Approvals();
        for (Pending p : saved) {
            if (p != null && p.winner() != null && p.units() > 0 && !a.pending.containsKey(p.id())) {
                a.pending.put(p.id(), p);
                a.nextId = Math.max(a.nextId, p.id() + 1);
            }
        }
        return a;
    }
}
