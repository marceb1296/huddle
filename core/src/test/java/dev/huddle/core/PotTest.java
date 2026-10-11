package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PotTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    private Pot<String> pot() {
        Pot<String> p = new Pot<>();
        p.deposit(a, "a-diamonds");
        p.deposit(b, "b-iron");
        p.deposit(c, "c-gold");
        return p;
    }

    @Test
    void winnerTakesEverythingIncludingLeavers() {
        Pot<String> p = pot();
        p.forfeit(c);
        List<Pot.Delivery<String>> out = p.settleWinner(a);
        assertEquals(3, out.size());
        assertTrue(out.stream().allMatch(d -> d.to().equals(a)));
    }

    // decisión del usuario: sin ganador, cada uno recupera su entrada, también quien abandonó
    @Test
    void noWinnerReturnsEachStakeToItsOwner() {
        Pot<String> p = pot();
        p.forfeit(c);
        Map<UUID, String> back = new java.util.HashMap<>();
        p.refundAll().forEach(d -> back.put(d.to(), d.stake()));
        assertEquals(Map.of(a, "a-diamonds", b, "b-iron", c, "c-gold"), back);
    }

    // dos finales seguidos (ganador y vencimiento en el mismo tick) duplicarían el bote
    @Test
    void settlesOnlyOnce() {
        Pot<String> p = pot();
        assertEquals(3, p.settleWinner(a).size());
        assertTrue(p.refundAll().isEmpty());
        assertTrue(p.settleWinner(b).isEmpty());
    }

    // el bote repartido que se guarda y se vuelve a cargar no puede repartirse otra vez
    @Test
    void settledPotSavedAndReloadedPaysNothing() {
        Pot<String> p = pot();
        p.settleWinner(a);
        assertTrue(Pot.restore(p.stakes(), p.forfeitedPlayers()).refundAll().isEmpty());
    }

    @Test
    void secondDepositDoesNotReplaceTheFirst() {
        Pot<String> p = new Pot<>();
        assertTrue(p.deposit(a, "first"));
        assertFalse(p.deposit(a, "second"));
        assertEquals(List.of(new Pot.Delivery<>(a, "first")), p.refundAll());
    }

    @Test
    void noDepositsAfterSettling() {
        Pot<String> p = pot();
        p.refundAll();
        assertFalse(p.deposit(UUID.randomUUID(), "late"));
        assertTrue(p.refundAll().isEmpty());
    }

    @Test
    void leaverCannotWin() {
        Pot<String> p = pot();
        p.forfeit(a);
        assertTrue(p.settleWinner(a).isEmpty());
        assertEquals(Pot.State.COLLECTING, p.state());
        assertEquals(3, p.refundAll().size());
    }

    @Test
    void restoredPotKeepsStakesAndForfeits() {
        Pot<String> p = pot();
        p.forfeit(b);
        Pot<String> again = Pot.restore(p.stakes(), p.forfeitedPlayers());
        assertTrue(again.settleWinner(b).isEmpty());
        assertEquals(3, again.settleWinner(a).size());
    }

    @Test
    void mailboxDeliversOnceAndKeepsUntilTaken() {
        Mailbox<String> m = new Mailbox<>();
        m.postAll(pot().settleWinner(a));
        Mailbox<String> again = Mailbox.restore(m.snapshot());
        assertEquals(3, again.count(a));
        assertEquals(3, again.take(a).size());
        assertTrue(again.take(a).isEmpty());
        assertEquals(3, m.count(a));
    }

    @Test
    void mailboxSnapshotDoesNotChangeAfterwards() {
        Mailbox<String> m = new Mailbox<>();
        m.post(a, "one");
        Map<UUID, List<String>> saved = m.snapshot();
        m.post(a, "two");
        assertEquals(List.of("one"), saved.get(a));
    }
}
