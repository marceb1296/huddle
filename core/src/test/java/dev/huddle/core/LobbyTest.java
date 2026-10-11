package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.huddle.core.Lobby.CancelReason;
import dev.huddle.core.Lobby.JoinResult;
import dev.huddle.core.Lobby.State;
import dev.huddle.core.Lobby.VoteResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LobbyTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();

    private static LobbyRules rules(int min, int max) {
        return new LobbyRules(min, max, 1200, 100, List.of("fists", "bow"));
    }

    @Test
    void cancelsWithoutMinimumWhenTimeRunsOut() {
        Lobby l = new Lobby("bounty", a, rules(3, 8), 0);
        l.join(b, 10);
        assertEquals(State.OPEN, l.tick(1199));
        assertEquals(State.CANCELLED, l.tick(1200));
        assertEquals(CancelReason.NOT_ENOUGH_PLAYERS, l.cancelReason());
    }

    @Test
    void startsAfterCountdownWithMinimum() {
        Lobby l = new Lobby("race", a, rules(2, 8), 0);
        l.join(b, 10);
        assertEquals(State.COUNTDOWN, l.tick(1200));
        assertEquals(State.COUNTDOWN, l.tick(1299));
        assertEquals(State.STARTED, l.tick(1300));
    }

    // quien sale en la cuenta regresiva no puede dejar arrancar un evento por debajo del mínimo
    @Test
    void leavingDuringCountdownBelowMinimumCancels() {
        Lobby l = new Lobby("bounty", a, rules(3, 8), 0);
        l.join(b, 1);
        l.join(c, 2);
        l.tick(1200);
        assertTrue(l.leave(c, 1210));
        assertEquals(State.CANCELLED, l.state());
        assertEquals(State.CANCELLED, l.tick(1300));
    }

    @Test
    void leavingWhileOpenKeepsWaiting() {
        Lobby l = new Lobby("bounty", a, rules(3, 8), 0);
        l.join(b, 1);
        l.leave(b, 2);
        assertEquals(State.OPEN, l.state());
        l.join(b, 3);
        l.join(c, 4);
        assertEquals(State.COUNTDOWN, l.tick(1200));
    }

    @Test
    void creatorLeavingAloneCancels() {
        Lobby l = new Lobby("race", a, rules(1, 8), 0);
        l.leave(a, 5);
        assertEquals(CancelReason.EVERYONE_LEFT, l.cancelReason());
    }

    @Test
    void neverExceedsMaxAndFullLobbyCountsDown() {
        Lobby l = new Lobby("race", a, rules(2, 3), 0);
        assertEquals(JoinResult.JOINED, l.join(b, 1));
        assertEquals(JoinResult.JOINED, l.join(c, 2));
        assertEquals(State.COUNTDOWN, l.state());
        assertEquals(JoinResult.FULL, l.join(d, 3));
        assertEquals(3, l.players().size());
    }

    @Test
    void doubleJoinDoesNotTakeTwoSlots() {
        Lobby l = new Lobby("race", a, rules(2, 3), 0);
        assertEquals(JoinResult.ALREADY_IN, l.join(a, 1));
        l.join(b, 2);
        assertEquals(JoinResult.ALREADY_IN, l.join(b, 3));
        assertEquals(2, l.players().size());
        assertEquals(State.OPEN, l.state());
    }

    // la sala vencida sin que nadie la mirara no deja entrar tarde
    @Test
    void lateJoinAfterStartIsRejected() {
        Lobby l = new Lobby("race", a, rules(1, 8), 0);
        assertEquals(JoinResult.CLOSED, l.join(b, 5000));
        assertEquals(State.STARTED, l.state());
        assertFalse(l.players().contains(b));
    }

    @Test
    void mostVotedModeWins() {
        Lobby l = new Lobby("bounty", a, rules(1, 8), 0);
        l.join(b, 1);
        l.join(c, 2);
        l.vote(a, "bow", 3);
        l.vote(b, "bow", 3);
        l.vote(c, "fists", 3);
        l.tick(1300);
        assertEquals("bow", l.combatMode());
    }

    @Test
    void noVotesMeansAnything() {
        Lobby l = new Lobby("bounty", a, rules(1, 8), 0);
        l.tick(1300);
        assertEquals(LobbyRules.ANY_COMBAT, l.combatMode());
    }

    @Test
    void tieGoesToAdminOrder() {
        Lobby l = new Lobby("bounty", a, rules(1, 8), 0);
        l.join(b, 1);
        l.vote(a, "bow", 2);
        l.vote(b, "fists", 2);
        l.tick(1300);
        assertEquals("fists", l.combatMode());
    }

    // el voto de quien se fue no cuenta
    @Test
    void voteOfLeaverIsDropped() {
        Lobby l = new Lobby("bounty", a, rules(1, 8), 0);
        l.join(b, 1);
        l.join(c, 1);
        l.vote(b, "bow", 2);
        l.vote(c, "bow", 2);
        l.vote(a, "fists", 2);
        l.leave(b, 3);
        l.leave(c, 3);
        l.tick(1300);
        assertEquals("fists", l.combatMode());
    }

    // contando doble ganaría bow 2 a 1; bien contado es empate y desempata el orden del admin
    @Test
    void changingVoteCountsOnce() {
        Lobby l = new Lobby("bounty", a, rules(1, 8), 0);
        l.join(b, 1);
        l.vote(a, "bow", 2);
        l.vote(a, "fists", 3);
        l.vote(b, "bow", 3);
        l.tick(1300);
        assertEquals("fists", l.combatMode());
    }

    @Test
    void outsidersAndUnknownModesCannotVote() {
        Lobby l = new Lobby("bounty", a, rules(1, 8), 0);
        assertEquals(VoteResult.NOT_IN_LOBBY, l.vote(d, "bow", 1));
        assertEquals(VoteResult.UNKNOWN_MODE, l.vote(a, "creative", 1));
        l.tick(1300);
        assertEquals(VoteResult.NOT_VOTING, l.vote(a, "bow", 1301));
        assertEquals(LobbyRules.ANY_COMBAT, l.combatMode());
    }

    @Test
    void combatModeUnknownBeforeStart() {
        Lobby l = new Lobby("bounty", a, rules(1, 8), 0);
        assertNull(l.combatMode());
    }

    @Test
    void cancelledLobbyIgnoresEverything() {
        Lobby l = new Lobby("race", a, rules(1, 8), 0);
        l.cancelByCreator();
        assertEquals(JoinResult.CLOSED, l.join(b, 1));
        assertFalse(l.leave(a, 1));
        assertEquals(State.CANCELLED, l.tick(99999));
        assertEquals(CancelReason.BY_CREATOR, l.cancelReason());
    }

    // configuración rota del admin: mínimos negativos, máximo menor que el mínimo, tiempos absurdos
    @Test
    void hostileRulesAreClamped() {
        LobbyRules r = new LobbyRules(-5, -1, -100, Long.MAX_VALUE, null);
        assertEquals(1, r.minPlayers());
        assertEquals(1, r.maxPlayers());
        assertEquals(1, r.openTicks());
        assertEquals(LobbyRules.HARD_MAX_TICKS, r.countdownTicks());
        assertEquals(List.of(LobbyRules.ANY_COMBAT), r.combatModes());

        LobbyRules r2 = new LobbyRules(10, 2, 100, 0, java.util.Arrays.asList("bow", null, " ", "bow"));
        assertEquals(10, r2.maxPlayers());
        assertEquals(List.of(LobbyRules.ANY_COMBAT, "bow"), r2.combatModes());
    }

    @Test
    void zeroCountdownStartsOnNextTick() {
        Lobby l = new Lobby("race", a, new LobbyRules(2, 2, 1200, 0, List.of()), 0);
        l.join(b, 5);
        assertEquals(State.STARTED, l.tick(5));
    }
}
