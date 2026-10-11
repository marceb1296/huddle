package dev.huddle.neoforge;

import dev.huddle.core.Pos;
import dev.huddle.core.RaceState;
import dev.huddle.core.Records;
import dev.huddle.core.Route;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// carreras completas con jugadores reales del servidor: sala, largada, puntos, llegada y regreso a casa
@GameTestHolder(Huddle.MODID)
@PrefixGameTestTemplate(false)
public final class RaceEventTest {
    private static int seq;

    // las pruebas del lote corren a la vez: todas usan esta misma configuración
    private static void configure() {
        HuddleConfig.TypeValues v = HuddleConfig.TYPES.get("race");
        v.enabled.set(true);
        v.minPlayers.set(2);
        v.maxPlayers.set(2);
        v.lobbySeconds.set(60);
        v.countdownSeconds.set(1);
        v.cooldownMinutes.set(0);
        v.maxArmor.set(-1);
        v.maxEnchantLevel.set(-1);
        HuddleConfig.ANNOUNCE_RADIUS.set(64);
        HuddleConfig.MAX_DISTANCE.set(5);
        HuddleConfig.DISCONNECT_GRACE_SECONDS.set(1);
        HuddleConfig.MAX_JUMP_BLOCKS.set(30);
        HuddleConfig.TRAVEL_GRACE_SECONDS.set(1);
        HuddleConfig.RACE_MAX_MINUTES.set(10);
        HuddleConfig.RACE_FINISH_WINDOW_SECONDS.set(2);
        HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(true);
        HuddleConfig.DEATH_DESTINATION.set(HuddleConfig.DeathDestination.JOIN_POINT);
    }

    private record Setup(ServerPlayer a, ServerPlayer b, Vec3 homeA, Vec3 homeB, Pos start, String track) {
    }

    // pista recta sobre x desde la largada, con los jugadores esperando a unos bloques de ella
    private static Setup setup(GameTestHelper helper, Route.Mode mode, double... xs) {
        configure();
        Vec3 base = helper.absoluteVec(new Vec3(2, 1, 2));
        Pos start = new Pos(base.x, base.y, base.z);
        List<Route.Checkpoint> cps = new ArrayList<>();
        for (double x : xs) {
            cps.add(new Route.Checkpoint(new Pos(start.x() + x, start.y(), start.z()), 3));
        }
        String name = "r" + (++seq);
        Route route = Route.of(name, null, "test", mode, 1, start, cps).orElseThrow();
        HuddleData.get(helper.getLevel().getServer()).tracks().put(name,
                new Tracks.Stored(route, helper.getLevel().dimension(), new Records()));
        Vec3 homeA = base.add(0, 0, 6);
        Vec3 homeB = base.add(2, 0, 6);
        ServerPlayer a = TestPlayers.join(helper, "ra" + seq);
        ServerPlayer b = TestPlayers.join(helper, "rb" + seq);
        a.teleportTo(helper.getLevel(), homeA.x, homeA.y, homeA.z, 0, 0);
        b.teleportTo(helper.getLevel(), homeB.x, homeB.y, homeB.z, 0, 0);
        return new Setup(a, b, homeA, homeB, start, name);
    }

    private static int run(ServerPlayer p, String command) {
        int[] result = {0};
        p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withCallback((ok, r) -> result[0] = ok ? r : 0), command);
        return result[0];
    }

    private static void open(Setup s, List<String> wrong) {
        check(wrong, run(s.a, "/event start race " + s.track) == 1, "start falló");
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 1, "join falló");
    }

    private static void step(ServerPlayer p, double dx) {
        p.teleportTo(p.serverLevel(), p.getX() + dx, p.getY(), p.getZ(), p.getYRot(), p.getXRot());
    }

    private static boolean near(ServerPlayer p, Vec3 at) {
        return p.position().distanceTo(at) < 0.01;
    }

    // por UUID: las pistas de otras pruebas del lote pueden pasar cerca
    private static long alive(GameTestHelper helper, List<UUID> ids) {
        return ids.stream().filter(id -> helper.getLevel().getEntity(id) != null).count();
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
            Homecoming.forget(p.server, p.getUUID());
            TestPlayers.leave(helper, p);
        }
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join("; ", wrong));
        }
        helper.succeed();
    }

    // de punta a punta: largada, ganador, récord, marcadores borrados y todos de vuelta en casa
    @GameTest(template = "empty", batch = "race", timeoutTicks = 400)
    public static void fullRace(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.FOOT, 10, 20);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        boolean[] seenAtStart = {false};
        boolean[] glowSeen = {false};
        boolean[] markersSeen = {false};
        List<UUID> ids = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        helper.onEachTick(() -> {
            if (r.race() == null || !Lobbies.all().contains(r)) {
                return;
            }
            for (Packet<?> packet : TestPlayers.received(s.a)) {
                if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket t) {
                    titles.add(t.text().getString());
                }
            }
            if (!r.race().racing()) {
                seenAtStart[0] |= s.a.position().distanceTo(new Vec3(s.start.x(), s.start.y(), s.start.z())) < 4;
                if (ids.isEmpty()) {
                    ids.addAll(r.race().displayIds());
                }
                markersSeen[0] |= ids.size() == 4 && alive(helper, ids) == 4;
                return;
            }
            glowSeen[0] |= Glow.remembers(s.a.getUUID());
            step(s.a, 1);
            step(s.b, 0.5);
        });
        helper.runAfterDelay(220, () -> {
            check(wrong, seenAtStart[0], "no llevó a nadie a la largada");
            // el primer tick de la cuenta llega con un tick ya pasado: el 3 no se mostraba
            // antes viene el "1" de la cuenta de la sala, de un segundo en esta prueba
            int three = titles.indexOf("3");
            check(wrong, three >= 0 && titles.size() >= three + 4 && titles.subList(three, three + 4).equals(List.of("3", "2", "1", "Go!")),
                    "cuenta: " + titles);
            check(wrong, markersSeen[0], "sin marcadores numerados en la pista");
            check(wrong, glowSeen[0], "el próximo punto no brilló para el jugador");
            check(wrong, !Lobbies.all().contains(r), "la carrera no terminó");
            check(wrong, r.race() != null && r.race().state() != null
                    && r.race().state().results().stream().map(RaceState.Result::player).toList().equals(List.of(s.a.getUUID(), s.b.getUUID())),
                    "orden de llegada mal");
            Records rec = HuddleData.get(s.a.server).tracks().get(s.track).records();
            check(wrong, !rec.top().isEmpty() && rec.top().get(0).player().equals(s.a.getUUID()), "sin récord del ganador");
            check(wrong, !ids.isEmpty() && alive(helper, ids) == 0, "quedaron marcadores en el mundo");
            check(wrong, near(s.a, s.homeA) && near(s.b, s.homeB), "no volvieron a casa: " + s.a.position() + " " + s.b.position());
            check(wrong, !Homecoming.pending(s.a.server, s.a.getUUID()), "regreso pendiente sin borrar");
            check(wrong, !Glow.remembers(s.a.getUUID()), "el brillo siguió después de la carrera");
            check(wrong, Participants.of(s.a) == null && Lobbies.of(s.a) == null, "siguen anotados");
            done(helper, wrong, s.a, s.b);
        });
    }

    // quien sigue corriendo ve en el centro de la pantalla quién llegó y en qué puesto; al terminar, todos los de la
    // carrera ven en grande quién ganó (el ganador, "You won!"). Quien no corre no ve nada de eso
    @GameTest(template = "empty", batch = "race", timeoutTicks = 400)
    public static void finishNoticesReachOnlyRacers(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.FOOT, 10, 20);
        ServerPlayer c = TestPlayers.join(helper, "rc" + seq);
        c.teleportTo(helper.getLevel(), s.homeA.x, s.homeA.y, s.homeA.z, 0, 0);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        String winner = s.a.getGameProfile().getName();
        int[] notices = {0};
        List<String> titlesA = new ArrayList<>();
        List<String> titlesB = new ArrayList<>();
        List<String> leaked = new ArrayList<>();
        boolean[] over = {false};
        helper.onEachTick(() -> {
            if (over[0]) {
                return;
            }
            over[0] = !Lobbies.all().contains(r);
            for (Packet<?> packet : TestPlayers.received(s.b)) {
                if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket st
                        && st.text().getString().contains(winner + " finished #1")) {
                    notices[0]++;
                }
                if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket t) {
                    titlesB.add(t.text().getString());
                }
            }
            for (Packet<?> packet : TestPlayers.received(s.a)) {
                if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket t) {
                    titlesA.add(t.text().getString());
                }
            }
            // el anuncio de la sala sí le llega: está en el radio
            List<Packet<?>> toC = TestPlayers.received(c);
            for (Packet<?> packet : r.race() == null ? List.<Packet<?>>of() : toC) {
                // los de Huddle; el récord nuevo sí se anuncia a todo el servidor
                if (packet instanceof ClientboundSystemChatPacket cp && cp.content().getString().contains(winner)
                        && cp.content().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                        && t.getKey().startsWith("huddle.") && !t.getKey().equals("huddle.race.record")
                        || packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket) {
                    leaked.add(packet.toString());
                }
            }
            // el ganador vuelve a casa antes del final: igual ve que ganó
            if (r.race() != null && r.race().state() != null && Lobbies.of(s.a) != null
                    && r.race().state().status(s.a.getUUID()) == RaceState.Status.FINISHED) {
                check(wrong, run(s.a, "/event leave") == 1, "no pudo volver a casa");
            }
            if (r.race() != null && r.race().racing() && !over[0]) {
                step(s.a, 1);
                step(s.b, 0.4);
            }
        });
        helper.runAfterDelay(260, () -> {
            check(wrong, over[0], "la carrera no terminó");
            check(wrong, notices[0] == 1, "aviso de llegada en el centro: " + notices[0] + " veces");
            check(wrong, titlesB.contains(winner + " won!"), "el otro no vio quién ganó: " + titlesB);
            check(wrong, titlesA.contains("You won!"), "el ganador no vio que ganó: " + titlesA);
            check(wrong, leaked.isEmpty(), "le llegó a quien no corre: " + leaked);
            TestPlayers.leave(helper, c);
            done(helper, wrong, s.a, s.b);
        });
    }

    // perla, fruta coral o /tp corto en plena carrera: descalifica y vuelve a casa; el otro sigue
    @GameTest(template = "empty", batch = "race", timeoutTicks = 300)
    public static void teleportDisqualifies(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.FOOT, 10, 60);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0] || r.race() == null || !r.race().racing()) {
                return;
            }
            done[0] = true;
            MinecraftServer server = s.a.server;
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), String.format(Locale.ROOT, "/tp %s %.3f %.3f %.3f",
                    s.a.getGameProfile().getName(), s.a.getX() + 3, s.a.getY(), s.a.getZ()));
        });
        helper.runAfterDelay(140, () -> {
            check(wrong, done[0], "la carrera no arrancó");
            check(wrong, r.race() != null && r.race().state() != null
                    && r.race().state().status(s.a.getUUID()) == RaceState.Status.DISQUALIFIED, "el /tp no descalificó");
            check(wrong, Lobbies.of(s.a) == null && near(s.a, s.homeA), "el descalificado no volvió a casa");
            check(wrong, Lobbies.of(s.b) != null && Lobbies.all().contains(r), "la carrera se cortó para el otro");
            done(helper, wrong, s.a, s.b);
        });
    }

    // en la largada quedan lado a lado, sin pisarse, sobre la línea y mirando al primer punto, no entre ellos
    @GameTest(template = "empty", batch = "race_grid", timeoutTicks = 300)
    public static void startGridSideBySide(GameTestHelper helper) {
        lineUp(helper, Route.Mode.FOOT, 1.0);
    }

    @GameTest(template = "empty", batch = "race_grid", timeoutTicks = 300)
    public static void lentBoatsStartSideBySide(GameTestHelper helper) {
        lineUp(helper, Route.Mode.BOAT, 1.4);
    }

    private static void lineUp(GameTestHelper helper, Route.Mode mode, double apart) {
        Setup s = setup(helper, mode, 10, 60);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        int[] seen = {0};
        helper.onEachTick(() -> {
            if (r.race() == null || r.race().racing() || !Lobbies.all().contains(r)) {
                return;
            }
            seen[0]++;
            Entity ea = s.a.getVehicle() == null ? s.a : s.a.getVehicle();
            Entity eb = s.b.getVehicle() == null ? s.b : s.b.getVehicle();
            double dx = ea.getX() - eb.getX(), dz = ea.getZ() - eb.getZ();
            if (Math.sqrt(dx * dx + dz * dz) < apart) {
                check(wrong, false, "encimados en la largada: " + ea.position() + " " + eb.position());
            }
            for (ServerPlayer p : List.of(s.a, s.b)) {
                if (p.getX() > s.start.x() + 0.01) {
                    check(wrong, false, "delante de la línea: " + p.position());
                }
                if (Math.abs(net.minecraft.util.Mth.wrapDegrees(p.getYRot() + 90)) > 1) {
                    check(wrong, false, "no mira al primer punto: " + p.getYRot());
                }
            }
        });
        helper.runAfterDelay(140, () -> {
            check(wrong, seen[0] > 20, "no hubo cuenta en la largada");
            done(helper, wrong.stream().distinct().limit(4).toList(), s.a, s.b);
        });
    }

    // un lugar de la parrilla dentro de una pared, detrás de ella o sobre un hueco se salta: nadie queda encerrado,
    // del otro lado de la pared ni en el aire
    // en su propio lote: los bloques que pone pisarían las pruebas vecinas
    @GameTest(template = "empty", batch = "race_grid_wall", timeoutTicks = 300)
    public static void gridSkipsWallsAndHoles(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.FOOT, 10, 60);
        net.minecraft.core.BlockPos c = net.minecraft.core.BlockPos.containing(s.start.x(), s.start.y(), s.start.z());
        Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> before = new java.util.HashMap<>();
        // plataforma propia: el suelo del mundo de pruebas no está justo debajo de la largada
        for (int x = -5; x <= 2; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = -1; y <= 2; y++) {
                    put(helper, before, c.offset(x, y, z), (y < 0 ? net.minecraft.world.level.block.Blocks.STONE
                            : net.minecraft.world.level.block.Blocks.AIR).defaultBlockState());
                }
            }
        }
        for (int x = -3; x <= 0; x++) {
            for (int y = 0; y <= 1; y++) {
                put(helper, before, c.offset(x, y, -1), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
        }
        // un pilar que no toca el centro del lugar pero sí el cuerpo del jugador
        for (int x = -1; x <= 0; x++) {
            for (int y = 0; y <= 1; y++) {
                put(helper, before, c.offset(x, y, 1), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
        }
        // el hueco abarca todo lo que pisaría un jugador en el segundo lugar de la segunda fila
        for (int x = -2; x <= -2; x++) {
            for (int y = -2; y <= -1; y++) {
                for (int z = 0; z <= 1; z++) {
                    put(helper, before, c.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
            }
        }
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        int[] seen = {0};
        int[] moved = {0};
        helper.onEachTick(() -> {
            if (r.race() == null || r.race().racing() || !Lobbies.all().contains(r)) {
                return;
            }
            seen[0]++;
            // quieto en su lugar, lejos de la largada: no se lo vuelve a mover en cada tick
            moved[0] += (int) TestPlayers.received(s.a).stream()
                    .filter(pk -> pk instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket).count();
            for (ServerPlayer p : List.of(s.a, s.b)) {
                net.minecraft.world.phys.AABB box = p.getBoundingBox();
                if (!helper.getLevel().noCollision(box)) {
                    check(wrong, false, String.format(Locale.ROOT, "dentro de un bloque: %s a %.2f %.2f %.2f de la largada",
                            p == s.a ? "a" : "b", p.getX() - s.start.x(), p.getY() - s.start.y(), p.getZ() - s.start.z()));
                }
                if (helper.getLevel().noCollision(box.move(0, -0.6, 0))) {
                    check(wrong, false, "sin suelo: " + p.position());
                }
                if (p.getZ() < c.getZ()) {
                    check(wrong, false, "del otro lado de la pared: " + p.position());
                }
            }
        });
        helper.runAfterDelay(140, () -> {
            check(wrong, seen[0] > 20, "no hubo cuenta en la largada");
            check(wrong, moved[0] <= 5, "lo reubicó " + moved[0] + " veces sin moverse");
            check(wrong, s.a.position().distanceTo(new Vec3(s.start.x(), s.start.y(), s.start.z())) > 1.5, "a no quedó lejos de la largada");
            before.forEach((pos, state) -> helper.getLevel().setBlockAndUpdate(pos, state));
            done(helper, wrong.stream().distinct().limit(4).toList(), s.a, s.b);
        });
    }

    // largada en el borde de un lago: los botes van al agua, nunca a la orilla aunque el primer lugar caiga ahí
    @GameTest(template = "empty", batch = "race_grid_water", timeoutTicks = 300)
    public static void gridKeepsBoatsOnWater(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.BOAT, 10, 60);
        net.minecraft.core.BlockPos c = net.minecraft.core.BlockPos.containing(s.start.x(), s.start.y(), s.start.z());
        Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> before = new java.util.HashMap<>();
        for (int x = -8; x <= 12; x++) {
            for (int z = 0; z <= 3; z++) {
                put(helper, before, c.offset(x, -1, z), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
            }
        }
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        int[] seen = {0};
        helper.onEachTick(() -> {
            if (r.race() == null || r.race().racing() || !Lobbies.all().contains(r)) {
                return;
            }
            seen[0]++;
            for (ServerPlayer p : List.of(s.a, s.b)) {
                Entity boat = p.getVehicle();
                if (boat == null || !helper.getLevel().getFluidState(boat.blockPosition().below()).is(net.minecraft.tags.FluidTags.WATER)) {
                    check(wrong, false, "bote fuera del agua: " + (boat == null ? "sin bote" : boat.position()));
                }
            }
        });
        helper.runAfterDelay(140, () -> {
            check(wrong, seen[0] > 20, "no hubo cuenta en la largada");
            done(helper, wrong.stream().distinct().limit(4).toList(), s.a, s.b);
            before.forEach((pos, state) -> helper.getLevel().setBlockAndUpdate(pos, state));
        });
    }

    private static void put(GameTestHelper helper, Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> before,
                            net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        before.putIfAbsent(pos, helper.getLevel().getBlockState(pos));
        helper.getLevel().setBlockAndUpdate(pos, state);
    }

    // cada punto suena con el mismo volumen, la nota sube con el avance y nunca llega a los agudos que molestan
    @GameTest(template = "empty", batch = "race_grid", timeoutTicks = 300)
    public static void checkpointSoundsStayPleasant(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.FOOT, 6, 12, 18, 24, 30, 36);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        List<float[]> sounds = new ArrayList<>();
        helper.onEachTick(() -> {
            if (!Lobbies.all().contains(r)) {
                return;
            }
            if (r.race() == null || !r.race().racing()) {
                TestPlayers.received(s.a);
                return;
            }
            step(s.a, 1);
            for (Packet<?> packet : TestPlayers.received(s.a)) {
                if (packet instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket sp
                        && (sp.getSound().value() == SoundEvents.NOTE_BLOCK_PLING.value()
                        || sp.getSound().value() == SoundEvents.EXPERIENCE_ORB_PICKUP)) {
                    sounds.add(new float[] {sp.getVolume(), sp.getPitch()});
                }
            }
        });
        helper.runAfterDelay(160, () -> {
            check(wrong, sounds.size() >= 5, "sonaron " + sounds.size() + " puntos");
            float last = 0;
            java.util.Set<Float> pitches = new java.util.HashSet<>();
            for (float[] v : sounds) {
                check(wrong, v[0] >= 1f, "volumen bajo: " + v[0]);
                check(wrong, v[1] <= 1.2f, "demasiado agudo: " + v[1]);
                check(wrong, v[1] >= last, "la nota bajó: " + last + " a " + v[1]);
                last = v[1];
                pitches.add(v[1]);
            }
            check(wrong, pitches.size() >= 3, "suenan casi igual: " + pitches);
            done(helper, wrong.stream().distinct().toList(), s.a, s.b);
        });
    }

    // quien muere en la sala y sigue muerto al arrancar queda fuera: no va a la largada, no recibe bote ni queda
    // anotado para volver a una casa que sería el lugar donde murió
    @GameTest(template = "empty", batch = "race", timeoutTicks = 300)
    public static void deadInLobbyIsLeftOut(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.BOAT, 10, 60);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        s.b.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000);
        check(wrong, s.b.isDeadOrDying(), "no murió en la sala");
        Vec3 diedAt = s.b.position();
        helper.runAfterDelay(140, () -> {
            check(wrong, Lobbies.of(s.b) == null && Participants.of(s.b) == null, "el muerto quedó en el evento");
            check(wrong, !Homecoming.pending(s.b.server, s.b.getUUID()), "el muerto quedó anotado para volver a casa");
            check(wrong, s.b.getVehicle() == null && s.b.position().distanceTo(diedAt) < 0.01, "llevó al muerto a la largada: " + s.b.position());
            check(wrong, !Lobbies.all().contains(r), "la carrera siguió con un solo jugador");
            done(helper, wrong, s.a, s.b);
        });
    }

    // adelantarse en la cuenta 3, 2, 1 devuelve a la largada
    @GameTest(template = "empty", batch = "race", timeoutTicks = 300)
    public static void falseStartGoesBack(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.FOOT, 10, 60);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        boolean[] cheated = {false};
        boolean[] passedEarly = {false};
        helper.onEachTick(() -> {
            if (r.race() == null || !Lobbies.all().contains(r)) {
                return;
            }
            if (!r.race().racing()) {
                // corre hacia el primer punto durante la cuenta
                cheated[0] = true;
                step(s.a, 2);
            } else {
                passedEarly[0] |= r.race().state().nextCheckpoint(s.a.getUUID()) > 0;
            }
        });
        helper.runAfterDelay(130, () -> {
            check(wrong, cheated[0], "no hubo cuenta");
            check(wrong, !passedEarly[0], "pasó un punto antes de la largada");
            check(wrong, Math.abs(s.a.getX() - s.start.x()) < 1.6, "no volvió a la largada: " + (s.a.getX() - s.start.x()));
            done(helper, wrong, s.a, s.b);
        });
    }

    // bote: cada uno sale en uno prestado (quien trae el suyo lo deja en casa); el de otro no se monta ni se rompe, quien se
    // baja queda fuera pasado el margen y al terminar no queda ningún bote prestado ni ítem de bote
    @GameTest(template = "empty", batch = "race", timeoutTicks = 300)
    public static void boatRaceLendsBoats(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.BOAT, 10, 60);
        List<String> wrong = new ArrayList<>();
        // dentro del pozo de la prueba: alrededor puede haber paredes de barrera de otras pruebas
        Vec3 home = helper.absoluteVec(new Vec3(0.5, 0, 4.5));
        Vec3 homeB = helper.absoluteVec(new Vec3(4.5, 0, 4.5));
        s.a.teleportTo(helper.getLevel(), home.x, home.y, home.z, 0, 0);
        s.b.teleportTo(helper.getLevel(), homeB.x, homeB.y, homeB.z, 0, 0);
        Boat own = EntityType.BOAT.create(helper.getLevel());
        own.moveTo(home.x, home.y, home.z, 0, 0);
        helper.getLevel().addFreshEntity(own);
        check(wrong, s.a.startRiding(own, true), "no subió a su bote");
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        helper.runAfterDelay(160, () -> {
            check(wrong, r.race() != null && r.race().racing(), "no arrancó");
            Entity va = s.a.getVehicle(), vb = s.b.getVehicle();
            check(wrong, va instanceof Boat && va != own && LentMounts.isLent(va), "a sin bote prestado: " + va);
            check(wrong, vb instanceof Boat && LentMounts.isLent(vb), "b sin bote prestado: " + vb);
            check(wrong, own.isAlive() && own.position().distanceTo(home) < 1, "el bote propio no quedó en casa: " + own.position());
            if (va == null || vb == null) {
                done(helper, wrong, s.a, s.b);
                return;
            }
            check(wrong, Math.hypot(va.getX() - s.start.x(), va.getZ() - s.start.z()) < 2, "el bote prestado no está en la largada");
            s.b.stopRiding();
            s.b.interactOn(va, InteractionHand.MAIN_HAND);
            check(wrong, s.b.getVehicle() != va, "b se subió al bote de a");
            // un golpe que rompería cualquier bote
            va.hurt(helper.getLevel().damageSources().playerAttack(s.b), 100);
            check(wrong, va.isAlive(), "b rompió el bote prestado");
            check(wrong, boatItems(helper, va.position()) == 0, "el bote prestado soltó su ítem");
            helper.runAfterDelay(60, () -> {
                check(wrong, r.race().state().status(s.b.getUUID()) == RaceState.Status.DISQUALIFIED, "el que se bajó sigue en carrera");
                check(wrong, !vb.isAlive(), "el bote prestado de b quedó en el mundo");
                check(wrong, near(s.b, homeB), "b no volvió a casa: " + s.b.position() + " casa " + homeB);
                check(wrong, r.race().state().status(s.a.getUUID()) == RaceState.Status.RUNNING, "descalificó al del bote");
                s.a.server.getCommands().performPrefixedCommand(s.a.server.createCommandSourceStack(), "/event end " + s.a.getGameProfile().getName());
                check(wrong, !va.isAlive(), "el bote prestado de a quedó en el mundo");
                // se unió sentado en su bote: vuelve sentado en él
                check(wrong, s.a.getVehicle() == own, "a no volvió a su bote: " + s.a.position() + " montado en " + s.a.getVehicle());
                check(wrong, boatItems(helper, va.position()) == 0 && boatItems(helper, home) == 0, "quedaron ítems de bote");
                check(wrong, own.isAlive(), "se llevó el bote propio");
                s.a.stopRiding();
                // uno que quedó de una caída del servidor no vuelve a entrar al mundo
                Boat stray = EntityType.BOAT.create(helper.getLevel());
                stray.addTag(LentMounts.TAG);
                stray.moveTo(s.homeB.x, s.homeB.y, s.homeB.z, 0, 0);
                check(wrong, !helper.getLevel().addFreshEntity(stray) && helper.getLevel().getEntity(stray.getUUID()) == null,
                        "un bote prestado sobrante entró al mundo");
                own.discard();
                done(helper, wrong, s.a, s.b);
            });
        });
    }

    // se unió en su bote y alguien lo rompió durante la carrera: vuelve a pie a donde lo dejó, no al spawn (sentado, sus pies
    // quedan bajo el suelo; el regreso usa la posición del bote)
    @GameTest(template = "empty", batch = "race", timeoutTicks = 300)
    public static void ownBoatGoneReturnsWhereItWas(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.BOAT, 10, 60);
        List<String> wrong = new ArrayList<>();
        Vec3 home = helper.absoluteVec(new Vec3(0.5, 0, 4.5));
        s.a.teleportTo(helper.getLevel(), home.x, home.y, home.z, 0, 0);
        Boat own = EntityType.BOAT.create(helper.getLevel());
        own.moveTo(home.x, home.y, home.z, 0, 0);
        helper.getLevel().addFreshEntity(own);
        check(wrong, s.a.startRiding(own, true), "no subió a su bote");
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        helper.runAfterDelay(160, () -> {
            check(wrong, r.race() != null && r.race().racing(), "no arrancó");
            own.discard();
            s.a.server.getCommands().performPrefixedCommand(s.a.server.createCommandSourceStack(), "/event end " + s.a.getGameProfile().getName());
            check(wrong, s.a.position().distanceTo(home) < 1 && s.a.getVehicle() == null, "a no volvió a donde dejó su bote: "
                    + s.a.position() + " casa " + home);
            done(helper, wrong, s.a, s.b);
        });
    }

    // caballo: prestado con montura y la misma velocidad para todos; no se le abre el inventario y muerto no suelta la montura.
    // Quien vino en su caballo y lo encuentra ocupado por otro vuelve a pie a donde lo dejó, no al spawn ni junto al caballo
    @GameTest(template = "empty", batch = "race", timeoutTicks = 300)
    public static void horseRaceLendsSaddledHorses(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.HORSE, 10, 60);
        List<String> wrong = new ArrayList<>();
        Vec3 home = helper.absoluteVec(new Vec3(0.5, 0, 4.5));
        s.a.teleportTo(helper.getLevel(), home.x, home.y, home.z, 0, 0);
        // domado: uno sin domar tira al jinete antes de la largada
        Horse own = EntityType.HORSE.create(helper.getLevel());
        own.setTamed(true);
        own.moveTo(home.x, home.y, home.z, 0, 0);
        helper.getLevel().addFreshEntity(own);
        check(wrong, s.a.startRiding(own, true), "no subió a su caballo");
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        helper.runAfterDelay(160, () -> {
            check(wrong, r.race() != null && r.race().racing(), "no arrancó");
            if (!(s.a.getVehicle() instanceof Horse ha) || !(s.b.getVehicle() instanceof Horse hb)) {
                check(wrong, false, "sin caballo prestado: " + s.a.getVehicle() + " " + s.b.getVehicle());
                done(helper, wrong, s.a, s.b);
                return;
            }
            check(wrong, LentMounts.isLent(ha) && ha.isSaddled() && ha.isTamed(), "caballo sin montura o sin domar");
            check(wrong, ha.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) == LentMounts.HORSE_SPEED
                    && hb.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) == LentMounts.HORSE_SPEED, "velocidades distintas");
            ha.openCustomInventoryScreen(s.a);
            check(wrong, s.a.containerMenu == s.a.inventoryMenu, "abrió el inventario del caballo");
            Vec3 at = ha.position();
            s.a.stopRiding();
            // agachado, el clic abre el inventario del caballo
            s.a.setShiftKeyDown(true);
            s.a.interactOn(ha, InteractionHand.MAIN_HAND);
            s.a.setShiftKeyDown(false);
            check(wrong, s.a.containerMenu == s.a.inventoryMenu, "abrió el inventario del caballo agachado");
            ha.kill();
            check(wrong, !ha.isAlive(), "no murió con kill");
            check(wrong, helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(at, at).inflate(4),
                    e -> e.getItem().is(Items.SADDLE) || e.getItem().is(Items.LEATHER)).isEmpty(), "el caballo prestado soltó algo");
            check(wrong, run(s.b, "/event leave") == 1, "b no pudo salir");
            check(wrong, !hb.isAlive(), "el caballo de b quedó en el mundo");
            // b, ya fuera del evento, se lleva el caballo de a a la otra punta del pozo
            own.teleportTo(helper.absoluteVec(new Vec3(4.5, 0, 0.5)).x, home.y, helper.absoluteVec(new Vec3(4.5, 0, 0.5)).z);
            check(wrong, s.b.startRiding(own, true), "b no se subió al caballo de a");
            helper.runAfterDelay(60, () -> {
                check(wrong, r.race().state().status(s.a.getUUID()) == RaceState.Status.DISQUALIFIED, "a sigue en carrera a pie");
                s.b.stopRiding();
                own.discard();
                check(wrong, s.a.position().distanceTo(home) < 1 && s.a.getVehicle() == null, "a no volvió a donde dejó su caballo: "
                        + s.a.position() + " casa " + home);
                done(helper, wrong, s.a, s.b);
            });
        });
    }

    private static long boatItems(GameTestHelper helper, Vec3 at) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(at, at).inflate(6), e -> e.getItem().getItem() instanceof BoatItem).size();
    }

    // con el primero en la meta, los demás tienen un tiempo para llegar y después todos vuelven a casa
    @GameTest(template = "empty", batch = "race", timeoutTicks = 400)
    public static void finishWindowEndsTheRace(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.FOOT, 10, 20);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        helper.onEachTick(() -> {
            if (r.race() != null && r.race().racing() && Lobbies.all().contains(r)) {
                step(s.a, 1);
                step(s.b, 0.05);
            }
        });
        helper.runAfterDelay(260, () -> {
            check(wrong, !Lobbies.all().contains(r), "la carrera siguió después del margen");
            check(wrong, near(s.b, s.homeB) && near(s.a, s.homeA), "no volvieron a casa");
            check(wrong, chat(s.b).stream().anyMatch(m -> m.contains("The race is over") && m.contains(s.a.getGameProfile().getName())),
                    "sin resultados para el que no llegó");
            check(wrong, !Glow.remembers(s.b.getUUID()), "el que seguía corriendo quedó con brillo");
            done(helper, wrong, s.a, s.b);
        });
    }

    // quien llega mientras otros siguen puede quedarse o volver ya con un botón; volver no le quita el resultado
    @GameTest(template = "empty", batch = "race_home", timeoutTicks = 400)
    public static void finisherMayGoHomeEarly(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.FOOT, 10, 20);
        HuddleConfig.RACE_FINISH_WINDOW_SECONDS.set(60);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        helper.onEachTick(() -> {
            if (r.race() != null && r.race().racing() && Lobbies.all().contains(r) && r.participants().contains(s.a.getUUID())) {
                step(s.a, 1);
            }
        });
        helper.runAfterDelay(200, () -> {
            check(wrong, r.race() != null && r.race().state().status(s.a.getUUID()) == RaceState.Status.FINISHED, "a no llegó");
            List<Packet<?>> toA = TestPlayers.received(s.a);
            List<String> clicks = new ArrayList<>();
            for (Packet<?> packet : toA) {
                if (packet instanceof ClientboundSystemChatPacket c && !c.overlay()) {
                    clicks(c.content(), clicks);
                }
            }
            check(wrong, clicks.contains("/event leave"), "sin botón para volver: " + clicks);
            check(wrong, run(s.a, "/event leave") == 1, "no pudo volver");
            check(wrong, near(s.a, s.homeA), "no volvió a casa");
            check(wrong, Lobbies.all().contains(r) && r.race().state().status(s.b.getUUID()) == RaceState.Status.RUNNING, "la carrera terminó para b");
            check(wrong, r.race().state().results().stream().anyMatch(res -> res.player().equals(s.a.getUUID())), "perdió el resultado");
            done(helper, wrong, s.a, s.b);
        });
    }

    // ya empezada y sin nadie en la meta, quien la creó no puede cortarla (iba perdiendo y recuperaba su entrada);
    // un operador sí
    @GameTest(template = "empty", batch = "race_home", timeoutTicks = 300)
    public static void creatorCannotEndARunningRace(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.FOOT, 10, 60);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        helper.runAfterDelay(140, () -> {
            check(wrong, r.race() != null && r.race().racing(), "no arrancó");
            check(wrong, run(s.a, "/event end") == 0, "el creador la terminó en plena carrera");
            check(wrong, Lobbies.all().contains(r), "la carrera terminó");
            s.b.server.getCommands().performPrefixedCommand(s.b.server.createCommandSourceStack(), "/event end " + s.a.getGameProfile().getName());
            check(wrong, !Lobbies.all().contains(r), "el operador no pudo terminarla");
            done(helper, wrong, s.a, s.b);
        });
    }

    // en el margen tras la primera llegada, quien creó la carrera puede cerrarla ya con un botón; cierra con resultados
    @GameTest(template = "empty", batch = "race_home", timeoutTicks = 400)
    public static void creatorMayEndTheWindowEarly(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.FOOT, 10, 20);
        HuddleConfig.RACE_FINISH_WINDOW_SECONDS.set(60);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        helper.onEachTick(() -> {
            if (r.race() != null && r.race().racing() && Lobbies.all().contains(r) && r.participants().contains(s.a.getUUID())) {
                step(s.a, 1);
            }
        });
        helper.runAfterDelay(200, () -> {
            check(wrong, r.race() != null && r.race().state().status(s.a.getUUID()) == RaceState.Status.FINISHED, "a no llegó");
            List<String> clicks = new ArrayList<>();
            for (Packet<?> packet : TestPlayers.received(s.a)) {
                if (packet instanceof ClientboundSystemChatPacket c && !c.overlay()) {
                    clicks(c.content(), clicks);
                }
            }
            check(wrong, clicks.contains("/event end"), "sin botón para terminar: " + clicks);
            check(wrong, run(s.a, "/event end") == 1, "no pudo terminar");
            check(wrong, !Lobbies.all().contains(r), "la carrera siguió");
            check(wrong, near(s.a, s.homeA) && near(s.b, s.homeB), "no volvieron a casa");
            // el canal de prueba entrega en el tick siguiente
            helper.runAfterDelay(1, () -> {
                List<String> said = chat(s.b);
                check(wrong, said.stream().anyMatch(m -> m.contains("The race is over") && m.contains(s.a.getGameProfile().getName())),
                        "terminó sin resultados: " + said);
                done(helper, wrong, s.a, s.b);
            });
        });
    }

    private static void clicks(net.minecraft.network.chat.Component c, List<String> out) {
        if (c.getStyle().getClickEvent() != null) {
            out.add(c.getStyle().getClickEvent().getValue());
        }
        for (net.minecraft.network.chat.Component sib : c.getSiblings()) {
            clicks(sib, out);
        }
        if (c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) {
            for (Object arg : t.getArgs()) {
                if (arg instanceof net.minecraft.network.chat.Component ac) {
                    clicks(ac, out);
                }
            }
        }
    }

    // morir corriendo no saca de la carrera: vuelve al último punto que pasó, sin perder nada, y sigue desde ahí
    // sin que el regreso cuente como salto
    @GameTest(template = "empty", batch = "race", timeoutTicks = 400)
    public static void deathInRaceGoesBackToTheLastCheckpoint(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.FOOT, 10, 60);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        boolean[] died = {false};
        boolean[] walk = {true};
        helper.onEachTick(() -> {
            if (r.race() == null || !r.race().racing() || !Lobbies.all().contains(r)) {
                return;
            }
            if (!died[0] && s.a.getX() >= s.start.x() + 20) {
                died[0] = true;
                walk[0] = false;
                s.a.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000);
                check(wrong, s.a.isAlive(), "murió de verdad");
                check(wrong, Math.hypot(s.a.getX() - (s.start.x() + 10), s.a.getZ() - s.start.z()) < 1.5,
                        "no volvió al último punto: " + s.a.position());
                helper.runAfterDelay(20, () -> {
                    check(wrong, r.race().state().status(s.a.getUUID()) == RaceState.Status.RUNNING, "el regreso lo descalificó");
                    check(wrong, Lobbies.of(s.a) == r, "quedó fuera de la carrera");
                    walk[0] = true;
                });
                return;
            }
            if (walk[0]) {
                step(s.a, 1);
            }
        });
        helper.runAfterDelay(330, () -> {
            check(wrong, died[0], "no llegó a morir");
            check(wrong, r.race() != null && r.race().state() != null
                    && r.race().state().results().stream().map(RaceState.Result::player).toList().equals(List.of(s.a.getUUID())),
                    "no terminó la carrera después de morir");
            done(helper, wrong, s.a, s.b);
        });
    }

    // en bote: quien cae en la cuenta vuelve a su lugar de la parrilla y quien cae corriendo, a la largada (no pasó
    // ningún punto); las dos veces sentado en su bote prestado y sin salir de la carrera
    @GameTest(template = "empty", batch = "race", timeoutTicks = 300)
    public static void boatDeathKeepsTheLentBoat(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.BOAT, 10, 60);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        boolean[] staged = {false};
        helper.onEachTick(() -> {
            if (staged[0] || r.race() == null || r.race().racing() || s.a.getVehicle() == null) {
                return;
            }
            staged[0] = true;
            s.a.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000);
            check(wrong, s.a.isAlive() && Lobbies.of(s.a) == r, "cayó en la cuenta y quedó fuera");
            check(wrong, s.a.getVehicle() != null && LentMounts.isLent(s.a.getVehicle()), "en la cuenta, sin su bote prestado");
            check(wrong, Math.hypot(s.a.getX() - s.start.x(), s.a.getZ() - s.start.z()) < 4, "en la cuenta, lejos de la largada: " + s.a.position());
        });
        helper.runAfterDelay(170, () -> {
            check(wrong, staged[0], "no llegó a caer en la cuenta");
            check(wrong, r.race() != null && r.race().racing(), "no arrancó");
            s.a.invulnerableTime = 0;
            s.a.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000);
            check(wrong, s.a.isAlive() && r.race().state().status(s.a.getUUID()) == RaceState.Status.RUNNING, "cayó corriendo y quedó fuera");
            check(wrong, s.a.getVehicle() != null && LentMounts.isLent(s.a.getVehicle()), "corriendo, sin su bote prestado");
            check(wrong, Math.hypot(s.a.getX() - s.start.x(), s.a.getZ() - s.start.z()) < 1.5, "no volvió a la largada: " + s.a.position());
            done(helper, wrong, s.a, s.b);
        });
    }

    // modo extremo: muere de verdad donde cayó, lo que suelta (ítems, o la tumba o el cuerpo de un mod) queda ahí y reaparece en casa
    @GameTest(template = "empty", batch = "race_extreme", timeoutTicks = 300)
    public static void extremeDeathDropsWhereItHappened(GameTestHelper helper) {
        Setup s = setup(helper, Route.Mode.FOOT, 10, 60);
        HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(false);
        List<String> wrong = new ArrayList<>();
        open(s, wrong);
        Lobbies.Running r = Lobbies.of(s.a);
        helper.runAfterDelay(140, () -> {
            check(wrong, r.race() != null && r.race().racing(), "no arrancó");
            s.a.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 7));
            Vec3 died = s.a.position();
            Map<BlockPos, BlockState> atDeathBefore = blocks(helper, died);
            Map<BlockPos, BlockState> atHomeBefore = blocks(helper, s.homeA);
            s.a.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000);
            check(wrong, s.a.isDeadOrDying(), "no murió");
            // los mods de tumbas colocan la tumba unos ticks después
            helper.runAfterDelay(10, () -> {
                check(wrong, !leftovers(helper, s.a, died, atDeathBefore).isEmpty(), "nada quedó donde murió");
                String home = leftovers(helper, s.a, s.homeA, atHomeBefore);
                check(wrong, home.isEmpty(), "algo quedó en casa:" + home);
                ServerPlayer back = s.a.server.getPlayerList().respawn(s.a, false, Entity.RemovalReason.KILLED);
                check(wrong, near(back, s.homeA), "no reapareció en casa: " + back.position());
                check(wrong, !Homecoming.pending(back.server, back.getUUID()), "regreso pendiente después de reaparecer");
                helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(died, died).inflate(3))
                        .forEach(Entity::discard);
                atDeathBefore.forEach((b, state) -> helper.getLevel().setBlock(b, state, 2));
                HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(true);
                done(helper, wrong, back, s.b);
            });
        });
    }

    private static Map<BlockPos, BlockState> blocks(GameTestHelper helper, Vec3 at) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        BlockPos c = BlockPos.containing(at);
        for (BlockPos b : BlockPos.betweenClosed(c.offset(-3, -3, -3), c.offset(3, 3, 3))) {
            out.put(b.immutable(), helper.getLevel().getBlockState(b));
        }
        return out;
    }

    // ítems, cuerpos y bloques que cambiaron cerca de un punto (sin jugadores ni las marcas de la pista)
    private static String leftovers(GameTestHelper helper, ServerPlayer p, Vec3 at,
            Map<BlockPos, BlockState> before) {
        StringBuilder found = new StringBuilder();
        for (Entity e : helper.getLevel().getEntities(p, new AABB(at, at).inflate(3), e -> !(e instanceof ServerPlayer)
                && !(e instanceof Display) && !(e instanceof Interaction))) {
            found.append(' ').append(e.getType().builtInRegistryHolder().key().location());
        }
        before.forEach((b, state) -> {
            if (helper.getLevel().getBlockState(b) != state) {
                found.append(" bloque ").append(helper.getLevel().getBlockState(b).getBlock().builtInRegistryHolder().key().location());
            }
        });
        return found.toString();
    }

    // caída del servidor en plena carrera: el regreso se guarda con el mundo y ocurre al volver a entrar
    @GameTest(template = "empty", batch = "race", timeoutTicks = 100)
    public static void pendingReturnSurvivesARestart(GameTestHelper helper) {
        configure();
        List<String> wrong = new ArrayList<>();
        ServerPlayer p = TestPlayers.join(helper, "cr" + (++seq));
        Vec3 home = helper.absoluteVec(new Vec3(1, 1, 1));
        // con la montura propia: también se guarda, para volver a sentarlo en ella
        UUID mount = UUID.randomUUID();
        Homecoming.remember(p.server, p.getUUID(), new Participants.Spot(helper.getLevel().dimension(), home, 0, 0, mount));
        HuddleData reloaded = HuddleData.load(HuddleData.get(p.server).save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        check(wrong, reloaded.returns().containsKey(p.getUUID()), "el regreso no se guardó");
        check(wrong, reloaded.returns().containsKey(p.getUUID()) && mount.equals(reloaded.returns().get(p.getUUID()).mount()),
                "la montura propia no se guardó");
        Vec3 track = home.add(20, 0, 0);
        p.teleportTo(helper.getLevel(), track.x, track.y, track.z, 0, 0);
        Homecoming.onLogin(p);
        check(wrong, near(p, home), "no volvió al entrar: " + p.position());
        check(wrong, !Homecoming.pending(p.server, p.getUUID()), "quedó pendiente");
        done(helper, wrong, p);
    }

    // marcadores que quedaron en el mundo por una caída: se borran al cargar
    @GameTest(template = "empty", batch = "race", timeoutTicks = 100)
    public static void leftoverMarkersAreRemoved(GameTestHelper helper) {
        List<String> wrong = new ArrayList<>();
        Vec3 at = helper.absoluteVec(new Vec3(1, 2, 1));
        Entity e = EntityType.BLOCK_DISPLAY.create(helper.getLevel());
        e.moveTo(at.x, at.y, at.z, 0, 0);
        e.addTag(Displays.TAG);
        helper.getLevel().addFreshEntity(e);
        UUID id = e.getUUID();
        check(wrong, helper.getLevel().getEntity(id) == null, "el marcador sobrante quedó en el mundo");
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join("; ", wrong));
        }
        helper.succeed();
    }
}
