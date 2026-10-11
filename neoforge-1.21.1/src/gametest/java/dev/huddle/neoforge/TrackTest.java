package dev.huddle.neoforge;

import dev.huddle.core.Route;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// grabación de pistas con los comandos del chat y un jugador real del servidor
@GameTestHolder(Huddle.MODID)
@PrefixGameTestTemplate(false)
public final class TrackTest {
    private static int seq;

    // las pruebas del lote corren a la vez: todas usan esta misma configuración
    private static void configure() {
        HuddleConfig.MAX_JUMP_BLOCKS.set(30);
        HuddleConfig.TRAVEL_GRACE_SECONDS.set(1);
        HuddleConfig.TRACK_POINT_EVERY.set(10);
        HuddleConfig.MAX_TRACKS_PER_PLAYER.set(2);
        HuddleConfig.BUSY_CHUNK_MINUTES.set(5);
        HuddleConfig.TYPES.get("race").enabled.set(true);
        HuddleConfig.TYPES.get("airdrop").enabled.set(true);
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer p = TestPlayers.join(helper, name + (++seq));
        Vec3 at = helper.absoluteVec(new Vec3(1, 1, 1));
        p.teleportTo(helper.getLevel(), at.x, at.y, at.z, 0, 0);
        return p;
    }

    // con prefijo propio: GenTest y TrackTest corren en el mismo servidor y las pistas no pueden repetir nombre
    private static String name(String base) {
        return "t" + base + (++seq);
    }

    private static int run(ServerPlayer p, String command) {
        int[] result = {0};
        p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withCallback((ok, r) -> result[0] = ok ? r : 0), command);
        return result[0];
    }

    private static int runAsServer(MinecraftServer server, String command) {
        int[] result = {0};
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withCallback((ok, r) -> result[0] = ok ? r : 0), command);
        return result[0];
    }

    // camina en línea recta como lo haría en el juego: un paso por tick del servidor
    private static void walk(ServerPlayer p, double dx, int steps) {
        for (int i = 0; i < steps; i++) {
            p.teleportTo(p.serverLevel(), p.getX() + dx, p.getY(), p.getZ(), 0, 0);
            Tracks.tick(p.server);
        }
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
            Tracks.cancel(p.getUUID());
            Lobbies.leave(p.server, p.getUUID());
            TestPlayers.leave(helper, p);
        }
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join("; ", wrong));
        }
        helper.succeed();
    }

    // grabar, guardar y volver a cargar desde NBT da la misma pista
    @GameTest(template = "empty", batch = "track", timeoutTicks = 100)
    public static void recordSaveAndReload(GameTestHelper helper) {
        configure();
        ServerPlayer p = player(helper, "tr");
        String track = name("lago");
        List<String> wrong = new ArrayList<>();
        Vec3 start = p.position();
        check(wrong, run(p, "/evento pista grabar " + track + " foot 2") == 1, "grabar en español falló");
        walk(p, 1, 25);
        check(wrong, run(p, "/event track point") == 0, "punto pegado al automático no avisó");
        walk(p, 1, 1);
        check(wrong, run(p, "/event track point") == 1, "punto a mano falló");
        walk(p, 1, 14);
        check(wrong, run(p, "/event track save") == 1, "guardar falló");
        Tracks.Stored s = HuddleData.get(p.server).tracks().get(track);
        if (s == null) {
            wrong.add("no quedó guardada");
            done(helper, wrong, p);
            return;
        }
        Route r = s.route();
        // automáticos a 10 y 20, a mano en 26, automático en 36 y la meta en 40 lo reemplaza
        check(wrong, r.checkpoints().size() == 4, "puntos: " + r.checkpoints().size());
        check(wrong, Math.abs(r.checkpoints().get(3).center().x() - (start.x + 40)) < 1e-6, "la meta no está donde terminó");
        check(wrong, r.laps() == 2 && r.mode() == Route.Mode.FOOT, "vueltas o medio mal guardados");
        check(wrong, p.getUUID().equals(r.author()), "sin autor");
        check(wrong, s.dimension().equals(p.level().dimension()), "dimensión mal guardada");
        Optional<Tracks.Stored> back = HuddleData.loadTrack(track, HuddleData.saveTrack(s));
        check(wrong, back.isPresent() && back.get().route().checkpoints().equals(r.checkpoints())
                && back.get().route().laps() == 2 && p.getUUID().equals(back.get().route().author()), "no vuelve igual desde NBT");
        check(wrong, !Tracks.recording(p.getUUID()), "sigue grabando después de guardar");
        done(helper, wrong, p);
    }

    // un /tp corto no supera la distancia de salto: sin escuchar el teletransporte pasaría
    @GameTest(template = "empty", batch = "track", timeoutTicks = 100)
    public static void shortTeleportCommandStopsRecording(GameTestHelper helper) {
        configure();
        ServerPlayer p = player(helper, "tp");
        List<String> wrong = new ArrayList<>();
        run(p, "/event track record " + name("corta") + " foot");
        walk(p, 1, 5);
        runAsServer(p.server, String.format(java.util.Locale.ROOT, "/tp %s %.3f %.3f %.3f", p.getGameProfile().getName(), p.getX() + 3, p.getY(), p.getZ()));
        Tracks.tick(p.server);
        check(wrong, !Tracks.recording(p.getUUID()), "el /tp no detuvo la grabación");
        check(wrong, run(p, "/event track save") == 0, "guardó una pista con /tp");
        done(helper, wrong, p);
    }

    // otro mod que mueve al jugador sin avisar: lo detecta la distancia
    @GameTest(template = "empty", batch = "track", timeoutTicks = 100)
    public static void bigJumpStopsRecording(GameTestHelper helper) {
        configure();
        ServerPlayer p = player(helper, "jp");
        List<String> wrong = new ArrayList<>();
        run(p, "/event track record " + name("salto") + " foot");
        walk(p, 1, 3);
        TestPlayers.received(p);
        walk(p, 40, 1);
        check(wrong, !Tracks.recording(p.getUUID()), "el salto no detuvo la grabación");
        check(wrong, chat(p).stream().anyMatch(m -> m.contains("teleported")), "sin aviso al jugador");
        done(helper, wrong, p);
    }

    // pista de bote recorrida a pie: pasado el margen se detiene
    @GameTest(template = "empty", batch = "track", timeoutTicks = 100)
    public static void wrongTravelStopsRecordingAfterGrace(GameTestHelper helper) {
        configure();
        ServerPlayer p = player(helper, "bt");
        List<String> wrong = new ArrayList<>();
        run(p, "/event track record " + name("bote") + " boat");
        walk(p, 0.5, 15);
        check(wrong, Tracks.recording(p.getUUID()), "se detuvo dentro del margen");
        walk(p, 0.5, 10);
        check(wrong, !Tracks.recording(p.getUUID()), "siguió grabando a pie una pista de bote");
        done(helper, wrong, p);
    }

    @GameTest(template = "empty", batch = "track", timeoutTicks = 100)
    public static void namesAndOwnership(GameTestHelper helper) {
        configure();
        ServerPlayer a = player(helper, "na");
        ServerPlayer b = player(helper, "nb");
        String track = name("mia");
        List<String> wrong = new ArrayList<>();
        check(wrong, run(a, "/event track record dos.palabras§c foot") == 0, "aceptó un nombre con formato");
        run(a, "/event track record " + track + " foot");
        check(wrong, run(b, "/event track record " + track.toUpperCase() + " foot") == 0, "dos grabaciones con el mismo nombre");
        walk(a, 1, 12);
        check(wrong, run(a, "/event track save") == 1, "guardar falló");
        check(wrong, run(b, "/event track record " + track + " foot") == 0, "grabó con el nombre de una pista guardada");
        check(wrong, run(b, "/event track delete " + track) == 0, "borró una pista ajena");
        check(wrong, HuddleData.get(a.server).tracks().containsKey(track), "la pista ajena desapareció");
        check(wrong, runAsServer(a.server, "/event track delete " + track) == 1, "el operador no pudo borrar");
        check(wrong, !HuddleData.get(a.server).tracks().containsKey(track), "sigue después de borrarla");
        done(helper, wrong, a, b);
    }

    // tope por jugador: sin él, uno solo podría llenar el guardado del mundo
    @GameTest(template = "empty", batch = "track", timeoutTicks = 100)
    public static void trackLimitPerPlayer(GameTestHelper helper) {
        configure();
        ServerPlayer p = player(helper, "lm");
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            run(p, "/event track record " + name("tope") + " foot");
            walk(p, 1, 12);
            check(wrong, run(p, "/event track save") == 1, "guardar " + i + " falló");
        }
        check(wrong, run(p, "/event track record " + name("tope") + " foot") == 0, "pasó el tope");
        done(helper, wrong, p);
    }

    // grabar y jugar a la vez dejaría al jugador en dos lugares
    @GameTest(template = "empty", batch = "track", timeoutTicks = 100)
    public static void recordingAndEventsExcludeEachOther(GameTestHelper helper) {
        configure();
        ServerPlayer a = player(helper, "ea");
        ServerPlayer b = player(helper, "eb");
        List<String> wrong = new ArrayList<>();
        run(a, "/event track record " + name("ocupado") + " foot");
        check(wrong, run(a, "/event start airdrop") == 0, "abrió un evento mientras grababa");
        check(wrong, run(b, "/event start airdrop") == 1, "start falló");
        check(wrong, run(b, "/event track record " + name("ocupado") + " foot") == 0, "grabó dentro de un evento");
        Tracks.cancel(a.getUUID());
        check(wrong, run(a, "/event join " + b.getGameProfile().getName()) == 1, "no pudo unirse tras cancelar");
        done(helper, wrong, a, b);
    }

    @GameTest(template = "empty", batch = "track", timeoutTicks = 100)
    public static void logoutDropsRecording(GameTestHelper helper) {
        configure();
        ServerPlayer p = player(helper, "lo");
        List<String> wrong = new ArrayList<>();
        run(p, "/event track record " + name("salida") + " foot");
        TestPlayers.leave(helper, p);
        Tracks.tick(helper.getLevel().getServer());
        check(wrong, !Tracks.recording(p.getUUID()), "la grabación quedó viva tras salir");
        done(helper, wrong);
    }

    // un punto en la base de alguien avisa al que graba, una sola vez por chunk
    @GameTest(template = "empty", batch = "track", timeoutTicks = 100)
    public static void busyChunkWarns(GameTestHelper helper) {
        configure();
        ServerPlayer p = player(helper, "bz");
        List<String> wrong = new ArrayList<>();
        net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(p.blockPosition());
        p.serverLevel().getChunk(chunk.x, chunk.z).setInhabitedTime(20L * 60 * 30);
        p.teleportTo(p.serverLevel(), chunk.getMinBlockX() + 1.5, p.getY(), p.getZ(), 0, 0);
        run(p, "/event track record " + name("base") + " foot");
        TestPlayers.received(p);
        check(wrong, run(p, "/event track point") == 1, "punto falló");
        check(wrong, chat(p).stream().anyMatch(m -> m.contains("someone's base")), "sin aviso de base");
        p.teleportTo(p.serverLevel(), chunk.getMinBlockX() + 14.5, p.getY(), p.getZ(), 0, 0);
        Tracks.tick(p.server);
        run(p, "/event track point");
        check(wrong, Tracks.recording(p.getUUID()), "se detuvo la grabación");
        check(wrong, chat(p).stream().noneMatch(m -> m.contains("someone's base")), "repite el aviso en el mismo chunk");
        done(helper, wrong, p);
    }

    // guardado del mundo editado a mano o roto: se carga lo que sirve, sin romper el arranque
    @GameTest(template = "empty", batch = "track", timeoutTicks = 100)
    public static void corruptSavedTracksLoad(GameTestHelper helper) {
        List<String> wrong = new ArrayList<>();
        CompoundTag good = new CompoundTag();
        good.putString("mode", "BOAT");
        good.putString("dim", "minecraft:overworld");
        good.putInt("laps", 999);
        ListTag points = new ListTag();
        for (double[] v : new double[][] {{10, 64, 0, 5}, {Double.NaN, 64, 0, 5}, {10, 64, 1, 5}, {40, 64, 0, 0}}) {
            CompoundTag c = new CompoundTag();
            c.putDouble("x", v[0]);
            c.putDouble("y", v[1]);
            c.putDouble("z", v[2]);
            c.putDouble("r", v[3]);
            points.add(c);
        }
        good.put("points", points);
        CompoundTag badMode = good.copy();
        badMode.putString("mode", "ROCKET");
        CompoundTag badDim = good.copy();
        badDim.putString("dim", "No Es Un Id");
        CompoundTag noPoints = good.copy();
        noPoints.remove("points");
        CompoundTag wrongType = good.copy();
        wrongType.putString("points", "hola");
        CompoundTag root = new CompoundTag();
        CompoundTag tracks = new CompoundTag();
        tracks.put("buena", good);
        tracks.put("modo", badMode);
        tracks.put("dim", badDim);
        tracks.put("vacia", noPoints);
        tracks.put("tipo", wrongType);
        tracks.put("Mal Nombre", good.copy());
        tracks.putString("texto", "no es un compuesto");
        root.put("tracks", tracks);
        try {
            HuddleData data = HuddleData.load(root, helper.getLevel().registryAccess());
            check(wrong, data.tracks().keySet().equals(java.util.Set.of("buena")), "cargó: " + data.tracks().keySet());
            Tracks.Stored s = data.tracks().get("buena");
            if (s != null) {
                check(wrong, s.route().checkpoints().size() == 2, "puntos rotos o repetidos cargados: " + s.route().checkpoints().size());
                check(wrong, s.route().laps() == Route.MAX_LAPS, "vueltas sin tope");
                check(wrong, s.route().checkpoints().get(1).radius() == Route.MIN_RADIUS, "radio 0 cargado");
                check(wrong, s.route().author() == null && s.route().authorName().equals("?"), "autor inventado");
            }
        } catch (RuntimeException e) {
            wrong.add("la carga lanzó " + e);
        }
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join("; ", wrong));
        }
        helper.succeed();
    }
}
