package dev.huddle.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// estado de una carrera en curso. Tiempos en ticks desde la largada, con fracción de tick
// según dónde del segmento se cruzó la meta (desempata llegadas en el mismo tick).
public final class RaceState {
    public enum Status { RUNNING, FINISHED, DISQUALIFIED, LEFT }

    public enum Kind { CHECKPOINT, LAP, FINISH, JUMP }

    // CHECKPOINT: índice del punto; LAP: vueltas completas. El puesto de FINISH se sabe recién
    // cuando se movieron todos en ese tick: ver newFinishes
    public record Event(Kind kind, int value) {
    }

    public record Result(UUID player, int place, double ticks) {
    }

    private static final class Runner {
        final UUID id;
        Status status = Status.RUNNING;
        int next;
        int lapsDone;
        int passed;
        double finishTicks = Double.NaN;
        int place;
        Pos last;

        Runner(UUID id, Pos at) {
            this.id = id;
            this.last = at;
        }
    }

    private final Route route;
    private final long startTick;
    private final double maxJumpSq;
    private final Map<UUID, Runner> runners = new LinkedHashMap<>();
    private int finished;

    // maxJump: más que eso en un tick (perlas, waystones, comandos) descalifica
    public RaceState(Route route, Map<UUID, Pos> startPositions, long startTick, double maxJump) {
        this.route = route;
        this.startTick = startTick;
        double j = Double.isFinite(maxJump) && maxJump > 0 ? maxJump : 0;
        this.maxJumpSq = j * j;
        startPositions.forEach((id, at) -> {
            if (id != null) {
                runners.put(id, new Runner(id, at != null && at.finite() ? at : route.start()));
            }
        });
    }

    // posición del jugador en este tick
    public List<Event> move(UUID player, Pos to, long tick) {
        Runner r = runners.get(player);
        if (r == null || r.status != Status.RUNNING || to == null || !to.finite()) {
            return List.of();
        }
        Pos from = r.last;
        r.last = to;
        if (from.distSq(to) > maxJumpSq) {
            r.status = Status.DISQUALIFIED;
            return List.of(new Event(Kind.JUMP, 0));
        }
        List<Event> out = new ArrayList<>();
        List<Route.Checkpoint> cps = route.checkpoints();
        double after = 0;
        // cada esfera corta el segmento en un solo tramo: exigir que la siguiente entre después
        // impide contar dos veces el mismo punto (y vueltas enteras) en un tick
        for (int guard = 0; guard <= cps.size(); guard++) {
            double t = cps.get(r.next).entry(from, to);
            if (t < 0 || t < after) {
                break;
            }
            after = t;
            out.add(new Event(Kind.CHECKPOINT, r.next));
            r.passed++;
            r.next++;
            if (r.next < cps.size()) {
                continue;
            }
            r.next = 0;
            r.lapsDone++;
            if (r.lapsDone >= route.laps()) {
                r.status = Status.FINISHED;
                r.finishTicks = Math.max(0, (tick - 1 - startTick) + t);
                out.add(new Event(Kind.FINISH, 0));
                break;
            }
            out.add(new Event(Kind.LAP, r.lapsDone));
        }
        return out;
    }

    // llegadas aún no informadas, con su puesto; llamar después de mover a todos en el tick.
    // Dos llegadas en el mismo tick: gana la que cruzó antes dentro del tick.
    public List<Result> newFinishes() {
        List<Runner> fresh = new ArrayList<>();
        for (Runner r : runners.values()) {
            if (r.status == Status.FINISHED && r.place == 0) {
                fresh.add(r);
            }
        }
        fresh.sort(Comparator.comparingDouble(r -> r.finishTicks));
        List<Result> out = new ArrayList<>();
        for (Runner r : fresh) {
            r.place = ++finished;
            out.add(new Result(r.id, r.place, r.finishTicks));
        }
        return out;
    }

    // quien muere corriendo vuelve al último punto que pasó (o a la largada) y sigue desde ahí sin que cuente como salto
    public Pos respawn(UUID player) {
        Runner r = runners.get(player);
        if (r == null || r.status != Status.RUNNING) {
            return null;
        }
        List<Route.Checkpoint> cps = route.checkpoints();
        r.last = r.passed == 0 ? route.start() : cps.get(Math.floorMod(r.next - 1, cps.size())).center();
        return r.last;
    }

    public void leave(UUID player) {
        Runner r = runners.get(player);
        if (r != null && r.status == Status.RUNNING) {
            r.status = Status.LEFT;
        }
    }

    // medio de transporte equivocado u otra regla que decide el loader
    public void disqualify(UUID player) {
        Runner r = runners.get(player);
        if (r != null && r.status == Status.RUNNING) {
            r.status = Status.DISQUALIFIED;
        }
    }

    public Status status(UUID player) {
        Runner r = runners.get(player);
        return r == null ? null : r.status;
    }

    // -1 si el jugador no corre
    public int nextCheckpoint(UUID player) {
        Runner r = runners.get(player);
        return r == null || r.status != Status.RUNNING ? -1 : r.next;
    }

    public int lapsDone(UUID player) {
        Runner r = runners.get(player);
        return r == null ? 0 : r.lapsDone;
    }

    public boolean over() {
        return runners.values().stream().noneMatch(r -> r.status == Status.RUNNING);
    }

    // todas las llegadas por tiempo, sin marcar nada como informado
    public List<Result> results() {
        List<Runner> done = new ArrayList<>();
        for (Runner r : runners.values()) {
            if (r.status == Status.FINISHED) {
                done.add(r);
            }
        }
        done.sort(Comparator.comparingInt((Runner r) -> r.place == 0 ? Integer.MAX_VALUE : r.place)
                .thenComparingDouble(r -> r.finishTicks));
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < done.size(); i++) {
            out.add(new Result(done.get(i).id, i + 1, done.get(i).finishTicks));
        }
        return out;
    }

    // posiciones en vivo: llegados por tiempo, después los que corren por puntos pasados y
    // distancia al próximo, y al final quienes quedaron fuera
    public List<UUID> standings() {
        List<Runner> all = new ArrayList<>(runners.values());
        all.sort(Comparator.comparingInt((Runner r) -> rank(r.status))
                .thenComparingDouble(r -> r.status == Status.FINISHED ? r.finishTicks : 0)
                .thenComparingInt(r -> -r.passed)
                .thenComparingDouble(r -> r.status == Status.RUNNING ? r.last.distSq(route.checkpoints().get(r.next).center()) : 0));
        List<UUID> out = new ArrayList<>();
        all.forEach(r -> out.add(r.id));
        return out;
    }

    private static int rank(Status s) {
        return switch (s) {
            case FINISHED -> 0;
            case RUNNING -> 1;
            case DISQUALIFIED, LEFT -> 2;
        };
    }

    public Route route() {
        return route;
    }
}
