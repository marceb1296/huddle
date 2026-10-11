package dev.huddle.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// último en pie: una zona cuadrada (como el borde del mundo) que se achica por etapas. Quien queda fuera tiene
// outsideTicks para volver o queda descalificado. Gana el último que queda; si se acaba el tiempo o empezó solo, nadie
public final class LastMan {
    // sizes: medio lado de cada etapa, de mayor a menor; cada etapa se queda quieta holdTicks y tarda shrinkTicks en
    // pasar a la siguiente; en la última se queda finalTicks y termina sin ganador
    public record Params(List<Double> sizes, long holdTicks, long shrinkTicks, long outsideTicks, long finalTicks) {
        public Params {
            List<Double> ok = new ArrayList<>();
            for (Double s : sizes) {
                if (s != null && Double.isFinite(s) && s > 0) {
                    ok.add(s);
                }
            }
            ok.sort((a, b) -> Double.compare(b, a));
            if (ok.size() > MAX_STAGES) {
                ok = new ArrayList<>(ok.subList(0, MAX_STAGES));
            }
            if (ok.isEmpty()) {
                throw new IllegalArgumentException("the zone needs at least one positive size");
            }
            sizes = List.copyOf(ok);
            // con valores enormes las sumas de ticks desbordarían
            holdTicks = Math.max(0, Math.min(MAX_TICKS, holdTicks));
            shrinkTicks = Math.max(0, Math.min(MAX_TICKS, shrinkTicks));
            outsideTicks = Math.max(0, Math.min(MAX_TICKS, outsideTicks));
            finalTicks = Math.max(0, Math.min(MAX_TICKS, finalTicks));
        }
    }

    private static final long MAX_TICKS = 1L << 32;
    private static final int MAX_STAGES = 64;

    private final Pos center;
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Map<UUID, Long> outsideSince = new HashMap<>();
    private final long start;
    private final Params params;
    // sin rivales nadie gana: con minPlayers en 1 sería botín gratis
    private final boolean contest;

    public LastMan(Pos center, Collection<UUID> participants, long start, Params params) {
        this.center = center;
        this.alive.addAll(participants);
        this.contest = alive.size() >= 2;
        this.start = start;
        this.params = params;
    }

    private long stageTicks() {
        return params.holdTicks() + params.shrinkTicks();
    }

    // cuándo llega a la última etapa
    private long lastStageTick() {
        return start + stageTicks() * (params.sizes().size() - 1);
    }

    public long endTick() {
        return lastStageTick() + params.finalTicks();
    }

    public double halfSize(long now) {
        List<Double> sizes = params.sizes();
        long t = Math.max(0, now - start);
        long each = stageTicks();
        int stage = each == 0 ? sizes.size() - 1 : (int) Math.min(sizes.size() - 1, t / each);
        if (stage >= sizes.size() - 1) {
            return sizes.get(sizes.size() - 1);
        }
        long into = t - stage * each;
        if (into < params.holdTicks()) {
            return sizes.get(stage);
        }
        double f = (into - params.holdTicks()) / (double) Math.max(1, params.shrinkTicks());
        return sizes.get(stage) + (sizes.get(stage + 1) - sizes.get(stage)) * f;
    }

    public boolean shrinking(long now) {
        return halfSize(now) > halfSize(now + 1);
    }

    // el próximo tick en que la zona empieza o deja de achicarse (o termina el evento)
    public long nextChange(long now) {
        if (now < start) {
            return start;
        }
        if (now >= lastStageTick()) {
            return endTick();
        }
        long into = (now - start) % stageTicks();
        long stageStart = now - into;
        return into < params.holdTicks() ? stageStart + params.holdTicks() : stageStart + stageTicks();
    }

    // una posición rota cuenta como fuera
    public boolean inside(Pos p, long now) {
        double h = halfSize(now);
        return p != null && Math.abs(p.x() - center.x()) <= h && Math.abs(p.z() - center.z()) <= h;
    }

    // at: dónde está cada participante conectado; devuelve quienes quedaron descalificados en este tick
    public List<UUID> tick(Map<UUID, Pos> at, long now) {
        List<UUID> out = new ArrayList<>();
        if (over(now)) {
            return out;
        }
        for (UUID id : List.copyOf(alive)) {
            if (!at.containsKey(id)) {
                continue;
            }
            if (inside(at.get(id), now)) {
                outsideSince.remove(id);
                continue;
            }
            long since = outsideSince.computeIfAbsent(id, k -> now);
            if (now - since >= params.outsideTicks()) {
                out.add(id);
                eliminate(id);
            }
        }
        return out;
    }

    // ticks que le quedan para volver a la zona, o -1 si está dentro
    public long outsideLeft(UUID id, long now) {
        Long since = outsideSince.get(id);
        return since == null ? -1 : Math.max(0, since + params.outsideTicks() - now);
    }

    public void eliminate(UUID id) {
        alive.remove(id);
        outsideSince.remove(id);
    }

    public Set<UUID> alive() {
        return Set.copyOf(alive);
    }

    public UUID winner() {
        return contest && alive.size() == 1 ? alive.iterator().next() : null;
    }

    public Pos center() {
        return center;
    }

    public boolean over(long now) {
        return alive.size() <= (contest ? 1 : 0) || now >= endTick();
    }
}
