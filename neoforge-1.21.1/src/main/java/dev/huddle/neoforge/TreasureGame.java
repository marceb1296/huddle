package dev.huddle.neoforge;

import dev.huddle.core.Pos;
import dev.huddle.core.TreasureFinder;
import dev.huddle.core.TreasureHunt;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.joml.Vector3f;

// búsqueda del tesoro: mientras la sala está abierta se copia el terreno cargado alrededor del creador y se elige
// el lugar; al empezar aparece un cofre de display con una caja de clic. La maneja Lobbies desde el hilo del servidor
final class TreasureGame {
    static final double REACH = 4.5;
    static final int ZONE_RADIUS = 24;
    static final int BEAM_HEIGHT = 30;
    private static final int GLOW_RANGE = 24;
    private static final int RING_HEIGHT = 25;
    private static final DustParticleOptions SKY_DUST = new DustParticleOptions(new Vector3f(1f, 0.84f, 0f), 4f);
    private static final DustParticleOptions BEAM_DUST = new DustParticleOptions(new Vector3f(1f, 0.84f, 0f), 2f);
    static final int RECENT = 3;
    // últimos lugares por dimensión, para no repetir la zona en las búsquedas siguientes (solo en memoria)
    private static final Map<ResourceKey<Level>, Deque<Pos>> recent = new HashMap<>();

    private final TreasureFinder.Preset preset;
    private final ServerLevel level;
    private final SpotSearch search;
    private Component biome;
    private double ringY;

    private TreasureHunt hunt;
    private Pos zone;
    private List<Pos> beam = List.of();
    private UUID chest;
    private UUID hitbox;
    private final Set<UUID> glowing = new HashSet<>();
    private long startTick;
    private int cluesGiven;

    private TreasureGame(ServerPlayer creator, TreasureFinder.Preset preset) {
        this.preset = preset;
        this.level = creator.serverLevel();
        int min = HuddleConfig.TREASURE_MIN_DISTANCE.get(), max = HuddleConfig.TREASURE_MAX_DISTANCE.get();
        this.search = new SpotSearch(creator, preset, min, max, preset == TreasureFinder.Preset.CAVES ? HuddleConfig.TREASURE_CAVE_DEPTH.get() : 0,
                List.copyOf(recent.getOrDefault(level.dimension(), new ArrayDeque<>())), (Math.min(max, HuddleConfig.GENERATOR_RADIUS.get()) - min) / 2.0);
    }

    static TreasureGame prepare(ServerPlayer creator, TreasureFinder.Preset preset) {
        return new TreasureGame(creator, preset);
    }

    static void forget() {
        recent.clear();
    }

    static Component presetName(TreasureFinder.Preset preset) {
        return switch (preset) {
            case CLASSIC -> Component.translatableWithFallback("huddle.treasure.preset.classic", "classic");
            case CAVES -> Component.translatableWithFallback("huddle.treasure.preset.caves", "caves");
            case PEAK -> Component.translatableWithFallback("huddle.airdrop.preset.peak", "peak");
        };
    }

    TreasureFinder.Preset preset() {
        return preset;
    }

    // un tick de lectura mientras la sala está abierta
    void search() {
        if (search.tick() && search.spot() != null) {
            Pos s = search.spot();
            BlockPos at = BlockPos.containing(s.x(), s.y(), s.z());
            // el bioma sale del generador si el chunk ya no está cargado: no carga nada
            biome = biomeName(level.getBiome(at));
            ringY = search.surface(s) + RING_HEIGHT;
            Huddle.LOGGER.info("Treasure ({}) hidden at {} {} {}", preset, at.getX(), at.getY(), at.getZ());
        }
    }

    boolean ready() {
        return search.ready();
    }

    // null mientras busca o si encontró lugar
    Component failure() {
        if (search.failure() == null) {
            return null;
        }
        int max = search.maxDistance();
        return switch (search.failure()) {
            case NO_GROUND -> Component.translatableWithFallback("huddle.treasure.no_ground",
                    "The treasure hunt was cancelled: its creator was not on open ground.");
            case NO_SPOT -> preset == TreasureFinder.Preset.CAVES
                    ? Component.translatableWithFallback("huddle.treasure.no_cave",
                    "The treasure hunt was cancelled: there are no caves under reachable ground %s to %s blocks away.",
                    HuddleConfig.TREASURE_MIN_DISTANCE.get(), max)
                    : Component.translatableWithFallback("huddle.treasure.no_spot",
                    "The treasure hunt was cancelled: there is no reachable open ground %s to %s blocks away.",
                    HuddleConfig.TREASURE_MIN_DISTANCE.get(), max);
        };
    }

    void begin(MinecraftServer server, Lobbies.Running r) {
        Pos spot = search.spot();
        Deque<Pos> last = recent.computeIfAbsent(level.dimension(), k -> new ArrayDeque<>());
        last.addFirst(spot);
        while (last.size() > RECENT) {
            last.removeLast();
        }
        startTick = server.getTickCount();
        hunt = new TreasureHunt(spot, r.participants(), startTick, new TreasureHunt.Params(
                HuddleConfig.TREASURE_CLUE_SECONDS.get() * 20L, HuddleConfig.TREASURE_MAX_SECONDS.get() * 20L, REACH));
        zone = TreasureHunt.zoneCenter(spot, ZONE_RADIUS, new Random());
        beam = beam();
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:item_display");
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "minecraft:chest");
        stack.putInt("count", 1);
        item.put("item", stack);
        item.putFloat("view_range", 0.375f);
        item.putInt("glow_color_override", 0xFFD700);
        item.put("transformation", Displays.transformation(0, 1));
        chest = Displays.spawn(level, item, spot.x(), spot.y() + 0.5, spot.z());
        CompoundTag box = new CompoundTag();
        box.putString("id", "minecraft:interaction");
        box.putFloat("width", 1.0f);
        box.putFloat("height", 1.0f);
        box.putBoolean("response", true);
        hitbox = Displays.spawn(level, box, spot.x(), spot.y(), spot.z());
    }

    boolean isChest(Entity e) {
        return e.getUUID().equals(hitbox) || e.getUUID().equals(chest);
    }

    TreasureHunt.Claim claim(ServerPlayer p) {
        if (hunt == null || p.level() != level) {
            return TreasureHunt.Claim.TOO_FAR;
        }
        return hunt.claim(p.getUUID(), new Pos(p.getX(), p.getEyeY(), p.getZ()), p.server.getTickCount());
    }

    Pos spot() {
        return search.spot();
    }

    // columna de la cuarta pista: sube desde el cofre; en una cueva, desde el suelo de arriba
    List<Pos> beam() {
        Pos spot = search.spot();
        if (spot == null) {
            return List.of();
        }
        double from = preset == TreasureFinder.Preset.CAVES ? search.surface(spot) : spot.y() + 1;
        List<Pos> out = new ArrayList<>();
        for (int k = 0; k <= BEAM_HEIGHT * 2; k++) {
            out.add(new Pos(spot.x(), from + k * 0.5, spot.z()));
        }
        return out;
    }

    UUID winner() {
        return hunt == null ? null : hunt.winner();
    }

    List<UUID> displayIds() {
        return chest == null ? List.of() : List.of(chest, hitbox);
    }

    // devuelve true cuando terminó
    boolean tick(MinecraftServer server, Lobbies.Running r, long now) {
        for (int clue : hunt.cluesDue(now)) {
            cluesGiven = clue + 1;
            for (UUID id : r.participants()) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) {
                    clue(p, clue);
                }
            }
        }
        Entity box = level.getEntity(chest);
        for (UUID id : r.participants()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                continue;
            }
            boolean near = box != null && p.level() == level && p.distanceToSqr(box) <= GLOW_RANGE * GLOW_RANGE;
            if (near && glowing.add(id)) {
                Glow.show(p, box);
            } else if (!near && glowing.remove(id) && box != null) {
                Glow.hide(p, box);
            }
            if (cluesGiven >= 3 && now % 5 == 0 && p.level() == level) {
                // la tercera pista es solo el anillo del cielo; la cuarta lo cambia por la columna sobre el cofre
                if (cluesGiven >= 4) {
                    beam(p);
                } else {
                    ring(p);
                }
            }
        }
        if (now % 20 == 0) {
            long end = startTick + HuddleConfig.TREASURE_MAX_SECONDS.get() * 20L;
            r.bar().setName(Component.translatableWithFallback("huddle.treasure.bar", "%s: %s left", Lobbies.name(r.type()),
                    minutes(Math.max(0, end - now))));
            r.bar().setProgress(Math.max(0, Math.min(1, (end - now) / (float) Math.max(1, end - startTick))));
        }
        return hunt.over(now);
    }

    private void clue(ServerPlayer p, int clue) {
        Pos spot = hunt.spot();
        Pos at = Tracks.pos(p);
        boolean here = p.level() == level;
        Component text = switch (clue) {
            case 0 -> here
                    ? Component.translatableWithFallback("huddle.treasure.clue1",
                            "Clue 1/4: the treasure is %s of you (right now, %s), in this biome: %s.",
                            heading(TreasureHunt.heading(at, spot)), side(p, at, spot), biome)
                    : Component.translatableWithFallback("huddle.treasure.clue1_away", "Clue 1/4: the treasure is in this biome: %s.", biome);
            case 1 -> here
                    ? Component.translatableWithFallback("huddle.treasure.clue2",
                            "Clue 2/4: it is about %s blocks from you, to the %s (right now, %s).",
                            TreasureHunt.roundedDistance(at, spot), heading(TreasureHunt.heading(at, spot)), side(p, at, spot))
                    : Component.translatableWithFallback("huddle.treasure.clue2_away", "Clue 2/4: come back to the hunt's world.");
            case 2 -> Component.translatableWithFallback("huddle.treasure.clue3",
                    "Clue 3/4: look for the ring of particles in the sky. The treasure is under it, %s blocks around its center.",
                    ZONE_RADIUS);
            default -> preset == TreasureFinder.Preset.CAVES
                    ? Component.translatableWithFallback("huddle.treasure.clue4_cave",
                            "Clue 4/4: it is underground, right under the column of particles, about %s blocks deep.",
                            Math.max(1, search.surface(spot) - (int) Math.floor(spot.y())))
                    : Component.translatableWithFallback("huddle.treasure.clue4", "Clue 4/4: the chest is at the foot of the column of particles.");
        };
        p.sendSystemMessage(text.copy().withStyle(ChatFormatting.GOLD));
        p.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1f, 1.2f);
        if (clue == 0) {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 40, 10));
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatableWithFallback("huddle.treasure.subtitle",
                    "First to open the chest wins")));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.translatableWithFallback("huddle.event.go", "Go!")));
        }
    }

    private void ring(ServerPlayer p) {
        int n = 64;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            double x = zone.x() + Math.cos(a) * ZONE_RADIUS;
            double z = zone.z() + Math.sin(a) * ZONE_RADIUS;
            level.sendParticles(p, ParticleTypes.END_ROD, true, x, ringY, z, 1, 0, 0, 0, 0);
            level.sendParticles(p, SKY_DUST, true, x, ringY, z, 1, 0, 0, 0, 0);
        }
    }

    private void beam(ServerPlayer p) {
        for (Pos pt : beam) {
            level.sendParticles(p, BEAM_DUST, true, pt.x(), pt.y(), pt.z(), 1, 0, 0, 0, 0);
        }
    }

    private static Component side(ServerPlayer p, Pos at, Pos spot) {
        return switch (TreasureHunt.side(at, p.getYRot(), spot)) {
            case AHEAD -> Component.translatableWithFallback("huddle.side.ahead", "straight ahead");
            case AHEAD_RIGHT -> Component.translatableWithFallback("huddle.side.ahead_right", "ahead to your right");
            case RIGHT -> Component.translatableWithFallback("huddle.side.right", "to your right");
            case BEHIND_RIGHT -> Component.translatableWithFallback("huddle.side.behind_right", "behind you to the right");
            case BEHIND -> Component.translatableWithFallback("huddle.side.behind", "behind you");
            case BEHIND_LEFT -> Component.translatableWithFallback("huddle.side.behind_left", "behind you to the left");
            case LEFT -> Component.translatableWithFallback("huddle.side.left", "to your left");
            case AHEAD_LEFT -> Component.translatableWithFallback("huddle.side.ahead_left", "ahead to your left");
        };
    }

    void onLeave(MinecraftServer server, UUID id) {
        if (hunt != null) {
            hunt.leave(id);
        }
        unglow(server, id);
    }

    private void unglow(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        Entity box = chest == null ? null : level.getEntity(chest);
        if (glowing.remove(id) && p != null && box != null) {
            Glow.hide(p, box);
        }
    }

    Component summary(MinecraftServer server) {
        UUID w = winner();
        if (w == null) {
            Pos s = hunt == null ? null : hunt.spot();
            return s == null
                    ? Component.translatableWithFallback("huddle.treasure.nobody", "The treasure hunt is over. Nobody found the chest.")
                    : Component.translatableWithFallback("huddle.treasure.nobody_at",
                            "The treasure hunt is over. Nobody found the chest; it was at %s %s %s.",
                            (int) Math.floor(s.x()), (int) Math.floor(s.y()), (int) Math.floor(s.z()));
        }
        ServerPlayer p = server.getPlayerList().getPlayer(w);
        return Component.translatableWithFallback("huddle.treasure.found", "%s found the treasure!",
                p == null ? "?" : p.getGameProfile().getName()).copy().withStyle(ChatFormatting.GOLD);
    }

    void end(MinecraftServer server, Set<UUID> members) {
        for (UUID id : members) {
            unglow(server, id);
        }
        glowing.clear();
        if (chest != null) {
            Displays.discard(level, chest);
            Displays.discard(level, hitbox);
        }
    }

    private static String minutes(long ticks) {
        long s = (ticks + 19) / 20;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    static Component heading(TreasureHunt.Heading h) {
        return switch (h) {
            case NORTH -> Component.translatableWithFallback("huddle.heading.north", "north");
            case NORTHEAST -> Component.translatableWithFallback("huddle.heading.northeast", "northeast");
            case EAST -> Component.translatableWithFallback("huddle.heading.east", "east");
            case SOUTHEAST -> Component.translatableWithFallback("huddle.heading.southeast", "southeast");
            case SOUTH -> Component.translatableWithFallback("huddle.heading.south", "south");
            case SOUTHWEST -> Component.translatableWithFallback("huddle.heading.southwest", "southwest");
            case WEST -> Component.translatableWithFallback("huddle.heading.west", "west");
            case NORTHWEST -> Component.translatableWithFallback("huddle.heading.northwest", "northwest");
        };
    }

    // el cliente vanilla trae los nombres de los biomas vanilla; los de mods llevan un respaldo legible
    private static Component biomeName(Holder<Biome> b) {
        ResourceLocation id = b.unwrapKey().map(k -> k.location()).orElse(null);
        if (id == null) {
            return Component.literal("?");
        }
        String pretty = id.getPath().replace('_', ' ');
        return Component.translatableWithFallback("biome." + id.getNamespace() + "." + id.getPath(), pretty);
    }
}
