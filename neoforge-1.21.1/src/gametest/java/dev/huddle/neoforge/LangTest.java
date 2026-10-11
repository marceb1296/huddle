package dev.huddle.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// lo que ve un cliente sin el mod es el texto de respaldo: tiene que llegar en el idioma de cada jugador
@GameTestHolder(Huddle.MODID)
@PrefixGameTestTemplate(false)
public final class LangTest {
    private static int seq;

    private static ServerPlayer player(GameTestHelper helper, String name, String language) {
        ServerPlayer p = TestPlayers.join(helper, name + (++seq));
        ClientInformation d = ClientInformation.createDefault();
        p.updateOptions(new ClientInformation(language, d.viewDistance(), d.chatVisibility(), d.chatColors(), d.modelCustomisation(),
                d.mainHand(), d.textFilteringEnabled(), d.allowsListing()));
        Vec3 at = helper.absoluteVec(new Vec3(1, 1, 1));
        p.teleportTo(helper.getLevel(), at.x, at.y, at.z, 0, 0);
        TestPlayers.received(p);
        return p;
    }

    private static String fallback(Component c) {
        return c != null && c.getContents() instanceof TranslatableContents tc ? tc.getFallback() : null;
    }

    private static Object arg(Component c, int i) {
        return ((TranslatableContents) c.getContents()).getArgs()[i];
    }

    private static List<Component> chat(ServerPlayer p) {
        List<Component> out = new ArrayList<>();
        for (Packet<?> packet : TestPlayers.received(p)) {
            if (packet instanceof ClientboundSystemChatPacket chat) {
                out.add(chat.content());
            }
        }
        return out;
    }

    private static Component chatWith(ServerPlayer p, String key) {
        for (Component c : chat(p)) {
            Component found = find(c, key);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // los errores de comando llegan envueltos en un componente vacío
    private static Component find(Component c, String key) {
        if (c.getContents() instanceof TranslatableContents tc && tc.getKey().equals(key)) {
            return c;
        }
        for (Component s : c.getSiblings()) {
            Component found = find(s, key);
            if (found != null) {
                return found;
            }
        }
        return null;
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

    // el mismo anuncio va a todos: el de habla hispana lo ve en español, los demás en inglés, y el botón sigue funcionando
    @GameTest(template = "empty", batch = "lang")
    public static void announcementInEachPlayersLanguage(GameTestHelper helper) {
        HuddleConfig.ANNOUNCE_RADIUS.set(64);
        HuddleConfig.TYPES.get("race").enabled.set(true);
        ServerPlayer es = player(helper, "es", "es_ar");
        ServerPlayer en = player(helper, "en", "en_us");
        ServerPlayer fr = player(helper, "fr", "fr_fr");
        List<String> wrong = new ArrayList<>();
        Lobbies.start(es, EventTypes.byId("race"), null, null);
        Component esMsg = chatWith(es, "huddle.lobby.announce");
        Component enMsg = chatWith(en, "huddle.lobby.announce");
        Component frMsg = chatWith(fr, "huddle.lobby.announce");
        check(wrong, "%s inició el evento %s. %s".equals(fallback(esMsg)), "es: " + fallback(esMsg));
        check(wrong, esMsg != null && "Carrera".equals(fallback((Component) arg(esMsg, 1))), "es: tipo sin traducir");
        Component button = esMsg == null ? null : (Component) arg(esMsg, 2);
        check(wrong, "[Unirse]".equals(fallback(button)), "es: botón " + fallback(button));
        ClickEvent click = button == null ? null : button.getStyle().getClickEvent();
        check(wrong, click != null && click.getValue().startsWith("/event join "), "es: el botón perdió el clic");
        check(wrong, "%s started a %s. %s".equals(fallback(enMsg)), "en: " + fallback(enMsg));
        check(wrong, enMsg != null && "[Join]".equals(fallback((Component) arg(enMsg, 2))), "en: el botón quedó en español");
        check(wrong, "%s started a %s. %s".equals(fallback(frMsg)), "fr sin archivo: " + fallback(frMsg));
        done(helper, wrong, es, en, fr);
    }

    // la barra de jefe se manda por operaciones privadas: el nombre llega traducido y el resto igual
    @GameTest(template = "empty", batch = "lang")
    public static void bossBarInSpanish(GameTestHelper helper) {
        ServerPlayer es = player(helper, "eb", "es_mx");
        List<String> wrong = new ArrayList<>();
        BossEvent bar = new BossEvent(UUID.randomUUID(), Component.translatableWithFallback("huddle.bar.running", "%s in progress",
                Lobbies.name(EventTypes.byId("bounty"))), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_10) {
        };
        bar.setProgress(0.25f);
        Component[] name = new Component[1];
        float[] progress = new float[1];
        BossEvent.BossBarColor[] color = new BossEvent.BossBarColor[1];
        Packet<?> out = Lang.forViewer(es, ClientboundBossEventPacket.createAddPacket(bar));
        ((ClientboundBossEventPacket) out).dispatch(new ClientboundBossEventPacket.Handler() {
            @Override
            public void add(UUID id, Component n, float p, BossEvent.BossBarColor c, BossEvent.BossBarOverlay o, boolean d, boolean m, boolean f) {
                name[0] = n;
                progress[0] = p;
                color[0] = c;
            }
        });
        check(wrong, "%s en curso".equals(fallback(name[0])), "barra: " + fallback(name[0]));
        check(wrong, name[0] != null && "Recompensa".equals(fallback((Component) arg(name[0], 0))), "barra: tipo sin traducir");
        check(wrong, progress[0] == 0.25f && color[0] == BossEvent.BossBarColor.GREEN, "barra: perdió progreso o color");
        Packet<?> rename = Lang.forViewer(es, ClientboundBossEventPacket.createUpdateNamePacket(bar));
        ((ClientboundBossEventPacket) rename).dispatch(new ClientboundBossEventPacket.Handler() {
            @Override
            public void updateName(UUID id, Component n) {
                name[0] = n;
            }
        });
        check(wrong, "%s en curso".equals(fallback(name[0])), "cambio de nombre: " + fallback(name[0]));
        done(helper, wrong, es);
    }

    @GameTest(template = "empty", batch = "lang")
    public static void titlesBundlesAndCommandErrors(GameTestHelper helper) {
        ServerPlayer es = player(helper, "et", "es_es");
        List<String> wrong = new ArrayList<>();
        Packet<?> title = Lang.forViewer(es, new ClientboundSetTitleTextPacket(Component.translatableWithFallback("huddle.event.go", "Go!")));
        check(wrong, "¡Ya!".equals(fallback(((ClientboundSetTitleTextPacket) title).text())), "título sin traducir");
        Packet<?> bundle = Lang.forViewer(es, new ClientboundBundlePacket(List.of(
                new ClientboundSetTitleTextPacket(Component.translatableWithFallback("huddle.event.go", "Go!")))));
        Packet<?> inside = ((ClientboundBundlePacket) bundle).subPackets().iterator().next();
        check(wrong, "¡Ya!".equals(fallback(((ClientboundSetTitleTextPacket) inside).text())), "título agrupado sin traducir");
        Component hovered = Component.literal("x").withStyle(st -> st.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                Component.translatableWithFallback("huddle.event.go", "Go!"))));
        Packet<?> withHover = Lang.forViewer(es, new ClientboundSystemChatPacket(hovered, false));
        HoverEvent hover = ((ClientboundSystemChatPacket) withHover).content().getStyle().getHoverEvent();
        check(wrong, hover != null && "¡Ya!".equals(fallback(hover.getValue(HoverEvent.Action.SHOW_TEXT))), "texto del hover sin traducir");
        // los textos que no son de Huddle salen intactos, sin crear paquetes nuevos
        Packet<?> vanilla = new ClientboundSystemChatPacket(Component.translatable("chat.type.text", "a", "b"), false);
        check(wrong, Lang.forViewer(es, vanilla) == vanilla, "tocó un texto vanilla");
        es.server.getCommands().performPrefixedCommand(es.createCommandSourceStack(), "/evento salir");
        Component err = chatWith(es, "huddle.cmd.not_in_event");
        check(wrong, "No estás en ningún evento.".equals(fallback(err)), "error de comando: " + fallback(err));
        done(helper, wrong, es);
    }
}
