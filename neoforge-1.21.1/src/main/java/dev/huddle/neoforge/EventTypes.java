package dev.huddle.neoforge;

import java.util.List;
import net.minecraft.network.chat.Component;

public final class EventTypes {
    // vote: con PvP, la sala vota el modo de combate al empezar la cuenta (el último en pie no: el kit ya lo define)
    public record Type(String id, String name, boolean pvp, boolean vote, int minPlayers) {
    }

    public static final List<Type> ALL = List.of(
            new Type("race", "Race", false, false, 2),
            new Type("treasure", "Treasure hunt", false, false, 2),
            new Type("bounty", "Bounty", true, true, 3),
            new Type("airdrop", "Airdrop", true, false, 2),
            new Type("lastman", "Last one standing", true, false, 3));

    private EventTypes() {
    }

    // los modos los define el admin: los conocidos tienen nombre traducido, los demás se muestran tal cual
    public static Component modeName(String mode) {
        String english = switch (mode) {
            case "any" -> "Anything goes";
            case "fists" -> "Fists only";
            case "wooden_sword" -> "Wooden sword only";
            case "bow" -> "Bow only";
            default -> mode;
        };
        return Component.translatableWithFallback("huddle.mode." + mode, english);
    }

    public static Type byId(String id) {
        for (Type t : ALL) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }
}
