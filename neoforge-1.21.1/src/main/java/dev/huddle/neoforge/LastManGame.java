package dev.huddle.neoforge;

import dev.huddle.core.LastMan;
import dev.huddle.core.Pos;
import dev.huddle.core.TreasureFinder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

// último en pie: mientras la sala está abierta se elige el centro (suelo abierto cerca del creador, lejos de bases);
// al empezar se los reparte dentro de la zona, cada uno recibe el kit prestado y ve la zona como el borde del mundo. Quien queda fuera tiene unos
// segundos para volver. La maneja Lobbies desde el hilo del servidor
final class LastManGame {
    static final int CENTER_DISTANCE = 16;
    static final double SPREAD_MARGIN = 0.8;

    private final SpotSearch search;
    private LastMan game;
    private Set<UUID> started = Set.of();
    private ServerLevel level;
    private long lastChange = Long.MIN_VALUE;
    private final Map<UUID, Long> warned = new HashMap<>();

    private LastManGame(ServerPlayer creator) {
        // abierto desde una base, la zona queda ahí: durante el evento no se puede romper, colocar ni abrir nada
        this.search = new SpotSearch(creator, TreasureFinder.Preset.CLASSIC, 0, CENTER_DISTANCE, 0).orInBases();
    }

    static LastManGame prepare(ServerPlayer creator) {
        return new LastManGame(creator);
    }

    void search() {
        if (search.tick() && search.spot() != null) {
            Pos s = search.spot();
            Huddle.LOGGER.info("Last one standing zone centered at {} {} {}", (int) Math.floor(s.x()), (int) Math.floor(s.y()), (int) Math.floor(s.z()));
        }
    }

    boolean ready() {
        return search.ready();
    }

    Component failure() {
        if (search.failure() == null) {
            return null;
        }
        return switch (search.failure()) {
            case NO_GROUND -> Component.translatableWithFallback("huddle.lastman.no_ground",
                    "The last one standing was cancelled: its creator was not on open ground.");
            case NO_SPOT -> Component.translatableWithFallback("huddle.lastman.no_spot",
                    "The last one standing was cancelled: there is no open ground nearby for the zone.");
        };
    }

    static LastMan.Params params() {
        List<Double> sizes = new ArrayList<>();
        for (Integer s : HuddleConfig.LASTMAN_ZONE_SIZES.get()) {
            sizes.add(s.doubleValue());
        }
        return new LastMan.Params(sizes, HuddleConfig.LASTMAN_HOLD_SECONDS.get() * 20L, HuddleConfig.LASTMAN_SHRINK_SECONDS.get() * 20L,
                HuddleConfig.LASTMAN_OUTSIDE_SECONDS.get() * 20L, HuddleConfig.LASTMAN_FINAL_SECONDS.get() * 20L);
    }

    void begin(MinecraftServer server, Lobbies.Running r) {
        level = search.level();
        Pos center = search.spot();
        long now = server.getTickCount();
        game = new LastMan(center, r.participants(), now, params());
        started = Set.copyOf(r.participants());
        List<ItemStack> kit = Kits.build(HuddleConfig.LASTMAN_KIT.get());
        // repartidos dentro de la zona, lejos unos de otros y lejos del borde, mirando al centro
        List<UUID> ids = List.copyOf(r.participants());
        List<Pos> spots = TreasureFinder.spread(search.grid(), search.from(), center, game.halfSize(now) * SPREAD_MARGIN, ids.size(), new Random());
        for (int i = 0; i < ids.size(); i++) {
            ServerPlayer p = server.getPlayerList().getPlayer(ids.get(i));
            if (p != null && i < spots.size()) {
                Pos s = spots.get(i);
                float yaw = (float) Math.toDegrees(Math.atan2(-(center.x() - s.x()), center.z() - s.z()));
                p.stopRiding();
                p.teleportTo(level, s.x(), s.y(), s.z(), yaw, 0);
            }
        }
        for (UUID id : ids) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                Kits.give(p, kit, r.lobby().id());
                Lobbies.title(p, Component.translatableWithFallback("huddle.event.go", "Go!"),
                        Component.translatableWithFallback("huddle.lastman.go", "Last one standing wins"));
            }
        }
        Lobbies.tellAll(r, server, Component.translatableWithFallback("huddle.lastman.started",
                "The zone is centered at %s %s. It shrinks every %s s; whoever stays outside for %s s is out.",
                (int) Math.floor(center.x()), (int) Math.floor(center.z()), HuddleConfig.LASTMAN_HOLD_SECONDS.get(),
                HuddleConfig.LASTMAN_OUTSIDE_SECONDS.get()).copy().withStyle(ChatFormatting.GOLD));
    }

    LastMan state() {
        return game;
    }

    UUID winner() {
        return game == null ? null : game.winner();
    }

    // la zona del cliente se mueve sola: solo se manda al cambiar de etapa o a quien recién la necesita
    private void showZone(ServerPlayer p, long now) {
        long next = game.nextChange(now);
        double from = game.halfSize(now);
        double to = game.halfSize(next);
        long millis = game.shrinking(now) ? (next - now) * 50L : 0;
        Pos c = game.center();
        Zones.show(p, c.x(), c.z(), from, to, millis);
    }

    // devuelve true cuando terminó
    boolean tick(MinecraftServer server, Lobbies.Running r, long now) {
        long change = game.nextChange(now);
        boolean newPhase = change != lastChange;
        lastChange = change;
        Map<UUID, Pos> where = new HashMap<>();
        for (UUID id : game.alive()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                continue;
            }
            // en otro mundo está fuera de la zona
            where.put(id, p.level() == level ? Tracks.pos(p) : null);
            if (p.level() == level && (newPhase || !Zones.has(id))) {
                showZone(p, now);
            }
        }
        List<UUID> out = game.tick(where, now);
        for (UUID id : game.alive()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            long left = game.outsideLeft(id, now);
            if (p == null) {
                continue;
            }
            if (left < 0) {
                warned.remove(id);
                continue;
            }
            if (warned.put(id, now) == null) {
                p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 30, 10));
                p.connection.send(new ClientboundSetTitleTextPacket(Component.translatableWithFallback("huddle.lastman.outside_title",
                        "Back to the zone!").copy().withStyle(ChatFormatting.RED)));
                p.connection.send(new ClientboundSetSubtitleTextPacket(Component.empty()));
                p.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 1f, 0.6f);
            }
            if (now % 5 == 0) {
                p.displayClientMessage(Component.translatableWithFallback("huddle.lastman.outside", "Outside the zone: %s s to get back",
                        (left + 19) / 20).copy().withStyle(ChatFormatting.RED), true);
            }
        }
        if (now % 10 == 0) {
            bar(r, now);
        }
        for (UUID id : out) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                p.sendSystemMessage(Component.translatableWithFallback("huddle.lastman.you_out", "You stayed outside the zone and are out.")
                        .withStyle(ChatFormatting.RED));
            }
            Lobbies.leave(server, id);
        }
        return game.over(now);
    }

    private void bar(Lobbies.Running r, long now) {
        long next = game.nextChange(now);
        int alive = game.alive().size();
        Component name;
        long truce = r.tag().pvpFrom() - now;
        if (truce > 0) {
            name = Component.translatableWithFallback("huddle.lastman.bar_truce", "%s: %s left, fighting starts in %s", Lobbies.name(r.type()),
                    alive, minutes(truce));
        } else if (next >= game.endTick() && !game.shrinking(now)) {
            name = Component.translatableWithFallback("huddle.lastman.bar_final", "%s: %s left, final zone, %s to go", Lobbies.name(r.type()),
                    alive, minutes(game.endTick() - now));
        } else if (game.shrinking(now)) {
            name = Component.translatableWithFallback("huddle.lastman.bar_shrinking", "%s: %s left, the zone is shrinking", Lobbies.name(r.type()),
                    alive);
        } else {
            name = Component.translatableWithFallback("huddle.lastman.bar_hold", "%s: %s left, the zone shrinks in %s", Lobbies.name(r.type()),
                    alive, minutes(next - now));
        }
        r.bar().setName(name);
        r.bar().setProgress(Math.max(0, Math.min(1, alive / (float) Math.max(1, r.lobby().players().size()))));
    }

    // al salir por cualquier motivo (muerte, descalificado, /event leave, desconexión larga)
    void onLeave(MinecraftServer server, UUID id, Lobbies.Running r) {
        boolean wasAlive = game != null && game.alive().contains(id);
        if (game != null) {
            game.eliminate(id);
        }
        warned.remove(id);
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) {
            Kits.strip(p);
            Zones.hide(p);
        } else {
            Zones.forget(id);
        }
        // la zona ya lo sacó de los vivos antes de llegar aquí
        if (started.contains(id) && p != null) {
            Lobbies.endTitle(p, Component.translatableWithFallback("huddle.lastman.you_are_out", "You're out").copy().withStyle(ChatFormatting.RED),
                    game.alive().size() > 1 ? Component.translatableWithFallback("huddle.lastman.left", "%s left", game.alive().size()) : Component.empty());
        }
        if (wasAlive && game.alive().size() > 1) {
            Lobbies.tellAll(r, server, Component.translatableWithFallback("huddle.lastman.eliminated", "%s is out. %s left.",
                    p == null ? "?" : p.getGameProfile().getName(), game.alive().size()));
        }
    }

    // los que empezaron, también los eliminados: ven cómo terminó
    Set<UUID> started() {
        return started;
    }

    Component summary(MinecraftServer server) {
        UUID w = winner();
        if (w == null) {
            return Component.translatableWithFallback("huddle.lastman.nobody", "The last one standing is over. Nobody won.");
        }
        ServerPlayer p = server.getPlayerList().getPlayer(w);
        return Component.translatableWithFallback("huddle.lastman.won", "%s is the last one standing!",
                p == null ? "?" : p.getGameProfile().getName()).copy().withStyle(ChatFormatting.GOLD);
    }

    // primero se retira el kit y después llega el premio, así el inventario tiene lugar
    void end(MinecraftServer server, Set<UUID> members) {
        for (UUID id : members) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                Kits.strip(p);
                Zones.hide(p);
            } else {
                Zones.forget(id);
            }
        }
        UUID w = winner();
        ServerPlayer p = w == null ? null : server.getPlayerList().getPlayer(w);
        if (p != null) {
            Pos c = game.center();
            Loot.give(p, HuddleConfig.LASTMAN_LOOT.get(), new Vec3(c.x(), c.y(), c.z()));
        }
    }

    private static String minutes(long ticks) {
        long s = (Math.max(0, ticks) + 19) / 20;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
