package dev.huddle.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.huddle.core.Lobby;
import dev.huddle.core.Route;
import dev.huddle.core.TrackGenerator;
import dev.huddle.core.TrackRecorder;
import dev.huddle.core.TreasureFinder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

// /event en inglés y /evento con los mismos subcomandos en español
public final class HuddleCommands {
    private record Words(String root, String start, String join, String leave, String vote, String end, String list,
                         String track, String record, String point, String save, String cancel, String delete, String generate) {
    }

    private static final Words EN = new Words("event", "start", "join", "leave", "vote", "end", "list",
            "track", "record", "point", "save", "cancel", "delete", "generate");
    private static final Words ES = new Words("evento", "iniciar", "unirse", "salir", "votar", "terminar", "lista",
            "pista", "grabar", "punto", "guardar", "cancelar", "borrar", "generar");

    private HuddleCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(tree(EN, context));
        dispatcher.register(tree(ES, context));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tree(Words w, CommandBuildContext context) {
        return Commands.literal(w.root())
                .then(Commands.literal(w.start()).then(Commands.argument("type", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(EventTypes.ALL.stream().map(EventTypes.Type::id), b))
                        .executes(ctx -> start(ctx, null, 0, null))
                        // el segundo argumento es la pista de una carrera o el preset del tesoro
                        .then(Commands.argument("track", StringArgumentType.word())
                                .suggests((ctx, b) -> "treasure".equals(StringArgumentType.getString(ctx, "type"))
                                        ? SharedSuggestionProvider.suggest(w == ES ? List.of("clasico", "cuevas") : List.of("classic", "caves"), b)
                                        : "airdrop".equals(StringArgumentType.getString(ctx, "type"))
                                        ? SharedSuggestionProvider.suggest(w == ES ? List.of("unico", "cima") : List.of("single", "peak"), b)
                                        : "lastman".equals(StringArgumentType.getString(ctx, "type"))
                                        ? SharedSuggestionProvider.suggest(w == ES ? List.of("clasico") : List.of("classic"), b)
                                        : SharedSuggestionProvider.suggest(
                                        Tracks.all(ctx.getSource().getServer()).stream().map(t -> t.route().name()), b))
                                .executes(ctx -> start(ctx, StringArgumentType.getString(ctx, "track"), 0, null))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10000))
                                        .then(Commands.argument("item", ItemArgument.item(context))
                                                .executes(ctx -> start(ctx, StringArgumentType.getString(ctx, "track"),
                                                        IntegerArgumentType.getInteger(ctx, "count"), ItemArgument.getItem(ctx, "item").getItem())))))
                        // entrada sin pista ni preset; va después de "track" para que "race 2" siga buscando la pista "2"
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 10000))
                                .then(Commands.argument("item", ItemArgument.item(context))
                                        .executes(ctx -> start(ctx, null,
                                                IntegerArgumentType.getInteger(ctx, "count"), ItemArgument.getItem(ctx, "item").getItem()))))))
                .then(Commands.literal(w.join()).then(Commands.argument("creator", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Lobbies.all().stream()
                                .filter(r -> r.phase() == Lobbies.Phase.LOBBY).map(Lobbies.Running::creatorName), b))
                        .executes(ctx -> join(ctx, null))
                        .then(Commands.argument("lobby", StringArgumentType.word())
                                .executes(ctx -> join(ctx, StringArgumentType.getString(ctx, "lobby"))))))
                .then(Commands.literal(w.leave()).executes(HuddleCommands::leave))
                .then(Commands.literal(w.vote()).then(Commands.argument("mode", StringArgumentType.word())
                        .suggests((ctx, b) -> {
                            Lobbies.Running r = ctx.getSource().getPlayer() == null ? null : Lobbies.of(ctx.getSource().getPlayer());
                            return r == null ? b.buildFuture() : SharedSuggestionProvider.suggest(Lobbies.modes(r.type()), b);
                        })
                        .executes(HuddleCommands::vote)))
                .then(Commands.literal(w.end()).executes(ctx -> end(ctx, null))
                        .then(Commands.argument("creator", StringArgumentType.word()).requires(src -> src.hasPermission(2))
                                .executes(ctx -> end(ctx, StringArgumentType.getString(ctx, "creator")))))
                .then(Commands.literal(w.list()).executes(HuddleCommands::list))
                .then(track(w));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> track(Words w) {
        return Commands.literal(w.track())
                .then(Commands.literal(w.record()).then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("travel", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(Route.Mode.values()).map(m -> m.name().toLowerCase(Locale.ROOT)), b))
                                .executes(ctx -> record(ctx, 1))
                                .then(Commands.argument("laps", IntegerArgumentType.integer(1, Route.MAX_LAPS))
                                        .executes(ctx -> record(ctx, IntegerArgumentType.getInteger(ctx, "laps")))))))
                .then(Commands.literal(w.generate()).then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("preset", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(TrackGenerator.Preset.values()).map(m -> m.name().toLowerCase(Locale.ROOT)), b))
                                .executes(ctx -> generate(ctx, null, 0))
                                .then(laps(null))
                                .then(Commands.literal("foot").executes(ctx -> generate(ctx, Route.Mode.FOOT, 0)).then(laps(Route.Mode.FOOT)))
                                .then(Commands.literal("horse").executes(ctx -> generate(ctx, Route.Mode.HORSE, 0)).then(laps(Route.Mode.HORSE))))))
                .then(Commands.literal(w.point()).executes(HuddleCommands::point))
                .then(Commands.literal(w.save()).executes(HuddleCommands::save))
                .then(Commands.literal(w.cancel()).executes(HuddleCommands::cancel))
                .then(Commands.literal(w.list()).executes(HuddleCommands::listTracks))
                .then(Commands.literal(w.delete()).then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                Tracks.all(ctx.getSource().getServer()).stream().map(t -> t.route().name()), b))
                        .executes(HuddleCommands::delete)));
    }

    private static int record(CommandContext<CommandSourceStack> ctx, int laps) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Route.Mode mode = Route.parseMode(StringArgumentType.getString(ctx, "travel")).orElse(null);
        if (mode == null) {
            return fail(ctx, Component.translatableWithFallback("huddle.track.bad_travel", "Use foot, horse, boat or elytra."));
        }
        return switch (Tracks.record(p, StringArgumentType.getString(ctx, "name"), mode, laps)) {
            case STARTED -> {
                ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("huddle.track.recording",
                        "Recording a track %s. Ride it to the end, then use save. Points are added as you go; add more with point.",
                        Tracks.modeName(mode)), false);
                yield 1;
            }
            case BAD_NAME -> fail(ctx, Component.translatableWithFallback("huddle.track.bad_name",
                    "Track names are one word: letters, numbers, _ . + - (up to %s).", Route.MAX_NAME));
            case NAME_TAKEN -> fail(ctx, Component.translatableWithFallback("huddle.track.name_taken", "There is already a track with that name."));
            case ALREADY_RECORDING -> fail(ctx, Component.translatableWithFallback("huddle.track.already", "You are already recording a track."));
            case IN_EVENT -> fail(ctx, Component.translatableWithFallback("huddle.cmd.busy", "You are already in an event. Leave it first."));
            case TOO_MANY -> fail(ctx, tooMany());
        };
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> laps(Route.Mode mode) {
        return Commands.argument("laps", IntegerArgumentType.integer(1, Route.MAX_LAPS))
                .executes(ctx -> generate(ctx, mode, IntegerArgumentType.getInteger(ctx, "laps")));
    }

    private static int generate(CommandContext<CommandSourceStack> ctx, Route.Mode mode, int laps) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        TrackGenerator.Preset preset;
        try {
            preset = TrackGenerator.Preset.valueOf(StringArgumentType.getString(ctx, "preset").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fail(ctx, Component.translatableWithFallback("huddle.gen.bad_preset", "Use lake, river, sprint or circuit."));
        }
        if (preset.water() && mode != null) {
            return fail(ctx, Component.translatableWithFallback("huddle.gen.boat_only", "Lakes and rivers are raced by boat."));
        }
        int l = laps > 0 ? laps : preset.loop() ? 3 : 1;
        return switch (TrackGen.start(p, StringArgumentType.getString(ctx, "name"), preset, mode, l)) {
            case STARTED -> {
                ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("huddle.gen.started",
                        "Looking for a place for the track around you..."), false);
                yield 1;
            }
            case BAD_NAME -> fail(ctx, Component.translatableWithFallback("huddle.track.bad_name",
                    "Track names are one word: letters, numbers, _ . + - (up to %s).", Route.MAX_NAME));
            case NAME_TAKEN -> fail(ctx, Component.translatableWithFallback("huddle.track.name_taken", "There is already a track with that name."));
            case BUSY -> fail(ctx, Component.translatableWithFallback("huddle.gen.busy", "Your other track is still being generated."));
            case TOO_MANY -> fail(ctx, tooMany());
        };
    }

    private static int point(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TrackRecorder.Outcome o = Tracks.point(ctx.getSource().getPlayerOrException());
        if (o == null) {
            return fail(ctx, notRecording());
        }
        return switch (o) {
            case ADDED -> 1;
            case TOO_CLOSE -> fail(ctx, Component.translatableWithFallback("huddle.track.too_close", "Too close to the last checkpoint."));
            case FULL -> fail(ctx, Component.translatableWithFallback("huddle.track.full",
                    "This track has the most checkpoints allowed (%s). Save it where it should end.", Route.MAX_CHECKPOINTS));
            default -> 0;
        };
    }

    private static int save(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return switch (Tracks.save(ctx.getSource().getPlayerOrException())) {
            case SAVED -> {
                ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("huddle.track.saved", "Track saved."), false);
                yield 1;
            }
            case NOT_RECORDING -> fail(ctx, notRecording());
            case BROKEN -> fail(ctx, Component.translatableWithFallback("huddle.track.broken", "That track could not be saved. Record it again."));
            case NAME_TAKEN -> fail(ctx, Component.translatableWithFallback("huddle.track.name_taken", "There is already a track with that name."));
            case TOO_MANY -> fail(ctx, tooMany());
        };
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!Tracks.cancel(ctx.getSource().getPlayerOrException().getUUID())) {
            return fail(ctx, notRecording());
        }
        ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("huddle.track.cancelled", "Recording cancelled."), false);
        return 1;
    }

    private static int listTracks(CommandContext<CommandSourceStack> ctx) {
        List<Tracks.Stored> all = Tracks.all(ctx.getSource().getServer());
        if (all.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("huddle.track.none", "No tracks yet."), false);
            return 0;
        }
        for (Tracks.Stored t : all) {
            Route r = t.route();
            ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("huddle.track.list_line",
                    "%s: %s, %s checkpoints, %s laps, by %s", r.name(), Tracks.modeName(r.mode()), r.checkpoints().size(), r.laps(),
                    r.authorName()), false);
        }
        return all.size();
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        java.util.UUID by = src.getPlayer() == null ? new java.util.UUID(0, 0) : src.getPlayer().getUUID();
        return switch (Tracks.delete(src.getServer(), StringArgumentType.getString(ctx, "name"), by, src.hasPermission(2))) {
            case DELETED -> {
                src.sendSuccess(() -> Component.translatableWithFallback("huddle.track.deleted", "Track deleted."), false);
                yield 1;
            }
            case NO_SUCH_TRACK -> fail(ctx, Component.translatableWithFallback("huddle.track.no_such", "There is no track with that name."));
            case NOT_YOURS -> fail(ctx, Component.translatableWithFallback("huddle.track.not_yours", "You can only delete your own tracks."));
        };
    }

    private static Component cantPay(BuyIns.Price price) {
        return Component.translatableWithFallback("huddle.pot.cant_pay", "The entry is %s %s and you do not have them (only plain items count).",
                price.count(), price.name());
    }

    private static Component recordingNow() {
        return Component.translatableWithFallback("huddle.cmd.recording", "You are recording a track. Save or cancel it first.");
    }

    private static Component notRecording() {
        return Component.translatableWithFallback("huddle.track.not_recording", "You are not recording a track.");
    }

    private static Component tooMany() {
        return Component.translatableWithFallback("huddle.track.too_many", "You have saved as many tracks as allowed (%s). Delete one first.",
                HuddleConfig.MAX_TRACKS_PER_PLAYER.get());
    }

    private static int start(CommandContext<CommandSourceStack> ctx, String trackName, int count, Item item) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        EventTypes.Type type = EventTypes.byId(StringArgumentType.getString(ctx, "type"));
        if (type == null) {
            return fail(ctx, Component.translatableWithFallback("huddle.cmd.unknown_type", "Unknown event type."));
        }
        Tracks.Stored track = null;
        if (type.id().equals("race")) {
            if (trackName == null) {
                return fail(ctx, Component.translatableWithFallback("huddle.race.need_track",
                        "Choose a track: /event start race <track>. Record one with /event track record."));
            }
            String name = Route.cleanName(trackName);
            track = name == null ? null : HuddleData.get(p.server).tracks().get(name);
            if (track == null) {
                return fail(ctx, Component.translatableWithFallback("huddle.track.no_such", "There is no track with that name."));
            }
        }
        TreasureFinder.Preset preset = null;
        if (type.id().equals("treasure") && trackName != null) {
            preset = switch (trackName.toLowerCase(Locale.ROOT)) {
                case "classic", "clasico" -> TreasureFinder.Preset.CLASSIC;
                case "caves", "cuevas" -> TreasureFinder.Preset.CAVES;
                default -> null;
            };
            if (preset == null) {
                return fail(ctx, Component.translatableWithFallback("huddle.treasure.bad_preset", "Use classic or caves."));
            }
        }
        if (type.id().equals("airdrop") && trackName != null) {
            preset = switch (trackName.toLowerCase(Locale.ROOT)) {
                case "single", "unico" -> TreasureFinder.Preset.CLASSIC;
                case "peak", "cima" -> TreasureFinder.Preset.PEAK;
                default -> null;
            };
            if (preset == null) {
                return fail(ctx, Component.translatableWithFallback("huddle.airdrop.bad_preset", "Use single or peak."));
            }
        }
        if (type.id().equals("lastman") && trackName != null && !List.of("classic", "clasico").contains(trackName.toLowerCase(Locale.ROOT))) {
            return fail(ctx, Component.translatableWithFallback("huddle.lastman.bad_preset", "Use classic."));
        }
        BuyIns.Price price = null;
        if (item != null) {
            if (count > HuddleConfig.MAX_BUY_IN.get()) {
                return fail(ctx, Component.translatableWithFallback("huddle.pot.too_big", "The largest entry allowed here is %s items.",
                        HuddleConfig.MAX_BUY_IN.get()));
            }
            if (item == Items.AIR) {
                return fail(ctx, Component.translatableWithFallback("huddle.pot.bad_item", "That item cannot be an entry."));
            }
            price = new BuyIns.Price(item, count);
        }
        return switch (Lobbies.start(p, type, track, preset, price)) {
            case STARTED -> 1;
            case DISABLED -> fail(ctx, Component.translatableWithFallback("huddle.cmd.disabled", "That event is turned off on this server."));
            case BUSY -> fail(ctx, Component.translatableWithFallback("huddle.cmd.busy", "You are already in an event. Leave it first."));
            case RECORDING -> fail(ctx, recordingNow());
            case CANT_PAY -> fail(ctx, cantPay(price));
            case WRONG_DIMENSION -> fail(ctx, Component.translatableWithFallback("huddle.race.wrong_dimension",
                    "That track is in another dimension."));
            case ON_COOLDOWN -> fail(ctx, cooldown(p, type));
            case TOO_MUCH_ARMOR -> fail(ctx, Component.translatableWithFallback("huddle.cmd.armor", "Your armor is over this event's limit."));
            case ENCHANT_TOO_HIGH -> fail(ctx, Component.translatableWithFallback("huddle.cmd.enchant",
                    "You carry enchantments over this event's limit."));
            case NOT_EMPTY -> fail(ctx, notEmpty(price));
        };
    }

    private static int join(CommandContext<CommandSourceStack> ctx, String code) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Lobbies.Running r = Lobbies.byCreator(StringArgumentType.getString(ctx, "creator"), code);
        return switch (Lobbies.join(p, r)) {
            case JOINED -> 1;
            case NO_SUCH_EVENT -> fail(ctx, Component.translatableWithFallback("huddle.cmd.no_event", "That event no longer exists."));
            case BUSY -> fail(ctx, Component.translatableWithFallback("huddle.cmd.busy", "You are already in an event. Leave it first."));
            case RECORDING -> fail(ctx, recordingNow());
            case CANT_PAY -> fail(ctx, cantPay(r.price()));
            case TOO_FAR -> fail(ctx, Component.translatableWithFallback("huddle.cmd.too_far", "You are too far from that event."));
            case ON_COOLDOWN -> fail(ctx, cooldown(p, r.type()));
            case TOO_MUCH_ARMOR -> fail(ctx, Component.translatableWithFallback("huddle.cmd.armor", "Your armor is over this event's limit."));
            case ENCHANT_TOO_HIGH -> fail(ctx, Component.translatableWithFallback("huddle.cmd.enchant",
                    "You carry enchantments over this event's limit."));
            case FULL -> fail(ctx, Component.translatableWithFallback("huddle.cmd.full", "That event is full."));
            case CLOSED -> fail(ctx, Component.translatableWithFallback("huddle.cmd.closed", "That event already started."));
            case NOT_EMPTY -> fail(ctx, notEmpty(r.price()));
        };
    }

    // el último en pie presta un kit: se entra sin nada, o solo con la entrada justa
    private static Component notEmpty(BuyIns.Price price) {
        return price == null
                ? Component.translatableWithFallback("huddle.cmd.not_empty",
                        "Everyone gets the same kit here: empty your inventory, armor and offhand first.")
                : Component.translatableWithFallback("huddle.cmd.not_empty_entry",
                        "Everyone gets the same kit here: carry only the entry (%s %s) and nothing else.", price.count(), price.name());
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        if (!Lobbies.leave(p.server, p.getUUID())) {
            return fail(ctx, Component.translatableWithFallback("huddle.cmd.not_in_event", "You are not in an event."));
        }
        ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("huddle.cmd.left", "You left the event."), false);
        return 1;
    }

    private static int vote(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        String mode = StringArgumentType.getString(ctx, "mode");
        Lobby.VoteResult res = Lobbies.vote(p, mode);
        return switch (res) {
            case COUNTED -> {
                ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("huddle.cmd.voted", "You voted: %s.", EventTypes.modeName(mode)), false);
                yield 1;
            }
            case NOT_IN_LOBBY -> fail(ctx, Component.translatableWithFallback("huddle.cmd.not_in_event", "You are not in an event."));
            case UNKNOWN_MODE -> fail(ctx, Component.translatableWithFallback("huddle.cmd.unknown_mode", "That mode is not available here."));
            case NOT_VOTING -> fail(ctx, Component.translatableWithFallback("huddle.cmd.not_voting", "Voting is closed."));
        };
    }

    // sin nombre: el propio creador termina su evento; con nombre: un operador termina el de otro
    private static int end(CommandContext<CommandSourceStack> ctx, String creator) throws CommandSyntaxException {
        Lobbies.Running r;
        if (creator == null) {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            r = Lobbies.of(p);
            if (r == null || !r.lobby().creator().equals(p.getUUID())) {
                return fail(ctx, Component.translatableWithFallback("huddle.cmd.not_creator", "Only the creator can end their event."));
            }
            if (!Lobbies.creatorMayEnd(r)) {
                return fail(ctx, Component.translatableWithFallback("huddle.cmd.already_started", "The event already started: only an operator can end it now."));
            }
        } else {
            r = Lobbies.byCreator(creator, null);
            if (r == null) {
                return fail(ctx, Component.translatableWithFallback("huddle.cmd.no_event", "That event no longer exists."));
            }
        }
        Lobbies.end(ctx.getSource().getServer(), r);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        if (Lobbies.all().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("huddle.cmd.none", "No events right now."), false);
            return 0;
        }
        for (Lobbies.Running r : Lobbies.all()) {
            Component state = r.phase() == Lobbies.Phase.LOBBY
                    ? Component.translatableWithFallback("huddle.cmd.state.lobby", "open")
                    : Component.translatableWithFallback("huddle.cmd.state.running", "in progress");
            int count = r.phase() == Lobbies.Phase.LOBBY ? r.lobby().players().size() : r.participants().size();
            ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("huddle.cmd.list_line", "%s by %s, %s, %s players",
                    Lobbies.name(r.type()), r.creatorName(), state, count), false);
        }
        return Lobbies.all().size();
    }

    private static Component cooldown(ServerPlayer p, EventTypes.Type type) {
        long ms = HuddleData.get(p.server).cooldowns().remaining(dev.huddle.core.Cooldowns.playerType(p.getUUID(), type.id()),
                System.currentTimeMillis());
        long minutes = Math.max(1, (ms + 59_999) / 60_000);
        return Component.translatableWithFallback("huddle.cmd.cooldown", "%s cooldown: %s min left.", Lobbies.name(type), minutes);
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, Component msg) {
        ctx.getSource().sendFailure(msg.copy().withStyle(ChatFormatting.RED));
        return 0;
    }
}
