package dev.huddle.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// cuenta jugadores distintos (cuenta o IP) y baja el premio si compiten siempre los mismos
public final class AntiAbuse {
    public record Past(long time, Set<String> identities) {
    }

    private final boolean groupByIp;
    private final long window;
    private final double overlap;
    private final int maxHistory;
    private final Deque<Past> history = new ArrayDeque<>();

    // overlap: fracción de identidades repetidas para que cuente como "los mismos de siempre"
    public AntiAbuse(boolean groupByIp, long window, double overlap, int maxHistory) {
        this.groupByIp = groupByIp;
        this.window = Math.max(0, window);
        this.overlap = Double.isFinite(overlap) ? Math.max(0.01, Math.min(overlap, 1)) : 0.75;
        this.maxHistory = Math.max(1, maxHistory);
    }

    // ips: cuenta -> dirección; sin dirección cuenta la cuenta
    public Set<String> identities(Map<UUID, String> ips) {
        Set<String> out = new HashSet<>();
        ips.forEach((player, ip) -> out.add(groupByIp && ip != null && !ip.isBlank() ? "ip:" + ip : "id:" + player));
        return out;
    }

    // eventos recientes con casi los mismos jugadores
    public int repeats(Set<String> ids, long now) {
        prune(now);
        if (ids.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Past p : history) {
            int shared = 0;
            for (String id : ids) {
                if (p.identities().contains(id)) {
                    shared++;
                }
            }
            if (shared >= Math.ceil(overlap * Math.max(ids.size(), p.identities().size()))) {
                n++;
            }
        }
        return n;
    }

    public static double repeatFactor(int repeats) {
        return 1.0 / (1 + Math.max(0, repeats));
    }

    public void record(Set<String> ids, long now) {
        prune(now);
        history.addLast(new Past(now, Set.copyOf(ids)));
        while (history.size() > maxHistory) {
            history.removeFirst();
        }
    }

    private void prune(long now) {
        history.removeIf(p -> now - p.time() >= window || p.time() > now);
    }
}
