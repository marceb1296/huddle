package dev.huddle.neoforge;

import dev.huddle.core.ModeGrace;
import dev.huddle.core.Pos;
import dev.huddle.core.RaceState;
import dev.huddle.core.Route;
import dev.huddle.core.StartGrid;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

// una carrera en curso sobre una pista guardada; la maneja Lobbies desde el hilo del servidor
final class RaceGame {
    static final int STAGING_TICKS = 60;

    private final Tracks.Stored track;
    private final ServerLevel level;
    // por UUID: si el chunk se descarga y vuelve a cargar, la entidad es otro objeto con otro id
    private final List<UUID> markers = new ArrayList<>();
    private final List<UUID> labels = new ArrayList<>();
    private final Map<UUID, ModeGrace> grace = new HashMap<>();
    private final Map<UUID, Entity> highlighted = new HashMap<>();
    private final Map<UUID, Vec3> slots = new HashMap<>();
    private float startYaw;
    private final long stagingStart;
    private RaceState state;
    private long goTick = -1;
    private long deadline;
    private long firstFinish = -1;
    private long lastShown = -1;

    private RaceGame(Tracks.Stored track, ServerLevel level, long now) {
        this.track = track;
        this.level = level;
        this.stagingStart = now;
    }

    static RaceGame begin(MinecraftServer server, Lobbies.Running r) {
        ServerLevel level = server.getLevel(r.track().dimension());
        if (level == null) {
            return null;
        }
        RaceGame g = new RaceGame(r.track(), level, server.getTickCount());
        g.spawnMarkers();
        g.lineUp(r.participants());
        for (UUID id : r.participants()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                Homecoming.remember(server, id, Participants.joinedAt(p));
                g.toStart(p);
            }
        }
        return g;
    }

    boolean racing() {
        return goTick >= 0;
    }

    // alguien ya llegó y los demás tienen el margen para terminar
    boolean inFinishWindow() {
        return firstFinish >= 0;
    }

    List<UUID> displayIds() {
        List<UUID> all = new ArrayList<>(markers);
        all.addAll(labels);
        return all;
    }

    RaceState state() {
        return state;
    }

    Route route() {
        return track.route();
    }

    // un lugar por jugador en la parrilla, todos mirando al primer punto. Se saltan los lugares con bloques, sin suelo,
    // con lava o fuego, en agua si la largada no lo está (y al revés) o con una pared entre ellos y la largada.
    // Si no queda ninguno, la largada misma
    private void lineUp(Collection<UUID> players) {
        Pos s = route().start();
        Pos first = route().checkpoints().get(0).center();
        startYaw = StartGrid.yaw(s, first);
        Vec3 origin = new Vec3(s.x(), s.y(), s.z());
        EntityDimensions size = size(route().mode());
        boolean water = water(origin);
        Iterator<Pos> spots = StartGrid.spots(s, first, Math.max(1.5, size.width() + 0.6), players.size() * 4 + 8).iterator();
        for (UUID id : players) {
            Vec3 at = null;
            while (at == null && spots.hasNext()) {
                at = fit(spots.next(), origin, water, size);
            }
            slots.put(id, at == null ? origin : at);
        }
    }

    private static EntityDimensions size(Route.Mode mode) {
        return switch (mode) {
            case HORSE -> EntityType.HORSE.getDimensions();
            case BOAT -> EntityType.BOAT.getDimensions();
            default -> EntityType.PLAYER.getDimensions();
        };
    }

    private boolean water(Vec3 at) {
        return level.getFluidState(BlockPos.containing(at).below()).is(FluidTags.WATER);
    }

    private Vec3 fit(Pos spot, Vec3 origin, boolean water, EntityDimensions size) {
        for (double dy : new double[] {0, 1, -1}) {
            Vec3 at = new Vec3(spot.x(), spot.y() + dy, spot.z());
            BlockPos feet = BlockPos.containing(at);
            AABB box = size.makeBoundingBox(at);
            if (!level.isLoaded(feet) || !level.noCollision(box) || water(at) != water
                    || level.getFluidState(feet).is(FluidTags.LAVA) || level.getBlockState(feet).is(BlockTags.FIRE)) {
                continue;
            }
            if (!water && level.noCollision(box.move(0, -0.6, 0))) {
                continue;
            }
            Vec3 up = new Vec3(0, 0.5, 0);
            if (level.clip(new ClipContext(at.add(up), origin.add(up), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    CollisionContext.empty())).getType() != HitResult.Type.MISS) {
                continue;
            }
            return at;
        }
        return null;
    }

    private Vec3 slot(UUID player) {
        Vec3 at = slots.get(player);
        if (at == null) {
            Pos s = route().start();
            at = new Vec3(s.x(), s.y(), s.z());
        }
        return at;
    }

    // en bote o a caballo sale en la montura prestada, la misma para todos; la propia queda donde estaba
    private void toStart(ServerPlayer p) {
        Vec3 at = slot(p.getUUID());
        if (LentMounts.lends(route().mode())) {
            LentMounts.seat(p, level, route().mode(), at, startYaw);
        } else {
            Homecoming.move(p, level, at, startYaw, 0, false);
        }
    }

    // devuelve true cuando la carrera terminó
    boolean tick(MinecraftServer server, Lobbies.Running r, long now) {
        if (goTick < 0) {
            stage(server, r, now);
            return false;
        }
        for (UUID id : r.participants()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || state.status(id) != RaceState.Status.RUNNING) {
                continue;
            }
            if (p.level() != level) {
                disqualify(server, p, Component.translatableWithFallback("huddle.race.dq.dimension", "You left the track's dimension."));
                continue;
            }
            if (grace.computeIfAbsent(id, k -> new ModeGrace(HuddleConfig.TRAVEL_GRACE_SECONDS.get() * 20)).update(Tracks.travelling(p, route().mode()))) {
                disqualify(server, p, Component.translatableWithFallback("huddle.race.dq.travel", "Disqualified: this race is %s.",
                        Tracks.modeName(route().mode())));
                continue;
            }
            for (RaceState.Event e : state.move(id, Tracks.pos(p), now)) {
                switch (e.kind()) {
                    case JUMP -> disqualify(server, p, Component.translatableWithFallback("huddle.race.dq.teleport", "Disqualified: you teleported."));
                    case CHECKPOINT -> p.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1f,
                            checkpointPitch(state.nextCheckpoint(id), route().checkpoints().size()));
                    case LAP -> lap(p, e.value() + 1);
                    case FINISH -> {
                    }
                }
            }
            highlight(p);
        }
        for (RaceState.Result res : state.newFinishes()) {
            finished(server, r, res, now);
        }
        if (now % 4 == 0 || now % 10 == 0) {
            for (UUID id : r.participants()) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null && state.status(id) == RaceState.Status.RUNNING) {
                    if (now % 4 == 0) {
                        ring(p, state.nextCheckpoint(id));
                    }
                    if (now % 10 == 0) {
                        status(p, now);
                    }
                }
            }
        }
        if (now % 20 == 0) {
            r.bar().setName(Component.translatableWithFallback("huddle.race.bar", "%s on %s: %s left", Lobbies.name(r.type()), route().name(),
                    clock(Math.max(0, end() - now))));
            r.bar().setProgress(Math.max(0, Math.min(1, (end() - now) / (float) Math.max(1, end() - goTick))));
        }
        return state.over() || now >= end();
    }

    private long end() {
        long window = HuddleConfig.RACE_FINISH_WINDOW_SECONDS.get() * 20L;
        return firstFinish < 0 ? deadline : Math.min(deadline, firstFinish + window);
    }

    // 3, 2, 1 en la largada; quien se adelanta vuelve a ella
    private void stage(MinecraftServer server, Lobbies.Running r, long now) {
        long elapsed = now - stagingStart;
        // el primer tick de la cuenta ya llega con elapsed 1: contar hacia arriba, no comparar con múltiplos de 20
        long shown = (STAGING_TICKS - elapsed + 19) / 20;
        boolean newNumber = shown != lastShown && shown > 0;
        if (lastShown < 0) {
            r.bar().setName(Component.translatableWithFallback("huddle.race.bar_ready", "%s on %s: get ready", Lobbies.name(r.type()),
                    route().name()));
        }
        lastShown = shown;
        for (UUID id : r.participants()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                continue;
            }
            Vec3 s = slot(id);
            double dx = p.getX() - s.x, dz = p.getZ() - s.z;
            if (p.level() != level || dx * dx + dz * dz > 1.5 * 1.5) {
                toStart(p);
            }
            if (newNumber) {
                title(p, Component.literal(Long.toString(shown)), Component.empty());
            }
        }
        if (elapsed < STAGING_TICKS) {
            return;
        }
        goTick = now;
        deadline = now + HuddleConfig.RACE_MAX_MINUTES.get() * 1200L;
        Map<UUID, Pos> starts = new LinkedHashMap<>();
        for (UUID id : r.participants()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            starts.put(id, p == null ? route().start() : Tracks.pos(p));
            if (p != null) {
                title(p, Component.translatableWithFallback("huddle.event.go", "Go!"), Component.empty());
                highlight(p);
            }
        }
        state = new RaceState(route(), starts, now, HuddleConfig.MAX_JUMP_BLOCKS.get());
    }

    // la nota sube con el avance dentro de la vuelta por una escala pentatónica en un tono medio, sin los agudos que
    // molestan; el volumen es siempre el mismo
    private static final float[] NOTES = {0.707f, 0.794f, 0.891f, 1.059f, 1.189f};

    static float checkpointPitch(int next, int count) {
        if (count <= 1) {
            return NOTES[NOTES.length - 1];
        }
        // next ya apunta al siguiente; 0 o -1 es que acaba de cerrar la vuelta
        int passed = next <= 0 ? count : next;
        int i = Math.round((float) (passed - 1) * (NOTES.length - 1) / (count - 1));
        return NOTES[Math.max(0, Math.min(NOTES.length - 1, i))];
    }

    private void lap(ServerPlayer p, int lap) {
        if (lap == route().laps()) {
            title(p, Component.translatableWithFallback("huddle.race.final_lap", "Final lap!").copy().withStyle(ChatFormatting.GOLD),
                    Component.translatableWithFallback("huddle.race.lap", "Lap %s/%s", lap, route().laps()));
            p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.9f, 1.2f);
        } else {
            title(p, Component.translatableWithFallback("huddle.race.lap", "Lap %s/%s", lap, route().laps()), Component.empty());
            p.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1f, 1.2f);
        }
    }

    private void finished(MinecraftServer server, Lobbies.Running r, RaceState.Result res, long now) {
        if (firstFinish < 0) {
            firstFinish = now;
        }
        ServerPlayer p = server.getPlayerList().getPlayer(res.player());
        String name = p == null ? "?" : p.getGameProfile().getName();
        Component arrived = Component.translatableWithFallback("huddle.race.finished", "%s finished %s in %s.",
                name, place(res.place()), clock(res.ticks()));
        Lobbies.tellAll(r, server, arrived);
        // a los demás de la carrera (corriendo o esperando en la meta), en el centro pero más chico que el título del ganador
        Component subtitle = Component.translatableWithFallback("huddle.race.arrived", "%s finished %s", name, place(res.place()))
                .copy().withStyle(ChatFormatting.YELLOW);
        for (UUID id : r.participants()) {
            ServerPlayer other = server.getPlayerList().getPlayer(id);
            if (other != null && !id.equals(res.player())) {
                title(other, Component.empty(), subtitle, 60);
            }
        }
        if (p != null) {
            unhighlight(p);
            title(p, place(res.place()), Component.literal(clock(res.ticks())));
            p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
            // los demás siguen: puede esperarlos o volver ya sin perder su puesto
            if (!state.over()) {
                p.sendSystemMessage(Component.translatableWithFallback("huddle.race.wait_or_home", "Wait here for the others or %s",
                        Component.translatableWithFallback("huddle.race.home_button", "[Go home]").copy().withStyle(Style.EMPTY
                                .withColor(ChatFormatting.GREEN)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/event leave"))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/event leave"))))));
            }
        }
        int rank = track.records().submit(res.player(), name, res.ticks());
        HuddleData.get(server).changed();
        if (rank == 1) {
            Component msg = Component.translatableWithFallback("huddle.race.record", "New record on %s: %s in %s!", route().name(), name,
                    clock(res.ticks())).withStyle(ChatFormatting.GOLD);
            for (ServerPlayer all : server.getPlayerList().getPlayers()) {
                all.sendSystemMessage(msg);
            }
        }
        long window = HuddleConfig.RACE_FINISH_WINDOW_SECONDS.get() * 20L;
        if (res.place() == 1 && !state.over() && firstFinish + window < deadline) {
            Lobbies.tellAll(r, server, Component.translatableWithFallback("huddle.race.window", "The race ends in %s.", clock(window)));
            // quien la creó puede no esperar el margen
            ServerPlayer creator = server.getPlayerList().getPlayer(r.lobby().creator());
            if (creator != null && r.participants().contains(creator.getUUID())) {
                creator.sendSystemMessage(Component.translatableWithFallback("huddle.race.end_now", "You created this race: %s",
                        Component.translatableWithFallback("huddle.race.end_button", "[End it now]").copy().withStyle(Style.EMPTY
                                .withColor(ChatFormatting.YELLOW)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/event end"))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/event end"))))));
            }
        }
    }

    private void disqualify(MinecraftServer server, ServerPlayer p, Component why) {
        state.disqualify(p.getUUID());
        p.sendSystemMessage(why.copy().withStyle(ChatFormatting.RED));
        Lobbies.leave(server, p.getUUID());
    }

    // perla, fruta coral o /tp durante la carrera; el teletransporte a la largada lo hace el mod sin evento
    boolean onTeleport(MinecraftServer server, ServerPlayer p) {
        if (state == null || state.status(p.getUUID()) != RaceState.Status.RUNNING) {
            return false;
        }
        disqualify(server, p, Component.translatableWithFallback("huddle.race.dq.teleport", "Disqualified: you teleported."));
        return true;
    }

    // quien muere corriendo vuelve al último punto que pasó, en su montura prestada si la carrera la da;
    // en la cuenta, a su lugar en la parrilla. false si ya no corre
    boolean respawn(ServerPlayer p) {
        if (p.level() != level) {
            return false;
        }
        if (goTick < 0) {
            toStart(p);
            return true;
        }
        Pos at = state.respawn(p.getUUID());
        if (at == null) {
            return false;
        }
        Pos next = route().checkpoints().get(state.nextCheckpoint(p.getUUID())).center();
        float yaw = StartGrid.yaw(at, next);
        Vec3 origin = new Vec3(at.x(), at.y(), at.z());
        Vec3 spot = fit(at, origin, route().mode() == Route.Mode.BOAT, size(route().mode()));
        if (spot == null) {
            spot = origin;
        }
        if (LentMounts.lends(route().mode())) {
            LentMounts.seat(p, level, route().mode(), spot, yaw);
        } else {
            Homecoming.move(p, level, spot, yaw, 0, false);
        }
        return true;
    }

    void onLeave(MinecraftServer server, UUID id) {
        if (state != null) {
            state.leave(id);
        }
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) {
            unhighlight(p);
        }
        LentMounts.release(server, id);
        Homecoming.bring(server, id);
    }

    // resultados para el chat de los participantes, antes de que vuelvan a casa
    Component summary() {
        List<RaceState.Result> results = state == null ? List.of() : state.results();
        if (results.isEmpty()) {
            return Component.translatableWithFallback("huddle.race.nobody", "The race is over. Nobody reached the finish.");
        }
        MutableComponent out = Component.translatableWithFallback("huddle.race.over", "The race is over:");
        for (RaceState.Result res : results) {
            out.append("\n").append(Component.translatableWithFallback("huddle.race.line", "%s. %s, %s", res.place(),
                    nameOf(res.player()), clock(res.ticks())));
        }
        return out;
    }

    private String nameOf(UUID id) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
        if (p != null) {
            return p.getGameProfile().getName();
        }
        return track.records().top().stream().filter(e -> e.player().equals(id)).map(e -> e.name()).findFirst().orElse("?");
    }

    void end(MinecraftServer server, Set<UUID> members) {
        announceWinner(server, members);
        for (UUID id : members) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                unhighlight(p);
            }
            LentMounts.release(server, id);
            Homecoming.bring(server, id);
        }
        for (UUID id : markers) {
            Displays.discard(level, id);
        }
        for (UUID id : labels) {
            Displays.discard(level, id);
        }
        markers.clear();
        labels.clear();
    }

    // en grande para los que siguen en la carrera y los que ya llegaron (aunque hayan vuelto a casa), nunca para el resto
    private void announceWinner(MinecraftServer server, Set<UUID> members) {
        List<RaceState.Result> results = state == null ? List.of() : state.results();
        if (results.isEmpty()) {
            return;
        }
        RaceState.Result first = results.get(0);
        Set<UUID> audience = new java.util.LinkedHashSet<>(members);
        results.forEach(res -> audience.add(res.player()));
        Component time = Component.literal(clock(first.ticks()));
        for (UUID id : audience) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                continue;
            }
            title(p, id.equals(first.player())
                    ? Component.translatableWithFallback("huddle.race.you_won", "You won!").copy().withStyle(ChatFormatting.GOLD)
                    : Component.translatableWithFallback("huddle.race.winner", "%s won!", nameOf(first.player())).copy()
                            .withStyle(ChatFormatting.GOLD), time, 70);
        }
    }

    // el próximo punto brilla solo para este jugador
    private void highlight(ServerPlayer p) {
        int next = state == null ? 0 : state.nextCheckpoint(p.getUUID());
        Entity target = next < 0 ? null : level.getEntity(markers.get(next));
        Entity before = highlighted.get(p.getUUID());
        if (before == target) {
            return;
        }
        if (before != null) {
            Glow.hide(p, before);
        }
        if (target != null) {
            Glow.show(p, target);
            highlighted.put(p.getUUID(), target);
        } else {
            highlighted.remove(p.getUUID());
        }
    }

    private void unhighlight(ServerPlayer p) {
        Entity before = highlighted.remove(p.getUUID());
        if (before != null) {
            Glow.hide(p, before);
        }
    }

    // anillo de partículas en el próximo punto, solo para ese jugador
    private void ring(ServerPlayer p, int index) {
        if (index < 0) {
            return;
        }
        Route.Checkpoint cp = route().checkpoints().get(index);
        int n = 16;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            level.sendParticles(p, ParticleTypes.END_ROD, true, cp.center().x() + Math.cos(a) * cp.radius(), cp.center().y() + 0.5,
                    cp.center().z() + Math.sin(a) * cp.radius(), 1, 0, 0, 0, 0);
        }
    }

    private void status(ServerPlayer p, long now) {
        UUID id = p.getUUID();
        int total = route().checkpoints().size();
        int place = state.standings().indexOf(id) + 1;
        Component line = route().laps() > 1
                ? Component.translatableWithFallback("huddle.race.status_laps", "Checkpoint %s/%s, lap %s/%s, %s, %s",
                        state.nextCheckpoint(id) + 1, total, state.lapsDone(id) + 1, route().laps(), place(place), clock(now - goTick))
                : Component.translatableWithFallback("huddle.race.status", "Checkpoint %s/%s, %s, %s",
                        state.nextCheckpoint(id) + 1, total, place(place), clock(now - goTick));
        p.displayClientMessage(line, true);
    }

    private void spawnMarkers() {
        List<Route.Checkpoint> cps = route().checkpoints();
        for (int i = 0; i < cps.size(); i++) {
            Pos c = cps.get(i).center();
            boolean finish = i == cps.size() - 1;
            CompoundTag block = new CompoundTag();
            block.putString("id", "minecraft:block_display");
            CompoundTag state = new CompoundTag();
            state.putString("Name", finish ? "minecraft:emerald_block" : "minecraft:gold_block");
            block.put("block_state", state);
            block.put("transformation", transformation(-0.25f, 0.5f));
            block.putInt("glow_color_override", 0x55FF55);
            markers.add(spawn(block, c.x(), c.y() + 1, c.z()));
            CompoundTag text = new CompoundTag();
            text.putString("id", "minecraft:text_display");
            text.putString("text", "\"" + (i + 1) + "\"");
            text.putString("billboard", "center");
            // sin estos dos, el juego deja un error en el log por cada número
            text.putString("alignment", "center");
            text.put("transformation", transformation(0, 1.5f));
            labels.add(spawn(text, c.x(), c.y() + 1.8, c.z()));
        }
    }

    private UUID spawn(CompoundTag tag, double x, double y, double z) {
        return Displays.spawn(level, tag, x, y, z);
    }

    private static CompoundTag transformation(float offset, float scale) {
        return Displays.transformation(offset, scale);
    }

    static Component place(int place) {
        return Component.translatableWithFallback("huddle.race.place", "#%s", place);
    }

    // ticks a mm:ss.cc
    static String clock(double ticks) {
        long centis = Math.round(Math.max(0, ticks) * 5);
        return String.format(Locale.ROOT, "%d:%02d.%02d", centis / 6000, centis / 100 % 60, centis % 100);
    }

    private static void title(ServerPlayer p, Component title, Component subtitle) {
        title(p, title, subtitle, 20);
    }

    private static void title(ServerPlayer p, Component title, Component subtitle, int stay) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(0, stay, 5));
        p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }
}
