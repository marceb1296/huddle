package dev.huddle.neoforge;

import dev.huddle.core.Airdrop;
import dev.huddle.core.Pos;
import dev.huddle.core.TreasureFinder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

// suministro aéreo: mientras la sala está abierta se elige dónde cae; al empezar una caja con paracaídas baja
// despacio y, en el suelo, la abre quien se queda solo a su lado. La maneja Lobbies desde el hilo del servidor
final class AirdropGame {
    static final double REACH = 3;
    static final double DROP_HEIGHT = 60;

    private final SpotSearch search;
    private Airdrop drop;
    private ServerLevel level;
    private UUID crate;
    private UUID canopy;
    private final Set<UUID> hurt = new HashSet<>();
    private final Map<UUID, Integer> glowing = new HashMap<>();
    private boolean landedSaid;
    private boolean rewarded;
    private long startTick;

    private AirdropGame(ServerPlayer creator, TreasureFinder.Preset preset) {
        this.search = new SpotSearch(creator, preset, HuddleConfig.AIRDROP_MIN_DISTANCE.get(), HuddleConfig.AIRDROP_MAX_DISTANCE.get(), 0);
    }

    static AirdropGame prepare(ServerPlayer creator, TreasureFinder.Preset preset) {
        return new AirdropGame(creator, preset);
    }

    static Component presetName(TreasureFinder.Preset preset) {
        return preset == TreasureFinder.Preset.PEAK
                ? Component.translatableWithFallback("huddle.airdrop.preset.peak", "peak")
                : Component.translatableWithFallback("huddle.airdrop.preset.single", "single");
    }

    TreasureFinder.Preset preset() {
        return search.preset();
    }

    void search() {
        if (search.tick() && search.spot() != null) {
            Pos s = search.spot();
            Huddle.LOGGER.info("Airdrop ({}) will land at {} {} {}", search.preset(), (int) Math.floor(s.x()), (int) Math.floor(s.y()),
                    (int) Math.floor(s.z()));
        }
    }

    boolean ready() {
        return search.ready();
    }

    Pos spot() {
        return search.spot();
    }

    Component failure() {
        if (search.failure() == null) {
            return null;
        }
        return switch (search.failure()) {
            case NO_GROUND -> Component.translatableWithFallback("huddle.airdrop.no_ground",
                    "The airdrop was cancelled: its creator was not on open ground.");
            case NO_SPOT -> Component.translatableWithFallback("huddle.airdrop.no_spot",
                    "The airdrop was cancelled: there is no reachable open ground %s to %s blocks away.",
                    HuddleConfig.AIRDROP_MIN_DISTANCE.get(), search.maxDistance());
        };
    }

    void begin(MinecraftServer server, Lobbies.Running r) {
        level = search.level();
        Pos spot = search.spot();
        startTick = server.getTickCount();
        drop = new Airdrop(spot, r.participants(), startTick, new Airdrop.Params(HuddleConfig.AIRDROP_FALL_SECONDS.get() * 20L,
                DROP_HEIGHT, HuddleConfig.AIRDROP_OPEN_SECONDS.get() * 20L, HuddleConfig.AIRDROP_GROUND_SECONDS.get() * 20L, REACH));
        Pos at = drop.position(startTick);
        crate = Displays.spawn(level, block("minecraft:barrel", Displays.transformation(-0.5f, 0, -0.5f, 1, 1, 1)), at.x(), at.y(), at.z());
        canopy = Displays.spawn(level, block("minecraft:white_wool", Displays.transformation(-1.5f, 3, -1.5f, 3, 0.4f, 3)), at.x(), at.y(), at.z());
        Lobbies.tellAll(r, server, Component.translatableWithFallback("huddle.airdrop.falling",
                "A supply crate is coming down at %s %s %s. Stay next to it, alone and unhurt, for %s s to open it.",
                (int) Math.floor(spot.x()), (int) Math.floor(spot.y()), (int) Math.floor(spot.z()), HuddleConfig.AIRDROP_OPEN_SECONDS.get())
                .copy().withStyle(ChatFormatting.GOLD));
    }

    private static CompoundTag block(String id, CompoundTag transformation) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        CompoundTag state = new CompoundTag();
        state.putString("Name", id);
        tag.put("block_state", state);
        tag.put("transformation", transformation);
        tag.putInt("teleport_duration", 2);
        tag.putInt("glow_color_override", 0xFFAA00);
        return tag;
    }

    void hurt(UUID player) {
        hurt.add(player);
    }

    UUID winner() {
        return drop == null ? null : drop.winner();
    }

    List<UUID> displayIds() {
        return crate == null ? List.of() : canopy == null ? List.of(crate) : List.of(crate, canopy);
    }

    Airdrop state() {
        return drop;
    }

    // devuelve true cuando terminó
    boolean tick(MinecraftServer server, Lobbies.Running r, long now) {
        Pos at = drop.position(now);
        for (UUID id : displayIds()) {
            Entity e = level.getEntity(id);
            if (e != null && (e.getY() != at.y() || e.getX() != at.x() || e.getZ() != at.z())) {
                e.teleportTo(at.x(), at.y(), at.z());
            }
        }
        boolean landed = drop.landed(now);
        if (landed && !landedSaid) {
            landedSaid = true;
            Displays.discard(level, canopy);
            canopy = null;
            level.playSound(null, at.x(), at.y(), at.z(), SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.6f, 0.8f);
            Lobbies.tellAll(r, server, Component.translatableWithFallback("huddle.airdrop.landed", "The supply crate landed!")
                    .copy().withStyle(ChatFormatting.GOLD));
        }
        Map<UUID, Pos> where = new HashMap<>();
        Entity box = level.getEntity(crate);
        for (UUID id : r.participants()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                continue;
            }
            if (p.level() == level) {
                where.put(id, Tracks.pos(p));
                if (now % (landed ? 10 : 4) == 0) {
                    level.sendParticles(p, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, at.x(), at.y() + 1.2, at.z(), 1, 0.1, 0.1, 0.1, 0.01);
                }
            }
            if (box != null && p.level() == level && !Integer.valueOf(box.getId()).equals(glowing.get(id))) {
                glowing.put(id, box.getId());
                Glow.show(p, box);
            }
        }
        drop.tick(where, hurt, now);
        hurt.clear();
        UUID holder = drop.holder();
        if (holder != null && now % 5 == 0) {
            ServerPlayer h = server.getPlayerList().getPlayer(holder);
            if (h != null) {
                h.displayClientMessage(Component.translatableWithFallback("huddle.airdrop.opening", "Opening the crate: %s%%",
                        (int) Math.round(drop.progress() * 100)).copy().withStyle(ChatFormatting.GREEN), true);
            }
        }
        if (now % 10 == 0) {
            bar(server, r, now, landed);
        }
        if (drop.winner() != null && !rewarded) {
            rewarded = true;
            reward(server);
        }
        return drop.over(now);
    }

    private void bar(MinecraftServer server, Lobbies.Running r, long now, boolean landed) {
        long landTick = startTick + HuddleConfig.AIRDROP_FALL_SECONDS.get() * 20L;
        long end = landTick + HuddleConfig.AIRDROP_GROUND_SECONDS.get() * 20L;
        UUID holder = drop.holder();
        ServerPlayer h = holder == null ? null : server.getPlayerList().getPlayer(holder);
        Component name;
        if (!landed) {
            name = Component.translatableWithFallback("huddle.airdrop.bar_falling", "%s: lands in %s", Lobbies.name(r.type()),
                    minutes(landTick - now));
            r.bar().setProgress(Math.max(0, Math.min(1, (landTick - now) / (float) Math.max(1, landTick - startTick))));
        } else if (h != null) {
            name = Component.translatableWithFallback("huddle.airdrop.bar_opening", "%s: %s is opening it (%s%%)", Lobbies.name(r.type()),
                    h.getGameProfile().getName(), (int) Math.round(drop.progress() * 100));
            r.bar().setProgress((float) drop.progress());
        } else {
            name = Component.translatableWithFallback("huddle.airdrop.bar_landed", "%s: %s left to open it", Lobbies.name(r.type()),
                    minutes(Math.max(0, end - now)));
            r.bar().setProgress(Math.max(0, Math.min(1, (end - now) / (float) Math.max(1, end - landTick))));
        }
        r.bar().setName(name);
    }

    private void reward(MinecraftServer server) {
        ServerPlayer p = server.getPlayerList().getPlayer(drop.winner());
        if (p == null) {
            return;
        }
        Pos s = drop.landing();
        int n = Loot.give(p, HuddleConfig.AIRDROP_LOOT.get(), new Vec3(s.x(), s.y(), s.z()));
        Huddle.LOGGER.info("Airdrop opened by {}: {} stacks", p.getGameProfile().getName(), Math.max(0, n));
    }

    void onLeave(MinecraftServer server, UUID id) {
        if (drop != null) {
            drop.leave(id);
        }
        unglow(server, id);
    }

    private void unglow(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        Entity box = level == null || crate == null ? null : level.getEntity(crate);
        if (glowing.remove(id) != null && p != null && box != null) {
            Glow.hide(p, box);
        }
    }

    Component summary(MinecraftServer server) {
        UUID w = winner();
        if (w == null) {
            return Component.translatableWithFallback("huddle.airdrop.nobody", "The airdrop is over. Nobody opened the crate.");
        }
        ServerPlayer p = server.getPlayerList().getPlayer(w);
        return Component.translatableWithFallback("huddle.airdrop.opened", "%s opened the supply crate!",
                p == null ? "?" : p.getGameProfile().getName()).copy().withStyle(ChatFormatting.GOLD);
    }

    void end(MinecraftServer server, Set<UUID> members) {
        for (UUID id : Set.copyOf(glowing.keySet())) {
            unglow(server, id);
        }
        glowing.clear();
        if (level != null) {
            for (UUID id : displayIds()) {
                Displays.discard(level, id);
            }
        }
        crate = null;
        canopy = null;
    }

    private static String minutes(long ticks) {
        long s = (Math.max(0, ticks) + 19) / 20;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
