package dev.huddle.neoforge;

import dev.huddle.core.Route;
import dev.huddle.core.TerrainGrid;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// pistas generadas sobre el mundo de pruebas: lectura de columnas, comandos y pistas que se pueden correr.
// Cada prueba está en un pozo de 5x5; las pistas se generan en la superficie plana de alrededor (z negativo)
@GameTestHolder(Huddle.MODID)
@PrefixGameTestTemplate(false)
public final class GenTest {
    private static int seq;

    private static void configure() {
        HuddleConfig.GENERATOR_RADIUS.set(32);
        // menos columnas por tick que las de la copia: la lectura se reparte en varios ticks
        HuddleConfig.GENERATOR_COLUMNS_PER_TICK.set(512);
        HuddleConfig.GENERATED_MIN_LENGTH.set(20);
        HuddleConfig.TRACK_POINT_EVERY.set(10);
        HuddleConfig.MAX_TRACKS_PER_PLAYER.set(3);
        // el mundo de prueba acumula tiempo habitado de una tanda a otra: solo la prueba de bases lo mira
        HuddleConfig.BUSY_CHUNK_MINUTES.set(0);
        HuddleConfig.TYPES.get("race").enabled.set(true);
    }

    private static ServerPlayer player(GameTestHelper helper, String name, double x, double z) {
        ServerPlayer p = TestPlayers.join(helper, name + (++seq));
        Vec3 at = helper.absoluteVec(new Vec3(x, 0, z));
        onSurface(p, at.x, at.z);
        return p;
    }

    private static void onSurface(ServerPlayer p, double x, double z) {
        ServerLevel level = p.serverLevel();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        p.teleportTo(level, x, y, z, 0, 0);
    }

    // con prefijo propio: GenTest y TrackTest corren en el mismo servidor y las pistas no pueden repetir nombre
    private static String name(String base) {
        return "g" + base + (++seq);
    }

    private static int run(ServerPlayer p, String command) {
        int[] result = {0};
        p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withCallback((ok, r) -> result[0] = ok ? r : 0), command);
        return result[0];
    }

    private static List<String> chat(ServerPlayer p) {
        List<String> out = new ArrayList<>();
        for (Packet<?> packet : TestPlayers.received(p)) {
            if (packet instanceof ClientboundSystemChatPacket c && !c.overlay()) {
                out.add(c.content().getString());
            }
        }
        return out;
    }

    private static void check(List<String> wrong, boolean ok, String what) {
        if (!ok) {
            wrong.add(what);
        }
    }

    private static void done(GameTestHelper helper, List<String> wrong, ServerPlayer... players) {
        for (ServerPlayer p : players) {
            Lobbies.leave(p.server, p.getUUID());
            TestPlayers.leave(helper, p);
        }
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join("; ", wrong));
        }
        helper.succeed();
    }

    // cada tipo de columna armado a mano; además, un lugar lejano sin cargar queda desconocido y sigue sin cargar
    @GameTest(template = "empty", batch = "gen", timeoutTicks = 100)
    public static void columnsReadTheWorld(GameTestHelper helper) {
        configure();
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(new BlockPos(0, 0, -8));
        int top = level.getMinBuildHeight();
        for (int k = 0; k < 8; k++) {
            top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, o.getX() + k, o.getZ()));
        }
        int y0 = top;
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before = new java.util.HashMap<>();
        // allana la fila: según el orden de los lotes, ahí puede caer el pozo de otra prueba
        for (int k = 0; k < 8; k++) {
            for (int y = y0 - 3; y <= y0 + 3; y++) {
                BlockPos b = o.offset(k, 0, 0).atY(y);
                before.put(b, level.getBlockState(b));
                level.setBlock(b, y < y0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
        }
        // columna x: 0 suelo, 1 agua quieta, 2 agua que corre, 3 magma, 4 hojas a 2 bloques, 5 lava, 6 arbusto de bayas
        BlockPos water = o.offset(1, 0, 0).atY(y0 - 1);
        BlockPos flowing = o.offset(2, 0, 0).atY(y0 - 1);
        BlockPos magma = o.offset(3, 0, 0).atY(y0 - 1);
        BlockPos leaves = o.offset(4, 0, 0).atY(y0 + 2);
        BlockPos lava = o.offset(5, 0, 0).atY(y0 - 1);
        BlockPos berries = o.offset(6, 0, 0).atY(y0);
        for (BlockPos b : List.of(water, flowing, magma, leaves, lava, berries)) {
            before.putIfAbsent(b, level.getBlockState(b));
        }
        // columna 7: vacía hasta el fondo del mundo (sin suelo no hay donde pararse)
        for (int y = level.getMinBuildHeight(); y < y0; y++) {
            BlockPos b = o.offset(7, 0, 0).atY(y);
            before.putIfAbsent(b, level.getBlockState(b));
            level.setBlock(b, Blocks.AIR.defaultBlockState(), 2);
        }
        level.setBlock(water, Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(flowing, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3), 2);
        level.setBlock(magma, Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
        level.setBlock(leaves, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);
        level.setBlock(lava, Blocks.LAVA.defaultBlockState(), 2);
        level.setBlock(berries, Blocks.SWEET_BERRY_BUSH.defaultBlockState(), 2);

        TerrainGrid g = new TerrainGrid(o.getX(), o.getZ(), 8);
        List<String> wrong = new ArrayList<>();
        check(wrong, TrackGen.read(level, g, 0, 100) == 100, "no respeta el tope de columnas");
        TrackGen.read(level, g, 100, g.cells());
        String[] what = {"suelo", "agua", "agua que corre", "magma", "hojas", "lava", "bayas", "vacío"};
        byte[] kind = {TerrainGrid.LAND, TerrainGrid.WATER, TerrainGrid.BLOCKED, TerrainGrid.BLOCKED, TerrainGrid.LAND, TerrainGrid.BLOCKED,
                TerrainGrid.LAND, TerrainGrid.BLOCKED};
        int[] room = {3, 3, -1, -1, 2, -1, 0, -1};
        for (int k = 0; k < what.length; k++) {
            int i = g.index(o.getX() + k, o.getZ());
            check(wrong, g.kind(i) == kind[k], what[k] + ": tipo " + g.kind(i));
            if (room[k] >= 0) {
                check(wrong, g.headroom(i) == room[k], what[k] + ": espacio " + g.headroom(i));
                check(wrong, g.height(i) == y0, what[k] + ": altura " + g.height(i) + " en vez de " + y0);
            }
        }

        int far = 1_000_000;
        TerrainGrid away = new TerrainGrid(far, far, 32);
        TrackGen.read(level, away, 0, away.cells());
        boolean allUnknown = true;
        for (int i = 0; i < away.cells(); i++) {
            allUnknown &= away.kind(i) == TerrainGrid.UNKNOWN;
        }
        check(wrong, allUnknown, "leyó columnas de chunks sin cargar");
        check(wrong, !level.getChunkSource().hasChunk(far >> 4, far >> 4), "cargó o generó un chunk");

        before.forEach((b, state) -> level.setBlock(b, state, 2));
        done(helper, wrong);
    }

    // de punta a punta en el mundo plano: se guarda, con autor, cerca del creador, y se puede abrir una carrera
    @GameTest(template = "empty", batch = "gen", timeoutTicks = 200)
    public static void generatedSprintCanBeRaced(GameTestHelper helper) {
        configure();
        ServerPlayer p = player(helper, "gs", 2.5, -5.5);
        Runnable removePlatform = platform(p, 20);
        String track = name("sprint");
        List<String> wrong = new ArrayList<>();
        check(wrong, run(p, "/evento pista generar " + track + " sprint foot") == 1, "generar en español falló");
        check(wrong, run(p, "/event track generate " + name("otra") + " circuit") == 0, "dos generaciones a la vez");
        check(wrong, run(p, "/event track record " + track + " foot") == 0, "grabó con el nombre de una pista en generación");
        check(wrong, !HuddleData.get(p.server).tracks().containsKey(track), "la guardó en el mismo tick: la lectura no se reparte");
        helper.runAfterDelay(120, () -> {
            Tracks.Stored s = HuddleData.get(p.server).tracks().get(track);
            List<String> said = chat(p);
            if (s == null) {
                wrong.add("no se generó: " + said + "\n" + map(p));
                removePlatform.run();
                done(helper, wrong, p);
                return;
            }
            Route r = s.route();
            check(wrong, r.mode() == Route.Mode.FOOT && r.laps() == 1, "medio o vueltas: " + r.mode() + " " + r.laps());
            check(wrong, p.getUUID().equals(r.author()) && r.authorName().equals(p.getGameProfile().getName()), "sin autor");
            check(wrong, r.start().dist(new dev.huddle.core.Pos(p.getX(), p.getY(), p.getZ())) < 9, "largada lejos del creador: " + r.start());
            check(wrong, s.dimension().equals(p.level().dimension()), "dimensión");
            check(wrong, said.stream().anyMatch(m -> m.contains("generated")), "sin aviso al terminar: " + said);
            check(wrong, run(p, "/event start race " + track) == 1, "no abre la carrera");
            removePlatform.run();
            done(helper, wrong, p);
        });
    }

    @GameTest(template = "empty", batch = "gen", timeoutTicks = 200)
    public static void failuresAreExplained(GameTestHelper helper) {
        configure();
        ServerPlayer p = player(helper, "gf", 2.5, -5.5);
        String track = name("seco");
        List<String> wrong = new ArrayList<>();
        check(wrong, run(p, "/event track generate " + name("x") + " volcano") == 0, "aceptó un preset que no existe");
        check(wrong, run(p, "/event track generate " + name("x") + " lake horse") == 0, "aceptó un lago a caballo");
        check(wrong, run(p, "/event track generate Mal:Nombre sprint") == 0, "aceptó un nombre inválido");
        check(wrong, run(p, "/event track generate " + track + " lake") == 1, "no empezó");
        helper.runAfterDelay(120, () -> {
            check(wrong, chat(p).stream().anyMatch(m -> m.contains("no water wide enough")), "sin explicar la falta de agua");
            check(wrong, !HuddleData.get(p.server).tracks().containsKey(track), "guardó una pista fallida");
            done(helper, wrong, p);
        });
    }

    // el creador en la base de alguien: la pista sale de la base, pero ningún punto queda dentro. Plataforma propia
    // y chunks forzados: alrededor hay estructuras de otras pruebas y más allá de los vecinos no está cargado
    @GameTest(template = "empty", batch = "gen_busy", timeoutTicks = 200)
    public static void busyChunksAreLeftOut(GameTestHelper helper) {
        configure();
        HuddleConfig.BUSY_CHUNK_MINUTES.set(5);
        ServerPlayer p = player(helper, "gb", 2.5, -5.5);
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(p.blockPosition());
        forced(level, chunk, true);
        onSurface(p, chunk.getMiddleBlockX() + 0.5, chunk.getMiddleBlockZ() + 0.5);
        Runnable removePlatform = platform(p, 30);
        setBusy(level, chunk, 20L * 60 * 30);
        String track = name("base");
        List<String> wrong = new ArrayList<>();
        check(wrong, run(p, "/event track generate " + track + " sprint") == 1, "no empezó");
        helper.runAfterDelay(120, () -> {
            setBusy(level, chunk, 0);
            Tracks.Stored s = HuddleData.get(p.server).tracks().get(track);
            check(wrong, s != null, "no generó desde la base: " + chat(p));
            if (s != null) {
                for (Route.Checkpoint cp : s.route().checkpoints()) {
                    ChunkPos at = new ChunkPos(BlockPos.containing(cp.center().x(), 0, cp.center().z()));
                    if (Math.abs(at.x - chunk.x) <= 1 && Math.abs(at.z - chunk.z) <= 1) {
                        wrong.add("punto dentro de la base: " + cp.center());
                    }
                }
            }
            removePlatform.run();
            forced(level, chunk, false);
            done(helper, wrong, p);
        });
    }

    private static void forced(ServerLevel level, ChunkPos c, boolean on) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setChunkForced(c.x + dx, c.z + dz, on);
            }
        }
    }

    // piso plano propio en el aire: la superficie de alrededor depende de dónde quedaron los pozos de los otros lotes.
    // Devuelve con qué quitarlo
    private static Runnable platform(ServerPlayer p, int r) {
        ServerLevel level = p.serverLevel();
        BlockPos c = p.blockPosition().above(12);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                level.setBlock(c.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        p.teleportTo(level, c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 0, 0);
        return () -> {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    level.setBlock(c.offset(dx, -1, dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        };
    }

    // mapa de lo leído alrededor del jugador, para entender un fallo: . suelo, ~ agua, # bloqueado, ? desconocido,
    // la letra es el espacio libre cuando no es 3 y el número la altura relativa al jugador cuando no es 0
    private static String map(ServerPlayer p) {
        TerrainGrid g = new TerrainGrid(p.getBlockX(), p.getBlockZ(), 12);
        TrackGen.read(p.serverLevel(), g, 0, g.cells());
        StringBuilder b = new StringBuilder();
        for (int z = 0; z < g.size(); z++) {
            for (int x = 0; x < g.size(); x++) {
                int i = z * g.size() + x;
                int dh = g.height(i) - p.getBlockY();
                b.append(switch (g.kind(i)) {
                    case TerrainGrid.LAND -> g.headroom(i) < 3 ? (char) ('a' + g.headroom(i)) : dh == 0 ? '.' : Character.forDigit(Math.min(Math.abs(dh), 9), 10);
                    case TerrainGrid.WATER -> '~';
                    case TerrainGrid.BLOCKED -> '#';
                    default -> '?';
                });
            }
            b.append('\n');
        }
        return b.toString();
    }

    // estanque de lado x lado con piso y borde de piedra y aire encima, a la altura de la superficie en o:
    // otras tandas de GameTest dejan pozos alrededor y el agua puesta sobre ellos se derramaría
    private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> pond(ServerLevel level, BlockPos o, int y0, int side) {
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before = new java.util.HashMap<>();
        for (int x = -1; x <= side; x++) {
            for (int z = -1; z <= side; z++) {
                boolean inside = x >= 0 && z >= 0 && x < side && z < side;
                for (int y = y0 - 2; y <= y0 + 8; y++) {
                    BlockPos b = new BlockPos(o.getX() + x, y, o.getZ() + z);
                    before.put(b, level.getBlockState(b));
                    net.minecraft.world.level.block.state.BlockState put = y == y0 - 2 ? Blocks.STONE.defaultBlockState()
                            : y == y0 - 1 ? (inside ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState())
                            : Blocks.AIR.defaultBlockState();
                    level.setBlock(b, put, 2);
                }
            }
        }
        return before;
    }

    // el chunk del creador y los de alrededor: el suelo libre más cercano queda a más de 8 bloques
    private static void setBusy(ServerLevel level, ChunkPos c, long ticks) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.getChunk(c.x + dx, c.z + dz).setInhabitedTime(ticks);
            }
        }
    }

    // agua que no alcanza: se dice cuánto mide lo mejor que hay y dónde está
    @GameTest(template = "empty", batch = "gen_short", timeoutTicks = 200)
    public static void shortWaterSaysWhere(GameTestHelper helper) {
        configure();
        ServerLevel level = helper.getLevel();
        ServerPlayer p = player(helper, "gw", 2.5, -5.5);
        BlockPos o = helper.absolutePos(new BlockPos(0, 0, -20));
        int y0 = p.getBlockY();
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before = pond(level, o, y0, 8);
        String track = name("charco");
        List<String> wrong = new ArrayList<>();
        check(wrong, run(p, "/event track generate " + track + " river") == 1, "no empezó");
        helper.runAfterDelay(120, () -> {
            List<String> said = chat(p);
            // la celda del charco más cercana al jugador (sin el borde de 1 bloque que el bote no usa)
            String where = (o.getX() + 2) + " " + y0 + " " + (o.getZ() + 6);
            check(wrong, said.stream().anyMatch(m -> m.contains("in the water at " + where)), "sin decir dónde (" + where + "): " + said + "\n" + map(p));
            check(wrong, !HuddleData.get(p.server).tracks().containsKey(track), "guardó una pista corta");
            before.forEach((b, state) -> level.setBlock(b, state, 2));
            done(helper, wrong, p);
        });
    }

    // un estanque cavado en el mundo plano: vueltas en bote, cada punto sobre el agua
    @GameTest(template = "empty", batch = "gen_lake", timeoutTicks = 200)
    public static void lakeOnAPond(GameTestHelper helper) {
        configure();
        ServerLevel level = helper.getLevel();
        ServerPlayer p = player(helper, "gl", 2.5, -5.5);
        BlockPos o = helper.absolutePos(new BlockPos(0, 0, -33));
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before = pond(level, o, p.getBlockY(), 24);
        String track = name("lago");
        List<String> wrong = new ArrayList<>();
        check(wrong, run(p, "/event track generate " + track + " lake") == 1, "no empezó");
        helper.runAfterDelay(120, () -> {
            Tracks.Stored s = HuddleData.get(p.server).tracks().get(track);
            if (s == null) {
                wrong.add("no se generó: " + chat(p));
            } else {
                Route r = s.route();
                check(wrong, r.mode() == Route.Mode.BOAT && r.laps() == 3, "medio o vueltas: " + r.mode() + " " + r.laps());
                check(wrong, r.checkpoints().size() >= 3, "pocos puntos: " + r.checkpoints().size());
                for (Route.Checkpoint cp : r.checkpoints()) {
                    BlockPos under = BlockPos.containing(cp.center().x(), cp.center().y() - 1, cp.center().z());
                    check(wrong, level.getFluidState(under).is(FluidTags.WATER), "punto fuera del agua: " + cp.center());
                    check(wrong, cp.radius() == Tracks.radius(Route.Mode.BOAT), "radio de bote: " + cp.radius());
                }
                BlockPos startUnder = BlockPos.containing(r.start().x(), r.start().y() - 1, r.start().z());
                check(wrong, level.getFluidState(startUnder).is(FluidTags.WATER), "largada fuera del agua: " + r.start());
            }
            before.forEach((b, state) -> level.setBlock(b, state, 2));
            done(helper, wrong, p);
        });
    }
}
