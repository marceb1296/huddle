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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// suministros aéreos completos sobre la superficie de alrededor del pozo de cada prueba (z negativo)
@GameTestHolder(Huddle.MODID)
@PrefixGameTestTemplate(false)
public final class AirdropTest {
    private static int seq;

    private static void configure() {
        HuddleConfig.TypeValues v = HuddleConfig.TYPES.get("airdrop");
        v.enabled.set(true);
        v.minPlayers.set(2);
        v.maxPlayers.set(2);
        v.lobbySeconds.set(60);
        v.countdownSeconds.set(1);
        v.cooldownMinutes.set(0);
        v.maxArmor.set(-1);
        v.maxEnchantLevel.set(-1);
        v.combatModes.set(List.of("any"));
        HuddleConfig.ANNOUNCE_RADIUS.set(64);
        HuddleConfig.MAX_DISTANCE.set(1000);
        HuddleConfig.DISCONNECT_GRACE_SECONDS.set(1);
        HuddleConfig.MAX_BUY_IN.set(64);
        HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(true);
        HuddleConfig.GENERATOR_RADIUS.set(32);
        HuddleConfig.GENERATOR_COLUMNS_PER_TICK.set(4096);
        HuddleConfig.BUSY_CHUNK_MINUTES.set(0);
        HuddleConfig.AIRDROP_MIN_DISTANCE.set(4);
        HuddleConfig.AIRDROP_MAX_DISTANCE.set(10);
        HuddleConfig.AIRDROP_FALL_SECONDS.set(3);
        HuddleConfig.AIRDROP_OPEN_SECONDS.set(1);
        HuddleConfig.AIRDROP_GROUND_SECONDS.set(10);
        HuddleConfig.AIRDROP_LOOT.set(List.of("minecraft:chests/simple_dungeon"));
    }

    private record Setup(ServerPlayer a, ServerPlayer b) {
    }

    private static Setup setup(GameTestHelper helper, int diamondsEach) {
        configure();
        ServerPlayer a = player(helper, "aa", 2.5, -6.5);
        ServerPlayer b = player(helper, "ab", 3.5, -6.5);
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
        check(wrong, run(s.a, ("/event start airdrop " + args).trim()) == 1, "start falló");
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 1, "join falló");
        return Lobbies.of(s.a);
    }

    private static boolean running(Lobbies.Running r) {
        return r != null && r.phase() == Lobbies.Phase.RUNNING && Lobbies.all().contains(r);
    }

    private static void at(ServerPlayer p, Pos spot, double dx) {
        p.teleportTo(p.serverLevel(), spot.x() + dx, spot.y(), spot.z(), 0, 0);
    }

    private static int diamonds(ServerPlayer p) {
        return p.getInventory().countItem(Items.DIAMOND);
    }

    private static int others(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && !s.is(Items.DIAMOND)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static List<String> chat(ServerPlayer p) {
        return chat(TestPlayers.received(p));
    }

    private static List<String> chat(List<Packet<?>> packets) {
        List<String> out = new ArrayList<>();
        for (Packet<?> packet : packets) {
            if (packet instanceof ClientboundSystemChatPacket c && !c.overlay()) {
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

    // de punta a punta: baja con paracaídas, se pelean (hay PvP), uno se queda solo al lado, la abre y se lleva botín y pozo
    @GameTest(template = "empty", batch = "airdrop", timeoutTicks = 300)
    public static void fullAirdropWithLootAndEntry(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "single 2 diamond", wrong);
        List<UUID> ids = new ArrayList<>();
        helper.runAfterDelay(30, () -> {
            check(wrong, running(r), "no arrancó");
            if (!running(r)) {
                return;
            }
            ids.addAll(r.airdrop().displayIds());
            Pos spot = r.airdrop().spot();
            check(wrong, ids.size() == 2 && alive(helper, ids) == 2, "sin caja y paracaídas");
            Entity crate = helper.getLevel().getEntity(ids.get(0));
            check(wrong, crate != null && crate.getY() > spot.y() + 5, "la caja no está en el aire: " + (crate == null ? null : crate.getY()));
            check(wrong, diamonds(s.a) == 3 && diamonds(s.b) == 3, "no cobró la entrada");
            check(wrong, chat(s.b).stream().anyMatch(m -> m.contains("coming down at")), "sin aviso del lugar");
            // esperar debajo mientras cae no sirve
            at(s.a, spot, 0.5);
            s.b.invulnerableTime = 0;
            s.b.setHealth(s.b.getMaxHealth());
            s.a.attack(s.b);
            check(wrong, s.b.getHealth() < s.b.getMaxHealth(), "sin PvP entre participantes");
        });
        helper.runAfterDelay(60, () -> {
            if (running(r)) {
                check(wrong, r.airdrop().state().winner() == null, "se abrió en el aire");
                // lejos al caer, para que a los 110 ticks siga en curso y se pueda revisar la caja en el suelo
                at(s.a, r.airdrop().spot(), 10);
                at(s.b, r.airdrop().spot(), -10);
            }
        });
        helper.runAfterDelay(110, () -> {
            check(wrong, ids.size() == 2 && alive(helper, ids.subList(1, 2)) == 0, "el paracaídas quedó al caer");
            check(wrong, running(r), "terminó antes de revisar la caja en el suelo");
            if (running(r)) {
                Entity crate = helper.getLevel().getEntity(ids.get(0));
                check(wrong, crate != null && Math.abs(crate.getY() - r.airdrop().spot().y()) < 0.01, "la caja no bajó al suelo: "
                        + (crate == null ? null : crate.getY()));
                at(s.a, r.airdrop().spot(), 1);
                at(s.b, r.airdrop().spot(), 10);
            }
        });
        helper.runAfterDelay(140, () -> {
            check(wrong, !running(r), "no se abrió");
            check(wrong, r != null && s.a.getUUID().equals(r.airdrop().winner()), "ganador equivocado");
            check(wrong, diamonds(s.a) >= 7 && diamonds(s.b) == 3, "pozo mal: " + diamonds(s.a) + " " + diamonds(s.b));
            check(wrong, others(s.a) > 0 || diamonds(s.a) > 7, "sin botín");
            check(wrong, alive(helper, ids) == 0, "la caja quedó en el mundo");
            check(wrong, !Glow.remembers(s.a.getUUID()), "el brillo siguió");
            List<Packet<?>> toB = TestPlayers.received(s.b);
            check(wrong, chat(toB).stream().anyMatch(m -> m.contains("opened the supply crate")), "sin aviso");
            List<String> other = TestPlayers.titles(toB), won = TestPlayers.titles(TestPlayers.received(s.a));
            check(wrong, other.contains("T:" + s.a.getGameProfile().getName()) && other.contains("S:opened the crate"), "sin título para los demás: " + other);
            check(wrong, won.contains("T:Crate opened!") && TestPlayers.shortTitles(won), "sin título para quien la abrió: " + won);
            done(helper, wrong, s.a, s.b);
        });
    }

    // dos al lado: nadie avanza; un daño reinicia la cuenta del que estaba solo
    @GameTest(template = "empty", batch = "airdrop", timeoutTicks = 300)
    public static void contestedAndHurtDoNotOpen(GameTestHelper helper) {
        Setup s = setup(helper, 0);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "", wrong);
        helper.runAfterDelay(90, () -> {
            check(wrong, running(r) && r.airdrop().state().landed(helper.getLevel().getServer().getTickCount()), "no cayó");
            if (running(r)) {
                at(s.a, r.airdrop().spot(), 1);
                at(s.b, r.airdrop().spot(), -1);
            }
        });
        helper.runAfterDelay(130, () -> {
            check(wrong, running(r) && r.airdrop().winner() == null, "se abrió con dos al lado");
            if (running(r)) {
                at(s.b, r.airdrop().spot(), 10);
            }
        });
        helper.runAfterDelay(140, () -> {
            if (running(r)) {
                check(wrong, s.a.getUUID().equals(r.airdrop().state().holder()), "el que quedó solo no la está abriendo");
                s.a.hurt(helper.getLevel().damageSources().generic(), 1);
            }
        });
        helper.runAfterDelay(142, () -> {
            check(wrong, running(r) && r.airdrop().state().progress() < 0.15, "el daño no reinició: "
                    + (r == null || r.airdrop().state() == null ? null : r.airdrop().state().progress()));
            done(helper, wrong, s.a, s.b);
        });
    }

    // nadie la abre a tiempo: cada uno recupera su entrada y la caja se va
    @GameTest(template = "empty", batch = "airdrop", timeoutTicks = 400)
    public static void unopenedCrateRefunds(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "single 2 diamond", wrong);
        List<UUID> ids = new ArrayList<>();
        helper.runAfterDelay(30, () -> {
            if (running(r)) {
                ids.addAll(r.airdrop().displayIds());
            }
        });
        helper.runAfterDelay(320, () -> {
            check(wrong, !running(r), "siguió después del tiempo");
            check(wrong, r != null && r.airdrop().winner() == null, "hubo ganador");
            check(wrong, diamonds(s.a) == 5 && diamonds(s.b) == 5, "no devolvió");
            check(wrong, !ids.isEmpty() && alive(helper, ids) == 0, "la caja quedó en el mundo");
            check(wrong, chat(s.a).stream().anyMatch(m -> m.contains("Nobody opened the crate")), "sin aviso");
            done(helper, wrong, s.a, s.b);
        });
    }

    // cima: sobre una plataforma propia en el aire (la superficie de alrededor depende de los otros lotes)
    // con una loma de escalones de un bloque, la caja cae arriba de todo
    @GameTest(template = "empty", batch = "airdrop_peak", timeoutTicks = 200)
    public static void peakLandsOnTop(GameTestHelper helper) {
        Setup s = setup(helper, 0);
        ServerLevel level = helper.getLevel();
        BlockPos c = s.a.blockPosition().above(15);
        Map<BlockPos, BlockState> before = new HashMap<>();
        java.util.function.BiConsumer<BlockPos, BlockState> put = (b, state) -> {
            before.putIfAbsent(b, level.getBlockState(b));
            level.setBlock(b, state, 2);
        };
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                put.accept(c.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
            }
        }
        // escalones hacia el este a 6, 7 y 8 bloques: +1, +2, +3
        for (int step = 1; step <= 3; step++) {
            for (int dz = -1; dz <= 2; dz++) {
                for (int y = 0; y < step; y++) {
                    put.accept(c.offset(5 + step, y, dz), Blocks.STONE.defaultBlockState());
                }
            }
        }
        s.a.teleportTo(level, c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 0, 0);
        s.b.teleportTo(level, c.getX() + 1.5, c.getY(), c.getZ() + 0.5, 0, 0);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "peak", wrong);
        helper.runAfterDelay(30, () -> {
            check(wrong, running(r), "no arrancó: " + chat(s.a));
            Pos spot = r == null || r.airdrop() == null ? null : r.airdrop().spot();
            check(wrong, spot != null && spot.y() == c.getY() + 3 && Math.floor(spot.x()) == c.getX() + 8, "no cayó en la cima: " + spot
                    + ", cima en " + (c.getX() + 8) + " " + (c.getY() + 3));
            done(helper, wrong, s.a, s.b);
            before.forEach((b, state) -> level.setBlock(b, state, 2));
        });
    }

    @GameTest(template = "empty", batch = "airdrop_nospot", timeoutTicks = 200)
    public static void noSpotCancelsBeforeCharging(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        HuddleConfig.AIRDROP_MIN_DISTANCE.set(40);
        List<String> wrong = new ArrayList<>();
        check(wrong, run(s.a, "/event start airdrop volcano") == 0, "aceptó un preset que no existe");
        Lobbies.Running r = open(s, "unico 2 diamond", wrong);
        helper.runAfterDelay(60, () -> {
            check(wrong, r != null && !Lobbies.all().contains(r), "la sala sigue abierta");
            check(wrong, chat(s.b).stream().anyMatch(m -> m.contains("airdrop was cancelled")), "sin explicar el fallo");
            check(wrong, diamonds(s.a) == 5 && diamonds(s.b) == 5, "cobró sin evento");
            HuddleConfig.AIRDROP_MIN_DISTANCE.set(4);
            done(helper, wrong, s.a, s.b);
        });
    }

    // quien sale del evento ya no puede abrir la caja aunque se quede solo a su lado
    @GameTest(template = "empty", batch = "airdrop", timeoutTicks = 300)
    public static void leaverCannotOpen(GameTestHelper helper) {
        Setup s = setup(helper, 0);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(s, "", wrong);
        helper.runAfterDelay(90, () -> {
            check(wrong, running(r), "no arrancó");
            if (running(r)) {
                check(wrong, Glow.remembers(s.b.getUUID()), "la caja no brillaba para el que va a salir");
                check(wrong, run(s.b, "/event leave") == 1, "leave falló");
                check(wrong, !Glow.remembers(s.b.getUUID()), "la caja siguió brillando para el que salió");
                at(s.b, r.airdrop().spot(), 1);
                at(s.a, r.airdrop().spot(), 10);
            }
        });
        helper.runAfterDelay(130, () -> {
            check(wrong, r != null && r.airdrop().winner() == null, "la abrió quien ya había salido");
            done(helper, wrong, s.a, s.b);
        });
    }
}
