package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.huddle.core.Rewards.Outcome;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RewardsTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 9);
    private static final long HOUR = 3_600_000;

    @Test
    void decaysWithEachWinOfTheDayAndResetsNextDay() {
        Rewards r = new Rewards();
        RewardRules rules = new RewardRules(8, 1000, 0.5, 1, -1);
        assertEquals(8, r.grant(rules, "x", 3, 0, 1, DAY).units());
        assertEquals(4, r.grant(rules, "x", 3, 0, 1, DAY).units());
        assertEquals(2, r.grant(rules, "x", 3, 0, 1, DAY).units());
        assertEquals(8, r.grant(rules, "x", 3, 0, 1, DAY.plusDays(1)).units());
    }

    @Test
    void dailyCapIsNeverExceeded() {
        Rewards r = new Rewards();
        RewardRules rules = new RewardRules(10, 25, 1, 1, -1);
        int total = 0;
        for (int i = 0; i < 10; i++) {
            total += r.grant(rules, "x", 3, 0, 2, DAY).units();
        }
        assertEquals(25, total);
        assertEquals(Outcome.CAPPED, r.grant(rules, "x", 3, 0, 1, DAY).outcome());
    }

    @Test
    void tooFewDistinctPlayersGetNothing() {
        Rewards r = new Rewards();
        RewardRules rules = new RewardRules(10, 100, 1, 3, -1);
        assertEquals(new Rewards.Result(Outcome.TOO_FEW_PLAYERS, 0), r.grant(rules, "x", 2, 0, 1, DAY));
        assertEquals(10, r.grant(rules, "x", 3, 0, 1, DAY).units());
    }

    @Test
    void bigRewardsWaitForAdminAndRejectGivesCapBack() {
        Rewards r = new Rewards();
        RewardRules rules = new RewardRules(10, 15, 1, 1, 5);
        Rewards.Result res = r.grant(rules, "x", 3, 0, 1, DAY);
        assertEquals(new Rewards.Result(Outcome.PENDING_APPROVAL, 10), res);
        r.reject("x", res.units(), DAY);
        assertEquals(10, r.grant(rules, "x", 3, 0, 1, DAY).units());
    }

    // multiplicador infinito o NaN de un archivo roto no puede dar premios enormes ni negativos
    @Test
    void hostileMultiplierIsSafe() {
        Rewards r = new Rewards();
        RewardRules rules = new RewardRules(10, 1_000_000, 1, 1, -1);
        assertEquals(10, r.grant(rules, "x", 3, 0, Double.POSITIVE_INFINITY, DAY).units());
        assertEquals(10, r.grant(rules, "y", 3, 0, Double.NaN, DAY).units());
        assertEquals(0, r.grant(rules, "z", 3, 0, -5, DAY).units());
        assertEquals(100, r.grant(rules, "w", 3, 0, 1e300, DAY).units());
        RewardRules huge = new RewardRules(Integer.MAX_VALUE, Integer.MAX_VALUE, Double.NaN, -3, -1);
        assertEquals(RewardRules.HARD_MAX_UNITS, huge.baseUnits());
        assertEquals(1, huge.decay());
    }

    // los mismos amigos repiten: el premio baja
    @Test
    void sameFriendsRepeatingGetLess() {
        AntiAbuse ab = new AntiAbuse(false, 24 * HOUR, 0.75, 100);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        Map<UUID, String> ips = new LinkedHashMap<>();
        ips.put(p1, null);
        ips.put(p2, null);
        ips.put(p3, null);
        Set<String> ids = ab.identities(ips);
        assertEquals(0, ab.repeats(ids, 0));
        ab.record(ids, 0);
        ab.record(ids, HOUR);
        assertEquals(2, ab.repeats(ids, 2 * HOUR));
        Rewards r = new Rewards();
        RewardRules rules = new RewardRules(9, 100, 1, 1, -1);
        assertEquals(3, r.grant(rules, "x", 3, ab.repeats(ids, 2 * HOUR), 1, DAY).units());
        assertEquals(0, ab.repeats(ids, 25 * HOUR));
    }

    @Test
    void addingOneStrangerDoesNotHideTheGroup() {
        AntiAbuse ab = new AntiAbuse(false, 24 * HOUR, 0.75, 100);
        Set<String> friends = Set.of("id:1", "id:2", "id:3");
        ab.record(friends, 0);
        assertEquals(1, ab.repeats(Set.of("id:1", "id:2", "id:3", "id:4"), 1));
        assertEquals(0, ab.repeats(Set.of("id:1", "id:5", "id:6", "id:7"), 1));
        // dos que estuvieron en un evento grande no son "los mismos de siempre"
        ab.record(Set.of("id:10", "id:11", "id:12", "id:13", "id:14", "id:15"), 2);
        assertEquals(0, ab.repeats(Set.of("id:10", "id:11"), 3));
        // ni un evento grande donde vuelven a coincidir dos que antes jugaron solos
        AntiAbuse duo = new AntiAbuse(false, 24 * HOUR, 0.75, 100);
        duo.record(Set.of("id:1", "id:2"), 0);
        assertEquals(0, duo.repeats(Set.of("id:1", "id:2", "id:3", "id:4", "id:5", "id:6"), 1));
    }

    // decisión del usuario: cuentas desde la misma IP son un solo jugador para premios
    @Test
    void altAccountsOnSameIpCountOnce() {
        Map<UUID, String> ips = new LinkedHashMap<>();
        ips.put(UUID.randomUUID(), "10.0.0.5");
        ips.put(UUID.randomUUID(), "10.0.0.5");
        ips.put(UUID.randomUUID(), "10.0.0.9");
        ips.put(UUID.randomUUID(), null);
        ips.put(UUID.randomUUID(), "");
        ips.put(UUID.randomUUID(), "");
        assertEquals(5, new AntiAbuse(true, HOUR, 0.75, 10).identities(ips).size());
        assertEquals(6, new AntiAbuse(false, HOUR, 0.75, 10).identities(ips).size());
    }

    @Test
    void historyIsBounded() {
        AntiAbuse ab = new AntiAbuse(false, 24 * HOUR, 0.75, 5);
        Set<String> ids = Set.of("id:1", "id:2");
        for (int i = 0; i < 1000; i++) {
            ab.record(ids, i);
        }
        assertEquals(5, ab.repeats(ids, 1000));
    }

    @Test
    void scheduleCoversItsWindowOnly() {
        Schedule s = new Schedule(List.of(new Schedule.Window(Set.of(DayOfWeek.FRIDAY), LocalTime.of(20, 0), LocalTime.of(23, 0), 2)));
        LocalDateTime fri = LocalDateTime.of(2026, 10, 9, 0, 0);
        assertEquals(1, s.multiplier(fri.withHour(19).withMinute(59)));
        assertEquals(2, s.multiplier(fri.withHour(20)));
        assertEquals(2, s.multiplier(fri.withHour(22).withMinute(59)));
        assertEquals(1, s.multiplier(fri.withHour(23)));
        assertEquals(1, s.multiplier(fri.plusDays(1).withHour(21)));
    }

    // viernes de 22 a 2: el sábado a la 1 sigue siendo la franja del viernes, el viernes a la 1 no
    @Test
    void scheduleCrossingMidnightBelongsToStartDay() {
        Schedule s = new Schedule(List.of(new Schedule.Window(Set.of(DayOfWeek.FRIDAY), LocalTime.of(22, 0), LocalTime.of(2, 0), 3)));
        LocalDateTime fri = LocalDateTime.of(2026, 10, 9, 0, 0);
        assertEquals(3, s.multiplier(fri.withHour(23)));
        assertEquals(3, s.multiplier(fri.plusDays(1).withHour(1)));
        assertEquals(1, s.multiplier(fri.withHour(1)));
        assertEquals(1, s.multiplier(fri.plusDays(1).withHour(2)));
    }

    @Test
    void overlappingWindowsUseTheHighestAndHostileValuesAreClamped() {
        Schedule s = new Schedule(List.of(
                new Schedule.Window(Set.of(DayOfWeek.FRIDAY), LocalTime.of(18, 0), LocalTime.of(23, 0), 2),
                new Schedule.Window(Set.of(DayOfWeek.FRIDAY), LocalTime.of(20, 0), LocalTime.of(21, 0), Double.NaN),
                new Schedule.Window(Set.of(DayOfWeek.FRIDAY), LocalTime.of(20, 0), LocalTime.of(20, 30), 1e9)));
        LocalDateTime fri = LocalDateTime.of(2026, 10, 9, 20, 10);
        assertEquals(Schedule.MAX_MULTIPLIER, s.multiplier(fri));
        assertEquals(2, s.multiplier(fri.withMinute(45)));
    }

    // un admin que aprueba dos veces el mismo premio lo duplicaría
    @Test
    void approvalResolvesOnceAndSurvivesRestart() {
        Approvals a = new Approvals();
        UUID w = UUID.randomUUID();
        Approvals.Pending p = a.add(w, "id:" + w, "race", 10, 0);
        Approvals again = Approvals.restore(a.list());
        assertEquals(p, again.resolve(p.id()));
        assertNull(again.resolve(p.id()));
        assertEquals(p.id() + 1, again.add(w, "id:" + w, "race", 1, 0).id());
    }

    @Test
    void joinCheckMeasuresEquipment() {
        JoinCheck.Limits l = new JoinCheck.Limits(10, 2);
        assertEquals(JoinCheck.Verdict.OK, JoinCheck.check(l, 0, 10, 2));
        assertEquals(JoinCheck.Verdict.TOO_MUCH_ARMOR, JoinCheck.check(l, 0, 11, 0));
        assertEquals(JoinCheck.Verdict.ENCHANT_TOO_HIGH, JoinCheck.check(l, 0, 0, 3));
        assertEquals(JoinCheck.Verdict.ON_COOLDOWN, JoinCheck.check(l, 1, 0, 0));
        assertEquals(JoinCheck.Verdict.OK, JoinCheck.check(JoinCheck.Limits.NONE, 0, 30, 255));
        assertEquals(JoinCheck.Verdict.OK, JoinCheck.check(new JoinCheck.Limits(0, 0), 0, 0, 0));
        assertEquals(JoinCheck.Verdict.ENCHANT_TOO_HIGH, JoinCheck.check(new JoinCheck.Limits(-1, 0), 0, 0, 1));
    }
}
