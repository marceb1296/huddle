package dev.huddle.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// enfriamientos con hora de reloj (milisegundos) para que sobrevivan a un reinicio
public final class Cooldowns {
    public record Entry(long until, long length) {
    }

    private final Map<String, Entry> entries = new HashMap<>();

    public void start(String key, long now, long length) {
        if (length <= 0) {
            entries.remove(key);
            return;
        }
        entries.put(key, new Entry(saturatedAdd(now, length), length));
    }

    // si el reloj del sistema retrocede, nunca falta más que la duración original
    public long remaining(String key, long now) {
        Entry e = entries.get(key);
        if (e == null) {
            return 0;
        }
        long left = e.until() - now;
        if (left <= 0) {
            entries.remove(key);
            return 0;
        }
        if (left > e.length()) {
            entries.put(key, new Entry(saturatedAdd(now, e.length()), e.length()));
            return e.length();
        }
        return left;
    }

    public boolean active(String key, long now) {
        return remaining(key, now) > 0;
    }

    public void prune(long now) {
        entries.values().removeIf(e -> e.until() <= now);
    }

    public Map<String, Entry> snapshot() {
        return Map.copyOf(entries);
    }

    public void restore(Map<String, Entry> saved) {
        entries.clear();
        saved.forEach((k, e) -> {
            if (k != null && e != null && e.length() > 0) {
                entries.put(k, e);
            }
        });
    }

    public static String playerType(UUID player, String type) {
        return "p:" + player + ":" + type;
    }

    private static long saturatedAdd(long a, long b) {
        long r = a + b;
        return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
    }
}
