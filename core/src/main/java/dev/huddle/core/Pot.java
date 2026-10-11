package dev.huddle.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// bote de un evento: las entradas quedan guardadas hasta repartirse una sola vez.
// T es lo que el loader use para los ítems; core no los mira.
public final class Pot<T> {
    public enum State { COLLECTING, SETTLED }

    public record Delivery<T>(UUID to, T stake) {
    }

    private final Map<UUID, T> stakes = new LinkedHashMap<>();
    private final List<UUID> forfeited = new ArrayList<>();
    private State state = State.COLLECTING;

    public boolean deposit(UUID player, T stake) {
        if (state != State.COLLECTING || stake == null || stakes.containsKey(player)) {
            return false;
        }
        stakes.put(player, stake);
        return true;
    }

    // abandonó: su entrada queda en el bote para el ganador
    public void forfeit(UUID player) {
        if (state == State.COLLECTING && stakes.containsKey(player) && !forfeited.contains(player)) {
            forfeited.add(player);
        }
    }

    // un ganador que abandonó no cobra: devuelve vacío y el bote sigue abierto para devolverlo
    public List<Delivery<T>> settleWinner(UUID winner) {
        if (winner == null || forfeited.contains(winner)) {
            return List.of();
        }
        return settle(winner);
    }

    // sin ganador o evento cancelado: cada entrada vuelve a su dueño, también la de quien abandonó
    public List<Delivery<T>> refundAll() {
        return settle(null);
    }

    private List<Delivery<T>> settle(UUID winner) {
        if (state != State.COLLECTING) {
            return List.of();
        }
        state = State.SETTLED;
        List<Delivery<T>> out = new ArrayList<>();
        stakes.forEach((owner, stake) -> out.add(new Delivery<>(winner == null ? owner : winner, stake)));
        stakes.clear();
        forfeited.clear();
        return out;
    }

    public State state() {
        return state;
    }

    public boolean hasStake(UUID player) {
        return stakes.containsKey(player);
    }

    public boolean forfeitedBy(UUID player) {
        return forfeited.contains(player);
    }

    public int size() {
        return stakes.size();
    }

    public Map<UUID, T> stakes() {
        return Map.copyOf(stakes);
    }

    public List<UUID> forfeitedPlayers() {
        return List.copyOf(forfeited);
    }

    public static <T> Pot<T> restore(Map<UUID, T> stakes, List<UUID> forfeited) {
        Pot<T> p = new Pot<>();
        stakes.forEach(p::deposit);
        forfeited.forEach(p::forfeit);
        return p;
    }
}
