package dev.huddle.core;

import java.util.List;

public final class CombatRule {
    public enum Hit { MELEE, ARROW, OTHER }

    public static final List<String> MODES = List.of(LobbyRules.ANY_COMBAT, "fists", "wooden_sword", "bow");

    private CombatRule() {
    }

    // modo votado de un evento en curso; held: id del ítem en la mano principal del atacante al pegar
    public static boolean allows(String mode, Hit hit, String held) {
        if (mode == null) {
            return true;
        }
        return switch (mode) {
            case LobbyRules.ANY_COMBAT -> true;
            case "fists" -> hit == Hit.MELEE && (held == null || held.isEmpty() || held.equals("minecraft:air"));
            case "wooden_sword" -> hit == Hit.MELEE && "minecraft:wooden_sword".equals(held);
            case "bow" -> hit == Hit.ARROW;
            default -> false;
        };
    }
}
