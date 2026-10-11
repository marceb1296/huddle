package dev.huddle.core;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// suministro aéreo: la caja baja durante fallTicks y, ya en el suelo, la abre quien se queda solo a menos de reach
// de su centro durante openTicks seguidos. Un daño o alejarse reinicia la cuenta; con dos o más cerca nadie avanza
public final class Airdrop {
    // maxTicks: cuánto dura en el suelo sin que nadie la abra
    public record Params(long fallTicks, double dropHeight, long openTicks, long maxTicks, double reach) {
    }

    private final Pos landing;
    private final Set<UUID> players = new LinkedHashSet<>();
    private final long start;
    private final Params params;
    private UUID holder;
    private long held;
    private UUID winner;

    public Airdrop(Pos landing, Collection<UUID> participants, long start, Params params) {
        this.landing = landing;
        this.players.addAll(participants);
        this.start = start;
        this.params = params;
    }

    private long landTick() {
        return start + params.fallTicks();
    }

    public Pos position(long now) {
        double left = Math.max(0, Math.min(1, (landTick() - now) / (double) Math.max(1, params.fallTicks())));
        return new Pos(landing.x(), landing.y() + params.dropHeight() * left, landing.z());
    }

    public boolean landed(long now) {
        return now >= landTick();
    }

    // at: dónde está cada participante en este tick; hurt: quienes recibieron daño desde el tick anterior
    public void tick(Map<UUID, Pos> at, Set<UUID> hurt, long now) {
        if (!landed(now) || over(now)) {
            return;
        }
        Pos center = new Pos(landing.x(), landing.y() + 0.5, landing.z());
        double r2 = params.reach() * params.reach();
        UUID only = null;
        int near = 0;
        for (Map.Entry<UUID, Pos> e : at.entrySet()) {
            // una posición rota da NaN y la comparación es falsa
            if (players.contains(e.getKey()) && e.getValue() != null && e.getValue().distSq(center) <= r2) {
                near++;
                only = e.getKey();
            }
        }
        boolean holderNear = holder != null && at.get(holder) != null && players.contains(holder) && at.get(holder).distSq(center) <= r2;
        if (holder != null && (!holderNear || hurt.contains(holder))) {
            holder = null;
            held = 0;
        }
        if (near != 1 || hurt.contains(only)) {
            return;
        }
        // si cambió quién la abre, la cuenta ya volvió a cero al irse o recibir daño el anterior
        holder = only;
        held++;
        if (held >= params.openTicks()) {
            winner = holder;
        }
    }

    public UUID holder() {
        return holder;
    }

    public double progress() {
        return held / (double) Math.max(1, params.openTicks());
    }

    public void leave(UUID player) {
        players.remove(player);
    }

    public UUID winner() {
        return winner;
    }

    public Pos landing() {
        return landing;
    }

    public boolean over(long now) {
        return winner != null || players.isEmpty() || now >= landTick() + params.maxTicks();
    }
}
