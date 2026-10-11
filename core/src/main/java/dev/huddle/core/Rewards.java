package dev.huddle.core;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

// cuántas unidades del premio por defecto le tocan a un ganador, con tope diario por identidad
public final class Rewards {
    public enum Outcome { GRANTED, PENDING_APPROVAL, TOO_FEW_PLAYERS, CAPPED, NONE }

    public record Result(Outcome outcome, int units) {
    }

    public record Day(LocalDate date, int units, int wins) {
    }

    private final Map<String, Day> today = new HashMap<>();

    // distinct: identidades distintas en el evento; repeats: de AntiAbuse.repeats
    public Result grant(RewardRules rules, String winner, int distinct, int repeats, double multiplier, LocalDate date) {
        if (distinct < rules.minDistinctPlayers()) {
            return new Result(Outcome.TOO_FEW_PLAYERS, 0);
        }
        Day d = dayOf(winner, date);
        double m = Double.isFinite(multiplier) ? Math.max(0, Math.min(multiplier, Schedule.MAX_MULTIPLIER)) : 1;
        double raw = rules.baseUnits() * m * Math.pow(rules.decay(), d.wins()) * AntiAbuse.repeatFactor(repeats);
        int units = (int) Math.min(Math.floor(raw), Integer.MAX_VALUE);
        int room = Math.max(0, rules.dailyCap() - d.units());
        boolean capped = units > room;
        units = Math.min(units, room);
        today.put(winner, new Day(date, d.units() + units, d.wins() + 1));
        if (units <= 0) {
            return new Result(capped ? Outcome.CAPPED : Outcome.NONE, 0);
        }
        if (rules.approvalAbove() >= 0 && units > rules.approvalAbove()) {
            return new Result(Outcome.PENDING_APPROVAL, units);
        }
        return new Result(capped ? Outcome.CAPPED : Outcome.GRANTED, units);
    }

    // el admin rechazó un premio pendiente: devuelve el cupo del día
    public void reject(String winner, int units, LocalDate date) {
        Day d = dayOf(winner, date);
        today.put(winner, new Day(date, Math.max(0, d.units() - Math.max(0, units)), d.wins()));
    }

    private Day dayOf(String id, LocalDate date) {
        Day d = today.get(id);
        return d == null || !d.date().equals(date) ? new Day(date, 0, 0) : d;
    }

    public Map<String, Day> snapshot() {
        return Map.copyOf(today);
    }

    public void restore(Map<String, Day> saved) {
        today.clear();
        saved.forEach((k, v) -> {
            if (k != null && v != null && v.date() != null) {
                today.put(k, new Day(v.date(), Math.max(0, v.units()), Math.max(0, v.wins())));
            }
        });
    }
}
