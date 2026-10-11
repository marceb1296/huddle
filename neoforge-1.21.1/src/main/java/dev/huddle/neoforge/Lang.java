package dev.huddle.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

// el cliente sin el mod no tiene las claves de Huddle y muestra el texto de respaldo: al salir cada paquete,
// el respaldo se cambia por la traducción al idioma de ese jugador; la clave y los argumentos quedan igual
public final class Lang {
    private static final String PREFIX = "huddle.";
    // idiomas con archivo en assets/huddle/lang; las demás variantes de un idioma usan la primera de su familia
    static final List<String> FILES = List.of("es_mx");
    private static Map<String, Map<String, String>> tables;

    private Lang() {
    }

    // null: inglés o idioma sin traducción, se queda el respaldo del código
    public static Map<String, String> table(String language) {
        if (language == null) {
            return null;
        }
        Map<String, Map<String, String>> all = tables();
        String code = language.toLowerCase(Locale.ROOT);
        Map<String, String> exact = all.get(code);
        if (exact != null) {
            return exact;
        }
        int cut = code.indexOf('_');
        String family = (cut < 0 ? code : code.substring(0, cut)) + "_";
        for (String file : FILES) {
            if (file.startsWith(family)) {
                return all.get(file);
            }
        }
        return null;
    }

    private static synchronized Map<String, Map<String, String>> tables() {
        if (tables == null) {
            Map<String, Map<String, String>> loaded = new HashMap<>();
            for (String file : FILES) {
                loaded.put(file, load(file));
            }
            tables = loaded;
        }
        return tables;
    }

    private static Map<String, String> load(String file) {
        Map<String, String> out = new HashMap<>();
        try (InputStream in = Lang.class.getResourceAsStream("/assets/huddle/lang/" + file + ".json")) {
            if (in == null) {
                Huddle.LOGGER.error("Missing language file {}", file);
                return out;
            }
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            json.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
        } catch (Exception e) {
            Huddle.LOGGER.error("Could not read language file {}", file, e);
        }
        return out;
    }

    public static Component localize(Component c, Map<String, String> table) {
        if (table == null || c == null) {
            return c;
        }
        Component out = rewrite(c, table);
        return out == null ? c : out;
    }

    // null si no cambió nada: la mayoría de los paquetes no llevan textos de Huddle y salen tal cual
    private static Component rewrite(Component c, Map<String, String> table) {
        boolean changed = false;
        Object contents = c.getContents();
        MutableComponent base;
        if (contents instanceof TranslatableContents tc) {
            Object[] args = tc.getArgs();
            Object[] newArgs = args;
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Component arg) {
                    Component r = rewrite(arg, table);
                    if (r != null) {
                        if (newArgs == args) {
                            newArgs = args.clone();
                        }
                        newArgs[i] = r;
                    }
                }
            }
            String text = tc.getKey().startsWith(PREFIX) ? table.get(tc.getKey()) : null;
            if (text != null || newArgs != args) {
                changed = true;
                base = MutableComponent.create(new TranslatableContents(tc.getKey(), text != null ? text : tc.getFallback(), newArgs));
            } else {
                base = MutableComponent.create(c.getContents());
            }
        } else {
            base = MutableComponent.create(c.getContents());
        }
        Style style = c.getStyle();
        HoverEvent hover = style.getHoverEvent();
        if (hover != null && hover.getAction() == HoverEvent.Action.SHOW_TEXT) {
            Component r = rewrite(hover.getValue(HoverEvent.Action.SHOW_TEXT), table);
            if (r != null) {
                changed = true;
                style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, r));
            }
        }
        List<Component> siblings = new ArrayList<>(c.getSiblings().size());
        for (Component s : c.getSiblings()) {
            Component r = rewrite(s, table);
            changed |= r != null;
            siblings.add(r != null ? r : s);
        }
        if (!changed) {
            return null;
        }
        base.setStyle(style);
        siblings.forEach(base::append);
        return base;
    }

    public static Packet<?> forViewer(ServerPlayer viewer, Packet<?> packet) {
        if (!(packet instanceof ClientboundSystemChatPacket || packet instanceof ClientboundSetTitleTextPacket
                || packet instanceof ClientboundSetSubtitleTextPacket || packet instanceof ClientboundSetActionBarTextPacket
                || packet instanceof ClientboundBossEventPacket || packet instanceof ClientboundBundlePacket)) {
            return packet;
        }
        Map<String, String> table = table(viewer.clientInformation().language());
        return table == null ? packet : rewrite(packet, table);
    }

    private static Packet<?> rewrite(Packet<?> packet, Map<String, String> table) {
        if (packet instanceof ClientboundSystemChatPacket p) {
            Component r = rewrite(p.content(), table);
            return r == null ? packet : new ClientboundSystemChatPacket(r, p.overlay());
        }
        if (packet instanceof ClientboundSetTitleTextPacket p) {
            Component r = rewrite(p.text(), table);
            return r == null ? packet : new ClientboundSetTitleTextPacket(r);
        }
        if (packet instanceof ClientboundSetSubtitleTextPacket p) {
            Component r = rewrite(p.text(), table);
            return r == null ? packet : new ClientboundSetSubtitleTextPacket(r);
        }
        if (packet instanceof ClientboundSetActionBarTextPacket p) {
            Component r = rewrite(p.text(), table);
            return r == null ? packet : new ClientboundSetActionBarTextPacket(r);
        }
        if (packet instanceof ClientboundBossEventPacket p) {
            return bossBar(p, table);
        }
        if (packet instanceof ClientboundBundlePacket p) {
            List<Packet<? super ClientGamePacketListener>> parts = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientGamePacketListener> sub : p.subPackets()) {
                @SuppressWarnings("unchecked")
                Packet<? super ClientGamePacketListener> r =
                        (Packet<? super ClientGamePacketListener>) rewrite(sub, table);
                changed |= r != sub;
                parts.add(r);
            }
            return changed ? new ClientboundBundlePacket(parts) : packet;
        }
        return packet;
    }

    // las operaciones de la barra son privadas: se leen con dispatch y se arma un paquete nuevo igual
    private static Packet<?> bossBar(ClientboundBossEventPacket packet, Map<String, String> table) {
        Packet<?>[] out = {packet};
        packet.dispatch(new ClientboundBossEventPacket.Handler() {
            @Override
            public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay,
                    boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
                Component r = rewrite(name, table);
                if (r != null) {
                    BossEvent copy = new BossEvent(id, r, color, overlay) {
                    };
                    copy.setProgress(progress);
                    copy.setDarkenScreen(darkenScreen);
                    copy.setPlayBossMusic(playMusic);
                    copy.setCreateWorldFog(createWorldFog);
                    out[0] = ClientboundBossEventPacket.createAddPacket(copy);
                }
            }

            @Override
            public void updateName(UUID id, Component name) {
                Component r = rewrite(name, table);
                if (r != null) {
                    out[0] = ClientboundBossEventPacket.createUpdateNamePacket(new BossEvent(id, r, BossEvent.BossBarColor.WHITE,
                            BossEvent.BossBarOverlay.PROGRESS) {
                    });
                }
            }
        });
        return out[0];
    }
}
