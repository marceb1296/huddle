package dev.huddle.neoforge;

import dev.huddle.core.Cooldowns;
import dev.huddle.core.EventTag;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// la sala de punta a punta con jugadores reales del servidor y los comandos tal como los manda el chat
@GameTestHolder(Huddle.MODID)
@PrefixGameTestTemplate(false)
public final class LobbyTest {
    private static int seq;

    // las pruebas del lote corren a la vez: todas usan esta misma configuración
    private static void configure() {
        set("race", 2, 3, 1, 5, -1, -1);
        set("bounty", 3, 3, 1, 5, -1, -1);
        set("lastman", 3, 4, 1, 5, 5, -1);
        set("treasure", 2, 2, 1, 5, -1, -1);
        set("airdrop", 2, 4, 1, 5, -1, 2);
        HuddleConfig.ANNOUNCE_RADIUS.set(64);
        HuddleConfig.MAX_DISTANCE.set(200);
        HuddleConfig.DISCONNECT_GRACE_SECONDS.set(1);
        // el tesoro necesita un lugar: lo busca en la superficie de alrededor del pozo
        HuddleConfig.GENERATOR_RADIUS.set(32);
        HuddleConfig.GENERATOR_COLUMNS_PER_TICK.set(4096);
        HuddleConfig.BUSY_CHUNK_MINUTES.set(0);
        HuddleConfig.TREASURE_MIN_DISTANCE.set(4);
        HuddleConfig.TREASURE_MAX_DISTANCE.set(10);
    }

    private static void onSurface(GameTestHelper helper, ServerPlayer p, double x, double z) {
        Vec3 at = helper.absoluteVec(new Vec3(x, 0, z));
        int y = helper.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(at.x),
                (int) Math.floor(at.z));
        p.teleportTo(helper.getLevel(), at.x, y, at.z, 0, 0);
    }

    private static void set(String type, int min, int max, int countdown, int cooldownMin, int armor, int enchant) {
        HuddleConfig.TypeValues v = HuddleConfig.TYPES.get(type);
        v.enabled.set(true);
        v.minPlayers.set(min);
        v.maxPlayers.set(max);
        v.lobbySeconds.set(60);
        v.countdownSeconds.set(countdown);
        v.cooldownMinutes.set(cooldownMin);
        v.maxArmor.set(armor);
        v.maxEnchantLevel.set(enchant);
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer p = TestPlayers.join(helper, name + (++seq));
        Vec3 at = helper.absoluteVec(new Vec3(1, 1, 1));
        p.teleportTo(helper.getLevel(), at.x, at.y, at.z, 0, 0);
        return p;
    }

    // pista recta desde donde está el jugador
    static String track(ServerPlayer at) {
        String name = "t" + (++seq);
        dev.huddle.core.Pos start = Tracks.pos(at);
        dev.huddle.core.Route route = dev.huddle.core.Route.of(name, at.getUUID(), "test", dev.huddle.core.Route.Mode.FOOT, 1, start,
                List.of(new dev.huddle.core.Route.Checkpoint(new dev.huddle.core.Pos(start.x() + 20, start.y(), start.z()), 3))).orElseThrow();
        HuddleData.get(at.server).tracks().put(name, new Tracks.Stored(route, at.level().dimension(), new dev.huddle.core.Records()));
        return name;
    }

    private static int run(ServerPlayer p, String command) {
        MinecraftServer server = p.server;
        int[] result = {0};
        server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withCallback((ok, r) -> result[0] = ok ? r : 0), command);
        return result[0];
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

    // la sala llena cuenta y arranca; todos quedan en el mismo evento sin PvP y con enfriamiento
    @GameTest(template = "empty", batch = "lobby", timeoutTicks = 200)
    public static void fullLobbyStartsWithCommands(GameTestHelper helper) {
        configure();
        ServerPlayer a = player(helper, "rc");
        ServerPlayer b = player(helper, "rb");
        ServerPlayer c = player(helper, "rd");
        List<String> wrong = new ArrayList<>();
        check(wrong, run(a, "/event start race " + track(a)) == 1, "start falló");
        check(wrong, run(b, "/evento unirse " + a.getGameProfile().getName()) == 1, "unirse en español falló");
        check(wrong, run(c, "/event join " + a.getGameProfile().getName()) == 1, "join falló");
        check(wrong, Lobbies.of(a).bar().getPlayers().size() == 3, "la barra no la ven los 3");
        helper.runAfterDelay(40, () -> {
            Lobbies.Running r = Lobbies.of(a);
            check(wrong, r != null && r.phase() == Lobbies.Phase.RUNNING, "no arrancó");
            EventTag tag = Participants.of(a);
            check(wrong, tag != null && tag.equals(Participants.of(b)) && tag.equals(Participants.of(c)), "no comparten el evento");
            check(wrong, tag != null && !tag.pvp(), "la carrera tiene PvP");
            check(wrong, HuddleData.get(a.server).cooldowns().active(Cooldowns.playerType(b.getUUID(), "race"), System.currentTimeMillis()),
                    "sin enfriamiento");
            check(wrong, run(b, "/event leave") == 1, "leave falló");
            check(wrong, Participants.of(b) == null && Lobbies.of(b) == null, "sigue en el evento tras salir");
            check(wrong, r != null && !r.bar().getPlayers().contains(b), "sigue viendo la barra");
            check(wrong, Participants.of(a) != null, "salir de uno sacó a otro");
            done(helper, wrong, a, b, c);
        });
    }

    // desconectarse en la cuenta regresiva deja la sala por debajo del mínimo: se cancela sin costo
    @GameTest(template = "empty", batch = "lobby", timeoutTicks = 200)
    public static void disconnectInCountdownCancels(GameTestHelper helper) {
        configure();
        ServerPlayer a = player(helper, "ba");
        ServerPlayer b = player(helper, "bb");
        ServerPlayer c = player(helper, "bc");
        List<String> wrong = new ArrayList<>();
        run(a, "/event start bounty");
        run(b, "/event join " + a.getGameProfile().getName());
        run(c, "/event join " + a.getGameProfile().getName());
        Lobbies.Running r = Lobbies.of(a);
        TestPlayers.leave(helper, c);
        helper.runAfterDelay(40, () -> {
            check(wrong, r != null && !Lobbies.all().contains(r), "la sala sigue viva");
            check(wrong, Lobbies.of(a) == null && Lobbies.of(b) == null, "quedaron anotados");
            check(wrong, Participants.of(a) == null && Participants.of(b) == null, "arrancó con menos del mínimo");
            check(wrong, r != null && r.bar().getPlayers().isEmpty(), "la barra quedó visible");
            check(wrong, !HuddleData.get(a.server).cooldowns().active(Cooldowns.playerType(a.getUUID(), "bounty"), System.currentTimeMillis()),
                    "la cancelación costó enfriamiento");
            check(wrong, run(a, "/event start bounty") == 1, "no puede volver a crear");
            done(helper, wrong, a, b);
        });
    }

    // quien se pone armadura después de unirse queda afuera al arrancar
    @GameTest(template = "empty", batch = "lobby", timeoutTicks = 200)
    public static void gearIsMeasuredAgainAtStart(GameTestHelper helper) {
        configure();
        ServerPlayer a = player(helper, "la");
        ServerPlayer b = player(helper, "lb");
        ServerPlayer c = player(helper, "lc");
        ServerPlayer d = player(helper, "ld");
        List<String> wrong = new ArrayList<>();
        run(a, "/event start lastman");
        for (ServerPlayer p : new ServerPlayer[] {b, c, d}) {
            check(wrong, run(p, "/event join " + a.getGameProfile().getName()) == 1, p.getGameProfile().getName() + " no pudo unirse");
        }
        d.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        // la armadura se recalcula en el tick del jugador, y el de prueba no avanza solo
        d.doTick();
        helper.runAfterDelay(40, () -> {
            check(wrong, Participants.of(a) != null && Participants.of(b) != null && Participants.of(c) != null, "no arrancó con los 3");
            check(wrong, Participants.of(d) == null && Lobbies.of(d) == null, "entró con armadura de más");
            check(wrong, Participants.of(a) == null || Participants.of(a).pvp(), "último en pie sin PvP");
            done(helper, wrong, a, b, c, d);
        });
    }

    // si los que quedan afuera por equipo dejan la sala bajo el mínimo, no arranca ni cobra enfriamiento
    @GameTest(template = "empty", batch = "lobby", timeoutTicks = 200)
    public static void gearRemovalBelowMinimumCancels(GameTestHelper helper) {
        configure();
        ServerPlayer a = player(helper, "ma");
        ServerPlayer b = player(helper, "mb");
        ServerPlayer c = player(helper, "mc");
        ServerPlayer d = player(helper, "md");
        List<String> wrong = new ArrayList<>();
        run(a, "/event start lastman");
        for (ServerPlayer p : new ServerPlayer[] {b, c, d}) {
            run(p, "/event join " + a.getGameProfile().getName());
        }
        for (ServerPlayer p : new ServerPlayer[] {c, d}) {
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            p.doTick();
        }
        Lobbies.Running r = Lobbies.of(a);
        helper.runAfterDelay(40, () -> {
            check(wrong, Participants.of(a) == null && Participants.of(b) == null, "arrancó con 2 de 3");
            check(wrong, r != null && !Lobbies.all().contains(r) && Lobbies.of(a) == null, "la sala sigue viva");
            check(wrong, !HuddleData.get(a.server).cooldowns().active(Cooldowns.playerType(a.getUUID(), "lastman"), System.currentTimeMillis()),
                    "cobró enfriamiento");
            done(helper, wrong, a, b, c, d);
        });
    }

    @GameTest(template = "empty", batch = "lobby", timeoutTicks = 100)
    public static void joinIsRefusedWhenItShould(GameTestHelper helper) {
        configure();
        ServerPlayer a = player(helper, "ja");
        ServerPlayer far = player(helper, "jf");
        ServerPlayer other = player(helper, "jo");
        ServerPlayer enchanted = player(helper, "je");
        List<String> wrong = new ArrayList<>();
        run(a, "/event start airdrop");
        far.teleportTo(far.getX() + 500, far.getY(), far.getZ());
        check(wrong, Lobbies.join(far, Lobbies.of(a)) == Lobbies.JoinResult.TOO_FAR, "se unió desde lejos");
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.SHARPNESS), 5);
        enchanted.getInventory().setItem(20, sword);
        check(wrong, Lobbies.join(enchanted, Lobbies.of(a)) == Lobbies.JoinResult.ENCHANT_TOO_HIGH, "se unió con filo V");
        // dos salas a la vez: estar en una impide unirse a otra
        run(other, "/event start treasure");
        check(wrong, Lobbies.join(other, Lobbies.of(a)) == Lobbies.JoinResult.BUSY, "se unió a dos salas");
        check(wrong, run(far, "/event vote bow") == 0, "votó sin estar en la sala");
        // el creador no puede abrir otro evento mientras el suyo siga vivo, aunque haya salido
        ServerPlayer stays = player(helper, "js");
        check(wrong, run(stays, "/event join " + a.getGameProfile().getName()) == 1, "no pudo unirse");
        run(a, "/event leave");
        check(wrong, Lobbies.of(stays) != null, "la sala se cerró con alguien adentro");
        check(wrong, Lobbies.start(a, EventTypes.byId("race"), null, null) == Lobbies.StartResult.BUSY, "abrió un segundo evento");
        Lobbies.Running mine = Lobbies.byCreator(other.getGameProfile().getName(), null);
        check(wrong, run(far, "/event end " + other.getGameProfile().getName()) == 0 && Lobbies.all().contains(mine),
                "terminó un evento ajeno sin permiso");
        done(helper, wrong, a, far, other, enchanted, stays);
    }

    // desconectado más que el margen o demasiado lejos: sale; el evento termina cuando no queda nadie
    @GameTest(template = "empty", batch = "lobby", timeoutTicks = 200)
    public static void offlineAndFarParticipantsLeave(GameTestHelper helper) {
        configure();
        ServerPlayer a = player(helper, "oa");
        ServerPlayer b = player(helper, "ob");
        onSurface(helper, a, 2.5, -6.5);
        onSurface(helper, b, 3.5, -6.5);
        List<String> wrong = new ArrayList<>();
        run(a, "/event start treasure");
        run(b, "/event join " + a.getGameProfile().getName());
        helper.runAfterDelay(30, () -> {
            Lobbies.Running r = Lobbies.of(a);
            check(wrong, r != null && r.phase() == Lobbies.Phase.RUNNING, "no arrancó");
            TestPlayers.leave(helper, b);
            check(wrong, r != null && r.participants().contains(b.getUUID()), "lo sacó sin esperar el margen");
            helper.runAfterDelay(30, () -> {
                check(wrong, r != null && !r.participants().contains(b.getUUID()), "sigue tras el margen");
                check(wrong, Participants.of(b) == null, "quedó marcado como participante");
                a.teleportTo(a.getX() + 300, a.getY(), a.getZ());
                helper.runAfterDelay(2, () -> {
                    check(wrong, Participants.of(a) == null, "sigue en el evento lejos");
                    check(wrong, r != null && !Lobbies.all().contains(r), "el evento vacío sigue vivo");
                    done(helper, wrong, a);
                });
            });
        });
    }

    // en la recompensa, un cazador que cae sigue; el marcado que cae sin que lo atrapen también sigue (a la vista un rato),
    // y si un cazador lo atrapa, el evento termina y quedan libres
    @GameTest(template = "empty", batch = "lobby", timeoutTicks = 200)
    public static void knockedOutLeavesTheEvent(GameTestHelper helper) {
        configure();
        ServerPlayer a = player(helper, "ka");
        ServerPlayer b = player(helper, "kb");
        ServerPlayer c = player(helper, "kc");
        List<String> wrong = new ArrayList<>();
        run(a, "/event start bounty");
        run(b, "/event join " + a.getGameProfile().getName());
        run(c, "/event join " + a.getGameProfile().getName());
        helper.runAfterDelay(30, () -> {
            Lobbies.Running r = Lobbies.of(a);
            check(wrong, r != null && r.phase() == Lobbies.Phase.RUNNING, "no arrancó");
            if (r == null || r.bounty() == null) {
                done(helper, wrong, a, b, c);
                return;
            }
            List<ServerPlayer> all = List.of(a, b, c);
            ServerPlayer marked = all.stream().filter(p -> p.getUUID().equals(r.bounty().marked())).findFirst().orElseThrow();
            ServerPlayer hunter = all.stream().filter(p -> p != marked).findFirst().orElseThrow();
            hunter.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000);
            check(wrong, hunter.isAlive() && r.participants().contains(hunter.getUUID()) && Lobbies.of(hunter) == r, "el cazador quedó fuera");
            marked.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000);
            check(wrong, marked.isAlive(), "murió de verdad");
            check(wrong, r.participants().contains(marked.getUUID()) && Lobbies.of(marked) == r, "el marcado quedó fuera sin que lo atraparan");
            marked.invulnerableTime = 0;
            marked.hurt(helper.getLevel().damageSources().playerAttack(hunter), 1000);
            check(wrong, marked.isAlive() && !r.participants().contains(marked.getUUID()) && Lobbies.of(marked) == null, "atrapado y sigue en la lista");
            // el marcado puede ser el creador: su evento termina en el tick siguiente (ganan los cazadores)
            helper.runAfterDelay(5, () -> {
                check(wrong, !Lobbies.all().contains(r), "el evento siguió sin marcado");
                Lobbies.StartResult again = Lobbies.start(marked, EventTypes.byId("race"), null, null);
                check(wrong, again == Lobbies.StartResult.STARTED, "no puede hacer otra cosa: " + again);
                done(helper, wrong, a, b, c);
            });
        });
    }

    // la entrada se pone sin preset, /event start <tipo> <cantidad> <ítem>: también en la recompensa, que no tiene presets
    @GameTest(template = "empty", batch = "lobby", timeoutTicks = 100)
    public static void entryWithoutPreset(GameTestHelper helper) {
        configure();
        ServerPlayer a = player(helper, "ea");
        ServerPlayer b = player(helper, "eb");
        ServerPlayer c = player(helper, "ec");
        for (ServerPlayer p : List.of(a, b, c)) {
            p.getInventory().add(new ItemStack(Items.DIAMOND, 5));
        }
        List<String> wrong = new ArrayList<>();
        check(wrong, run(a, "/event start bounty 2 minecraft:diamond") == 1, "la recompensa con entrada no abrió");
        Lobbies.Running r = Lobbies.of(a);
        check(wrong, r != null && r.price() != null && r.price().count() == 2 && r.price().item() == Items.DIAMOND,
                "recompensa sin la entrada: " + (r == null ? null : r.price()));
        check(wrong, run(b, "/evento iniciar treasure 3 minecraft:diamond") == 1, "el tesoro con entrada no abrió");
        Lobbies.Running t = Lobbies.of(b);
        check(wrong, t != null && t.price() != null && t.price().count() == 3 && t.treasure() != null
                && t.treasure().preset() == dev.huddle.core.TreasureFinder.Preset.CLASSIC, "tesoro sin entrada o sin el preset clásico");
        check(wrong, run(c, "/event start race 2 minecraft:diamond") == 0 && Lobbies.of(c) == null, "abrió una carrera sin pista");
        done(helper, wrong, a, b, c);
    }

    // el enfriamiento se guarda con el mundo
    @GameTest(template = "empty", batch = "lobby")
    public static void cooldownSurvivesSave(GameTestHelper helper) {
        configure();
        ServerPlayer a = player(helper, "ca");
        List<String> wrong = new ArrayList<>();
        HuddleData data = HuddleData.get(a.server);
        data.cooldowns().start(Cooldowns.playerType(a.getUUID(), "airdrop"), System.currentTimeMillis(), 60_000);
        CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
        HuddleData loaded = HuddleData.load(saved, helper.getLevel().registryAccess());
        check(wrong, loaded.cooldowns().active(Cooldowns.playerType(a.getUUID(), "airdrop"), System.currentTimeMillis()), "se perdió al guardar");
        check(wrong, Lobbies.start(a, EventTypes.byId("airdrop"), null, null) == Lobbies.StartResult.ON_COOLDOWN, "creó estando en enfriamiento");
        data.cooldowns().start(Cooldowns.playerType(a.getUUID(), "airdrop"), 0, 0);
        done(helper, wrong, a);
    }

    // la sala abierta la puede cancelar quien la creó (empezada ya no: ver RaceEventTest.creatorCannotEndARunningRace)
    @GameTest(template = "empty", batch = "lobby", timeoutTicks = 100)
    public static void creatorCancelsOpenLobby(GameTestHelper helper) {
        configure();
        ServerPlayer a = player(helper, "xa");
        List<String> wrong = new ArrayList<>();
        check(wrong, run(a, "/event start bounty") == 1, "no creó");
        Lobbies.Running r = Lobbies.of(a);
        check(wrong, run(a, "/event end") == 1, "no pudo cancelar su sala");
        check(wrong, r != null && !Lobbies.all().contains(r), "la sala siguió");
        done(helper, wrong, a);
    }

    // un [Join] de un anuncio viejo no lleva a la sala que el mismo creador abrió después; el nuevo sí
    @GameTest(template = "empty", batch = "lobby", timeoutTicks = 100)
    public static void oldJoinButtonMissesTheNewLobby(GameTestHelper helper) {
        configure();
        ServerPlayer a = player(helper, "oa");
        ServerPlayer b = player(helper, "ob");
        List<String> wrong = new ArrayList<>();
        check(wrong, run(a, "/event start bounty") == 1, "no creó la primera");
        String old = joinButton(b);
        check(wrong, run(a, "/event end") == 1, "no canceló la primera");
        check(wrong, run(a, "/event start bounty") == 1, "no creó la segunda");
        String fresh = joinButton(b);
        check(wrong, old != null && fresh != null && !old.equals(fresh), "los dos botones son iguales: " + old);
        check(wrong, old != null && run(b, old) == 0 && Lobbies.of(b) == null, "el botón viejo unió a la sala nueva");
        check(wrong, fresh != null && run(b, fresh) == 1 && Lobbies.of(b) != null && Lobbies.of(b) == Lobbies.of(a), "el botón nuevo no unió");
        Lobbies.leave(b.server, b.getUUID());
        check(wrong, run(b, "/event join " + a.getGameProfile().getName()) == 1, "a mano, sin la sala, ya no une");
        done(helper, wrong, a, b);
    }

    // el comando del último [Join] que le llegó al jugador
    private static String joinButton(ServerPlayer p) {
        String found = null;
        for (Packet<?> packet : TestPlayers.received(p)) {
            if (packet instanceof ClientboundSystemChatPacket c) {
                String cmd = joinClick(c.content());
                found = cmd == null ? found : cmd;
            }
        }
        return found;
    }

    private static String joinClick(net.minecraft.network.chat.Component c) {
        net.minecraft.network.chat.ClickEvent click = c.getStyle().getClickEvent();
        if (click != null && click.getValue().startsWith("/event join ")) {
            return click.getValue();
        }
        List<net.minecraft.network.chat.Component> inner = new ArrayList<>(c.getSiblings());
        if (c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) {
            for (Object arg : t.getArgs()) {
                if (arg instanceof net.minecraft.network.chat.Component ac) {
                    inner.add(ac);
                }
            }
        }
        for (net.minecraft.network.chat.Component i : inner) {
            String cmd = joinClick(i);
            if (cmd != null) {
                return cmd;
            }
        }
        return null;
    }

    // la recompensa rehace el tag con el equipo: el modo votado tiene que llegar igual a cazadores y marcado, y anunciarse
    @GameTest(template = "empty", batch = "lobby", timeoutTicks = 200)
    public static void bountyAppliesTheVotedMode(GameTestHelper helper) {
        configure();
        // otros lotes dejan la recompensa sin modos para votar
        HuddleConfig.TYPES.get("bounty").combatModes.set(List.of("any", "fists"));
        ServerPlayer a = player(helper, "va");
        ServerPlayer b = player(helper, "vb");
        ServerPlayer c = player(helper, "vc");
        List<String> wrong = new ArrayList<>();
        run(a, "/event start bounty");
        run(b, "/event join " + a.getGameProfile().getName());
        run(c, "/event join " + a.getGameProfile().getName());
        for (ServerPlayer p : List.of(a, b, c)) {
            check(wrong, run(p, "/event vote fists") == 1, "no votó " + p.getGameProfile().getName());
        }
        TestPlayers.received(a);
        helper.runAfterDelay(30, () -> {
            Lobbies.Running r = Lobbies.of(a);
            check(wrong, r != null && r.phase() == Lobbies.Phase.RUNNING && r.bounty != null, "no arrancó");
            if (wrong.isEmpty()) {
                ServerPlayer target = a.server.getPlayerList().getPlayer(r.bounty.marked());
                ServerPlayer hunter = target == a ? b : a;
                check(wrong, "fists".equals(Participants.of(target).combat()) && "fists".equals(Participants.of(hunter).combat()),
                        "el modo no llegó: " + Participants.of(target) + " " + Participants.of(hunter));
                target.invulnerableTime = 0;
                hunter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
                hunter.attack(target);
                check(wrong, target.getHealth() == target.getMaxHealth(), "pegó con espada en modo puño");
                hunter.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                target.invulnerableTime = 0;
                hunter.attack(target);
                check(wrong, target.getHealth() < target.getMaxHealth(), "el puño no pegó");
                target.setHealth(target.getMaxHealth());
                List<String> said = new ArrayList<>();
                for (Packet<?> packet : TestPlayers.received(a)) {
                    if (packet instanceof ClientboundSystemChatPacket m && !m.overlay()) {
                        said.add(m.content().getString());
                    }
                }
                check(wrong, said.stream().anyMatch(m -> m.contains("Combat: Fists only")), "no anunció el modo: " + said);
            }
            done(helper, wrong, a, b, c);
        });
    }
}
