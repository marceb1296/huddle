package dev.huddle.neoforge;

import dev.huddle.core.Pos;
import dev.huddle.core.Records;
import dev.huddle.core.Route;
import dev.huddle.core.TerrainGrid;
import dev.huddle.core.TrackGenerator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

// pistas generadas: se copia el terreno ya cargado alrededor del jugador, unas columnas por tick,
// y los caminos se buscan en otro hilo sobre esa copia. Nunca carga, genera ni modifica chunks.
public final class TrackGen {
    public enum StartResult { STARTED, BAD_NAME, NAME_TAKEN, BUSY, TOO_MANY }

    private static final class Job {
        final UUID player;
        final String playerName;
        final String name;
        final TrackGenerator.Preset preset;
        final Route.Mode mode;
        final int laps;
        final ResourceKey<Level> dimension;
        final Pos creator;
        final TerrainGrid grid;
        int next;
        CompletableFuture<TrackGenerator.Result> result;

        Job(ServerPlayer p, String name, TrackGenerator.Preset preset, Route.Mode mode, int laps) {
            this.player = p.getUUID();
            this.playerName = p.getGameProfile().getName();
            this.name = name;
            this.preset = preset;
            this.mode = TrackGenerator.modeFor(preset, mode);
            this.laps = laps;
            this.dimension = p.level().dimension();
            this.creator = Tracks.pos(p);
            this.grid = new TerrainGrid(p.getBlockX(), p.getBlockZ(), HuddleConfig.GENERATOR_RADIUS.get());
        }
    }

    private static final Deque<Job> READING = new ArrayDeque<>();
    private static final List<Job> SEARCHING = new ArrayList<>();

    private TrackGen() {
    }

    static boolean pending(String name) {
        return all().stream().anyMatch(j -> j.name.equals(name));
    }

    private static List<Job> all() {
        List<Job> out = new ArrayList<>(READING);
        out.addAll(SEARCHING);
        return out;
    }

    public static StartResult start(ServerPlayer p, String rawName, TrackGenerator.Preset preset, Route.Mode mode, int laps) {
        String name = Route.cleanName(rawName);
        if (name == null) {
            return StartResult.BAD_NAME;
        }
        if (all().stream().anyMatch(j -> j.player.equals(p.getUUID()))) {
            return StartResult.BUSY;
        }
        if (Tracks.nameInUse(p.server, name)) {
            return StartResult.NAME_TAKEN;
        }
        if (Tracks.owned(HuddleData.get(p.server), p.getUUID()) >= HuddleConfig.MAX_TRACKS_PER_PLAYER.get()) {
            return StartResult.TOO_MANY;
        }
        READING.add(new Job(p, name, preset, mode, laps));
        return StartResult.STARTED;
    }

    public static void tick(MinecraftServer server) {
        int budget = HuddleConfig.GENERATOR_COLUMNS_PER_TICK.get();
        while (budget > 0 && !READING.isEmpty()) {
            Job job = READING.peek();
            ServerLevel level = server.getLevel(job.dimension);
            if (level == null) {
                READING.poll();
                continue;
            }
            int done = read(level, job.grid, job.next, budget);
            job.next += done;
            budget -= done;
            if (job.next >= job.grid.cells()) {
                READING.poll();
                job.result = CompletableFuture.supplyAsync(() -> search(job), Util.backgroundExecutor());
                SEARCHING.add(job);
            }
        }
        for (Job job : List.copyOf(SEARCHING)) {
            if (job.result.isDone()) {
                SEARCHING.remove(job);
                finish(server, job);
            }
        }
    }

    private static TrackGenerator.Result search(Job job) {
        double spacing = HuddleConfig.TRACK_POINT_EVERY.get() > 0 ? HuddleConfig.TRACK_POINT_EVERY.get() : 32;
        int min = HuddleConfig.GENERATED_MIN_LENGTH.get();
        boolean point = !job.preset.loop();
        TrackGenerator.Params params = new TrackGenerator.Params(spacing, Tracks.radius(job.mode), min,
                point ? Math.max(min, job.preset.water() ? 600 : 250) : 100000, job.preset.water() ? 60 : 40);
        return TrackGenerator.generate(job.grid, job.preset, job.mode, job.creator, params, job.name, job.player, job.playerName, job.laps);
    }

    static int read(ServerLevel level, TerrainGrid g, int from, int budget) {
        return read(level, g, from, budget, 0);
    }

    // lee hasta budget columnas desde from; devuelve cuántas leyó. caveDepth > 0 también busca huecos bajo tierra
    static int read(ServerLevel level, TerrainGrid g, int from, int budget, int caveDepth) {
        int done = 0;
        long busy = HuddleConfig.BUSY_CHUNK_MINUTES.get() * 1200L;
        LevelChunk chunk = null;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        while (done < budget && from + done < g.cells()) {
            int x = g.x(from + done), z = g.z(from + done);
            done++;
            if (chunk == null || chunk.getPos().x != x >> 4 || chunk.getPos().z != z >> 4) {
                // solo lo que ya está cargado: getChunkNow nunca carga ni genera
                chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {
                    continue;
                }
            }
            column(level, chunk, g, x, z, at);
            // las bases se leen para poder cruzarlas, pero no se pone nada en ellas
            if (busy > 0 && chunk.getInhabitedTime() > busy) {
                g.markBusy(x, z);
                continue;
            }
            if (caveDepth > 0) {
                cave(level, chunk, g, x, z, caveDepth, at);
            }
        }
        return done;
    }

    static void column(ServerLevel level, LevelChunk chunk, TerrainGrid g, int x, int z, BlockPos.MutableBlockPos at) {
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
        if (top < level.getMinBuildHeight()) {
            g.set(x, z, TerrainGrid.BLOCKED, 0, 0);
            return;
        }
        FluidState fluid = chunk.getFluidState(at.set(x, top, z));
        if (fluid.is(FluidTags.WATER)) {
            // agua que corre: cascadas y bordes de un río que baja; el bote no la sube
            g.set(x, z, fluid.isSource() ? TerrainGrid.WATER : TerrainGrid.BLOCKED, top + 1, 3);
            return;
        }
        int ground = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        BlockState floor = chunk.getBlockState(at.set(x, ground, z));
        if (!floor.getFluidState().isEmpty() || hurts(floor)) {
            g.set(x, z, TerrainGrid.BLOCKED, 0, 0);
            return;
        }
        int free = 0;
        while (free < 3) {
            BlockState s = chunk.getBlockState(at.set(x, ground + 1 + free, z));
            if (!s.getCollisionShape(level, at).isEmpty() || !s.getFluidState().isEmpty() || hurts(s)) {
                break;
            }
            free++;
        }
        g.set(x, z, TerrainGrid.LAND, ground + 1, free);
    }

    // el hueco más alto bajo la superficie con suelo firme y dos bloques de aire, sin líquidos ni nada que lastime
    static void cave(ServerLevel level, LevelChunk chunk, TerrainGrid g, int x, int z, int depth, BlockPos.MutableBlockPos at) {
        int i = g.index(x, z);
        if (g.kind(i) != TerrainGrid.LAND) {
            return;
        }
        int surface = g.height(i);
        int bottom = Math.max(level.getMinBuildHeight(), surface - depth);
        boolean above = false, here = false;
        for (int y = surface - 2; y >= bottom; y--) {
            BlockState s = chunk.getBlockState(at.set(x, y, z));
            boolean open = open(level, s, at);
            if (!open && above && here && !hurts(s) && s.getFluidState().isEmpty() && s.isFaceSturdy(level, at, net.minecraft.core.Direction.UP)) {
                g.setCave(x, z, y + 1);
                return;
            }
            above = here;
            here = open;
        }
    }

    private static boolean open(ServerLevel level, BlockState s, BlockPos at) {
        return s.getCollisionShape(level, at).isEmpty() && s.getFluidState().isEmpty() && !hurts(s);
    }

    private static boolean hurts(BlockState s) {
        return s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CACTUS) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.WITHER_ROSE)
                || s.is(Blocks.POINTED_DRIPSTONE) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.LAVA);
    }

    private static void finish(MinecraftServer server, Job job) {
        ServerPlayer p = server.getPlayerList().getPlayer(job.player);
        TrackGenerator.Result r;
        try {
            r = job.result.join();
        } catch (RuntimeException e) {
            Huddle.LOGGER.error("Track generation failed", e);
            tell(p, Component.translatableWithFallback("huddle.gen.error", "Something went wrong while generating the track.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (r.route().isEmpty()) {
            Huddle.LOGGER.info("Track {} not generated for {}: {} (longest {} blocks)", job.name, job.playerName, r.failure(), r.best());
            tell(p, failure(job, r).copy().withStyle(ChatFormatting.RED));
            return;
        }
        HuddleData data = HuddleData.get(server);
        if (data.tracks().containsKey(job.name) || Tracks.recordingName(job.name)) {
            tell(p, Component.translatableWithFallback("huddle.track.name_taken", "There is already a track with that name.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (Tracks.owned(data, job.player) >= HuddleConfig.MAX_TRACKS_PER_PLAYER.get()) {
            tell(p, Component.translatableWithFallback("huddle.track.too_many", "You have saved as many tracks as allowed (%s). Delete one first.",
                    HuddleConfig.MAX_TRACKS_PER_PLAYER.get()).withStyle(ChatFormatting.RED));
            return;
        }
        Route route = r.route().get();
        data.tracks().put(job.name, new Tracks.Stored(route, job.dimension, new Records()));
        data.changed();
        Huddle.LOGGER.info("Track {} generated for {}: {} {}, {} checkpoints, {} blocks, start {}", job.name, job.playerName,
                job.preset, route.mode(), route.checkpoints().size(), r.length(), route.start());
        tell(p, Component.translatableWithFallback("huddle.gen.done",
                "Track %s generated: %s, %s checkpoints, %s blocks. Start it with /event start race %s.",
                route.name(), Tracks.modeName(route.mode()), route.checkpoints().size(), r.length(), route.name())
                .withStyle(ChatFormatting.GREEN));
    }

    private static Component failure(Job job, TrackGenerator.Result r) {
        int radius = HuddleConfig.GENERATOR_RADIUS.get();
        return switch (r.failure()) {
            case NO_GROUND -> job.mode == Route.Mode.HORSE
                    ? Component.translatableWithFallback("huddle.gen.no_ground_horse",
                    "Stand on open ground with room for a horse to generate this track.")
                    : Component.translatableWithFallback("huddle.gen.no_ground",
                    "Stand on open ground to generate this track.");
            case NO_WATER -> Component.translatableWithFallback("huddle.gen.no_water",
                    "There is no water wide enough for boats (3 blocks) within %s blocks.", radius);
            case NO_ROOM -> job.preset.water()
                    ? Component.translatableWithFallback("huddle.gen.no_room_water", "The water around here has no room for laps.")
                    : Component.translatableWithFallback("huddle.gen.no_room", "There is not enough open ground around you for a circuit.");
            case TOO_SHORT -> r.near() == null
                    ? Component.translatableWithFallback("huddle.gen.too_short",
                    "The longest track found here is %s blocks; it needs at least %s.", r.best(), HuddleConfig.GENERATED_MIN_LENGTH.get())
                    : Component.translatableWithFallback("huddle.gen.too_short_at",
                    "The longest track found here is %s blocks, in the water at %s %s %s; it needs at least %s.", r.best(),
                    (int) Math.floor(r.near().x()), (int) Math.floor(r.near().y()), (int) Math.floor(r.near().z()),
                    HuddleConfig.GENERATED_MIN_LENGTH.get());
        };
    }

    private static void tell(ServerPlayer p, Component c) {
        if (p != null) {
            p.sendSystemMessage(c);
        }
    }

    public static void onLogout(ServerPlayer p) {
        READING.removeIf(j -> j.player.equals(p.getUUID()));
    }

    public static void clear() {
        READING.clear();
        SEARCHING.clear();
    }
}
