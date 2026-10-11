package dev.huddle.neoforge;

import dev.huddle.core.BountyHunt;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// recompensas completas con tres jugadores reales del servidor: un marcado al azar y dos cazadores
@GameTestHolder(Huddle.MODID)
@PrefixGameTestTemplate(false)
public final class BountyTest {
    private static int seq;

    private static void configure() {
        HuddleConfig.TypeValues v = HuddleConfig.TYPES.get("bounty");
        v.enabled.set(true);
        v.minPlayers.set(3);
        v.maxPlayers.set(3);
        v.lobbySeconds.set(60);
        v.countdownSeconds.set(1);
        v.cooldownMinutes.set(0);
        v.maxArmor.set(-1);
        v.maxEnchantLevel.set(-1);
        v.combatModes.set(List.of("any"));
        HuddleConfig.ANNOUNCE_RADIUS.set(64);
        HuddleConfig.MAX_DISTANCE.set(200);
        HuddleConfig.DISCONNECT_GRACE_SECONDS.set(1);
        HuddleConfig.MAX_BUY_IN.set(64);
        HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(true);
        HuddleConfig.DEATH_DESTINATION.set(HuddleConfig.DeathDestination.JOIN_POINT);
        HuddleConfig.BOUNTY_MAX_SECONDS.set(30);
        HuddleConfig.BOUNTY_CAMP_SECONDS.set(10);
    }

    private record Game(Lobbies.Running r, ServerPlayer marked, ServerPlayer h1, ServerPlayer h2, List<ServerPlayer> all) {
    }

    private static List<ServerPlayer> players(GameTestHelper helper, int diamonds) {
        configure();
        List<ServerPlayer> out = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ServerPlayer p = TestPlayers.join(helper, "b" + i + "_" + (++seq));
            Vec3 at = helper.absoluteVec(new Vec3(1.5 + i, 1, 2.5));
            p.teleportTo(helper.getLevel(), at.x, at.y, at.z, 0, 0);
            p.getInventory().add(new ItemStack(Items.DIAMOND, diamonds));
            out.add(p);
        }
        return out;
    }

    private static Lobbies.Running open(List<ServerPlayer> ps, String args, List<String> wrong) {
        check(wrong, run(ps.get(0), ("/event start bounty " + args).trim()) == 1, "start falló");
        for (int i = 1; i < ps.size(); i++) {
            check(wrong, run(ps.get(i), "/event join " + ps.get(0).getGameProfile().getName()) == 1, "join falló");
        }
        return Lobbies.of(ps.get(0));
    }

    // null si no arrancó
    private static Game game(Lobbies.Running r, List<ServerPlayer> ps) {
        if (r == null || r.bounty() == null || !Lobbies.all().contains(r)) {
            return null;
        }
        UUID m = r.bounty().marked();
        List<ServerPlayer> hunters = ps.stream().filter(p -> !p.getUUID().equals(m)).toList();
        ServerPlayer marked = ps.stream().filter(p -> p.getUUID().equals(m)).findFirst().orElseThrow();
        return new Game(r, marked, hunters.get(0), hunters.get(1), ps);
    }

    private static boolean hurts(ServerPlayer attacker, ServerPlayer victim) {
        victim.setHealth(victim.getMaxHealth());
        victim.invulnerableTime = 0;
        attacker.attack(victim);
        boolean hurt = victim.getHealth() < victim.getMaxHealth();
        victim.setHealth(victim.getMaxHealth());
        return hurt;
    }

    private static void place(GameTestHelper helper, ServerPlayer p, double x, double z) {
        Vec3 at = helper.absoluteVec(new Vec3(x, 1, z));
        p.teleportTo(helper.getLevel(), at.x, at.y, at.z, 0, 0);
    }

    private static int diamonds(ServerPlayer p) {
        return p.getInventory().countItem(Items.DIAMOND);
    }

    private static int run(ServerPlayer p, String command) {
        int[] result = {0};
        p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withCallback((ok, r) -> result[0] = ok ? r : 0), command);
        return result[0];
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

    // títulos y subtítulos en el orden en que llegaron
    private static List<String> titles(List<Packet<?>> packets) {
        List<String> out = new ArrayList<>();
        for (Packet<?> packet : packets) {
            if (packet instanceof ClientboundSetTitleTextPacket t) {
                out.add("T:" + t.text().getString());
            } else if (packet instanceof ClientboundSetSubtitleTextPacket t) {
                out.add("S:" + t.text().getString());
            }
        }
        return out;
    }

    private static void check(List<String> wrong, boolean ok, String what) {
        if (!ok) {
            wrong.add(what);
        }
    }

    private static void done(GameTestHelper helper, List<String> wrong, List<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            p.getAbilities().invulnerable = false;
            Lobbies.leave(p.server, p.getUUID());
            HuddleData.get(p.server).mailbox().take(p.getUUID());
            TestPlayers.leave(helper, p);
        }
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join("; ", wrong));
        }
        helper.succeed();
    }

    // equipos: los cazadores no se pegan entre sí; cazador y marcado sí. El que lo mata se lleva el pozo
    @GameTest(template = "empty", batch = "bounty", timeoutTicks = 200)
    public static void hunterKillTakesThePot(GameTestHelper helper) {
        List<ServerPlayer> ps = players(helper, 5);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(ps, "x 2 diamond", wrong);
        helper.runAfterDelay(40, () -> {
            Game g = game(r, ps);
            check(wrong, g != null, "no arrancó");
            if (g == null) {
                done(helper, wrong, ps);
                return;
            }
            check(wrong, ps.stream().allMatch(p -> diamonds(p) == 3), "no cobró la entrada");
            check(wrong, !hurts(g.h1, g.h2), "un cazador dañó a otro");
            check(wrong, hurts(g.h1, g.marked), "el cazador no pudo dañar al marcado");
            check(wrong, hurts(g.marked, g.h2), "el marcado no pudo defenderse");
            // el jugador de prueba no avanza ticks: su golpe sale con la fuerza mínima
            g.marked.setHealth(0.1f);
            g.marked.invulnerableTime = 0;
            g.h2.attack(g.marked);
            check(wrong, g.marked.isAlive() && Lobbies.of(g.marked) == null, "el marcado murió de verdad o sigue en el evento");
            helper.runAfterDelay(3, () -> {
                check(wrong, !Lobbies.all().contains(r), "no terminó al caer el marcado");
                check(wrong, g.h2.getUUID().equals(g.r.bounty().winner()), "no ganó quien lo mató");
                check(wrong, diamonds(g.h2) == 9 && diamonds(g.h1) == 3 && diamonds(g.marked) == 3,
                        "pozo mal: " + diamonds(g.h2) + " " + diamonds(g.h1) + " " + diamonds(g.marked));
                List<Packet<?>> h1 = TestPlayers.received(g.h1);
                check(wrong, chat(h1, false).stream().anyMatch(m -> m.contains("caught")), "sin aviso de la captura");
                // al terminar: título para cada uno según su papel
                String name = g.h2.getGameProfile().getName(), target = g.marked.getGameProfile().getName();
                check(wrong, titles(h1).contains("T:" + name) && titles(h1).contains("S:caught " + target), "sin título para el otro cazador: " + titles(h1));
                List<String> winner = titles(TestPlayers.received(g.h2));
                check(wrong, winner.contains("T:Target caught!") && TestPlayers.shortTitles(winner), "sin título para quien lo atrapó: " + winner);
                List<String> caught = titles(TestPlayers.received(g.marked));
                check(wrong, caught.contains("T:You were caught") && caught.contains("S:" + name + " caught you"), "sin título para el marcado: " + caught);
                check(wrong, Participants.of(g.h1) == null, "siguen anotados");
                done(helper, wrong, ps);
            });
        });
    }

    // cae en el vacío justo después de un golpe: gana el cazador que le pegó
    @GameTest(template = "empty", batch = "bounty", timeoutTicks = 200)
    public static void recentHitCreditsOtherDeaths(GameTestHelper helper) {
        List<ServerPlayer> ps = players(helper, 0);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(ps, "", wrong);
        helper.runAfterDelay(40, () -> {
            Game g = game(r, ps);
            check(wrong, g != null, "no arrancó");
            if (g != null) {
                check(wrong, hurts(g.h1, g.marked), "el golpe no llegó");
                g.marked.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000);
                check(wrong, g.r.bounty().hunt().outcome(helper.getLevel().getServer().getTickCount()) == BountyHunt.Outcome.CAUGHT
                        && g.h1.getUUID().equals(g.r.bounty().winner()), "no ganó quien le pegó último");
            }
            done(helper, wrong, ps);
        });
    }

    // salir no salva al marcado: gana el cazador más cercano a donde estaba al salir
    @GameTest(template = "empty", batch = "bounty", timeoutTicks = 200)
    public static void markedLeavingHandsItToTheNearest(GameTestHelper helper) {
        List<ServerPlayer> ps = players(helper, 5);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(ps, "x 2 diamond", wrong);
        helper.runAfterDelay(40, () -> {
            Game g = game(r, ps);
            check(wrong, g != null, "no arrancó");
            if (g == null) {
                done(helper, wrong, ps);
                return;
            }
            place(helper, g.marked, 1.5, 1.5);
            place(helper, g.h1, 3.5, 3.5);
            place(helper, g.h2, 2.5, 1.5);
            // el evento ya lo vio junto a h2; en el mismo tick se va junto a h1 y sale: cuenta dónde estaba al salir
            helper.runAfterDelay(5, () -> {
                place(helper, g.marked, 3.5, 4.5);
                check(wrong, run(g.marked, "/event leave") == 1, "leave falló");
                helper.runAfterDelay(3, () -> {
                    check(wrong, !Lobbies.all().contains(r), "siguió sin el marcado");
                    check(wrong, g.h1.getUUID().equals(g.r.bounty().winner()), "no ganó el más cercano");
                    check(wrong, diamonds(g.h1) == 9 && diamonds(g.marked) == 3, "pozo mal: " + diamonds(g.h1) + " " + diamonds(g.marked));
                    done(helper, wrong, ps);
                });
            });
        });
    }

    // lejos no brilla y la barra da la distancia redondeada; quedarse en un chunk lo deja a la vista
    @GameTest(template = "empty", batch = "bounty", timeoutTicks = 400)
    public static void campingGivesTheMarkedAway(GameTestHelper helper) {
        List<ServerPlayer> ps = players(helper, 0);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(ps, "", wrong);
        helper.runAfterDelay(40, () -> {
            Game g = game(r, ps);
            check(wrong, g != null, "no arrancó");
            if (g == null) {
                done(helper, wrong, ps);
                return;
            }
            for (ServerPlayer h : List.of(g.h1, g.h2)) {
                h.getAbilities().invulnerable = true;
            }
            place(helper, g.h1, 2.5, 80.5);
            place(helper, g.h2, 2.5, 82.5);
            helper.runAfterDelay(20, () -> {
                check(wrong, !Glow.remembers(g.h1.getUUID()), "brilla de lejos antes de tiempo");
                check(wrong, chat(g.h1, true).stream().anyMatch(m -> m.matches("Target: about \\d+ blocks to the north")),
                        "sin la distancia en la barra");
                helper.runAfterDelay(200, () -> {
                    check(wrong, Lobbies.all().contains(r), "terminó antes de tiempo");
                    check(wrong, Glow.remembers(g.h1.getUUID()) && Glow.remembers(g.h2.getUUID()), "no brilló tras quedarse quieto");
                    check(wrong, chat(g.h2, false).stream().anyMatch(m -> m.contains("stayed in one place too long")), "sin aviso");
                    Lobbies.end(helper.getLevel().getServer(), r);
                    check(wrong, !Glow.remembers(g.h1.getUUID()) && !Glow.remembers(g.h2.getUUID()), "el brillo siguió después");
                    done(helper, wrong, ps);
                });
            });
        });
    }

    // nadie lo atrapa: el marcado sobrevive y se lleva el pozo
    @GameTest(template = "empty", batch = "bounty", timeoutTicks = 800)
    public static void survivingWinsThePot(GameTestHelper helper) {
        List<ServerPlayer> ps = players(helper, 5);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(ps, "x 2 diamond", wrong);
        Game[] g = {null};
        helper.runAfterDelay(40, () -> g[0] = game(r, ps));
        helper.runAfterDelay(700, () -> {
            check(wrong, g[0] != null, "no arrancó");
            check(wrong, !Lobbies.all().contains(r), "siguió después del tiempo");
            if (g[0] != null) {
                check(wrong, g[0].marked.getUUID().equals(r.bounty().winner()), "no ganó el marcado");
                check(wrong, diamonds(g[0].marked) == 9, "pozo mal: " + diamonds(g[0].marked));
                List<Packet<?>> h1 = TestPlayers.received(g[0].h1);
                check(wrong, chat(h1, false).stream().anyMatch(m -> m.contains("survived")), "sin aviso");
                check(wrong, titles(h1).contains("T:" + g[0].marked.getGameProfile().getName()) && titles(h1).contains("S:survived"), "sin título para los cazadores: " + titles(h1));
                List<String> m = titles(TestPlayers.received(g[0].marked));
                check(wrong, m.contains("T:You survived!"), "sin título para el marcado: " + m);
            }
            done(helper, wrong, ps);
        });
    }

    // decidido: muere en el vacío sin que nadie le haya pegado y sigue en el evento, de vuelta donde se unió y a la vista
    // de los cazadores un rato; atraparlo después sigue valiendo
    @GameTest(template = "empty", batch = "bounty", timeoutTicks = 200)
    public static void lonelyDeathExposesTheMarked(GameTestHelper helper) {
        List<ServerPlayer> ps = players(helper, 5);
        HuddleConfig.BOUNTY_DEATH_EXPOSE_SECONDS.set(30);
        // donde se unen tiene que ser seguro para volver: dentro del pozo hay barreras, se usa la superficie de al lado
        for (int i = 0; i < ps.size(); i++) {
            Vec3 at = helper.absoluteVec(new Vec3(1.5 + i, 0, -6.5));
            int y = helper.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(at.x), (int) Math.floor(at.z));
            ps.get(i).teleportTo(helper.getLevel(), at.x, y, at.z, 0, 0);
        }
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(ps, "2 diamond", wrong);
        helper.runAfterDelay(40, () -> {
            Game g = game(r, ps);
            check(wrong, g != null, "no arrancó");
            if (g == null) {
                done(helper, wrong, ps);
                return;
            }
            Vec3 home = g.marked.position();
            place(helper, g.marked, 3.5, 3.5);
            for (ServerPlayer h : List.of(g.h1, g.h2)) {
                place(helper, h, 2.5, 80.5);
            }
            helper.runAfterDelay(5, () -> {
                g.marked.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000);
                check(wrong, Lobbies.all().contains(r) && Lobbies.of(g.marked) == r, "el marcado quedó fuera");
                check(wrong, g.marked.isAlive() && g.marked.position().distanceTo(home) < 1, "no volvió a donde se unió: " + g.marked.position());
                helper.runAfterDelay(3, () -> {
                    check(wrong, Glow.remembers(g.h1.getUUID()) && Glow.remembers(g.h2.getUUID()), "no brilla para los cazadores lejos");
                    // received vacía la lista: se lee una sola vez
                    List<String> said = chat(g.h1, false);
                    check(wrong, said.stream().anyMatch(m -> m.contains("died")), "sin aviso a los cazadores");
                    check(wrong, said.stream().noneMatch(m -> m.contains("stayed in one place")), "avisó que se quedó quieto");
                    place(helper, g.h1, 2.5, 2.5);
                    g.marked.invulnerableTime = 0;
                    g.marked.hurt(helper.getLevel().damageSources().playerAttack(g.h1), 1000);
                    helper.runAfterDelay(3, () -> {
                        check(wrong, !Lobbies.all().contains(r) && g.h1.getUUID().equals(r.bounty().winner()), "atraparlo después no valió");
                        check(wrong, diamonds(g.h1) == 9, "pozo mal: " + diamonds(g.h1));
                        done(helper, wrong, ps);
                    });
                });
            });
        });
    }

    // con keepInventoryOnDeath apagado muere de verdad y sale: sin nadie que le haya pegado, no gana nadie y vuelve cada entrada
    @GameTest(template = "empty", batch = "bounty_hardcore", timeoutTicks = 200)
    public static void hardcoreLonelyDeathHasNoWinner(GameTestHelper helper) {
        List<ServerPlayer> ps = players(helper, 5);
        HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(false);
        List<String> wrong = new ArrayList<>();
        Lobbies.Running r = open(ps, "2 diamond", wrong);
        helper.runAfterDelay(40, () -> {
            Game g = game(r, ps);
            check(wrong, g != null, "no arrancó");
            if (g == null) {
                HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(true);
                done(helper, wrong, ps);
                return;
            }
            // lo que suelta al morir no cuenta: se mira el pozo de los cazadores
            g.marked.getInventory().clearContent();
            g.marked.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000);
            helper.runAfterDelay(3, () -> {
                check(wrong, !Lobbies.all().contains(r), "siguió sin el marcado");
                check(wrong, g.r.bounty().winner() == null, "ganó alguien sin atraparlo: " + g.r.bounty().winner());
                check(wrong, diamonds(g.h1) == 5 && diamonds(g.h2) == 5, "no devolvió las entradas");
                HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(true);
                done(helper, wrong, ps);
            });
        });
    }
}
