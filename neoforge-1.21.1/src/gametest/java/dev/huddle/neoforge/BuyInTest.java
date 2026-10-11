package dev.huddle.neoforge;

import dev.huddle.core.Pos;
import dev.huddle.core.Pot;
import dev.huddle.core.Records;
import dev.huddle.core.Route;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// compra de entrada en la carrera: nunca se duplica ni se pierde un ítem
@GameTestHolder(Huddle.MODID)
@PrefixGameTestTemplate(false)
public final class BuyInTest {
    private static int seq;

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
        HuddleConfig.DISCONNECT_GRACE_SECONDS.set(1);
        HuddleConfig.MAX_JUMP_BLOCKS.set(30);
        HuddleConfig.TRAVEL_GRACE_SECONDS.set(1);
        HuddleConfig.RACE_MAX_MINUTES.set(10);
        HuddleConfig.RACE_FINISH_WINDOW_SECONDS.set(30);
        HuddleConfig.MAX_BUY_IN.set(64);
        HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(true);
    }

    private record Setup(ServerPlayer a, ServerPlayer b, Vec3 home, String track) {
    }

    private static Setup setup(GameTestHelper helper, int diamondsEach) {
        configure();
        Vec3 base = helper.absoluteVec(new Vec3(2, 1, 2));
        Pos start = new Pos(base.x, base.y, base.z);
        String name = "b" + (++seq);
        Route route = Route.of(name, null, "test", Route.Mode.FOOT, 1, start,
                List.of(new Route.Checkpoint(new Pos(start.x() + 10, start.y(), start.z()), 3),
                        new Route.Checkpoint(new Pos(start.x() + 20, start.y(), start.z()), 3))).orElseThrow();
        HuddleData.get(helper.getLevel().getServer()).tracks().put(name, new Tracks.Stored(route, helper.getLevel().dimension(), new Records()));
        Vec3 home = base.add(0, 0, 6);
        ServerPlayer a = player(helper, "pa" + seq, UUID.randomUUID(), home);
        ServerPlayer b = player(helper, "pb" + seq, UUID.randomUUID(), home);
        a.getInventory().add(new ItemStack(Items.DIAMOND, diamondsEach));
        b.getInventory().add(new ItemStack(Items.DIAMOND, diamondsEach));
        return new Setup(a, b, home, name);
    }

    private static ServerPlayer player(GameTestHelper helper, String name, UUID id, Vec3 at) {
        ServerPlayer p = TestPlayers.join(helper, name, id);
        p.teleportTo(helper.getLevel(), at.x, at.y, at.z, 0, 0);
        return p;
    }

    private static int run(ServerPlayer p, String command) {
        int[] result = {0};
        p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withCallback((ok, r) -> result[0] = ok ? r : 0), command);
        return result[0];
    }

    private static void step(ServerPlayer p, double dx) {
        p.teleportTo(p.serverLevel(), p.getX() + dx, p.getY(), p.getZ(), p.getYRot(), p.getXRot());
    }

    private static int diamonds(ServerPlayer p) {
        return p.getInventory().countItem(Items.DIAMOND);
    }

    // diamantes sueltos en el suelo cerca de un punto
    private static int dropped(GameTestHelper helper, Vec3 at) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(at, at).inflate(4)).stream()
                .filter(e -> e.getItem().is(Items.DIAMOND)).mapToInt(e -> e.getItem().getCount()).sum();
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
            HuddleData.get(p.server).mailbox().take(p.getUUID());
            TestPlayers.leave(helper, p);
        }
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join("; ", wrong));
        }
        helper.succeed();
    }

    // a: velocidad de a por tick una vez largada la carrera (0: quieto); lo mismo b
    private static Lobbies.Running race(GameTestHelper helper, Setup s, String entry, double speedA, double speedB, List<String> wrong) {
        check(wrong, run(s.a, "/event start race " + s.track + " " + entry) == 1, "start falló");
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 1, "join falló");
        Lobbies.Running r = Lobbies.of(s.a);
        helper.onEachTick(() -> {
            if (r != null && r.race() != null && r.race().racing() && Lobbies.all().contains(r)) {
                if (Lobbies.of(s.a) == r) {
                    step(s.a, speedA);
                }
                if (Lobbies.of(s.b) == r) {
                    step(s.b, speedB);
                }
            }
        });
        return r;
    }

    @GameTest(template = "empty", batch = "buyin", timeoutTicks = 400)
    public static void winnerTakesThePot(GameTestHelper helper) {
        Setup s = setup(helper, 8);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = race(helper, s, "5 minecraft:diamond", 1, 0.5, wrong);
        int[] during = {-1, -1};
        helper.runAfterDelay(110, () -> {
            during[0] = diamonds(s.a);
            during[1] = diamonds(s.b);
        });
        helper.runAfterDelay(260, () -> {
            check(wrong, during[0] == 3 && during[1] == 3, "no cobró la entrada al arrancar: " + during[0] + " " + during[1]);
            check(wrong, !Lobbies.all().contains(r), "la carrera no terminó");
            check(wrong, diamonds(s.a) == 13 && diamonds(s.b) == 3, "reparto: " + diamonds(s.a) + " " + diamonds(s.b));
            check(wrong, HuddleData.get(s.a.server).pots().isEmpty() || !HuddleData.get(s.a.server).pots().containsKey(r.lobby().id()),
                    "el pozo quedó guardado");
            done(helper, wrong, s.a, s.b);
        });
    }

    // quien abandona sin llegar pierde su entrada, que va al ganador
    @GameTest(template = "empty", batch = "buyin", timeoutTicks = 400)
    public static void leaverPaysTheWinner(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = race(helper, s, "5 minecraft:diamond", 0.5, 0, wrong);
        helper.runAfterDelay(110, () -> check(wrong, run(s.b, "/event leave") == 1, "leave falló"));
        helper.runAfterDelay(260, () -> {
            check(wrong, !Lobbies.all().contains(r), "la carrera no terminó");
            check(wrong, diamonds(s.a) == 10 && diamonds(s.b) == 0, "reparto: " + diamonds(s.a) + " " + diamonds(s.b));
            done(helper, wrong, s.a, s.b);
        });
    }

    // sin ganador (un operador la termina antes) cada uno recupera lo suyo, también quien abandonó
    @GameTest(template = "empty", batch = "buyin", timeoutTicks = 400)
    public static void noWinnerRefundsEveryone(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = race(helper, s, "5 minecraft:diamond", 0, 0, wrong);
        helper.runAfterDelay(110, () -> {
            check(wrong, diamonds(s.a) == 0 && diamonds(s.b) == 0, "no cobró");
            run(s.b, "/event leave");
            s.a.server.getCommands().performPrefixedCommand(s.a.server.createCommandSourceStack(), "/event end " + s.a.getGameProfile().getName());
        });
        helper.runAfterDelay(130, () -> {
            check(wrong, !Lobbies.all().contains(r), "la carrera no terminó");
            check(wrong, diamonds(s.a) == 5 && diamonds(s.b) == 5, "devolución: " + diamonds(s.a) + " " + diamonds(s.b));
            done(helper, wrong, s.a, s.b);
        });
    }

    // llegar primero y después salir no le quita el pozo
    @GameTest(template = "empty", batch = "buyin", timeoutTicks = 400)
    public static void finishedThenLeftStillWins(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = race(helper, s, "5 minecraft:diamond", 1, 0.3, wrong);
        helper.runAfterDelay(130, () -> {
            check(wrong, r.race() != null && r.race().state() != null && !r.race().state().results().isEmpty(), "nadie llegó todavía");
            check(wrong, run(s.a, "/event leave") == 1, "leave falló");
        });
        helper.runAfterDelay(300, () -> {
            check(wrong, !Lobbies.all().contains(r), "la carrera no terminó");
            check(wrong, diamonds(s.a) == 10 && diamonds(s.b) == 0, "reparto: " + diamonds(s.a) + " " + diamonds(s.b));
            done(helper, wrong, s.a, s.b);
        });
    }

    // tirar la entrada después de unirse: queda fuera al confirmar la sala, y el creador no paga una carrera que no arranca
    @GameTest(template = "empty", batch = "buyin", timeoutTicks = 200)
    public static void entryLostBeforeStart(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        List<String> wrong = new ArrayList<>();
        check(wrong, run(s.a, "/event start race " + s.track + " 5 minecraft:diamond") == 1, "start falló");
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 1, "join falló");
        s.b.getInventory().clearOrCountMatchingItems(st -> st.is(Items.DIAMOND), 2, s.b.inventoryMenu.getCraftSlots());
        Lobbies.Running r = Lobbies.of(s.a);
        helper.runAfterDelay(60, () -> {
            check(wrong, !Lobbies.all().contains(r), "arrancó con alguien que no pagó");
            check(wrong, diamonds(s.a) == 5 && diamonds(s.b) == 3, "inventarios: " + diamonds(s.a) + " " + diamonds(s.b));
            check(wrong, !HuddleData.get(s.a.server).pots().containsKey(r.lobby().id()), "quedó un pozo");
            done(helper, wrong, s.a, s.b);
        });
    }

    // un diamante con nombre o una espada gastada no son la entrada
    @GameTest(template = "empty", batch = "buyin", timeoutTicks = 100)
    public static void onlyPlainItemsPay(GameTestHelper helper) {
        Setup s = setup(helper, 0);
        List<String> wrong = new ArrayList<>();
        s.a.getInventory().add(new ItemStack(Items.DIAMOND, 5));
        ItemStack named = new ItemStack(Items.DIAMOND, 5);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("falso"));
        s.b.getInventory().add(named);
        check(wrong, run(s.a, "/event start race " + s.track + " 5 minecraft:diamond") == 1, "start falló");
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 0, "pagó con diamantes con nombre");
        ItemStack worn = new ItemStack(Items.IRON_SWORD);
        worn.setDamageValue(200);
        s.b.getInventory().add(worn);
        Lobbies.leave(s.a.server, s.a.getUUID());
        check(wrong, run(s.b, "/event start race " + s.track + " 1 minecraft:iron_sword") == 0, "pagó con una espada gastada");
        // con diamantes de sobra: el rechazo tiene que venir del tope, no de que le falten
        s.a.getInventory().add(new ItemStack(Items.DIAMOND, 64));
        s.a.getInventory().add(new ItemStack(Items.DIAMOND, 64));
        check(wrong, run(s.a, "/event start race " + s.track + " 65 minecraft:diamond") == 0, "aceptó una entrada sobre el máximo");
        // la armadura puesta no se cobra
        s.b.getInventory().clearContent();
        s.b.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        check(wrong, run(s.b, "/event start race " + s.track + " 1 minecraft:diamond_helmet") == 0, "cobró la armadura puesta");
        done(helper, wrong, s.a, s.b);
    }

    // el ganador se desconecta antes del reparto: lo recibe al volver, una sola vez
    @GameTest(template = "empty", batch = "buyin", timeoutTicks = 500)
    public static void offlineWinnerGetsItOnce(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        List<String> wrong = new ArrayList<>();
        UUID id = s.a.getUUID();
        String name = s.a.getGameProfile().getName();
        Lobbies.Running r = race(helper, s, "5 minecraft:diamond", 1, 0.2, wrong);
        helper.runAfterDelay(130, () -> TestPlayers.leave(helper, s.a));
        helper.runAfterDelay(320, () -> {
            check(wrong, !Lobbies.all().contains(r), "la carrera no terminó");
            // una entrega por cada entrada del pozo
            check(wrong, HuddleData.get(s.b.server).mailbox().count(id) == 2, "el premio no quedó esperando");
            ServerPlayer back = player(helper, name, id, s.home);
            check(wrong, diamonds(back) == 10, "al volver tiene " + diamonds(back));
            BuyIns.deliver(back);
            check(wrong, diamonds(back) == 10, "segunda entrega duplicó: " + diamonds(back));
            done(helper, wrong, back, s.b);
        });
    }

    // inventario lleno: el pozo cae a sus pies y no se pierde
    @GameTest(template = "empty", batch = "buyin", timeoutTicks = 400)
    public static void fullInventoryDropsAtFeet(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = race(helper, s, "5 minecraft:diamond", 1, 0, wrong);
        helper.runAfterDelay(110, () -> {
            for (int i = 0; i < 36; i++) {
                if (s.a.getInventory().items.get(i).isEmpty()) {
                    s.a.getInventory().items.set(i, new ItemStack(Items.DIRT, 64));
                }
            }
            s.a.getInventory().offhand.set(0, new ItemStack(Items.DIRT, 64));
            run(s.b, "/event leave");
        });
        helper.runAfterDelay(260, () -> {
            check(wrong, !Lobbies.all().contains(r), "la carrera no terminó");
            check(wrong, diamonds(s.a) + dropped(helper, s.home) == 10, "pozo perdido: " + diamonds(s.a) + " + " + dropped(helper, s.home));
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(s.home, s.home).inflate(4)).forEach(e -> e.discard());
            done(helper, wrong, s.a, s.b);
        });
    }

    // caída del servidor con el pozo cobrado: se guarda con el mundo y al arrancar cada uno recupera lo suyo, una vez
    // en su propio lote: devuelve todos los pozos guardados, también los de otras pruebas
    @GameTest(template = "empty", batch = "buyin_restart", timeoutTicks = 100)
    public static void restartRefundsUnfinishedPots(GameTestHelper helper) {
        Setup s = setup(helper, 5);
        List<String> wrong = new ArrayList<>();
        UUID event = UUID.randomUUID();
        BuyIns.collect(s.a.server, event, new BuyIns.Price(Items.DIAMOND, 4), List.of(s.a, s.b));
        BuyIns.forfeit(s.a.server, event, s.b.getUUID());
        check(wrong, diamonds(s.a) == 1 && diamonds(s.b) == 1, "no cobró");
        HuddleData saved = HuddleData.load(HuddleData.get(s.a.server).save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        Pot<BuyIns.Stake> pot = saved.pots().get(event);
        check(wrong, pot != null && pot.size() == 2 && pot.forfeitedBy(s.b.getUUID()), "el pozo no se guardó entero");
        // lo que hace el arranque del servidor con un pozo sin terminar
        BuyIns.refundLeftovers(s.a.server);
        BuyIns.refundLeftovers(s.a.server);
        BuyIns.deliver(s.a);
        BuyIns.deliver(s.b);
        BuyIns.deliver(s.a);
        check(wrong, diamonds(s.a) == 5 && diamonds(s.b) == 5, "devolución: " + diamonds(s.a) + " " + diamonds(s.b));
        done(helper, wrong, s.a, s.b);
    }

    // buzón guardado roto o con un ítem de un mod que ya no está: nada se crea de la nada y nada se tira
    @GameTest(template = "empty", batch = "buyin", timeoutTicks = 100)
    public static void corruptMailboxIsSafe(GameTestHelper helper) {
        configure();
        List<String> wrong = new ArrayList<>();
        ServerPlayer p = player(helper, "mb" + (++seq), UUID.randomUUID(), helper.absoluteVec(new Vec3(1, 1, 1)));
        CompoundTag root = new CompoundTag();
        CompoundTag mail = new CompoundTag();
        ListTag items = new ListTag();
        for (Object[] v : new Object[][] {{"minecraft:diamond", 2}, {"minecraft:diamond", -50}, {"minecraft:diamond", 0}, {"", 5},
                {"otromod:gema", 3}}) {
            CompoundTag t = new CompoundTag();
            t.putString("item", (String) v[0]);
            t.putInt("count", (Integer) v[1]);
            items.add(t);
        }
        mail.put(p.getUUID().toString(), items);
        mail.put("no-es-uuid", new ListTag());
        root.put("mailbox", mail);
        HuddleData loaded = HuddleData.load(root, helper.getLevel().registryAccess());
        check(wrong, loaded.mailbox().count(p.getUUID()) == 2, "entradas rotas cargadas: " + loaded.mailbox().count(p.getUUID()));
        loaded.mailbox().take(p.getUUID()).forEach(st -> HuddleData.get(p.server).mailbox().post(p.getUUID(), st));
        BuyIns.deliver(p);
        check(wrong, diamonds(p) == 2, "diamantes: " + diamonds(p));
        check(wrong, HuddleData.get(p.server).mailbox().count(p.getUUID()) == 1, "el ítem desconocido no quedó guardado");
        done(helper, wrong, p);
    }
}
