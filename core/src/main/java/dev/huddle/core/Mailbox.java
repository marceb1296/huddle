package dev.huddle.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// entregas pendientes (premios, devoluciones) que esperan a que el jugador esté conectado.
// take y la entrega al inventario deben ocurrir en el mismo tick: el guardado del mundo
// guarda los dos juntos, así una caída no duplica ni pierde.
public final class Mailbox<T> {
    private final Map<UUID, List<T>> pending = new HashMap<>();

    public void post(UUID to, T item) {
        if (to != null && item != null) {
            pending.computeIfAbsent(to, k -> new ArrayList<>()).add(item);
        }
    }

    public void postAll(List<Pot.Delivery<T>> deliveries) {
        for (Pot.Delivery<T> d : deliveries) {
            post(d.to(), d.stake());
        }
    }

    public List<T> take(UUID player) {
        List<T> out = pending.remove(player);
        return out == null ? List.of() : out;
    }

    public int count(UUID player) {
        List<T> l = pending.get(player);
        return l == null ? 0 : l.size();
    }

    public Map<UUID, List<T>> snapshot() {
        Map<UUID, List<T>> copy = new HashMap<>();
        pending.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return copy;
    }

    public static <T> Mailbox<T> restore(Map<UUID, List<T>> saved) {
        Mailbox<T> m = new Mailbox<>();
        saved.forEach((k, v) -> {
            if (v != null) {
                v.forEach(i -> m.post(k, i));
            }
        });
        return m;
    }
}
