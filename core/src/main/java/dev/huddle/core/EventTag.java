package dev.huddle.core;

import java.util.UUID;

// a qué evento pertenece un jugador; pvp: el evento deja que sus participantes se dañen entre sí.
// team: 0 todos contra todos; con el mismo equipo distinto de 0 no se dañan.
// combat: modo votado (null o "any": todo permitido); pvpFrom: tick del servidor en que termina la tregua
public record EventTag(UUID id, boolean pvp, int team, String combat, long pvpFrom) {
    public EventTag(UUID id, boolean pvp) {
        this(id, pvp, 0, null, 0);
    }

    public EventTag(UUID id, boolean pvp, int team) {
        this(id, pvp, team, null, 0);
    }

    public EventTag withTeam(int team) {
        return new EventTag(id, pvp, team, combat, pvpFrom);
    }
}
