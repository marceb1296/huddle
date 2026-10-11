package dev.huddle.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

// mejores tiempos de una pista: uno por jugador, los TOP más rápidos
public final class Records {
    public static final int TOP = 5;

    public record Entry(UUID player, String name, double ticks) {
    }

    private final List<Entry> entries = new ArrayList<>();

    // puesto logrado (1 es récord de la pista) o 0 si no entra o no mejora su propio tiempo
    public int submit(UUID player, String name, double ticks) {
        if (player == null || !Double.isFinite(ticks) || ticks < 0) {
            return 0;
        }
        for (Entry e : entries) {
            if (e.player().equals(player) && e.ticks() <= ticks) {
                return 0;
            }
        }
        entries.removeIf(e -> e.player().equals(player));
        Entry mine = new Entry(player, name == null ? "?" : name, ticks);
        entries.add(mine);
        entries.sort(Comparator.comparingDouble(Entry::ticks));
        while (entries.size() > TOP) {
            entries.remove(entries.size() - 1);
        }
        int i = entries.indexOf(mine);
        return i < 0 ? 0 : i + 1;
    }

    public List<Entry> top() {
        return List.copyOf(entries);
    }

    // datos guardados: se ignoran tiempos rotos y jugadores repetidos (queda su mejor tiempo)
    public static Records restore(List<Entry> saved) {
        Records r = new Records();
        if (saved != null) {
            for (Entry e : saved) {
                if (e != null) {
                    r.submit(e.player(), e.name(), e.ticks());
                }
            }
        }
        return r;
    }
}
