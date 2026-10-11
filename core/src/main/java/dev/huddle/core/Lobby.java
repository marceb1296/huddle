package dev.huddle.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// sala de espera de un evento; el tiempo lo pasa quien la usa (ticks del servidor)
public final class Lobby {
    public enum State { OPEN, COUNTDOWN, STARTED, CANCELLED }

    public enum JoinResult { JOINED, ALREADY_IN, FULL, CLOSED }

    public enum VoteResult { COUNTED, NOT_IN_LOBBY, UNKNOWN_MODE, NOT_VOTING }

    public enum CancelReason { NOT_ENOUGH_PLAYERS, EVERYONE_LEFT, BY_CREATOR }

    private final UUID id = UUID.randomUUID();
    private final String type;
    private final UUID creator;
    private final LobbyRules rules;
    private final Set<UUID> players = new LinkedHashSet<>();
    private final Map<UUID, String> votes = new HashMap<>();
    private State state = State.OPEN;
    private long deadline;
    private String combatMode;
    private CancelReason cancelReason;

    public Lobby(String type, UUID creator, LobbyRules rules, long now) {
        this.type = type;
        this.creator = creator;
        this.rules = rules;
        this.deadline = now + rules.openTicks();
        players.add(creator);
    }

    public JoinResult join(UUID player, long now) {
        tick(now);
        if (state != State.OPEN && state != State.COUNTDOWN) {
            return JoinResult.CLOSED;
        }
        if (players.contains(player)) {
            return JoinResult.ALREADY_IN;
        }
        if (players.size() >= rules.maxPlayers()) {
            return JoinResult.FULL;
        }
        players.add(player);
        if (state == State.OPEN && players.size() >= rules.maxPlayers()) {
            startCountdown(now);
        }
        return JoinResult.JOINED;
    }

    // devuelve true si el jugador estaba en la sala
    public boolean leave(UUID player, long now) {
        tick(now);
        if (state != State.OPEN && state != State.COUNTDOWN) {
            return false;
        }
        if (!players.remove(player)) {
            return false;
        }
        votes.remove(player);
        if (players.isEmpty()) {
            cancel(CancelReason.EVERYONE_LEFT);
        } else if (state == State.COUNTDOWN && players.size() < rules.minPlayers()) {
            cancel(CancelReason.NOT_ENOUGH_PLAYERS);
        }
        return true;
    }

    public VoteResult vote(UUID player, String mode, long now) {
        tick(now);
        if (state != State.OPEN && state != State.COUNTDOWN) {
            return VoteResult.NOT_VOTING;
        }
        if (!players.contains(player)) {
            return VoteResult.NOT_IN_LOBBY;
        }
        if (!rules.combatModes().contains(mode)) {
            return VoteResult.UNKNOWN_MODE;
        }
        votes.put(player, mode);
        return VoteResult.COUNTED;
    }

    public void cancelByCreator() {
        if (state == State.OPEN || state == State.COUNTDOWN) {
            cancel(CancelReason.BY_CREATOR);
        }
    }

    public State tick(long now) {
        if (state == State.OPEN && now >= deadline) {
            if (players.size() >= rules.minPlayers()) {
                // desde el vencimiento, no desde este tick: un tick atrasado no alarga la espera
                startCountdown(deadline);
            } else {
                cancel(CancelReason.NOT_ENOUGH_PLAYERS);
            }
        }
        if (state == State.COUNTDOWN && now >= deadline) {
            combatMode = countVotes();
            state = State.STARTED;
        }
        return state;
    }

    private void startCountdown(long now) {
        state = State.COUNTDOWN;
        deadline = now + rules.countdownTicks();
    }

    private void cancel(CancelReason reason) {
        state = State.CANCELLED;
        cancelReason = reason;
    }

    // más votado; empate: el primero en el orden del admin ("any" va primero)
    private String countVotes() {
        Map<String, Integer> count = new HashMap<>();
        for (String m : votes.values()) {
            count.merge(m, 1, Integer::sum);
        }
        String best = LobbyRules.ANY_COMBAT;
        int bestCount = 0;
        for (String m : rules.combatModes()) {
            int c = count.getOrDefault(m, 0);
            if (c > bestCount) {
                best = m;
                bestCount = c;
            }
        }
        return best;
    }

    public long ticksLeft(long now) {
        return state == State.OPEN || state == State.COUNTDOWN ? Math.max(0, deadline - now) : 0;
    }

    public UUID id() {
        return id;
    }

    public String type() {
        return type;
    }

    public UUID creator() {
        return creator;
    }

    public State state() {
        return state;
    }

    public List<UUID> players() {
        return Collections.unmodifiableList(new ArrayList<>(players));
    }

    // null hasta que arranca
    public String combatMode() {
        return combatMode;
    }

    public CancelReason cancelReason() {
        return cancelReason;
    }
}
