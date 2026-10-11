package dev.huddle.core;

import static dev.huddle.core.CombatRule.Hit.ARROW;
import static dev.huddle.core.CombatRule.Hit.MELEE;
import static dev.huddle.core.CombatRule.Hit.OTHER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CombatRuleTest {
    private static final String AIR = "minecraft:air";
    private static final String WOODEN = "minecraft:wooden_sword";
    private static final String DIAMOND = "minecraft:diamond_sword";

    @Test
    void anythingGoesAllowsEverything() {
        for (CombatRule.Hit h : CombatRule.Hit.values()) {
            assertTrue(CombatRule.allows("any", h, DIAMOND));
        }
    }

    @Test
    void fistsOnlyWithEmptyHand() {
        assertTrue(CombatRule.allows("fists", MELEE, AIR));
        assertTrue(CombatRule.allows("fists", MELEE, null));
        assertFalse(CombatRule.allows("fists", MELEE, WOODEN));
        assertFalse(CombatRule.allows("fists", MELEE, "minecraft:dirt"));
        assertFalse(CombatRule.allows("fists", ARROW, AIR));
        // tnt con dueño, lobos, espinas: nada de eso es un puño
        assertFalse(CombatRule.allows("fists", OTHER, AIR));
    }

    @Test
    void woodenSwordOnlyThatSwordUpClose() {
        assertTrue(CombatRule.allows("wooden_sword", MELEE, WOODEN));
        assertFalse(CombatRule.allows("wooden_sword", MELEE, DIAMOND));
        assertFalse(CombatRule.allows("wooden_sword", MELEE, AIR));
        assertFalse(CombatRule.allows("wooden_sword", ARROW, WOODEN));
        assertFalse(CombatRule.allows("wooden_sword", OTHER, WOODEN));
    }

    // la flecha puede llegar cuando el arquero ya cambió de ítem: cuenta igual
    @Test
    void bowOnlyArrows() {
        assertTrue(CombatRule.allows("bow", ARROW, AIR));
        assertTrue(CombatRule.allows("bow", ARROW, DIAMOND));
        assertFalse(CombatRule.allows("bow", MELEE, "minecraft:bow"));
        assertFalse(CombatRule.allows("bow", OTHER, AIR));
    }

    // sin evento en curso no hay modo: decide el resto de las reglas
    @Test
    void noModeAllows() {
        assertTrue(CombatRule.allows(null, OTHER, DIAMOND));
    }

    // un modo mal escrito por el admin no se puede votar: se mostraría y no se aplicaría
    @Test
    void unknownModesAreDroppedFromTheVote() {
        LobbyRules r = new LobbyRules(2, 8, 100, 100, List.of("fists", "bows", "sword"));
        assertEquals(List.of("any", "fists"), r.combatModes());
        assertFalse(CombatRule.allows("bows", OTHER, AIR));
    }
}
