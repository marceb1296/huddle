package dev.huddle.core;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

// franjas con multiplicador de premio, por ejemplo viernes de 20 a 23 doble
public final class Schedule {
    static final double MAX_MULTIPLIER = 10;

    // to antes que from: cruza la medianoche y el día es el del comienzo
    public record Window(Set<DayOfWeek> days, LocalTime from, LocalTime to, double multiplier) {
        public Window {
            days = days == null ? Set.of() : Set.copyOf(days);
            multiplier = Double.isFinite(multiplier) ? Math.max(0, Math.min(multiplier, MAX_MULTIPLIER)) : 1;
        }

        boolean covers(LocalDateTime t) {
            if (from == null || to == null || from.equals(to)) {
                return false;
            }
            LocalTime time = t.toLocalTime();
            if (from.isBefore(to)) {
                return days.contains(t.getDayOfWeek()) && !time.isBefore(from) && time.isBefore(to);
            }
            if (!time.isBefore(from)) {
                return days.contains(t.getDayOfWeek());
            }
            return time.isBefore(to) && days.contains(t.getDayOfWeek().minus(1));
        }
    }

    private final List<Window> windows;

    public Schedule(List<Window> windows) {
        this.windows = windows == null ? List.of() : List.copyOf(windows);
    }

    // fuera de toda franja: 1; si se pisan, la mayor
    public double multiplier(LocalDateTime t) {
        double best = -1;
        for (Window w : windows) {
            if (w.covers(t)) {
                best = Math.max(best, w.multiplier());
            }
        }
        return best < 0 ? 1 : best;
    }
}
