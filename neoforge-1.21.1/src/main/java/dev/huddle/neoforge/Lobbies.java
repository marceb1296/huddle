package dev.huddle.neoforge;

import dev.huddle.core.BountyHunt;
import dev.huddle.core.Cooldowns;
import dev.huddle.core.EventTag;
import dev.huddle.core.JoinCheck;
import dev.huddle.core.Lobby;
import dev.huddle.core.RaceState;
import dev.huddle.core.TreasureFinder;
import dev.huddle.core.TreasureHunt;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// salas abiertas y eventos en curso; solo se usa desde el hilo del servidor
public final class Lobbies {
    public enum Phase { LOBBY, RUNNING }

    public static final class Running {
        final Lobby lobby;
        final EventTypes.Type type;
        final String creatorName;
        // va en el "[Join]": un anuncio viejo no lleva a la sala que el mismo creador abra después
        final String code;
        final ResourceKey<Level> dimension;
        final Vec3 origin;
        final ServerBossEvent bar;
        final Set<UUID> participants = new LinkedHashSet<>();
        final Map<UUID, Long> offlineSince = new HashMap<>();
        final Tracks.Stored track;
        final BuyIns.Price price;
        Phase phase = Phase.LOBBY;
        EventTag tag;
        RaceGame race;
        TreasureGame treasure;
        BountyGame bounty;
        AirdropGame airdrop;
        LastManGame lastman;
        Lobby.State lastState = Lobby.State.OPEN;
        long lastSecond = -1;

        Running(Lobby lobby, EventTypes.Type type, ServerPlayer creator, Tracks.Stored track, BuyIns.Price price) {
            this.lobby = lobby;
            this.track = track;
            this.price = price;
            this.type = type;
            this.creatorName = creator.getGameProfile().getName();
            this.code = lobby.id().toString().substring(0, 8);
            this.dimension = creator.level().dimension();
            this.origin = creator.position();
            this.bar = new ServerBossEvent(name(type), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
        }

        public Phase phase() {
            return phase;
        }

        public Lobby lobby() {
            return lobby;
        }

        public EventTypes.Type type() {
            return type;
        }

        public String creatorName() {
            return creatorName;
        }

        public Set<UUID> participants() {
            return Set.copyOf(participants);
        }

        public ServerBossEvent bar() {
            return bar;
        }

        public EventTag tag() {
            return tag;
        }

        public Tracks.Stored track() {
            return track;
        }

        public BuyIns.Price price() {
            return price;
        }

        RaceGame race() {
            return race;
        }

        TreasureGame treasure() {
            return treasure;
        }

        BountyGame bounty() {
            return bounty;
        }

        AirdropGame airdrop() {
            return airdrop;
        }

        LastManGame lastman() {
            return lastman;
        }

        // en la sala, los de la sala; en curso, los participantes
        List<UUID> members() {
            return phase == Phase.LOBBY ? lobby.players() : List.copyOf(participants);
        }
    }

    public enum StartResult { STARTED, DISABLED, BUSY, RECORDING, WRONG_DIMENSION, CANT_PAY, ON_COOLDOWN, TOO_MUCH_ARMOR, ENCHANT_TOO_HIGH, NOT_EMPTY }

    public enum JoinResult { JOINED, NO_SUCH_EVENT, BUSY, RECORDING, CANT_PAY, TOO_FAR, ON_COOLDOWN, TOO_MUCH_ARMOR, ENCHANT_TOO_HIGH, FULL, CLOSED, NOT_EMPTY }

    private static final Map<UUID, Running> EVENTS = new LinkedHashMap<>();
    private static final Map<UUID, Running> BY_PLAYER = new HashMap<>();

    private Lobbies() {
    }

    public static Running of(ServerPlayer player) {
        return BY_PLAYER.get(player.getUUID());
    }

    // participante de un último en pie en curso: no suelta ni guarda el kit, no junta ítems ni toca bloques
    public static boolean inLastMan(UUID player) {
        Running r = BY_PLAYER.get(player);
        return r != null && r.lastman != null && r.phase == Phase.RUNNING && r.participants.contains(player);
    }

    public static Collection<Running> all() {
        return List.copyOf(EVENTS.values());
    }

    // por nombre del creador; code, si viene del "[Unirse]", tiene que ser el de esa sala
    public static Running byCreator(String name, String code) {
        for (Running r : EVENTS.values()) {
            if (r.creatorName.equalsIgnoreCase(name)) {
                return code == null || r.code.equals(code) ? r : null;
            }
        }
        return null;
    }

    public static StartResult start(ServerPlayer creator, EventTypes.Type type, Tracks.Stored track, BuyIns.Price price) {
        return start(creator, type, track, null, price);
    }

    // track: la pista de una carrera, null en los demás eventos; preset: el del tesoro (null es el clásico);
    // price: la compra de entrada, null sin entrada
    public static StartResult start(ServerPlayer creator, EventTypes.Type type, Tracks.Stored track, TreasureFinder.Preset preset,
                                    BuyIns.Price price) {
        HuddleConfig.TypeValues cfg = HuddleConfig.TYPES.get(type.id());
        if (!cfg.enabled.get()) {
            return StartResult.DISABLED;
        }
        // un evento propio todavía vivo, aunque ya haya salido: el "[Unirse]" va por el nombre del creador
        if (BY_PLAYER.containsKey(creator.getUUID()) || EVENTS.values().stream().anyMatch(r -> r.lobby.creator().equals(creator.getUUID()))) {
            return StartResult.BUSY;
        }
        if (Tracks.recording(creator.getUUID())) {
            return StartResult.RECORDING;
        }
        if (track != null && !track.dimension().equals(creator.level().dimension())) {
            return StartResult.WRONG_DIMENSION;
        }
        if (price != null && !BuyIns.canPay(creator, price)) {
            return StartResult.CANT_PAY;
        }
        if (type.id().equals("lastman") && !Kits.ready(creator, price)) {
            return StartResult.NOT_EMPTY;
        }
        switch (check(creator, type)) {
            case ON_COOLDOWN -> {
                return StartResult.ON_COOLDOWN;
            }
            case TOO_MUCH_ARMOR -> {
                return StartResult.TOO_MUCH_ARMOR;
            }
            case ENCHANT_TOO_HIGH -> {
                return StartResult.ENCHANT_TOO_HIGH;
            }
            default -> {
            }
        }
        long now = creator.server.getTickCount();
        Running r = new Running(new Lobby(type.id(), creator.getUUID(), cfg.rules(), now), type, creator, track, price);
        if (type.id().equals("treasure")) {
            r.treasure = TreasureGame.prepare(creator, preset == null ? TreasureFinder.Preset.CLASSIC : preset);
        }
        if (type.id().equals("airdrop")) {
            r.airdrop = AirdropGame.prepare(creator, preset == null ? TreasureFinder.Preset.CLASSIC : preset);
        }
        if (type.id().equals("lastman")) {
            r.lastman = LastManGame.prepare(creator);
        }
        EVENTS.put(r.lobby.id(), r);
        BY_PLAYER.put(creator.getUUID(), r);
        r.bar.addPlayer(creator);
        announce(creator, r);
        updateBar(creator.server, r, now);
        return StartResult.STARTED;
    }

    public static JoinResult join(ServerPlayer player, Running r) {
        if (r == null || !EVENTS.containsKey(r.lobby.id())) {
            return JoinResult.NO_SUCH_EVENT;
        }
        if (BY_PLAYER.containsKey(player.getUUID())) {
            return JoinResult.BUSY;
        }
        if (Tracks.recording(player.getUUID())) {
            return JoinResult.RECORDING;
        }
        if (r.price != null && !BuyIns.canPay(player, r.price)) {
            return JoinResult.CANT_PAY;
        }
        if (r.phase != Phase.LOBBY) {
            return JoinResult.CLOSED;
        }
        if (r.lastman != null && !Kits.ready(player, r.price)) {
            return JoinResult.NOT_EMPTY;
        }
        if (!inRange(player, r, HuddleConfig.ANNOUNCE_RADIUS.get())) {
            return JoinResult.TOO_FAR;
        }
        switch (check(player, r.type)) {
            case ON_COOLDOWN -> {
                return JoinResult.ON_COOLDOWN;
            }
            case TOO_MUCH_ARMOR -> {
                return JoinResult.TOO_MUCH_ARMOR;
            }
            case ENCHANT_TOO_HIGH -> {
                return JoinResult.ENCHANT_TOO_HIGH;
            }
            default -> {
            }
        }
        long now = player.server.getTickCount();
        switch (r.lobby.join(player.getUUID(), now)) {
            case FULL -> {
                return JoinResult.FULL;
            }
            case CLOSED, ALREADY_IN -> {
                return JoinResult.CLOSED;
            }
            default -> {
            }
        }
        BY_PLAYER.put(player.getUUID(), r);
        r.bar.addPlayer(player);
        tell(r, player.server, Component.translatableWithFallback("huddle.lobby.joined", "%s joined (%s/%s).",
                player.getDisplayName(), r.lobby.players().size(), HuddleConfig.TYPES.get(r.type.id()).maxPlayers.get()));
        updateBar(player.server, r, now);
        return JoinResult.JOINED;
    }

    public static Lobby.VoteResult vote(ServerPlayer player, String mode) {
        Running r = BY_PLAYER.get(player.getUUID());
        if (r == null) {
            return Lobby.VoteResult.NOT_IN_LOBBY;
        }
        if (r.phase != Phase.LOBBY) {
            return Lobby.VoteResult.NOT_VOTING;
        }
        return r.lobby.vote(player.getUUID(), mode, player.server.getTickCount());
    }

    // devuelve false si no estaba en ningún evento
    public static boolean leave(MinecraftServer server, UUID player) {
        Running r = BY_PLAYER.remove(player);
        if (r == null) {
            Participants.leave(player);
            return false;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            r.bar.removePlayer(online);
        }
        r.offlineSince.remove(player);
        if (r.phase == Phase.LOBBY) {
            r.lobby.leave(player, server.getTickCount());
            afterLobbyChange(server, r);
        } else {
            r.participants.remove(player);
            Participants.leave(player);
            // quien ya llegó a la meta y después sale no pierde su entrada
            boolean finished = r.race != null && r.race.state() != null && r.race.state().status(player) == RaceState.Status.FINISHED;
            if (r.price != null && !finished) {
                BuyIns.forfeit(server, r.lobby.id(), player);
            }
            if (r.race != null) {
                r.race.onLeave(server, player);
            }
            if (r.treasure != null) {
                r.treasure.onLeave(server, player);
            }
            if (r.bounty != null) {
                r.bounty.onLeave(server, player, server.getTickCount());
            }
            if (r.airdrop != null) {
                r.airdrop.onLeave(server, player);
            }
            if (r.lastman != null) {
                r.lastman.onLeave(server, player, r);
            }
            if (r.participants.isEmpty()) {
                finish(server, r, Component.translatableWithFallback("huddle.event.everyone_left", "Everyone left the event."));
            }
        }
        return true;
    }

    // el creador corta la sala antes de empezar o el margen de la carrera tras la primera llegada; en pleno evento no,
    // porque iba perdiendo y sin ganador recuperaba su entrada
    public static boolean creatorMayEnd(Running r) {
        return r.phase == Phase.LOBBY || r.race != null && r.race.inFinishWindow();
    }

    public static void end(MinecraftServer server, Running r) {
        if (r.phase == Phase.LOBBY) {
            r.lobby.cancelByCreator();
            afterLobbyChange(server, r);
        } else if (r.race != null && r.race.state() != null && !r.race.state().results().isEmpty()) {
            // alguien ya llegó: cierra como una carrera normal, con resultados
            finish(server, r, r.race.summary());
        } else {
            finish(server, r, Component.translatableWithFallback("huddle.event.ended", "The event was ended."));
        }
    }

    public static void onLogout(ServerPlayer player) {
        Running r = BY_PLAYER.get(player.getUUID());
        if (r == null) {
            return;
        }
        if (r.phase == Phase.LOBBY) {
            leave(player.server, player.getUUID());
        } else {
            r.bar.removePlayer(player);
            r.offlineSince.put(player.getUUID(), (long) player.server.getTickCount());
        }
    }

    public static void onLogin(ServerPlayer player) {
        Running r = BY_PLAYER.get(player.getUUID());
        if (r != null && r.offlineSince.remove(player.getUUID()) != null) {
            r.bar.addPlayer(player);
        }
    }

    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        for (Running r : List.copyOf(EVENTS.values())) {
            if (!EVENTS.containsKey(r.lobby.id())) {
                continue;
            }
            if (r.phase == Phase.LOBBY) {
                for (UUID id : r.lobby.players()) {
                    if (server.getPlayerList().getPlayer(id) == null) {
                        leave(server, id);
                    }
                }
                if (!EVENTS.containsKey(r.lobby.id())) {
                    continue;
                }
                if (r.airdrop != null) {
                    r.airdrop.search();
                    Component failed = r.airdrop.failure();
                    if (failed != null) {
                        finish(server, r, failed.copy().withStyle(ChatFormatting.RED));
                        continue;
                    }
                }
                if (r.lastman != null) {
                    r.lastman.search();
                    Component failed = r.lastman.failure();
                    if (failed != null) {
                        finish(server, r, failed.copy().withStyle(ChatFormatting.RED));
                        continue;
                    }
                }
                if (r.treasure != null) {
                    r.treasure.search();
                    Component failed = r.treasure.failure();
                    if (failed != null) {
                        finish(server, r, failed.copy().withStyle(ChatFormatting.RED));
                        continue;
                    }
                }
                r.lobby.tick(now);
                afterLobbyChange(server, r);
                if (r.phase == Phase.LOBBY && EVENTS.containsKey(r.lobby.id())) {
                    updateBar(server, r, now);
                }
            } else {
                watchParticipants(server, r, now);
                if (r.race != null && EVENTS.containsKey(r.lobby.id()) && r.race.tick(server, r, now)) {
                    finish(server, r, r.race.summary());
                }
                if (r.treasure != null && EVENTS.containsKey(r.lobby.id()) && r.treasure.tick(server, r, now)) {
                    finish(server, r, r.treasure.summary(server));
                }
                if (r.bounty != null && EVENTS.containsKey(r.lobby.id()) && r.bounty.tick(server, r, now)) {
                    finish(server, r, r.bounty.summary(server));
                }
                if (r.airdrop != null && EVENTS.containsKey(r.lobby.id()) && r.airdrop.tick(server, r, now)) {
                    finish(server, r, r.airdrop.summary(server));
                }
                if (r.lastman != null && EVENTS.containsKey(r.lobby.id()) && r.lastman.tick(server, r, now)) {
                    finish(server, r, r.lastman.summary(server));
                }
            }
        }
    }

    public static void clear(MinecraftServer server) {
        for (Running r : List.copyOf(EVENTS.values())) {
            finish(server, r, null);
        }
    }

    private static void afterLobbyChange(MinecraftServer server, Running r) {
        Lobby.State s = r.lobby.state();
        Lobby.State before = r.lastState;
        r.lastState = s;
        if (s == Lobby.State.COUNTDOWN && before == Lobby.State.OPEN) {
            countdownStarted(server, r);
        }
        if (s == Lobby.State.STARTED) {
            begin(server, r);
        } else if (s == Lobby.State.CANCELLED) {
            Component why = switch (r.lobby.cancelReason()) {
                case NOT_ENOUGH_PLAYERS -> Component.translatableWithFallback("huddle.lobby.cancelled.players",
                        "The %s was cancelled: not enough players.", name(r.type));
                case BY_CREATOR -> Component.translatableWithFallback("huddle.lobby.cancelled.creator",
                        "The %s was cancelled by its creator.", name(r.type));
                case EVERYONE_LEFT -> null;
            };
            finish(server, r, why);
        }
    }

    private static void countdownStarted(MinecraftServer server, Running r) {
        List<String> modes = HuddleConfig.TYPES.get(r.type.id()).rules().combatModes();
        if (!r.type.pvp() || modes.size() < 2) {
            return;
        }
        MutableComponent line = Component.translatableWithFallback("huddle.lobby.vote", "Vote the combat mode:");
        for (String mode : modes) {
            line.append(" ").append(Component.literal("[").append(EventTypes.modeName(mode)).append("]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/event vote " + mode))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/event vote " + mode)))));
        }
        tell(r, server, line);
    }

    // el equipo se vuelve a medir: pudo ponerse más armadura después de unirse
    private static void begin(MinecraftServer server, Running r) {
        long nowMs = System.currentTimeMillis();
        for (UUID id : r.lobby.players()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            JoinCheck.Verdict v = p == null ? JoinCheck.Verdict.OK : JoinCheck.check(HuddleConfig.TYPES.get(r.type.id()).limits(), 0,
                    p.getArmorValue(), highestEnchant(p));
            boolean cantPay = p != null && v == JoinCheck.Verdict.OK && r.price != null && !BuyIns.canPay(p, r.price);
            // el kit se entrega con el inventario vacío: pudo juntar cosas después de unirse
            boolean notEmpty = p != null && v == JoinCheck.Verdict.OK && !cantPay && r.lastman != null && !Kits.ready(p, r.price);
            // muerto en la sala y sin reaparecer todavía: se lo llevaría a la largada y su casa sería donde murió
            boolean dead = p != null && p.isDeadOrDying();
            if (p == null || dead || v != JoinCheck.Verdict.OK || cantPay || notEmpty) {
                BY_PLAYER.remove(id);
                if (p != null) {
                    r.bar.removePlayer(p);
                    p.sendSystemMessage((dead
                            ? Component.translatableWithFallback("huddle.event.removed_dead",
                                    "You were left out of the event: you were dead when it started.")
                            : notEmpty
                            ? Component.translatableWithFallback("huddle.event.removed_not_empty",
                                    "You were left out of the event: your inventory was not empty.")
                            : cantPay
                            ? Component.translatableWithFallback("huddle.event.removed_entry",
                                    "You were left out of the event: you no longer have the entry (%s %s).", r.price.count(), r.price.name())
                            : Component.translatableWithFallback("huddle.event.removed_gear",
                                    "You were left out of the event: your gear is over the limit.")).copy().withStyle(ChatFormatting.RED));
                }
                continue;
            }
            r.participants.add(id);
        }
        int min = HuddleConfig.TYPES.get(r.type.id()).rules().minPlayers();
        boolean noSpot = r.treasure != null && !r.treasure.ready() || r.airdrop != null && !r.airdrop.ready() || r.lastman != null && !r.lastman.ready();
        if (r.participants.size() < min || noSpot) {
            r.participants.forEach(BY_PLAYER::remove);
            r.participants.clear();
            finish(server, r, noSpot
                    ? Component.translatableWithFallback("huddle.event.not_ready",
                            "The %s was cancelled: its place was not ready in time.", name(r.type))
                    : Component.translatableWithFallback("huddle.lobby.cancelled.players",
                            "The %s was cancelled: not enough players.", name(r.type)));
            return;
        }
        r.phase = Phase.RUNNING;
        // el último en pie empieza con tregua: nadie se pega mientras se acomodan tras el reparto
        long pvpFrom = r.lastman != null ? server.getTickCount() + HuddleConfig.LASTMAN_TRUCE_SECONDS.get() * 20L : 0;
        r.tag = new EventTag(r.lobby.id(), r.type.pvp(), 0, r.lobby.combatMode(), pvpFrom);
        if (r.price != null) {
            List<ServerPlayer> payers = new ArrayList<>();
            r.participants.forEach(id -> payers.add(server.getPlayerList().getPlayer(id)));
            BuyIns.collect(server, r.lobby.id(), r.price, payers);
        }
        HuddleData data = HuddleData.get(server);
        long cooldown = HuddleConfig.TYPES.get(r.type.id()).cooldownMinutes.get() * 60_000L;
        for (UUID id : r.participants) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            Participants.join(p, r.tag);
            data.cooldowns().start(Cooldowns.playerType(id, r.type.id()), nowMs, cooldown);
            // la carrera tiene su cuenta en la largada; el tesoro, la recompensa, el suministro y el último en pie, su título
            if (r.track != null || r.treasure != null || r.type.id().equals("bounty") || r.airdrop != null || r.lastman != null) {
                continue;
            }
            title(p, Component.translatableWithFallback("huddle.event.go", "Go!"), r.type.vote()
                    ? Component.translatableWithFallback("huddle.event.mode", "Combat: %s", EventTypes.modeName(r.lobby.combatMode()))
                    : Component.empty());
        }
        data.changed();
        if (r.track != null) {
            r.race = RaceGame.begin(server, r);
            if (r.race == null) {
                finish(server, r, Component.translatableWithFallback("huddle.race.no_world", "The track's world is not loaded."));
                return;
            }
        }
        if (r.treasure != null) {
            r.treasure.begin(server, r);
        }
        if (r.airdrop != null) {
            r.airdrop.begin(server, r);
        }
        if (r.lastman != null) {
            r.lastman.begin(server, r);
        }
        if (r.type.id().equals("bounty")) {
            r.bounty = BountyGame.begin(server, r);
            if (r.bounty == null) {
                finish(server, r, Component.translatableWithFallback("huddle.event.everyone_left", "Everyone left the event."));
                return;
            }
        }
        if (r.type.vote()) {
            tell(r, server, Component.translatableWithFallback("huddle.event.mode", "Combat: %s", EventTypes.modeName(r.lobby.combatMode()))
                    .copy().withStyle(ChatFormatting.GOLD));
        }
        r.bar.setName(Component.translatableWithFallback("huddle.bar.running", "%s in progress", name(r.type)));
        r.bar.setProgress(1);
        r.bar.setColor(BossEvent.BossBarColor.GREEN);
        Huddle.LOGGER.info("Event {} ({}) started with {} players, combat {}", r.lobby.id(), r.type.id(), r.participants.size(),
                r.lobby.combatMode());
    }

    private static void watchParticipants(MinecraftServer server, Running r, long now) {
        int maxDistance = HuddleConfig.MAX_DISTANCE.get();
        long grace = HuddleConfig.DISCONNECT_GRACE_SECONDS.get() * 20L;
        for (UUID id : List.copyOf(r.participants)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            Long since = r.offlineSince.get(id);
            if (p == null) {
                if (since == null) {
                    r.offlineSince.put(id, now);
                } else if (now - since >= grace) {
                    leave(server, id);
                }
            } else if (maxDistance > 0 && r.race == null && !inRange(p, r, maxDistance)) {
                p.sendSystemMessage(Component.translatableWithFallback("huddle.event.too_far", "You went too far and left the event.")
                        .withStyle(ChatFormatting.RED));
                leave(server, id);
            }
            if (!EVENTS.containsKey(r.lobby.id())) {
                return;
            }
        }
    }

    private static void finish(MinecraftServer server, Running r, Component message) {
        if (EVENTS.remove(r.lobby.id()) == null) {
            return;
        }
        if (message != null) {
            tell(r, server, message);
        }
        if (r.race != null) {
            r.race.end(server, Set.copyOf(r.participants));
        }
        if (r.treasure != null) {
            r.treasure.end(server, Set.copyOf(r.participants));
        }
        if (r.bounty != null) {
            r.bounty.end(server, Set.copyOf(r.participants));
        }
        if (r.airdrop != null) {
            r.airdrop.end(server, Set.copyOf(r.participants));
        }
        if (r.lastman != null && r.phase == Phase.RUNNING) {
            r.lastman.end(server, Set.copyOf(r.participants));
        }
        if (r.phase == Phase.RUNNING) {
            endTitles(server, r);
        }
        if (r.price != null) {
            payOut(server, r);
        }
        for (UUID id : r.members()) {
            BY_PLAYER.remove(id, r);
            Participants.leave(id);
        }
        r.participants.clear();
        r.bar.removeAllPlayers();
    }

    private static void updateBar(MinecraftServer server, Running r, long now) {
        long left = r.lobby.ticksLeft(now);
        long second = (left + 19) / 20;
        if (second == r.lastSecond) {
            return;
        }
        r.lastSecond = second;
        HuddleConfig.TypeValues cfg = HuddleConfig.TYPES.get(r.type.id());
        boolean countdown = r.lobby.state() == Lobby.State.COUNTDOWN;
        long total = countdown ? cfg.rules().countdownTicks() : cfg.rules().openTicks();
        r.bar.setProgress(total <= 0 ? 0 : Math.max(0, Math.min(1, left / (float) total)));
        r.bar.setName(countdown
                ? Component.translatableWithFallback("huddle.bar.countdown", "%s starts in %s s", name(r.type), second)
                : Component.translatableWithFallback("huddle.bar.lobby", "%s: %s s to join (%s/%s)", name(r.type), second,
                        r.lobby.players().size(), cfg.maxPlayers.get()));
        if (countdown && second >= 1 && second <= 3) {
            for (UUID id : r.lobby.players()) {
                title(server.getPlayerList().getPlayer(id), Component.literal(Long.toString(second)), Component.empty());
            }
        }
    }

    private static void announce(ServerPlayer creator, Running r) {
        int radius = HuddleConfig.ANNOUNCE_RADIUS.get();
        String cmd = "/event join " + r.creatorName + " " + r.code;
        Component join = Component.translatableWithFallback("huddle.lobby.join_button", "[Join]")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(cmd))));
        Component what = r.treasure != null
                ? Component.translatableWithFallback("huddle.treasure.named", "%s (%s)", name(r.type), TreasureGame.presetName(r.treasure.preset()))
                : r.airdrop != null
                ? Component.translatableWithFallback("huddle.treasure.named", "%s (%s)", name(r.type), AirdropGame.presetName(r.airdrop.preset()))
                : name(r.type);
        Component msg = r.track == null
                ? r.price == null
                ? Component.translatableWithFallback("huddle.lobby.announce", "%s started a %s. %s", creator.getDisplayName(), what, join)
                : Component.translatableWithFallback("huddle.lobby.announce_entry", "%s started a %s. Entry: %s %s, the winner takes it all. %s",
                        creator.getDisplayName(), what, r.price.count(), r.price.name(), join)
                : r.price == null
                ? Component.translatableWithFallback("huddle.lobby.announce_track", "%s started a %s on the track %s (%s). %s", creator.getDisplayName(),
                        name(r.type), r.track.route().name(), Tracks.modeName(r.track.route().mode()), join)
                : Component.translatableWithFallback("huddle.lobby.announce_track_entry",
                        "%s started a %s on the track %s (%s). Entry: %s %s, the winner takes it all. %s", creator.getDisplayName(),
                        name(r.type), r.track.route().name(), Tracks.modeName(r.track.route().mode()), r.price.count(), r.price.name(), join);
        for (ServerPlayer p : creator.server.getPlayerList().getPlayers()) {
            if (radius == 0 || inRange(p, r, radius)) {
                p.sendSystemMessage(msg);
            }
        }
    }

    private static boolean inRange(ServerPlayer p, Running r, int radius) {
        if (radius == 0) {
            return true;
        }
        return p.level().dimension() == r.dimension && p.position().distanceToSqr(r.origin) <= (double) radius * radius;
    }

    private static JoinCheck.Verdict check(ServerPlayer p, EventTypes.Type type) {
        HuddleConfig.TypeValues cfg = HuddleConfig.TYPES.get(type.id());
        long left = HuddleData.get(p.server).cooldowns().remaining(Cooldowns.playerType(p.getUUID(), type.id()), System.currentTimeMillis());
        return JoinCheck.check(cfg.limits(), left, p.getArmorValue(), highestEnchant(p));
    }

    // todo lo que lleva encima; los libros encantados guardan sus encantamientos aparte y no cuentan
    static int highestEnchant(ServerPlayer p) {
        Inventory inv = p.getInventory();
        int best = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            for (var e : stack.getTagEnchantments().entrySet()) {
                best = Math.max(best, e.getIntValue());
            }
        }
        return best;
    }

    // después del regreso a casa: lo que no entre en el inventario cae ahí y no en la pista
    private static void payOut(MinecraftServer server, Running r) {
        List<RaceState.Result> results = r.race == null || r.race.state() == null ? List.of() : r.race.state().results();
        UUID winner = r.treasure != null ? r.treasure.winner() : r.bounty != null ? r.bounty.winner() : r.airdrop != null ? r.airdrop.winner()
                : r.lastman != null ? r.lastman.winner()
                : results.isEmpty() ? null : results.get(0).player();
        int pot = HuddleData.get(server).pots().containsKey(r.lobby.id()) ? HuddleData.get(server).pots().get(r.lobby.id()).size() : 0;
        if (pot == 0) {
            BuyIns.settle(server, r.lobby.id(), null);
            return;
        }
        Component msg = BuyIns.settle(server, r.lobby.id(), winner)
                ? Component.translatableWithFallback("huddle.pot.won", "%s takes the pot: %s %s.", nameOf(server, winner),
                        pot * r.price.count(), r.price.name())
                : Component.translatableWithFallback("huddle.pot.refunded", "Nobody won the pot: everyone gets their entry back.");
        tell(r, server, msg);
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p == null ? "?" : p.getGameProfile().getName();
    }

    static void tellAll(Running r, MinecraftServer server, Component msg) {
        tell(r, server, msg);
    }

    // daño que de verdad llegó a un participante; attacker null si no lo causó un jugador
    public static void onPlayerHurt(ServerPlayer victim, ServerPlayer attacker) {
        Running r = BY_PLAYER.get(victim.getUUID());
        if (r != null && r.airdrop != null && r.phase == Phase.RUNNING) {
            r.airdrop.hurt(victim.getUUID());
        }
        if (r != null && attacker != null && r.bounty != null && victim.getUUID().equals(r.bounty.marked())) {
            r.bounty.onHit(attacker, victim.server.getTickCount());
        }
    }

    // antes de sacarlo del evento: si era el marcado, cuenta quién lo mató
    public enum AfterDeath { OUT, PLACED, JOIN_POINT }

    // muerto en un evento empezado: sigue en él, salvo en el último en pie y el marcado de la recompensa atrapado.
    // En la carrera vuelve al último punto que pasó (PLACED); en los demás, a donde se unió
    public static AfterDeath afterDeath(ServerPlayer p) {
        Running r = BY_PLAYER.get(p.getUUID());
        if (r == null || r.phase != Phase.RUNNING || r.lastman != null) {
            return AfterDeath.OUT;
        }
        // el marcado sigue solo si nadie lo atrapó (Lobbies.onDeath ya avisó la muerte)
        if (r.bounty != null && p.getUUID().equals(r.bounty.marked())
                && r.bounty.hunt().outcome(p.server.getTickCount()) != BountyHunt.Outcome.RUNNING) {
            return AfterDeath.OUT;
        }
        if (r.race != null) {
            return r.race.respawn(p) ? AfterDeath.PLACED : AfterDeath.OUT;
        }
        return AfterDeath.JOIN_POINT;
    }

    public static void onDeath(ServerPlayer player, ServerPlayer killer) {
        Running r = BY_PLAYER.get(player.getUUID());
        if (r != null && r.bounty != null && player.getUUID().equals(r.bounty.marked())) {
            r.bounty.onMarkedDeath(killer == null ? null : killer.getUUID(), player.server.getTickCount());
        }
    }

    // clic en un cofre del tesoro, con cualquiera de los dos botones; devuelve true si era un cofre
    public static boolean onTreasureClick(ServerPlayer p, Entity e) {
        for (Running r : List.copyOf(EVENTS.values())) {
            if (r.treasure == null || r.phase != Phase.RUNNING || !r.treasure.isChest(e)) {
                continue;
            }
            switch (r.treasure.claim(p)) {
                case WON -> {
                    p.playNotifySound(net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f);
                    finish(p.server, r, r.treasure.summary(p.server));
                }
                case TOO_FAR -> p.displayClientMessage(Component.translatableWithFallback("huddle.treasure.closer",
                        "Get closer to the chest."), true);
                case NOT_PARTICIPANT -> p.displayClientMessage(Component.translatableWithFallback("huddle.treasure.not_yours",
                        "This chest belongs to a treasure hunt you are not in."), true);
                case OVER -> {
                }
            }
            return true;
        }
        return false;
    }

    // true si el teletransporte descalificó al jugador
    public static boolean onTeleport(ServerPlayer p) {
        Running r = BY_PLAYER.get(p.getUUID());
        return r != null && r.race != null && r.race.onTeleport(p.server, p);
    }

    private static void tell(Running r, MinecraftServer server, Component msg) {
        for (UUID id : r.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                p.sendSystemMessage(msg);
            }
        }
    }

    // al terminar: el ganador ve que ganó y los demás quién ganó (la carrera y la recompensa tienen los suyos)
    private static void endTitles(MinecraftServer server, Running r) {
        UUID w = r.treasure != null ? r.treasure.winner() : r.airdrop != null ? r.airdrop.winner() : r.lastman != null ? r.lastman.winner() : null;
        if (w == null) {
            return;
        }
        ServerPlayer wp = server.getPlayerList().getPlayer(w);
        String name = wp == null ? "?" : wp.getGameProfile().getName();
        // el título grande no salta de línea: corto, y el detalle en el subtítulo
        Component well = Component.translatableWithFallback("huddle.event.well_done", "Well done");
        Component mine, mineSub, othersSub;
        if (r.treasure != null) {
            mine = Component.translatableWithFallback("huddle.treasure.you_won", "Found it!");
            mineSub = well;
            othersSub = Component.translatableWithFallback("huddle.treasure.found_sub", "found the treasure");
        } else if (r.airdrop != null) {
            mine = Component.translatableWithFallback("huddle.airdrop.you_opened", "Crate opened!");
            mineSub = well;
            othersSub = Component.translatableWithFallback("huddle.airdrop.opened_sub", "opened the crate");
        } else {
            mine = Component.translatableWithFallback("huddle.lastman.you_won", "You won!");
            mineSub = Component.translatableWithFallback("huddle.lastman.you_won_sub", "You are the last one standing");
            othersSub = Component.translatableWithFallback("huddle.lastman.won_sub", "is the last one standing");
        }
        Set<UUID> who = new java.util.LinkedHashSet<>(r.participants);
        if (r.lastman != null) {
            who.addAll(r.lastman.started());
        }
        for (UUID id : who) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                endTitle(p, (id.equals(w) ? mine : Component.literal(name)).copy().withStyle(ChatFormatting.GOLD), id.equals(w) ? mineSub : othersSub);
            }
        }
    }

    static void endTitle(ServerPlayer p, Component title, Component subtitle) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(5, 70, 10));
        p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    static void title(ServerPlayer p, Component title, Component subtitle) {
        if (p == null) {
            return;
        }
        p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 20, 5));
        p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    static Component name(EventTypes.Type type) {
        return Component.translatableWithFallback("huddle.type." + type.id(), type.name());
    }

    public static List<String> modes(EventTypes.Type type) {
        return new ArrayList<>(HuddleConfig.TYPES.get(type.id()).rules().combatModes());
    }
}
