package dev.huddle.core;

import static dev.huddle.core.PvpRule.Verdict.ALLOW;
import static dev.huddle.core.PvpRule.Verdict.DENY;
import static dev.huddle.core.PvpRule.Verdict.VANILLA;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PvpRuleTest {
    private final UUID id = UUID.randomUUID();

    @Test
    void outsiderNeverTouchesParticipant() {
        EventTag bounty = new EventTag(id, true);
        assertEquals(DENY, PvpRule.between(bounty, null, 0));
        assertEquals(DENY, PvpRule.between(null, bounty, 0));
    }

    @Test
    void differentEventsDoNotFight() {
        assertEquals(DENY, PvpRule.between(new EventTag(id, true), new EventTag(UUID.randomUUID(), true), 0));
    }

    // tags creados por separado para el mismo evento: la comparación es por id, no por identidad
    @Test
    void sameEventWithPvpFightsEvenFromSeparateTags() {
        assertEquals(ALLOW, PvpRule.between(new EventTag(id, true), new EventTag(new UUID(id.getMostSignificantBits(), id.getLeastSignificantBits()), true), 0));
    }

    @Test
    void sameEventWithoutPvpProtectsRacers() {
        assertEquals(DENY, PvpRule.between(new EventTag(id, false), new EventTag(id, false), 0));
    }

    @Test
    void nobodyInEventsLeavesItToTheServer() {
        assertEquals(VANILLA, PvpRule.between(null, null, 0));
    }

    // recompensa: los cazadores (mismo equipo) no se pegan; cazador y marcado sí
    @Test
    void teammatesDoNotFight() {
        assertEquals(DENY, PvpRule.between(new EventTag(id, true, 2), new EventTag(id, true, 2), 0));
        assertEquals(ALLOW, PvpRule.between(new EventTag(id, true, 2), new EventTag(id, true, 1), 0));
        assertEquals(ALLOW, PvpRule.between(new EventTag(id, true, 0), new EventTag(id, true, 0), 0));
        assertEquals(DENY, PvpRule.between(new EventTag(id, false, 1), new EventTag(id, false, 2), 0));
    }

    // último en pie: nadie se pega hasta que termina la tregua, ni siquiera en el mismo tick del reparto
    @Test
    void truceHoldsUntilItsTick() {
        EventTag lastman = new EventTag(id, true, 0, null, 200);
        assertEquals(DENY, PvpRule.between(lastman, lastman, 0));
        assertEquals(DENY, PvpRule.between(lastman, lastman, 199));
        assertEquals(ALLOW, PvpRule.between(lastman, lastman, 200));
        assertEquals(DENY, PvpRule.between(lastman, new EventTag(id, true), 199));
        assertEquals(DENY, PvpRule.between(new EventTag(id, true), lastman, 199));
    }

    // la recompensa rehace el tag con el equipo: no puede perder el modo votado ni la tregua
    @Test
    void teamKeepsCombatAndTruce() {
        EventTag t = new EventTag(id, true, 0, "fists", 50).withTeam(2);
        assertEquals(new EventTag(id, true, 2, "fists", 50), t);
    }
}
