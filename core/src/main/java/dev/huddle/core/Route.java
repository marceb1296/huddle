package dev.huddle.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

// pista de carrera: largada y puntos de control en orden; el último es la meta.
// Con varias vueltas, después de la meta se vuelve al primer punto.
public final class Route {
    public enum Mode { FOOT, HORSE, BOAT, ELYTRA }

    public record Checkpoint(Pos center, double radius) {
        public boolean overlaps(Checkpoint o) {
            double r = radius + o.radius;
            return center.distSq(o.center) < r * r;
        }

        // primer instante (0 a 1) en que el segmento a-b entra en la esfera, o -1 si no la toca.
        // Con élitros o en bote se avanza más de un radio por tick: mirar solo la posición final lo saltaría.
        public double entry(Pos a, Pos b) {
            double fx = a.x() - center.x(), fy = a.y() - center.y(), fz = a.z() - center.z();
            double c = fx * fx + fy * fy + fz * fz - radius * radius;
            if (c <= 0) {
                return 0;
            }
            double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
            double qa = dx * dx + dy * dy + dz * dz;
            if (qa == 0) {
                return -1;
            }
            double qb = 2 * (fx * dx + fy * dy + fz * dz);
            double disc = qb * qb - 4 * qa * c;
            if (disc < 0) {
                return -1;
            }
            double t = (-qb - Math.sqrt(disc)) / (2 * qa);
            return t >= 0 && t <= 1 ? t : -1;
        }
    }

    public static final int MAX_CHECKPOINTS = 256;
    public static final int MAX_LAPS = 20;
    public static final double MIN_RADIUS = 1;
    public static final double MAX_RADIUS = 16;
    public static final int MAX_NAME = 32;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_.+-]{1," + MAX_NAME + "}");

    private final String name;
    private final UUID author;
    private final String authorName;
    private final Mode mode;
    private final int laps;
    private final Pos start;
    private final List<Checkpoint> checkpoints;

    private Route(String name, UUID author, String authorName, Mode mode, int laps, Pos start, List<Checkpoint> checkpoints) {
        this.name = name;
        this.author = author;
        this.authorName = authorName;
        this.mode = mode;
        this.laps = laps;
        this.start = start;
        this.checkpoints = checkpoints;
    }

    // lo usan la grabación y la carga de datos guardados: los datos rotos se corrigen o se descartan.
    // Se quitan puntos no finitos y los que se pisan con el anterior (quedarse quieto en dos esferas
    // superpuestas contaría puntos, y en un circuito vueltas, sin moverse).
    public static Optional<Route> of(String name, UUID author, String authorName, Mode mode, int laps, Pos start, List<Checkpoint> raw) {
        String n = cleanName(name);
        if (n == null || mode == null || start == null || !start.finite() || raw == null) {
            return Optional.empty();
        }
        List<Checkpoint> kept = new ArrayList<>();
        for (Checkpoint cp : raw) {
            if (kept.size() >= MAX_CHECKPOINTS) {
                break;
            }
            if (cp == null || cp.center() == null || !cp.center().finite() || Double.isNaN(cp.radius())) {
                continue;
            }
            Checkpoint c = new Checkpoint(cp.center(), Math.max(MIN_RADIUS, Math.min(cp.radius(), MAX_RADIUS)));
            if (!kept.isEmpty() && kept.get(kept.size() - 1).overlaps(c)) {
                continue;
            }
            kept.add(c);
        }
        int l = Math.max(1, Math.min(laps, MAX_LAPS));
        if (l > 1) {
            while (kept.size() > 1 && kept.get(kept.size() - 1).overlaps(kept.get(0))) {
                kept.remove(kept.size() - 1);
            }
            if (kept.size() < 2) {
                l = 1;
            }
        }
        if (kept.isEmpty()) {
            return Optional.empty();
        }
        String who = authorName == null || authorName.isBlank() ? "?" : authorName.strip();
        return Optional.of(new Route(n, author, who.length() > 64 ? who.substring(0, 64) : who, mode, l, start, List.copyOf(kept)));
    }

    // nombres que el comando acepta como una sola palabra
    public static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().toLowerCase(Locale.ROOT);
        return NAME.matcher(s).matches() ? s : null;
    }

    public static Optional<Mode> parseMode(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Mode.valueOf(raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public String name() {
        return name;
    }

    public UUID author() {
        return author;
    }

    public String authorName() {
        return authorName;
    }

    public Mode mode() {
        return mode;
    }

    public int laps() {
        return laps;
    }

    public Pos start() {
        return start;
    }

    public List<Checkpoint> checkpoints() {
        return checkpoints;
    }

    public int pointsPerRace() {
        return checkpoints.size() * laps;
    }
}
