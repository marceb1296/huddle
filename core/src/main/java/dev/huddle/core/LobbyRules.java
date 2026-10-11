package dev.huddle.core;

import java.util.ArrayList;
import java.util.List;

// tiempos en ticks; combatModes en el orden que eligió el admin, el primero desempata
public record LobbyRules(int minPlayers, int maxPlayers, long openTicks, long countdownTicks, List<String> combatModes) {
    public static final String ANY_COMBAT = "any";
    static final int HARD_MAX_PLAYERS = 1000;
    static final long HARD_MAX_TICKS = 20L * 60 * 60;

    // la configuración viene del admin: se corrige en vez de fallar; un modo de combate desconocido se descarta
    public LobbyRules {
        minPlayers = Math.max(1, Math.min(minPlayers, HARD_MAX_PLAYERS));
        maxPlayers = Math.max(minPlayers, Math.min(maxPlayers, HARD_MAX_PLAYERS));
        openTicks = Math.max(1, Math.min(openTicks, HARD_MAX_TICKS));
        countdownTicks = Math.max(0, Math.min(countdownTicks, HARD_MAX_TICKS));
        List<String> modes = new ArrayList<>();
        if (combatModes != null) {
            for (String m : combatModes) {
                if (m != null && CombatRule.MODES.contains(m) && !modes.contains(m)) {
                    modes.add(m);
                }
            }
        }
        if (!modes.contains(ANY_COMBAT)) {
            modes.add(0, ANY_COMBAT);
        }
        combatModes = List.copyOf(modes);
    }
}
