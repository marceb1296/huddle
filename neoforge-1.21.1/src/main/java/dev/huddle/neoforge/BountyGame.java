package dev.huddle.neoforge;

import dev.huddle.core.BountyHunt;
import dev.huddle.core.Pos;
import dev.huddle.core.TreasureHunt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

// recompensa (cacería): un participante al azar es el marcado y los demás lo cazan. La maneja Lobbies
// desde el hilo del servidor
final class BountyGame {
    static final int TEAM_MARKED = 1;
    static final int TEAM_HUNTERS = 2;
    static final int NEAR_GLOW = 32;
    static final long HIT_WINDOW = 200;

    private final BountyHunt hunt;
    private final UUID marked;
    private final String markedName;
    private final long startTick;
    private final Set<UUID> glowing = new HashSet<>();
    private ResourceKey<Level> lastLevel;
    private Pos lastPos;
    private boolean wasExposed;
    private boolean wasDead;

    private BountyGame(BountyHunt hunt, ServerPlayer marked, long now) {
        this.hunt = hunt;
        this.marked = marked.getUUID();
        this.markedName = marked.getGameProfile().getName();
        this.startTick = now;
        this.lastLevel = marked.level().dimension();
        this.lastPos = Tracks.pos(marked);
    }

    // null si nadie del sorteo está conectado
    static BountyGame begin(MinecraftServer server, Lobbies.Running r) {
        List<UUID> online = new ArrayList<>();
        for (UUID id : r.participants()) {
            if (server.getPlayerList().getPlayer(id) != null) {
                online.add(id);
            }
        }
        UUID pick = BountyHunt.pick(online, new Random());
        if (pick == null) {
            return null;
        }
        long now = server.getTickCount();
        ServerPlayer target = server.getPlayerList().getPlayer(pick);
        BountyGame g = new BountyGame(new BountyHunt(pick, r.participants(), now, new BountyHunt.Params(
                HuddleConfig.BOUNTY_MAX_SECONDS.get() * 20L, HuddleConfig.BOUNTY_CAMP_SECONDS.get() * 20L, HIT_WINDOW,
                HuddleConfig.BOUNTY_DEATH_EXPOSE_SECONDS.get() * 20L)), target, now);
        String time = minutes(HuddleConfig.BOUNTY_MAX_SECONDS.get() * 20L);
        for (UUID id : r.participants()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                continue;
            }
            boolean isMarked = id.equals(pick);
            // los cazadores no se dañan entre sí
            Participants.join(p, r.tag().withTeam(isMarked ? TEAM_MARKED : TEAM_HUNTERS));
            if (isMarked) {
                title(p, Component.translatableWithFallback("huddle.bounty.you_title", "You are the target!"),
                        Component.translatableWithFallback("huddle.bounty.you_subtitle", "Survive %s", time));
            } else {
                title(p, Component.translatableWithFallback("huddle.bounty.hunt_title", "Hunt %s!", g.markedName),
                        Component.translatableWithFallback("huddle.bounty.hunt_subtitle", "%s to catch the target", time));
            }
        }
        Lobbies.tellAll(r, server, Component.translatableWithFallback("huddle.bounty.announce",
                "%s is the target. The hunters see where with more precision as time goes by.", g.markedName).copy()
                .withStyle(ChatFormatting.GOLD));
        Huddle.LOGGER.info("Bounty: {} is the target", g.markedName);
        return g;
    }

    UUID marked() {
        return marked;
    }

    UUID winner() {
        return hunt.winner();
    }

    BountyHunt hunt() {
        return hunt;
    }

    void onHit(ServerPlayer attacker, long now) {
        hunt.hit(attacker.getUUID(), now);
    }

    void onMarkedDeath(UUID killer, long now) {
        hunt.markedDied(killer, now, HuddleConfig.KEEP_INVENTORY_ON_DEATH.get());
    }

    // si sale el marcado, gana el cazador más cercano a donde estaba (o el que le pegó hace poco)
    void onLeave(MinecraftServer server, UUID id, long now) {
        UUID nearest = null;
        if (id.equals(marked)) {
            // pudo moverse en este mismo tick antes de salir
            ServerPlayer target = server.getPlayerList().getPlayer(marked);
            if (target != null) {
                lastLevel = target.level().dimension();
                lastPos = Tracks.pos(target);
            }
            double best = Double.MAX_VALUE;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (hunt.hunter(p.getUUID()) && p.level().dimension() == lastLevel) {
                    double d = Tracks.pos(p).distSq(lastPos);
                    if (d < best) {
                        best = d;
                        nearest = p.getUUID();
                    }
                }
            }
        }
        hunt.leave(id, nearest, now);
        unglow(server, id);
    }

    // devuelve true cuando terminó
    boolean tick(MinecraftServer server, Lobbies.Running r, long now) {
        ServerPlayer target = server.getPlayerList().getPlayer(marked);
        if (target != null) {
            lastLevel = target.level().dimension();
            lastPos = Tracks.pos(target);
            hunt.markedAt(target.chunkPosition().x, target.chunkPosition().z, now);
        }
        boolean exposed = hunt.exposed(now);
        boolean died = hunt.diedRecently(now);
        if (died && !wasDead) {
            Lobbies.tellAll(r, server, Component.translatableWithFallback("huddle.bounty.died_exposed",
                    "%s died without being caught: the hunters can see the target for %s seconds.", markedName,
                    HuddleConfig.BOUNTY_DEATH_EXPOSE_SECONDS.get()).copy().withStyle(ChatFormatting.RED));
        }
        wasDead = died;
        if (exposed && !wasExposed && !died) {
            Lobbies.tellAll(r, server, Component.translatableWithFallback("huddle.bounty.exposed",
                    "%s stayed in one place too long: the hunters can see the target now.", markedName).copy()
                    .withStyle(ChatFormatting.RED));
        }
        wasExposed = exposed;
        int step = hunt.rounding(now);
        for (UUID id : r.participants()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || id.equals(marked)) {
                continue;
            }
            boolean sameWorld = target != null && p.level() == target.level();
            boolean show = sameWorld && (exposed || p.distanceToSqr(target) <= NEAR_GLOW * NEAR_GLOW);
            if (show && glowing.add(id)) {
                Glow.show(p, target);
            } else if (!show && glowing.remove(id) && target != null) {
                Glow.hide(p, target);
            }
            if (now % 10 == 0) {
                p.displayClientMessage(where(p, target, sameWorld, step), true);
            }
        }
        if (target != null && now % 10 == 0) {
            long left = Math.max(0, startTick + HuddleConfig.BOUNTY_MAX_SECONDS.get() * 20L - now);
            target.displayClientMessage(exposed
                    ? Component.translatableWithFallback("huddle.bounty.you_exposed", "Move! The hunters can see you. %s left",
                            minutes(left)).copy().withStyle(ChatFormatting.RED)
                    : Component.translatableWithFallback("huddle.bounty.you_status", "Survive: %s left", minutes(left)), true);
        }
        if (now % 20 == 0) {
            long end = startTick + HuddleConfig.BOUNTY_MAX_SECONDS.get() * 20L;
            r.bar().setName(Component.translatableWithFallback("huddle.bounty.bar", "%s on %s: %s left", Lobbies.name(r.type()),
                    markedName, minutes(Math.max(0, end - now))));
            r.bar().setProgress(Math.max(0, Math.min(1, (end - now) / (float) Math.max(1, end - startTick))));
        }
        return hunt.outcome(now) != BountyHunt.Outcome.RUNNING;
    }

    private static Component where(ServerPlayer hunter, ServerPlayer target, boolean sameWorld, int step) {
        if (target == null) {
            return Component.translatableWithFallback("huddle.bounty.offline", "The target is offline.");
        }
        if (!sameWorld) {
            return Component.translatableWithFallback("huddle.bounty.other_world", "The target is in another world.");
        }
        Pos from = Tracks.pos(hunter), to = Tracks.pos(target);
        double dist = Math.hypot(to.x() - from.x(), to.z() - from.z());
        int d = BountyHunt.round(dist, step);
        Component h = TreasureGame.heading(TreasureHunt.heading(from, to));
        if (BountyHunt.near(dist, step)) {
            return Component.translatableWithFallback("huddle.bounty.where_near", "Target: less than %s blocks to the %s", step, h);
        }
        return step <= 1
                ? Component.translatableWithFallback("huddle.bounty.where_exact", "Target: %s blocks to the %s", d, h)
                : Component.translatableWithFallback("huddle.bounty.where", "Target: about %s blocks to the %s", d, h);
    }

    Component summary(MinecraftServer server) {
        return switch (hunt.outcome(server.getTickCount())) {
            case SURVIVED -> Component.translatableWithFallback("huddle.bounty.survived", "%s survived the bounty!", markedName)
                    .copy().withStyle(ChatFormatting.GOLD);
            case CAUGHT -> Component.translatableWithFallback("huddle.bounty.caught", "%s caught %s!", nameOf(server, hunt.winner()), markedName)
                    .copy().withStyle(ChatFormatting.GOLD);
            default -> Component.translatableWithFallback("huddle.bounty.no_winner", "The bounty is over with no winner.");
        };
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer p = id == null ? null : server.getPlayerList().getPlayer(id);
        return p == null ? "?" : p.getGameProfile().getName();
    }

    private void unglow(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        ServerPlayer target = server.getPlayerList().getPlayer(marked);
        if (glowing.remove(id) && p != null && target != null) {
            Glow.hide(p, target);
        }
    }

    void end(MinecraftServer server, Set<UUID> members) {
        for (UUID id : List.copyOf(glowing)) {
            unglow(server, id);
        }
        glowing.clear();
        BountyHunt.Outcome outcome = hunt.outcome(server.getTickCount());
        // el marcado atrapado ya salió de la lista, pero también ve cómo terminó
        Set<UUID> told = new java.util.LinkedHashSet<>(members);
        told.add(marked);
        String winnerName = nameOf(server, hunt.winner());
        for (UUID id : told) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                continue;
            }
            if (id.equals(hunt.winner())) {
                p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
            }
            // el título grande no salta de línea: corto, y el detalle en el subtítulo
            Component well = Component.translatableWithFallback("huddle.event.well_done", "Well done");
            if (outcome == BountyHunt.Outcome.CAUGHT) {
                if (id.equals(hunt.winner())) {
                    Lobbies.endTitle(p, Component.translatableWithFallback("huddle.bounty.you_caught", "Target caught!").copy()
                            .withStyle(ChatFormatting.GOLD), well);
                } else if (id.equals(marked)) {
                    Lobbies.endTitle(p, Component.translatableWithFallback("huddle.bounty.you_were_caught", "You were caught").copy()
                            .withStyle(ChatFormatting.RED), Component.translatableWithFallback("huddle.bounty.caught_you", "%s caught you", winnerName));
                } else {
                    Lobbies.endTitle(p, Component.literal(winnerName).withStyle(ChatFormatting.GOLD),
                            Component.translatableWithFallback("huddle.bounty.caught_sub", "caught %s", markedName));
                }
            } else if (outcome == BountyHunt.Outcome.SURVIVED) {
                if (id.equals(marked)) {
                    Lobbies.endTitle(p, Component.translatableWithFallback("huddle.bounty.you_survived", "You survived!").copy()
                            .withStyle(ChatFormatting.GOLD), well);
                } else {
                    Lobbies.endTitle(p, Component.literal(markedName).withStyle(ChatFormatting.GOLD),
                            Component.translatableWithFallback("huddle.bounty.survived_sub", "survived"));
                }
            }
        }
    }

    private static String minutes(long ticks) {
        long s = (ticks + 19) / 20;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static void title(ServerPlayer p, Component title, Component subtitle) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 50, 10));
        p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }
}
