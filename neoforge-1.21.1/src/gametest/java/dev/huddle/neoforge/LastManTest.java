package dev.huddle.neoforge;

import dev.huddle.core.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// último en pie sobre la superficie de alrededor del pozo de cada prueba (z negativo)
@GameTestHolder(Huddle.MODID)
@PrefixGameTestTemplate(false)
public final class LastManTest {
    private static int seq;

    private static void configure() {
        HuddleConfig.TypeValues v = HuddleConfig.TYPES.get("lastman");
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
        HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(true);
        HuddleConfig.GENERATOR_RADIUS.set(32);
        HuddleConfig.GENERATOR_COLUMNS_PER_TICK.set(4096);
        HuddleConfig.BUSY_CHUNK_MINUTES.set(0);
        HuddleConfig.LASTMAN_ZONE_SIZES.set(List.of(12, 6));
        HuddleConfig.LASTMAN_HOLD_SECONDS.set(1);
        HuddleConfig.LASTMAN_SHRINK_SECONDS.set(2);
        HuddleConfig.LASTMAN_OUTSIDE_SECONDS.set(3);
        HuddleConfig.LASTMAN_FINAL_SECONDS.set(30);
        HuddleConfig.LASTMAN_TRUCE_SECONDS.set(2);
        HuddleConfig.LASTMAN_LOOT.set(List.of("minecraft:chests/simple_dungeon"));
        HuddleConfig.LASTMAN_KIT.set(List.of("minecraft:iron_sword", "minecraft:bow", "minecraft:arrow 16", "minecraft:shield",
                "minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings", "minecraft:iron_boots", "minecraft:cooked_beef 8"));
    }

    private record Setup(ServerPlayer a, ServerPlayer b) {
    }

    private static Setup setup(GameTestHelper helper) {
        configure();
        return new Setup(player(helper, "la", 2.5, -6.5), player(helper, "lb", 3.5, -6.5));
    }

    private static ServerPlayer player(GameTestHelper helper, String name, double x, double z) {
        return place(helper, TestPlayers.join(helper, name + (++seq)), x, z);
    }

    private static ServerPlayer place(GameTestHelper helper, ServerPlayer p, double x, double z) {
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

    private static boolean running(Lobbies.Running r) {
        return r != null && r.phase() == Lobbies.Phase.RUNNING && Lobbies.all().contains(r);
    }

    private static int kitStacks(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            n += Kits.isKit(p.getInventory().getItem(i)) ? 1 : 0;
        }
        return n;
    }

    private static int slotOf(ServerPlayer p, net.minecraft.world.item.Item item) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    private static int count(ServerPlayer p, net.minecraft.world.item.Item item) {
        return p.getInventory().countItem(item);
    }

    private static int nonKit(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && !Kits.isKit(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static List<ClientboundInitializeBorderPacket> borders(List<Packet<?>> packets) {
        List<ClientboundInitializeBorderPacket> out = new ArrayList<>();
        for (Packet<?> p : packets) {
            if (p instanceof ClientboundInitializeBorderPacket b) {
                out.add(b);
            }
        }
        return out;
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

    private static long kitItemsOnGround(GameTestHelper helper, ServerPlayer near) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(near.blockPosition()).inflate(40), e -> Kits.isKit(e.getItem())).size();
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
            p.getInventory().clearContent();
            TestPlayers.leave(helper, p);
        }
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join("; ", wrong));
        }
        helper.succeed();
    }

    // de punta a punta: entran solo con la entrada, reciben el kit, la zona les llega como borde del mundo (y el borde
    // real no la pisa), el kit no sale del inventario, uno se queda fuera y el otro gana pozo y botín sin quedarse el kit
    // plataforma propia en el aire: los pozos de los otros lotes cortan la superficie y no hay dónde repartir.
    // Deja a los dos encima y devuelve cómo quitarla
    private static Runnable platform(GameTestHelper helper, Setup s) {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        BlockPos top = s.a.blockPosition().above(15);
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before = new java.util.HashMap<>();
        for (int dx = -18; dx <= 18; dx++) {
            for (int dz = -18; dz <= 18; dz++) {
                BlockPos b = top.offset(dx, -1, dz);
                before.put(b, level.getBlockState(b));
                level.setBlock(b, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
            }
        }
        s.a.teleportTo(level, top.getX() + 0.5, top.getY(), top.getZ() + 0.5, 0, 0);
        s.b.teleportTo(level, top.getX() + 1.5, top.getY(), top.getZ() + 0.5, 0, 0);
        return () -> before.forEach((b, state) -> level.setBlock(b, state, 2));
    }

    // abierto desde una base (todo lo que está a 16 bloques es base): no se cancela, el centro queda en la base
    @GameTest(template = "empty", batch = "lastman_busy", timeoutTicks = 200)
    public static void startFromABase(GameTestHelper helper) {
        Setup s = setup(helper);
        Runnable restore = platform(helper, s);
        HuddleConfig.BUSY_CHUNK_MINUTES.set(100000);
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        net.minecraft.world.level.ChunkPos base = new net.minecraft.world.level.ChunkPos(s.a.blockPosition());
        java.util.function.LongConsumer busy = ticks -> {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    level.getChunk(base.x + dx, base.z + dz).setInhabitedTime(ticks);
                }
            }
        };
        busy.accept(20L * 60 * 200000);
        Vec3 from = s.a.position();
        List<String> wrong = new ArrayList<>();
        check(wrong, run(s.a, "/event start lastman") == 1, "start falló");
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 1, "join falló");
        Lobbies.Running r = Lobbies.of(s.a);
        helper.runAfterDelay(60, () -> {
            busy.accept(0);
            HuddleConfig.BUSY_CHUNK_MINUTES.set(0);
            check(wrong, running(r), "no arrancó desde la base");
            if (running(r)) {
                Pos c = r.lastman().state().center();
                check(wrong, Math.hypot(c.x() - from.x, c.z() - from.z) <= LastManGame.CENTER_DISTANCE + 1, "centro lejos: " + c);
            }
            // antes de done, que lanza si algo falló: la plataforma que queda parece el techo de una cueva en otras pruebas
            restore.run();
            done(helper, wrong, s.a, s.b);
        });
    }

    @GameTest(template = "empty", batch = "lastman", timeoutTicks = 400)
    public static void fullLastManWithKitAndEntry(GameTestHelper helper) {
        Setup s = setup(helper);
        Runnable restore = platform(helper, s);
        s.a.getInventory().add(new ItemStack(Items.DIAMOND, 2));
        s.b.getInventory().add(new ItemStack(Items.DIAMOND, 2));
        List<String> wrong = new ArrayList<>();
        check(wrong, run(s.a, "/event start lastman classic 2 diamond") == 1, "start falló");
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 1, "join falló");
        Lobbies.Running r = Lobbies.of(s.a);
        List<Packet<?>> toA = new ArrayList<>();
        helper.runAfterDelay(30, () -> {
            toA.addAll(TestPlayers.received(s.a));
            check(wrong, running(r), "no arrancó");
            if (!running(r)) {
                return;
            }
            check(wrong, count(s.a, Items.DIAMOND) == 0 && count(s.b, Items.DIAMOND) == 0, "no cobró la entrada");
            check(wrong, s.a.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE) && Kits.isKit(s.a.getItemBySlot(EquipmentSlot.CHEST)),
                    "sin armadura del kit puesta");
            check(wrong, s.a.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD), "sin escudo en la otra mano");
            check(wrong, count(s.a, Items.IRON_SWORD) == 1 && count(s.b, Items.ARROW) == 16, "kit incompleto");
            check(wrong, nonKit(s.a) == 0, "algo del kit sin marcar");
            Pos c = r.lastman().state().center();
            // repartidos: empezaron uno al lado del otro y aparecen lejos, dentro de la zona
            double apart = Math.hypot(s.a.getX() - s.b.getX(), s.a.getZ() - s.b.getZ());
            check(wrong, apart >= 10, "no los repartió: a " + apart + ", a en " + s.a.position() + ", b en " + s.b.position() + ", centro " + c);
            check(wrong, r.lastman().state().inside(Tracks.pos(s.a), helper.getLevel().getServer().getTickCount())
                    && r.lastman().state().inside(Tracks.pos(s.b), helper.getLevel().getServer().getTickCount()), "repartió fuera de la zona");
            List<ClientboundInitializeBorderPacket> zone = borders(toA);
            check(wrong, !zone.isEmpty() && Math.abs(zone.get(zone.size() - 1).getNewSize() - 24) < 0.01
                    && Math.abs(zone.get(zone.size() - 1).getNewCenterX() - c.x()) < 0.01, "la zona no llegó como borde");
            // el borde real que manda el servidor (reaparecer, el admin lo cambia) no pisa la zona
            TestPlayers.received(s.a);
            s.a.connection.send(new ClientboundInitializeBorderPacket(s.a.level().getWorldBorder()));
            // el canal entrega lo enviado desde fuera de su hilo en el tick siguiente
            helper.runAfterDelay(1, () -> {
                List<ClientboundInitializeBorderPacket> real = borders(TestPlayers.received(s.a));
                check(wrong, !real.isEmpty() && real.stream().allMatch(b -> Math.abs(b.getNewSize() - 24) < 0.01), "el borde real pisó la zona: "
                        + (real.isEmpty() ? null : real.get(0).getNewSize()));
            });
            // soltar con Q: se queda en el inventario y no cae nada
            for (int i = 0; i < 9; i++) {
                if (s.a.getInventory().getItem(i).is(Items.IRON_SWORD)) {
                    s.a.getInventory().selected = i;
                }
            }
            s.a.drop(true);
            check(wrong, count(s.a, Items.IRON_SWORD) == 1, "la espada se perdió al soltarla");
            check(wrong, kitItemsOnGround(helper, s.a) == 0, "el kit cayó al suelo");
            // ni juntar, ni romper, ni abrir contenedores
            ItemEntity dirt = new ItemEntity(helper.getLevel(), s.a.getX(), s.a.getY(), s.a.getZ(), new ItemStack(Items.DIRT, 3));
            dirt.setNoPickUpDelay();
            helper.getLevel().addFreshEntity(dirt);
            dirt.playerTouch(s.a);
            check(wrong, count(s.a, Items.DIRT) == 0, "juntó un ítem del suelo");
            dirt.discard();
            BlockPos below = s.a.blockPosition().below();
            var belowState = helper.getLevel().getBlockState(below);
            s.a.gameMode.destroyBlock(below);
            check(wrong, helper.getLevel().getBlockState(below) == belowState, "rompió un bloque");
            s.a.openMenu(new SimpleMenuProvider((id, inv, pl) -> ChestMenu.threeRows(id, inv), Component.literal("x")));
            check(wrong, s.a.containerMenu == s.a.inventoryMenu, "abrió un contenedor");
            // una flecha disparada no se recoge
            Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
            arrow.setOwner(s.a);
            arrow.setPos(s.a.getX(), s.a.getY() + 5, s.a.getZ());
            helper.getLevel().addFreshEntity(arrow);
            check(wrong, arrow.pickup == AbstractArrow.Pickup.CREATIVE_ONLY, "la flecha se puede recoger");
            arrow.discard();
            s.b.teleportTo(helper.getLevel(), c.x() + 30, s.b.getY() + 20, c.z(), 0, 0);
            s.b.setNoGravity(true);
        });
        helper.runAfterDelay(80, () -> {
            toA.addAll(TestPlayers.received(s.a));
            // después de la primera espera la zona se achica: el cliente recibe el achique con su duración
            check(wrong, borders(toA).stream().anyMatch(b -> b.getLerpTime() > 0 && b.getNewSize() < b.getOldSize()), "sin achique de la zona");
            check(wrong, running(r) && r.participants().contains(s.b.getUUID()), "lo sacó antes de los 3 s fuera");
        });
        helper.runAfterDelay(140, () -> {
            s.b.setNoGravity(false);
            List<Packet<?>> toB = TestPlayers.received(s.b);
            check(wrong, !running(r), "no terminó al quedar uno");
            check(wrong, r != null && s.a.getUUID().equals(r.lastman().winner()), "ganador equivocado");
            check(wrong, kitStacks(s.a) == 0 && kitStacks(s.b) == 0, "el kit quedó: " + kitStacks(s.a) + " " + kitStacks(s.b));
            check(wrong, s.a.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "la armadura del kit quedó puesta");
            check(wrong, count(s.a, Items.DIAMOND) >= 4 && count(s.b, Items.DIAMOND) == 0, "pozo mal: " + count(s.a, Items.DIAMOND) + " "
                    + count(s.b, Items.DIAMOND));
            check(wrong, nonKit(s.a) > 4 || count(s.a, Items.DIAMOND) > 4, "sin botín");
            check(wrong, !Zones.has(s.a.getUUID()) && !Zones.has(s.b.getUUID()), "la zona siguió");
            List<ClientboundInitializeBorderPacket> back = borders(toB);
            check(wrong, !back.isEmpty() && Math.abs(back.get(back.size() - 1).getNewSize() - s.b.level().getWorldBorder().getSize()) < 1,
                    "no le devolvió el borde real");
            check(wrong, chat(toB).stream().anyMatch(m -> m.contains("outside the zone")), "sin aviso al que quedó fuera");
            List<String> out = TestPlayers.titles(toB), won = TestPlayers.titles(TestPlayers.received(s.a));
            check(wrong, out.contains("T:You're out"), "sin título al quedar fuera: " + out);
            check(wrong, out.contains("T:" + s.a.getGameProfile().getName()) && out.contains("S:is the last one standing"), "sin título del ganador para el eliminado: " + out);
            check(wrong, won.contains("T:You won!") && won.contains("S:You are the last one standing"), "sin título para el ganador: " + won);
            check(wrong, kitItemsOnGround(helper, s.a) == 0, "el kit cayó al suelo");
            restore.run();
            done(helper, wrong, s.a, s.b);
        });
    }

    // tras el reparto nadie se pega hasta que termina la tregua, y la barra cuenta cuánto falta
    @GameTest(template = "empty", batch = "lastman_truce", timeoutTicks = 200)
    public static void truceAfterTheSpread(GameTestHelper helper) {
        Setup s = setup(helper);
        // la zona quieta toda la prueba: al achicarse los dejaría fuera
        HuddleConfig.LASTMAN_HOLD_SECONDS.set(60);
        Runnable restore = platform(helper, s);
        List<String> wrong = new ArrayList<>();
        check(wrong, run(s.a, "/event start lastman") == 1, "start falló");
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 1, "join falló");
        Lobbies.Running r = Lobbies.of(s.a);
        // la barra se actualiza cada 10 ticks: a los 40 ya lo hizo y la tregua (hasta unos 60) sigue
        helper.runAfterDelay(40, () -> {
            check(wrong, running(r), "no arrancó");
            check(wrong, running(r) && helper.getLevel().getServer().getTickCount() < r.tag().pvpFrom(), "la tregua ya terminó");
            check(wrong, r != null && r.bar().getName().getString().contains("fighting starts in"), "la barra no cuenta la tregua: "
                    + (r == null ? null : r.bar().getName().getString()));
            s.b.invulnerableTime = 0;
            s.a.attack(s.b);
            check(wrong, s.b.getHealth() == s.b.getMaxHealth(), "pegó durante la tregua");
        });
        helper.runAfterDelay(40 + 2 * 20 + 20, () -> {
            check(wrong, running(r), "terminó antes de tiempo");
            s.b.invulnerableTime = 0;
            s.a.attack(s.b);
            check(wrong, s.b.getHealth() < s.b.getMaxHealth(), "no pegó después de la tregua");
            s.b.setHealth(s.b.getMaxHealth());
            restore.run();
            done(helper, wrong, s.a, s.b);
        });
    }

    // inventario con cosas: no puede crear ni unirse; si junta algo después de unirse, queda fuera al empezar y nadie
    // recibe kit (la sala se cancela por falta de jugadores)
    @GameTest(template = "empty", batch = "lastman_empty", timeoutTicks = 200)
    public static void onlyEmptyInventoriesGetTheKit(GameTestHelper helper) {
        Setup s = setup(helper);
        List<String> wrong = new ArrayList<>();
        s.a.getInventory().add(new ItemStack(Items.DIRT));
        check(wrong, run(s.a, "/event start lastman") == 0, "creó con el inventario lleno");
        s.a.getInventory().clearContent();
        s.a.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        check(wrong, run(s.a, "/event start lastman") == 0, "creó con armadura puesta");
        s.a.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        // con entrada: solo la entrada justa
        s.a.getInventory().add(new ItemStack(Items.DIAMOND, 3));
        check(wrong, run(s.a, "/event start lastman clasico 2 diamond") == 0, "creó con más que la entrada");
        s.a.getInventory().clearContent();
        check(wrong, run(s.a, "/event start lastman") == 1, "no creó con el inventario vacío");
        s.b.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH));
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 0, "se unió con algo en la otra mano");
        s.b.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        // la mesa de 2x2 del inventario también cuenta: al cerrarlo vuelve al inventario
        s.b.inventoryMenu.getCraftSlots().setItem(0, new ItemStack(Items.GOLDEN_APPLE));
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 0, "se unió con algo en la mesa de 2x2");
        s.b.inventoryMenu.getCraftSlots().setItem(0, ItemStack.EMPTY);
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 1, "no se unió vacío");
        s.b.getInventory().add(new ItemStack(Items.GOLDEN_APPLE, 4));
        Lobbies.Running r = Lobbies.of(s.a);
        helper.runAfterDelay(40, () -> {
            check(wrong, r != null && !Lobbies.all().contains(r), "arrancó igual");
            check(wrong, kitStacks(s.a) == 0 && kitStacks(s.b) == 0, "dio el kit");
            check(wrong, count(s.b, Items.GOLDEN_APPLE) == 4, "le tocó lo suyo");
            check(wrong, chat(TestPlayers.received(s.b)).stream().anyMatch(m -> m.contains("inventory was not empty")), "sin explicar");
            done(helper, wrong, s.a, s.b);
        });
    }

    // morir saca del evento sin que el kit caiga al suelo ni se quede, con y sin keepInventoryOnDeath
    @GameTest(template = "empty", batch = "lastman_death", timeoutTicks = 200)
    public static void deathTakesTheKitBack(GameTestHelper helper) {
        deathCase(helper, true);
    }

    @GameTest(template = "empty", batch = "lastman_realdeath", timeoutTicks = 200)
    public static void realDeathDropsNoKit(GameTestHelper helper) {
        deathCase(helper, false);
    }

    private static void deathCase(GameTestHelper helper, boolean keep) {
        Setup s = setup(helper);
        HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(keep);
        List<String> wrong = new ArrayList<>();
        check(wrong, run(s.a, "/event start lastman") == 1, "start falló");
        check(wrong, run(s.b, "/event join " + s.a.getGameProfile().getName()) == 1, "join falló");
        Lobbies.Running r = Lobbies.of(s.a);
        helper.runAfterDelay(30, () -> {
            check(wrong, running(r) && kitStacks(s.b) > 0, "no arrancó con kit");
            s.b.invulnerableTime = 0;
            s.b.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000);
        });
        helper.runAfterDelay(40, () -> {
            check(wrong, !running(r) && r != null && s.a.getUUID().equals(r.lastman().winner()), "no ganó el que quedó");
            check(wrong, kitStacks(s.b) == 0 && kitStacks(s.a) == 0, "el kit quedó");
            check(wrong, kitItemsOnGround(helper, s.a) == 0 && kitItemsOnGround(helper, s.b) == 0, "el kit cayó al suelo");
            HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(true);
            done(helper, wrong, s.a, s.b);
        });
    }

    // el servidor se cayó con el kit encima: al volver a entrar se quita, y lo demás queda
    @GameTest(template = "empty", batch = "lastman_login", timeoutTicks = 100)
    public static void leftoverKitIsSweptOnLogin(GameTestHelper helper) {
        configure();
        UUID id = UUID.randomUUID();
        String name = "lk" + (++seq);
        ServerPlayer p = place(helper, TestPlayers.join(helper, name, id), 2.5, -6.5);
        Kits.give(p, Kits.build(HuddleConfig.LASTMAN_KIT.get()), UUID.randomUUID());
        p.getInventory().add(new ItemStack(Items.DIRT, 5));
        List<String> wrong = new ArrayList<>();
        check(wrong, kitStacks(p) > 0, "el kit no se dio");
        TestPlayers.leave(helper, p);
        ServerPlayer back = place(helper, TestPlayers.join(helper, name, id), 2.5, -6.5);
        check(wrong, kitStacks(back) == 0 && back.getItemBySlot(EquipmentSlot.FEET).isEmpty(), "el kit quedó: " + kitStacks(back));
        check(wrong, count(back, Items.DIRT) == 5, "se llevó lo suyo");
        back.getInventory().clearContent();
        TestPlayers.leave(helper, back);
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join("; ", wrong));
        }
        helper.succeed();
    }

    // el kit en la mesa de 2x2 o en el cursor también se quita
    @GameTest(template = "empty", batch = "lastman_strip", timeoutTicks = 100)
    public static void stripFindsHiddenKit(GameTestHelper helper) {
        configure();
        ServerPlayer p = player(helper, "ls", 2.5, -6.5);
        Kits.give(p, Kits.build(List.of("minecraft:iron_sword", "minecraft:bread 2")), UUID.randomUUID());
        List<String> wrong = new ArrayList<>();
        int sword = slotOf(p, Items.IRON_SWORD);
        ItemStack s = sword < 0 ? ItemStack.EMPTY : p.getInventory().removeItemNoUpdate(sword);
        check(wrong, Kits.isKit(s), "la espada no vino marcada");
        p.inventoryMenu.getCraftSlots().setItem(1, s);
        int bread = slotOf(p, Items.BREAD);
        p.containerMenu.setCarried(bread < 0 ? ItemStack.EMPTY : p.getInventory().removeItemNoUpdate(bread));
        check(wrong, Kits.strip(p) == 2, "no encontró lo escondido");
        check(wrong, p.inventoryMenu.getCraftSlots().getItem(1).isEmpty() && p.containerMenu.getCarried().isEmpty(), "quedó escondido");
        check(wrong, !Kits.isKit(new ItemStack(Items.IRON_SWORD)), "una espada común cuenta como kit");
        p.getInventory().clearContent();
        TestPlayers.leave(helper, p);
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join("; ", wrong));
        }
        helper.succeed();
    }
}
