package dev.huddle.neoforge;

import dev.huddle.core.Pos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// búsquedas del tesoro completas sobre la superficie plana alrededor del pozo de cada prueba (z negativo)
@GameTestHolder(Huddle.MODID)
@PrefixGameTestTemplate(false)
public final class TreasureTest {
    private static int seq;

    private static void configure() {
        HuddleConfig.TypeValues v = HuddleConfig.TYPES.get("treasure");
        v.enabled.set(true);
        v.minPlayers.set(2);
        v.maxPlayers.set(2);
        v.lobbySeconds.set(60);
        v.countdownSeconds.set(1);
        v.cooldownMinutes.set(0);
        v.maxArmor.set(-1);
        v.maxEnchantLevel.set(-1);
        HuddleConfig.ANNOUNCE_RADIUS.set(64);
        HuddleConfig.MAX_DISTANCE.set(1000);
        HuddleConfig.DISCONNECT_GRACE_SECONDS.set(1);
        HuddleConfig.MAX_BUY_IN.set(64);
        HuddleConfig.GENERATOR_RADIUS.set(32);
        HuddleConfig.GENERATOR_COLUMNS_PER_TICK.set(4096);
        HuddleConfig.BUSY_CHUNK_MINUTES.set(0);
        HuddleConfig.TREASURE_MIN_DISTANCE.set(4);
        HuddleConfig.TREASURE_MAX_DISTANCE.set(10);
        HuddleConfig.TREASURE_CAVE_DEPTH.set(8);
        HuddleConfig.TREASURE_CLUE_SECONDS.set(5);
        HuddleConfig.TREASURE_MAX_SECONDS.set(30);
    }

    private record Setup(ServerPlayer a, ServerPlayer b) {
    }

    private static Setup setup(GameTestHelper helper, int diamondsEach) {
        configure();
        ServerPlayer a = player(helper, "ta", 2.5, -6.5);
        ServerPlayer b = player(helper, "tb", 3.5, -6.5);
        a.getInventory().add(new ItemStack(Items.DIAMOND, diamondsEach));
        b.getInventory().add(new ItemStack(Items.DIAMOND, diamondsEach));
        return new Setup(a, b);
    }

    private static ServerPlayer player(GameTestHelper helper, String name, double x, double z) {
        ServerPlayer p = TestPlayers.join(helper, name + (++seq));
        Vec3 at = helper.absoluteVec(new Vec3(x, 0, z));
        int y = helper.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(at.x), (int) Math.floor(at.z));
        p.teleportTo(helper.getLevel(), at.x, y, at.z, 0, 0);
        return p;
    }

    private static int run(ServerPlayer p, String command) {
        int[] result = {0};
        p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withCallback((ok, r) -> result[0] = ok ? r : 0), command);
        return result[0];
    }

    private static Lobbies.Running open(Setup s, String args, List<String> wrong) {
        check(wrong, run(s.a, "/event start treasure " + args) == 1, "start falló");
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 1, "join falló");
        return Lobbies.of(s.a);
    }

    private static boolean hunting(Lobbies.Running r) {
        return r != null && r.phase() == Lobbies.Phase.RUNNING && Lobbies.all().contains(r);
    }

    private static Entity hitbox(GameTestHelper helper, Lobbies.Running r) {
        List<UUID> ids = r.treasure().displayIds();
        return ids.isEmpty() ? null : helper.getLevel().getEntity(ids.get(1));
    }

    private static void besideChest(ServerPlayer p, Pos spot, double dx) {
        p.teleportTo(p.serverLevel(), spot.x() + dx, spot.y(), spot.z(), 0, 0);
    }

    private static int diamonds(ServerPlayer p) {
        return p.getInventory().countItem(Items.DIAMOND);
    }

    private static List<String> chat(ServerPlayer p, boolean overlay) {
        return chat(TestPlayers.received(p), overlay);
    }

    private static List<String> chat(List<Packet<?>> packets, boolean overlay) {
        List<String> out = new ArrayList<>();
        for (Packet<?> packet : packets) {
            if (packet instanceof ClientboundSystemChatPacket c && c.overlay() == overlay) {
                out.add(c.content().getString());
            }
        }
        return out;
    }

    private static long alive(GameTestHelper helper, List<UUID> ids) {
        return ids.stream().filter(id -> helper.getLevel().getEntity(id) != null).count();
    }

    private static void check(List<String> wrong, boolean ok, String what) {
        if (!ok) {
            wrong.add(what);
        }
    }

    private static void done(GameTestHelper helper, List<String> wrong, ServerPlayer... players) {
        for (ServerPlayer p : players) {
            Lobbies.leave(p.server, p.getUUID());
            HuddleData.get(p.server).mailbox().take(p.getUUID());
            TestPlayers.leave(helper, p);
        }
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join("; ", wrong));
        }
        helper.succeed();
    }

    // de punta a punta con entrada: pista, brillo, clic de lejos y de alguien de afuera, hallazgo, pozo y limpieza
    @GameTest(template = "empty", batch = "treasure", timeoutTicks = 300)
    public static void fullHuntWithEntry(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        ServerPlayer outsider = player(helper, "tc", 4.5, -6.5);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "classic 2 diamond", wrong);
        helper.runAfterDelay(60, () -> {
            check(wrong, hunting(r), "no arrancó");
            if (!hunting(r)) {
                done(helper, wrong, s.a, s.b, outsider);
                return;
            }
            Pos spot = r.treasure().spot();
            double d = Math.hypot(spot.x() - s.a.getX(), spot.z() - s.a.getZ());
            check(wrong, d >= 3 && d <= 11, "cofre a " + d + " del creador");
            List<UUID> ids = List.copyOf(r.treasure().displayIds());
            check(wrong, ids.size() == 2 && alive(helper, ids) == 2, "sin cofre en el mundo");
            check(wrong, diamonds(s.a) == 3 && diamonds(s.b) == 3, "no cobró la entrada: " + diamonds(s.a) + " " + diamonds(s.b));
            check(wrong, chat(s.a, false).stream().anyMatch(m -> m.startsWith("Clue 1/4")), "sin la primera pista");
            check(wrong, Glow.remembers(s.a.getUUID()), "el cofre no brilla para un participante cerca");
            check(wrong, !Glow.remembers(outsider.getUUID()), "el cofre brilla para alguien de afuera");
            Entity box = hitbox(helper, r);
            besideChest(s.b, spot, 8);
            s.b.interactOn(box, InteractionHand.MAIN_HAND);
            check(wrong, hunting(r) && r.treasure().winner() == null, "ganó con un clic de lejos");
            besideChest(outsider, spot, 1);
            outsider.interactOn(box, InteractionHand.MAIN_HAND);
            check(wrong, hunting(r), "alguien de afuera terminó la búsqueda");
            // los paquetes de este tick llegan al canal en el siguiente
            helper.runAfterDelay(2, () -> {
                check(wrong, chat(s.b, true).stream().anyMatch(m -> m.contains("closer")), "sin aviso de acercarse");
                check(wrong, chat(outsider, true).stream().anyMatch(m -> m.contains("not in")), "sin aviso al de afuera");
                besideChest(s.a, spot, 1);
                s.a.attack(box);
                check(wrong, !hunting(r), "el clic izquierdo de cerca no ganó");
                check(wrong, s.a.getUUID().equals(r.treasure().winner()), "ganador equivocado");
                check(wrong, diamonds(s.a) == 7 && diamonds(s.b) == 3, "pozo mal: " + diamonds(s.a) + " " + diamonds(s.b));
                check(wrong, alive(helper, ids) == 0, "el cofre quedó en el mundo");
                check(wrong, !Glow.remembers(s.a.getUUID()), "el brillo siguió después de terminar");
                check(wrong, Participants.of(s.a) == null && Lobbies.of(s.b) == null, "siguen anotados");
                helper.runAfterDelay(2, () -> {
                    List<Packet<?>> toB = TestPlayers.received(s.b);
                    check(wrong, chat(toB, false).stream().anyMatch(m -> m.contains("found the treasure")), "sin aviso del hallazgo");
                    List<String> other = TestPlayers.titles(toB), won = TestPlayers.titles(TestPlayers.received(s.a));
                    check(wrong, other.contains("T:" + s.a.getGameProfile().getName()) && other.contains("S:found the treasure"), "sin título para los demás: " + other);
                    check(wrong, won.contains("T:Found it!") && won.contains("S:Well done"), "sin título para quien lo encontró: " + won);
                    check(wrong, TestPlayers.titles(TestPlayers.received(outsider)).isEmpty(), "título para alguien de afuera");
                    done(helper, wrong, s.a, s.b, outsider);
                });
            });
        });
    }

    // los dos llegan juntos y hacen clic en el mismo tick: uno solo gana y el pozo se paga una vez
    @GameTest(template = "empty", batch = "treasure", timeoutTicks = 200)
    public static void twoClicksInOneTick(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "classic 2 diamond", wrong);
        helper.runAfterDelay(60, () -> {
            check(wrong, hunting(r), "no arrancó");
            if (hunting(r)) {
                Pos spot = r.treasure().spot();
                Entity box = hitbox(helper, r);
                besideChest(s.a, spot, 1);
                besideChest(s.b, spot, -1);
                s.b.interactOn(box, InteractionHand.MAIN_HAND);
                s.a.interactOn(box, InteractionHand.MAIN_HAND);
                check(wrong, s.b.getUUID().equals(r.treasure().winner()), "no ganó el primer clic");
                check(wrong, diamonds(s.b) == 7 && diamonds(s.a) == 3, "pozo mal: " + diamonds(s.a) + " " + diamonds(s.b));
                check(wrong, HuddleData.get(s.a.server).mailbox().count(s.a.getUUID()) == 0
                        && HuddleData.get(s.a.server).mailbox().count(s.b.getUUID()) == 0, "quedó algo en el buzón");
            }
            done(helper, wrong, s.a, s.b);
        });
    }

    // nadie lo encuentra: cada uno recupera su entrada y se dice dónde estaba
    @GameTest(template = "empty", batch = "treasure", timeoutTicks = 900)
    public static void timeRunsOutAndEntriesGoBack(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "classic 2 diamond", wrong);
        helper.runAfterDelay(60, () -> check(wrong, hunting(r) && diamonds(s.a) == 3, "no arrancó o no cobró"));
        helper.runAfterDelay(720, () -> {
            check(wrong, !hunting(r), "siguió después del tiempo máximo");
            check(wrong, r.treasure().winner() == null, "hubo ganador sin clic");
            check(wrong, diamonds(s.a) == 5 && diamonds(s.b) == 5, "no devolvió: " + diamonds(s.a) + " " + diamonds(s.b));
            List<String> said = chat(s.b, false);
            check(wrong, said.stream().anyMatch(m -> m.startsWith("Clue 4/4")), "no llegaron las cuatro pistas");
            check(wrong, said.stream().anyMatch(m -> m.contains("Nobody found the chest; it was at")), "sin decir dónde estaba");
            done(helper, wrong, s.a, s.b);
        });
    }

    // quien se desconecta y no vuelve a tiempo pierde su entrada; el que encuentra el cofre se lleva las dos
    @GameTest(template = "empty", batch = "treasure", timeoutTicks = 300)
    public static void disconnectForfeitsTheEntry(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "classic 2 diamond", wrong);
        UUID aId = s.a.getUUID();
        helper.runAfterDelay(60, () -> {
            check(wrong, hunting(r), "no arrancó");
            TestPlayers.leave(helper, s.a);
        });
        helper.runAfterDelay(120, () -> {
            check(wrong, hunting(r) && !r.participants().contains(aId), "el desconectado sigue en la búsqueda");
            if (hunting(r)) {
                besideChest(s.b, r.treasure().spot(), 1);
                s.b.interactOn(hitbox(helper, r), InteractionHand.MAIN_HAND);
            }
            check(wrong, !hunting(r) && s.b.getUUID().equals(r.treasure().winner()), "no ganó el que quedó");
            check(wrong, diamonds(s.b) == 7, "pozo mal: " + diamonds(s.b));
            check(wrong, HuddleData.get(s.b.server).mailbox().count(aId) == 0, "el que abandonó recupera la entrada");
            done(helper, wrong, s.b);
        });
    }

    // cofre y caja de clic que quedaron por una caída del servidor: se borran al cargarse
    @GameTest(template = "empty", batch = "treasure", timeoutTicks = 100)
    public static void leftoverChestIsRemoved(GameTestHelper helper) {
        List<String> wrong = new ArrayList<>();
        Vec3 at = helper.absoluteVec(new Vec3(1, 2, 1));
        for (EntityType<?> type : List.of(EntityType.ITEM_DISPLAY, EntityType.INTERACTION)) {
            Entity e = type.create(helper.getLevel());
            e.moveTo(at.x, at.y, at.z, 0, 0);
            e.addTag(Displays.TAG);
            helper.getLevel().addFreshEntity(e);
            check(wrong, helper.getLevel().getEntity(e.getUUID()) == null, type + " sobrante quedó en el mundo");
        }
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join("; ", wrong));
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "treasure", timeoutTicks = 100)
    public static void badPresetAndEntryRules(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        List<String> wrong = new ArrayList<>();
        check(wrong, run(s.a, "/event start treasure volcano") == 0, "aceptó un preset que no existe");
        check(wrong, run(s.a, "/event start lastman x 2 diamond") == 0, "aceptó entrada en un evento sin ganador");
        check(wrong, run(s.a, "/evento iniciar treasure cuevas") == 1, "no aceptó el preset en español");
        check(wrong, Lobbies.of(s.a) != null && Lobbies.of(s.a).treasure() != null
                && Lobbies.of(s.a).treasure().preset() == dev.huddle.core.TreasureFinder.Preset.CAVES, "preset equivocado");
        done(helper, wrong, s.a, s.b);
    }

    // sin terreno en el anillo pedido: la sala se cancela antes de cobrar y se explica
    @GameTest(template = "empty", batch = "treasure_nospot", timeoutTicks = 200)
    public static void noSpotCancelsBeforeCharging(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        HuddleConfig.TREASURE_MIN_DISTANCE.set(40);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "classic 2 diamond", wrong);
        helper.runAfterDelay(60, () -> {
            check(wrong, r != null && !Lobbies.all().contains(r), "la sala sigue abierta");
            check(wrong, chat(s.b, false).stream().anyMatch(m -> m.contains("no reachable open ground")), "sin explicar el fallo");
            check(wrong, diamonds(s.a) == 5 && diamonds(s.b) == 5, "cobró sin evento");
            HuddleConfig.TREASURE_MIN_DISTANCE.set(4);
            done(helper, wrong, s.a, s.b);
        });
    }

    // la columna de la cuarta pista sube justo sobre el cofre y arranca en el suelo, a la vista
    private static void beamAboveTheChest(ServerLevel level, TreasureGame game, List<String> wrong) {
        Pos spot = game.spot();
        List<Pos> beam = game.beam();
        check(wrong, beam.size() >= 40, "columna con " + beam.size() + " puntos");
        if (beam.isEmpty()) {
            return;
        }
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(spot.x()), (int) Math.floor(spot.z()));
        double low = beam.stream().mapToDouble(Pos::y).min().orElseThrow();
        check(wrong, low >= h && low <= h + 1, "la columna arranca en Y=" + low + " con el suelo en " + h);
        check(wrong, beam.stream().allMatch(pt -> pt.x() == spot.x() && pt.z() == spot.z()), "la columna no está sobre el cofre");
    }

    private static float facing(ServerPlayer p, Pos to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x() - p.getX()), to.z() - p.getZ()));
    }

    // un novato no sabe usar F3: la segunda pista dice hacia dónde queda según adónde mira cada uno,
    // y la cuarta marca el cofre con una columna en vez de dar la altura
    @GameTest(template = "empty", batch = "treasure", timeoutTicks = 450)
    public static void cluesWorkWithoutF3(GameTestHelper helper) {
        Setup s = setup(helper, 0);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "classic", wrong);
        helper.runAfterDelay(60, () -> {
            check(wrong, hunting(r), "no arrancó");
            if (hunting(r)) {
                Pos spot = r.treasure().spot();
                s.a.teleportTo(s.a.serverLevel(), s.a.getX(), s.a.getY(), s.a.getZ(), facing(s.a, spot), 0);
                s.b.teleportTo(s.b.serverLevel(), s.b.getX(), s.b.getY(), s.b.getZ(), facing(s.b, spot) + 180, 0);
            }
        });
        helper.runAfterDelay(175, () -> {
            List<String> a2 = chat(s.a, false).stream().filter(m -> m.startsWith("Clue 2/4")).toList();
            List<String> b2 = chat(s.b, false).stream().filter(m -> m.startsWith("Clue 2/4")).toList();
            check(wrong, a2.size() == 1 && a2.get(0).contains("straight ahead"), "segunda pista de quien mira al cofre: " + a2);
            check(wrong, b2.size() == 1 && b2.get(0).contains("behind you") && !b2.get(0).contains("behind you to"),
                    "segunda pista de quien le da la espalda: " + b2);
        });
        helper.runAfterDelay(400, () -> {
            List<String> fourth = chat(s.a, false).stream().filter(m -> m.startsWith("Clue 4/4")).toList();
            check(wrong, fourth.size() == 1 && fourth.get(0).contains("column") && !fourth.get(0).contains("Y="),
                    "cuarta pista: " + fourth);
            if (hunting(r)) {
                beamAboveTheChest(helper.getLevel(), r.treasure(), wrong);
            }
            done(helper, wrong, s.a, s.b);
        });
    }

    // el creador abre la búsqueda desde su base: se sale caminando de los chunks habitados y el cofre queda afuera.
    // Plataforma propia en el aire: alrededor quedan las estructuras de otras pruebas
    @GameTest(template = "empty", batch = "treasure_busy", timeoutTicks = 200)
    public static void startFromABase(GameTestHelper helper) {
        Setup s = setup(helper, 0);
        HuddleConfig.BUSY_CHUNK_MINUTES.set(100000);
        HuddleConfig.TREASURE_MIN_DISTANCE.set(4);
        HuddleConfig.TREASURE_MAX_DISTANCE.set(32);
        ServerLevel level = helper.getLevel();
        ChunkPos base = new ChunkPos(s.a.blockPosition());
        BlockPos c = new BlockPos(base.getMiddleBlockX(), s.a.getBlockY() + 15, base.getMiddleBlockZ());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setChunkForced(base.x + dx, base.z + dz, true);
            }
        }
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (int dx = -36; dx <= 36; dx++) {
            for (int dz = -36; dz <= 36; dz++) {
                BlockPos b = c.offset(dx, -1, dz);
                before.put(b, level.getBlockState(b));
                level.setBlock(b, Blocks.STONE.defaultBlockState(), 2);
            }
        }
        // la base son los 9 chunks de alrededor: el suelo libre más cercano queda a más de 8 bloques
        java.util.function.LongConsumer busy = ticks -> {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.getChunk(base.x + dx, base.z + dz).setInhabitedTime(ticks);
                }
            }
        };
        busy.accept(20L * 60 * 200000);
        s.a.teleportTo(level, c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 0, 0);
        s.b.teleportTo(level, c.getX() + 1.5, c.getY(), c.getZ() + 0.5, 0, 0);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "classic", wrong);
        helper.runAfterDelay(80, () -> {
            busy.accept(0);
            check(wrong, hunting(r), "no arrancó: " + chat(s.a, false));
            if (hunting(r)) {
                Pos spot = r.treasure().spot();
                ChunkPos at = new ChunkPos(BlockPos.containing(spot.x(), 0, spot.z()));
                check(wrong, Math.abs(at.x - base.x) > 1 || Math.abs(at.z - base.z) > 1, "cofre dentro de la base: " + at);
                check(wrong, spot.y() == c.getY(), "cofre fuera de la plataforma: " + spot);
            }
            HuddleConfig.BUSY_CHUNK_MINUTES.set(0);
            done(helper, wrong, s.a, s.b);
            before.forEach((b, state) -> level.setBlock(b, state, 2));
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    level.setChunkForced(base.x + dx, base.z + dz, false);
                }
            }
        });
    }

    // la búsqueda siguiente no repite la zona de la anterior: con el anillo de 4 a 10, a más de 3 bloques del cofre anterior
    @GameTest(template = "empty", batch = "treasure_recent", timeoutTicks = 200)
    public static void nextHuntAvoidsTheLastSpot(GameTestHelper helper) {
        TreasureGame.forget();
        Setup s = setup(helper, 0);
        // alrededor del pozo casi no hay suelo libre: plataforma propia en el aire, con los chunks cargados
        ServerLevel level = helper.getLevel();
        ChunkPos base = new ChunkPos(s.a.blockPosition());
        BlockPos c = new BlockPos(base.getMiddleBlockX(), s.a.getBlockY() + 15, base.getMiddleBlockZ());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced(base.x + dx, base.z + dz, true);
            }
        }
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                BlockPos b = c.offset(dx, -1, dz);
                before.put(b, level.getBlockState(b));
                level.setBlock(b, Blocks.STONE.defaultBlockState(), 2);
            }
        }
        s.a.teleportTo(level, c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 0, 0);
        s.b.teleportTo(level, c.getX() + 1.5, c.getY(), c.getZ() + 0.5, 0, 0);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "classic", wrong);
        helper.runAfterDelay(60, () -> {
            check(wrong, hunting(r), "no arrancó");
            if (hunting(r)) {
                Pos first = r.treasure().spot();
                // el lugar se sortea: con 200 intentos, sin la memoria alguno cae cerca casi seguro
                for (int i = 0; i < 200 && wrong.isEmpty(); i++) {
                    TreasureGame next = TreasureGame.prepare(s.a, dev.huddle.core.TreasureFinder.Preset.CLASSIC);
                    for (int t = 0; t < 10 && !next.ready(); t++) {
                        next.search();
                    }
                    Pos spot = next.spot();
                    check(wrong, spot != null && spot.y() == c.getY(), "sin lugar en la plataforma: " + spot);
                    if (spot != null) {
                        double d = Math.hypot(spot.x() - first.x(), spot.z() - first.z());
                        check(wrong, d > 3, "a " + d + " del cofre anterior");
                    }
                }
            }
            done(helper, wrong, s.a, s.b);
            TreasureGame.forget();
            before.forEach((b, state) -> level.setBlock(b, state, 2));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setChunkForced(base.x + dx, base.z + dz, false);
                }
            }
        });
    }

    // morir en la búsqueda no saca: vuelve a donde se unió, con la barra, y sigue pudiendo ganar, también la segunda vez
    @GameTest(template = "empty", batch = "treasure", timeoutTicks = 200)
    public static void deathKeepsHunting(GameTestHelper helper) {
        Setup s = setup(helper, 0);
        Vec3 home = s.b.position();
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "classic", wrong);
        helper.runAfterDelay(60, () -> {
            check(wrong, hunting(r), "no arrancó");
            if (!hunting(r)) {
                done(helper, wrong, s.a, s.b);
                return;
            }
            Pos spot = r.treasure().spot();
            for (int i = 1; i <= 2; i++) {
                besideChest(s.b, spot, 3);
                // sin esto el segundo golpe en el mismo tick no cuenta: queda la invulnerabilidad del primero
                s.b.invulnerableTime = 0;
                s.b.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000);
                check(wrong, s.b.isAlive() && s.b.getHealth() == s.b.getMaxHealth(), "murió de verdad (" + i + ")");
                check(wrong, r.participants().contains(s.b.getUUID()) && Lobbies.of(s.b) == r, "quedó fuera del evento (" + i + ")");
                check(wrong, r.bar().getPlayers().contains(s.b), "sin la barra (" + i + ")");
                check(wrong, s.b.position().distanceTo(home) < 1, "no volvió a donde se unió (" + i + "): " + s.b.position());
            }
            helper.runAfterDelay(2, () -> {
                check(wrong, chat(s.b, false).stream().anyMatch(m -> m.contains("still in the event")), "sin avisar que sigue");
                besideChest(s.b, spot, 1);
                s.b.interactOn(hitbox(helper, r), InteractionHand.MAIN_HAND);
                check(wrong, s.b.getUUID().equals(r.treasure().winner()), "no pudo ganar después de morir");
                done(helper, wrong, s.a, s.b);
            });
        });
    }

    // cuevas: un hueco cavado bajo el césped es el único lugar posible
    @GameTest(template = "empty", batch = "treasure_cave", timeoutTicks = 500)
    public static void cavesHideTheChestUnderground(GameTestHelper helper) {
        Setup s = setup(helper, 0);
        ServerLevel level = helper.getLevel();
        // plataforma propia en el aire, de 6 de espesor: la superficie de alrededor cambia según los otros lotes
        BlockPos c = s.a.blockPosition().above(15);
        Map<BlockPos, BlockState> before = new HashMap<>();
        java.util.function.BiConsumer<BlockPos, BlockState> put = (b, state) -> {
            before.putIfAbsent(b, level.getBlockState(b));
            level.setBlock(b, state, 2);
        };
        for (int dx = -14; dx <= 14; dx++) {
            for (int dz = -14; dz <= 14; dz++) {
                for (int dy = -6; dy <= -1; dy++) {
                    put.accept(c.offset(dx, dy, dz), Blocks.STONE.defaultBlockState());
                }
            }
        }
        int surface = c.getY();
        BlockPos o = c.offset(7, 0, 0);
        for (int x = 0; x < 2; x++) {
            BlockPos floor = new BlockPos(o.getX() + x, surface - 4, o.getZ());
            put.accept(floor.above(), Blocks.AIR.defaultBlockState());
            put.accept(floor.above(2), Blocks.AIR.defaultBlockState());
        }
        s.a.teleportTo(level, c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 0, 0);
        s.b.teleportTo(level, c.getX() + 1.5, c.getY(), c.getZ() + 0.5, 0, 0);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "caves", wrong);
        helper.runAfterDelay(80, () -> {
            check(wrong, hunting(r), "no arrancó: " + chat(s.a, false));
            Pos spot = r == null || r.treasure() == null ? null : r.treasure().spot();
            check(wrong, spot != null && spot.y() == surface - 3 && Math.floor(spot.z()) == o.getZ()
                    && (Math.floor(spot.x()) == o.getX() || Math.floor(spot.x()) == o.getX() + 1), "cofre fuera del hueco: " + spot
                    + " hueco en " + o.getX() + " " + (surface - 3) + " " + o.getZ());
            check(wrong, chat(s.a, false).stream().noneMatch(m -> m.startsWith("Clue 4/4")) , "la altura llegó antes de tiempo");
        });
        // la cuarta pista llega a los 15 s: dice cuán hondo está, sin coordenadas, y la columna sale del suelo de arriba
        helper.runAfterDelay(400, () -> {
            List<String> fourth = chat(s.a, false).stream().filter(m -> m.startsWith("Clue 4/4")).toList();
            check(wrong, fourth.size() == 1 && fourth.get(0).contains("underground") && fourth.get(0).contains("3 blocks")
                    && !fourth.get(0).contains("Y="), "cuarta pista de cuevas: " + fourth);
            if (r != null && r.treasure() != null) {
                beamAboveTheChest(level, r.treasure(), wrong);
            }
            done(helper, wrong, s.a, s.b);
            before.forEach((b, state) -> level.setBlock(b, state, 2));
        });
    }

    // quien sale de la búsqueda junto al cofre deja de verlo brillar y ya no puede ganar
    @GameTest(template = "empty", batch = "treasure", timeoutTicks = 200)
    public static void leaverCannotWin(GameTestHelper helper) {
        Setup s = setup(helper, 0);
        ServerPlayer c = player(helper, "td", 4.5, -6.5);
        HuddleConfig.TYPES.get("treasure").maxPlayers.set(3);
        List<String> wrong = new ArrayList<>();
        check(wrong, run(s.a, "/event start treasure") == 1, "start falló");
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 1, "join falló");
        check(wrong, run(c, "/event join " + s.a.getGameProfile().getName()) == 1, "join falló");
        HuddleConfig.TYPES.get("treasure").maxPlayers.set(2);
        Lobbies.Running r = Lobbies.of(s.a);
        helper.runAfterDelay(60, () -> {
            check(wrong, hunting(r), "no arrancó");
            if (hunting(r)) {
                besideChest(s.b, r.treasure().spot(), 1);
                helper.runAfterDelay(2, () -> {
                    check(wrong, Glow.remembers(s.b.getUUID()), "no brilló de cerca");
                    check(wrong, run(s.b, "/event leave") == 1, "leave falló");
                    check(wrong, !Glow.remembers(s.b.getUUID()), "sigue brillando para quien salió");
                    s.b.interactOn(hitbox(helper, r), InteractionHand.MAIN_HAND);
                    check(wrong, hunting(r) && r.treasure().winner() == null, "ganó quien ya había salido");
                    done(helper, wrong, s.a, s.b, c);
                });
            } else {
                done(helper, wrong, s.a, s.b, c);
            }
        });
    }

    // un hueco de un solo bloque de alto no sirve: ahí no se puede estar de pie
    @GameTest(template = "empty", batch = "treasure_cave_low", timeoutTicks = 200)
    public static void lowPocketIsNotACave(GameTestHelper helper) {
        Setup s = setup(helper, 0);
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(new BlockPos(9, 0, -6));
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, o.getX(), o.getZ());
        BlockPos floor = new BlockPos(o.getX(), surface - 4, o.getZ());
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (BlockPos b : List.of(floor, floor.above())) {
            before.put(b, level.getBlockState(b));
        }
        level.setBlock(floor, Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 2);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "caves", wrong);
        helper.runAfterDelay(60, () -> {
            check(wrong, r != null && !Lobbies.all().contains(r), "usó un hueco de un bloque: " + (r == null || r.treasure() == null ? null : r.treasure().spot()));
            done(helper, wrong, s.a, s.b);
            before.forEach((b, state) -> level.setBlock(b, state, 2));
        });
    }
}
