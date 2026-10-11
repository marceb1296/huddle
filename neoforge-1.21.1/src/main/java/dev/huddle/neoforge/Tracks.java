package dev.huddle.neoforge;

import dev.huddle.core.ModeGrace;
import dev.huddle.core.Pos;
import dev.huddle.core.Records;
import dev.huddle.core.Route;
import dev.huddle.core.TrackRecorder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

// pistas de carrera: la grabación en curso de cada jugador y las pistas guardadas en HuddleData
public final class Tracks {
    public record Stored(Route route, ResourceKey<Level> dimension, Records records) {
    }

    public enum RecordResult { STARTED, BAD_NAME, NAME_TAKEN, ALREADY_RECORDING, IN_EVENT, TOO_MANY }

    public enum SaveResult { SAVED, NOT_RECORDING, BROKEN, NAME_TAKEN, TOO_MANY }

    public enum DeleteResult { DELETED, NO_SUCH_TRACK, NOT_YOURS }

    private static final class Recording {
        final TrackRecorder recorder;
        final String name;
        final Route.Mode mode;
        final int laps;
        final ResourceKey<Level> dimension;
        final ModeGrace grace = new ModeGrace(HuddleConfig.TRAVEL_GRACE_SECONDS.get() * 20);
        final Set<Long> warnedChunks = new HashSet<>();
        boolean warnedFull;

        Recording(ServerPlayer p, String name, Route.Mode mode, int laps) {
            this.name = name;
            this.mode = mode;
            this.laps = laps;
            this.dimension = p.level().dimension();
            this.recorder = new TrackRecorder(mode, pos(p), HuddleConfig.TRACK_POINT_EVERY.get(), radius(mode), HuddleConfig.MAX_JUMP_BLOCKS.get());
        }
    }

    private static final Map<UUID, Recording> RECORDING = new HashMap<>();

    private Tracks() {
    }

    static boolean recordingName(String name) {
        return RECORDING.values().stream().anyMatch(r -> r.name.equals(name));
    }

    // guardadas, en grabación o en generación
    static boolean nameInUse(MinecraftServer server, String name) {
        return HuddleData.get(server).tracks().containsKey(name) || recordingName(name) || TrackGen.pending(name);
    }

    public static boolean recording(UUID player) {
        return RECORDING.containsKey(player);
    }

    // el radio crece con la velocidad del medio: con élitros un punto chico es casi imposible de acertar
    static double radius(Route.Mode mode) {
        return switch (mode) {
            case FOOT -> 3;
            case HORSE -> 4;
            case BOAT -> 5;
            case ELYTRA -> 8;
        };
    }

    static Pos pos(Entity e) {
        return new Pos(e.getX(), e.getY(), e.getZ());
    }

    // si el jugador va en el medio de la pista; espectadores y vuelo de creativo nunca cuentan
    static boolean travelling(ServerPlayer p, Route.Mode mode) {
        if (p.isSpectator() || p.getAbilities().flying) {
            return false;
        }
        Entity v = p.getVehicle();
        return switch (mode) {
            case FOOT -> v == null && !p.isFallFlying();
            case HORSE -> v instanceof AbstractHorse;
            case BOAT -> v instanceof Boat;
            case ELYTRA -> p.isFallFlying();
        };
    }

    public static RecordResult record(ServerPlayer p, String rawName, Route.Mode mode, int laps) {
        String name = Route.cleanName(rawName);
        if (name == null) {
            return RecordResult.BAD_NAME;
        }
        if (RECORDING.containsKey(p.getUUID())) {
            return RecordResult.ALREADY_RECORDING;
        }
        if (Lobbies.of(p) != null) {
            return RecordResult.IN_EVENT;
        }
        HuddleData data = HuddleData.get(p.server);
        if (nameInUse(p.server, name)) {
            return RecordResult.NAME_TAKEN;
        }
        if (owned(data, p.getUUID()) >= HuddleConfig.MAX_TRACKS_PER_PLAYER.get()) {
            return RecordResult.TOO_MANY;
        }
        RECORDING.put(p.getUUID(), new Recording(p, name, mode, laps));
        return RecordResult.STARTED;
    }

    // null si no está grabando
    public static TrackRecorder.Outcome point(ServerPlayer p) {
        Recording r = RECORDING.get(p.getUUID());
        if (r == null) {
            return null;
        }
        TrackRecorder.Outcome o = r.recorder.point(pos(p));
        after(p, r, o);
        return o;
    }

    public static SaveResult save(ServerPlayer p) {
        Recording r = RECORDING.get(p.getUUID());
        if (r == null) {
            return SaveResult.NOT_RECORDING;
        }
        HuddleData data = HuddleData.get(p.server);
        if (data.tracks().containsKey(r.name)) {
            return SaveResult.NAME_TAKEN;
        }
        if (owned(data, p.getUUID()) >= HuddleConfig.MAX_TRACKS_PER_PLAYER.get()) {
            return SaveResult.TOO_MANY;
        }
        RECORDING.remove(p.getUUID());
        Optional<Route> route = r.dimension.equals(p.level().dimension())
                ? r.recorder.save(pos(p), r.name, p.getUUID(), p.getGameProfile().getName(), r.laps)
                : Optional.empty();
        if (route.isEmpty()) {
            return SaveResult.BROKEN;
        }
        warnIfBusy(p, r);
        data.tracks().put(r.name, new Stored(route.get(), r.dimension, new Records()));
        data.changed();
        return SaveResult.SAVED;
    }

    public static boolean cancel(UUID player) {
        return RECORDING.remove(player) != null;
    }

    // el autor borra las suyas; un operador, cualquiera
    public static DeleteResult delete(MinecraftServer server, String rawName, UUID by, boolean op) {
        HuddleData data = HuddleData.get(server);
        String name = Route.cleanName(rawName);
        Stored s = name == null ? null : data.tracks().get(name);
        if (s == null) {
            return DeleteResult.NO_SUCH_TRACK;
        }
        if (!op && !by.equals(s.route().author())) {
            return DeleteResult.NOT_YOURS;
        }
        data.tracks().remove(name);
        data.changed();
        return DeleteResult.DELETED;
    }

    public static List<Stored> all(MinecraftServer server) {
        return List.copyOf(HuddleData.get(server).tracks().values());
    }

    public static void tick(MinecraftServer server) {
        for (Map.Entry<UUID, Recording> e : List.copyOf(RECORDING.entrySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            Recording r = e.getValue();
            if (p == null) {
                RECORDING.remove(e.getKey());
                continue;
            }
            if (!r.dimension.equals(p.level().dimension())) {
                ruin(p, Component.translatableWithFallback("huddle.track.ruined.dimension",
                        "Recording stopped: you changed dimension."));
                continue;
            }
            if (r.grace.update(travelling(p, r.mode))) {
                ruin(p, Component.translatableWithFallback("huddle.track.ruined.travel", "Recording stopped: this track is %s.",
                        modeName(r.mode)));
                continue;
            }
            after(p, r, r.recorder.sample(pos(p)));
        }
    }

    // perla, fruta coral o /tp: a la distancia que sea, la pista grabada no se podría correr
    public static void onTeleport(ServerPlayer p) {
        if (RECORDING.containsKey(p.getUUID())) {
            ruin(p, Component.translatableWithFallback("huddle.track.ruined.teleport", "Recording stopped: you teleported."));
        }
    }

    public static void onLogout(ServerPlayer p) {
        RECORDING.remove(p.getUUID());
    }

    public static void clear() {
        RECORDING.clear();
    }

    private static void after(ServerPlayer p, Recording r, TrackRecorder.Outcome o) {
        switch (o) {
            case ADDED -> {
                p.displayClientMessage(Component.translatableWithFallback("huddle.track.point", "Checkpoint %s saved.", r.recorder.size()), true);
                warnIfBusy(p, r);
            }
            case JUMP -> ruin(p, Component.translatableWithFallback("huddle.track.ruined.teleport", "Recording stopped: you teleported."));
            case FULL -> {
                if (!r.warnedFull) {
                    r.warnedFull = true;
                    p.sendSystemMessage(Component.translatableWithFallback("huddle.track.full",
                            "This track has the most checkpoints allowed (%s). Save it where it should end.", Route.MAX_CHECKPOINTS)
                            .withStyle(ChatFormatting.YELLOW));
                }
            }
            default -> {
            }
        }
    }

    private static void ruin(ServerPlayer p, Component why) {
        RECORDING.remove(p.getUUID());
        p.sendSystemMessage(why.copy().withStyle(ChatFormatting.RED));
    }

    // un punto en un chunk donde se pasó mucho tiempo suele ser la base de alguien
    private static void warnIfBusy(ServerPlayer p, Recording r) {
        int minutes = HuddleConfig.BUSY_CHUNK_MINUTES.get();
        if (minutes <= 0) {
            return;
        }
        BlockPos at = p.blockPosition();
        ChunkPos chunk = new ChunkPos(at);
        if (r.warnedChunks.contains(chunk.toLong())) {
            return;
        }
        if (p.level().getChunkAt(at).getInhabitedTime() > minutes * 1200L) {
            r.warnedChunks.add(chunk.toLong());
            p.sendSystemMessage(Component.translatableWithFallback("huddle.track.busy",
                    "Heads up: this point is in a place where players spend a lot of time, maybe someone's base.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    static int owned(HuddleData data, UUID player) {
        return (int) data.tracks().values().stream().filter(s -> player.equals(s.route().author())).count();
    }

    static Component modeName(Route.Mode mode) {
        return switch (mode) {
            case FOOT -> Component.translatableWithFallback("huddle.travel.foot", "on foot");
            case HORSE -> Component.translatableWithFallback("huddle.travel.horse", "on horseback");
            case BOAT -> Component.translatableWithFallback("huddle.travel.boat", "by boat");
            case ELYTRA -> Component.translatableWithFallback("huddle.travel.elytra", "with elytra");
        };
    }
}
