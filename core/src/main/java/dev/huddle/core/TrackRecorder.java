package dev.huddle.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// grabación de una pista mientras el creador la recorre: así no hay pistas imposibles
public final class TrackRecorder {
    public enum Outcome { NONE, ADDED, TOO_CLOSE, FULL, JUMP, BROKEN }

    private final Route.Mode mode;
    private final Pos start;
    private final double spacing;
    private final double radius;
    private final double maxJumpSq;
    private final List<Route.Checkpoint> points = new ArrayList<>();
    private Pos last;
    private boolean broken;

    // spacing: cada cuántos bloques se agrega un punto solo (0 o menos: solo a mano)
    public TrackRecorder(Route.Mode mode, Pos start, double spacing, double radius, double maxJump) {
        this.mode = mode;
        this.start = start;
        this.radius = Math.max(Route.MIN_RADIUS, Math.min(Double.isNaN(radius) ? Route.MIN_RADIUS : radius, Route.MAX_RADIUS));
        // con menos separación que dos radios el punto nuevo pisaría al anterior y se perdería
        this.spacing = Double.isFinite(spacing) && spacing > 0 ? Math.max(spacing, 2 * this.radius) : 0;
        double j = Double.isFinite(maxJump) && maxJump > 0 ? maxJump : 0;
        this.maxJumpSq = j * j;
        this.last = start;
    }

    // posición del creador en cada tick
    public Outcome sample(Pos at) {
        Outcome o = follow(at);
        if (o != Outcome.NONE || spacing <= 0) {
            return o;
        }
        Pos ref = points.isEmpty() ? start : points.get(points.size() - 1).center();
        return ref.distSq(at) >= spacing * spacing ? add(at) : Outcome.NONE;
    }

    // punto a mano donde está el creador
    public Outcome point(Pos at) {
        Outcome o = follow(at);
        return o != Outcome.NONE ? o : add(at);
    }

    private Outcome follow(Pos at) {
        if (broken) {
            return Outcome.BROKEN;
        }
        if (at == null || !at.finite()) {
            return Outcome.BROKEN;
        }
        if (last.distSq(at) > maxJumpSq) {
            broken = true;
            return Outcome.JUMP;
        }
        last = at;
        return Outcome.NONE;
    }

    private Outcome add(Pos at) {
        Route.Checkpoint cp = new Route.Checkpoint(at, radius);
        if (!points.isEmpty() && points.get(points.size() - 1).overlaps(cp)) {
            return Outcome.TOO_CLOSE;
        }
        if (points.size() >= Route.MAX_CHECKPOINTS) {
            return Outcome.FULL;
        }
        points.add(cp);
        return Outcome.ADDED;
    }

    // la meta es donde termina el creador; si pisa al último punto, ese punto pasa a ser la meta
    public Optional<Route> save(Pos at, String name, UUID author, String authorName, int laps) {
        if (broken || follow(at) != Outcome.NONE) {
            return Optional.empty();
        }
        List<Route.Checkpoint> all = new ArrayList<>(points);
        Route.Checkpoint finish = new Route.Checkpoint(at, radius);
        if (!all.isEmpty() && all.get(all.size() - 1).overlaps(finish)) {
            all.set(all.size() - 1, finish);
        } else if (all.size() < Route.MAX_CHECKPOINTS) {
            all.add(finish);
        }
        return Route.of(name, author, authorName, mode, laps, start, all);
    }

    public boolean broken() {
        return broken;
    }

    public int size() {
        return points.size();
    }
}
