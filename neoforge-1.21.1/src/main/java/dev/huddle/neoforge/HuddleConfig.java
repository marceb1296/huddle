package dev.huddle.neoforge;

import dev.huddle.core.JoinCheck;
import dev.huddle.core.LobbyRules;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.common.ModConfigSpec;

final class HuddleConfig {
    enum DeathDestination { JOIN_POINT, RESPAWN }

    static final class TypeValues {
        final ModConfigSpec.BooleanValue enabled;
        final ModConfigSpec.IntValue minPlayers;
        final ModConfigSpec.IntValue maxPlayers;
        final ModConfigSpec.IntValue lobbySeconds;
        final ModConfigSpec.IntValue countdownSeconds;
        final ModConfigSpec.IntValue cooldownMinutes;
        final ModConfigSpec.ConfigValue<List<? extends String>> combatModes;
        final ModConfigSpec.IntValue maxArmor;
        final ModConfigSpec.IntValue maxEnchantLevel;

        TypeValues(ModConfigSpec.Builder b, EventTypes.Type t) {
            b.push(t.id());
            enabled = b.define("enabled", true);
            minPlayers = b.comment("Players needed to start.").defineInRange("minPlayers", t.minPlayers(), 1, 1000);
            maxPlayers = b.defineInRange("maxPlayers", 16, 1, 1000);
            lobbySeconds = b.comment("How long the lobby stays open for others to join.").defineInRange("lobbySeconds", 60, 5, 3600);
            countdownSeconds = b.comment("Countdown once the lobby closes; players vote the combat mode meanwhile.")
                    .defineInRange("countdownSeconds", 10, 0, 600);
            cooldownMinutes = b.comment("After taking part, a player waits this long to join this event type again.")
                    .defineInRange("cooldownMinutes", 5, 0, 10080);
            combatModes = b.comment("Combat modes players can vote for: any, fists, wooden_sword, bow (\"any\" is always available, others are ignored).")
                    .defineListAllowEmpty("combatModes", t.vote() ? List.of("any", "fists", "wooden_sword", "bow") : List.of("any"),
                            () -> "any", o -> o instanceof String s && !s.isBlank());
            maxArmor = b.comment("Highest armor value allowed when joining and at the start (a full diamond set is 20). -1: no limit.")
                    .defineInRange("maxArmor", -1, -1, 1000);
            maxEnchantLevel = b.comment("Highest enchantment level allowed on any carried item. -1: no limit.")
                    .defineInRange("maxEnchantLevel", -1, -1, 255);
            b.pop();
        }

        LobbyRules rules() {
            return new LobbyRules(minPlayers.get(), maxPlayers.get(), lobbySeconds.get() * 20L, countdownSeconds.get() * 20L,
                    List.copyOf(combatModes.get()));
        }

        JoinCheck.Limits limits() {
            return new JoinCheck.Limits(maxArmor.get(), maxEnchantLevel.get());
        }
    }

    static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue KEEP_INVENTORY_ON_DEATH;
    static final ModConfigSpec.EnumValue<DeathDestination> DEATH_DESTINATION;
    static final ModConfigSpec.IntValue ANNOUNCE_RADIUS;
    static final ModConfigSpec.IntValue MAX_DISTANCE;
    static final ModConfigSpec.IntValue DISCONNECT_GRACE_SECONDS;
    static final ModConfigSpec.IntValue MAX_JUMP_BLOCKS;
    static final ModConfigSpec.IntValue TRAVEL_GRACE_SECONDS;
    static final ModConfigSpec.IntValue TRACK_POINT_EVERY;
    static final ModConfigSpec.IntValue MAX_TRACKS_PER_PLAYER;
    static final ModConfigSpec.IntValue BUSY_CHUNK_MINUTES;
    static final ModConfigSpec.IntValue GENERATOR_RADIUS;
    static final ModConfigSpec.IntValue GENERATOR_COLUMNS_PER_TICK;
    static final ModConfigSpec.IntValue GENERATED_MIN_LENGTH;
    static final ModConfigSpec.IntValue RACE_MAX_MINUTES;
    static final ModConfigSpec.IntValue RACE_FINISH_WINDOW_SECONDS;
    static final ModConfigSpec.IntValue MAX_BUY_IN;
    static final ModConfigSpec.IntValue TREASURE_MIN_DISTANCE;
    static final ModConfigSpec.IntValue TREASURE_MAX_DISTANCE;
    static final ModConfigSpec.IntValue TREASURE_CAVE_DEPTH;
    static final ModConfigSpec.IntValue TREASURE_CLUE_SECONDS;
    static final ModConfigSpec.IntValue TREASURE_MAX_SECONDS;
    static final ModConfigSpec.IntValue BOUNTY_MAX_SECONDS;
    static final ModConfigSpec.IntValue BOUNTY_CAMP_SECONDS;
    static final ModConfigSpec.IntValue BOUNTY_DEATH_EXPOSE_SECONDS;
    static final ModConfigSpec.IntValue AIRDROP_MIN_DISTANCE;
    static final ModConfigSpec.IntValue AIRDROP_MAX_DISTANCE;
    static final ModConfigSpec.IntValue AIRDROP_FALL_SECONDS;
    static final ModConfigSpec.IntValue AIRDROP_OPEN_SECONDS;
    static final ModConfigSpec.IntValue AIRDROP_GROUND_SECONDS;
    static final ModConfigSpec.ConfigValue<List<? extends String>> AIRDROP_LOOT;
    static final ModConfigSpec.ConfigValue<List<? extends Integer>> LASTMAN_ZONE_SIZES;
    static final ModConfigSpec.IntValue LASTMAN_HOLD_SECONDS;
    static final ModConfigSpec.IntValue LASTMAN_SHRINK_SECONDS;
    static final ModConfigSpec.IntValue LASTMAN_OUTSIDE_SECONDS;
    static final ModConfigSpec.IntValue LASTMAN_FINAL_SECONDS;
    static final ModConfigSpec.IntValue LASTMAN_TRUCE_SECONDS;
    static final ModConfigSpec.ConfigValue<List<? extends String>> LASTMAN_KIT;
    static final ModConfigSpec.ConfigValue<List<? extends String>> LASTMAN_LOOT;
    static final Map<String, TypeValues> TYPES = new LinkedHashMap<>();

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        KEEP_INVENTORY_ON_DEATH = b.comment("Dying in an event takes the player out of it without losing anything.",
                        "false: they die like anywhere else and lose their items (grave mods keep working).")
                .define("keepInventoryOnDeath", true);
        DEATH_DESTINATION = b.comment("Where a player goes after dying in an event:",
                        "JOIN_POINT: back where they were when they joined (their bed or the world spawn if that spot is no longer safe).",
                        "RESPAWN: their bed, respawn anchor or the world spawn, like a normal respawn.")
                .defineEnum("deathDestination", DeathDestination.JOIN_POINT);
        ANNOUNCE_RADIUS = b.comment("New events are announced to players within this many blocks of the creator, in the same dimension.",
                        "Only they can join. 0: the whole server, any dimension.")
                .defineInRange("announceRadius", 256, 0, 100000);
        MAX_DISTANCE = b.comment("A participant who goes farther than this from where the event was created leaves it. 0: no limit.")
                .defineInRange("maxDistance", 1000, 0, 100000);
        DISCONNECT_GRACE_SECONDS = b.comment("A participant who disconnects during an event leaves it unless they come back within this time.")
                .defineInRange("disconnectGraceSeconds", 60, 0, 3600);
        MAX_JUMP_BLOCKS = b.comment("Moving more than this in one tick counts as a teleport: it disqualifies a racer and ruins a track recording.",
                        "Ender pearls, chorus fruit and /tp are always caught, whatever the distance.")
                .defineInRange("maxJumpBlocks", 30, 5, 1000);
        TRAVEL_GRACE_SECONDS = b.comment("How long a racer or a track recorder may be off the track's way of travel (off the horse, out of the boat,",
                        "landed with elytra) before being disqualified. Time back on it earns the margin back.")
                .defineInRange("travelGraceSeconds", 4, 0, 60);
        TRACK_POINT_EVERY = b.comment("While recording a track, a checkpoint is added every this many blocks. 0: only by hand.")
                .defineInRange("trackPointEvery", 32, 0, 1000);
        MAX_TRACKS_PER_PLAYER = b.comment("How many tracks each player may save. Operators may delete any track.")
                .defineInRange("maxTracksPerPlayer", 10, 0, 1000);
        BUSY_CHUNK_MINUTES = b.comment("Warn the recorder when a checkpoint lands in a chunk where players spent more than this long",
                        "(someone's base, probably). Generated checkpoints, treasures, airdrops and zones are never placed in those chunks",
                        "(players may still go through them). 0: no chunk counts as busy.")
                .defineInRange("busyChunkMinutes", 60, 0, 1000000);
        GENERATOR_RADIUS = b.comment("Generated tracks are looked for this far around the player, only in chunks that are already loaded",
                        "(nothing is generated or loaded from disk). No checkpoint lands in a busy chunk (see busyChunkMinutes).")
                .defineInRange("generatorRadius", 160, 32, 256);
        GENERATOR_COLUMNS_PER_TICK = b.comment("How many block columns the track generator reads per server tick. Lower it if generating causes lag.")
                .defineInRange("generatorColumnsPerTick", 4096, 64, 65536);
        GENERATED_MIN_LENGTH = b.comment("Shortest generated track, in blocks (one lap for lakes and circuits).")
                .defineInRange("generatedMinLength", 100, 16, 1000);
        RACE_MAX_MINUTES = b.comment("A race ends after this long even if racers are still on the track.")
                .defineInRange("raceMaxMinutes", 10, 1, 240);
        RACE_FINISH_WINDOW_SECONDS = b.comment("Once the first racer finishes, the others have this long to finish.")
                .defineInRange("raceFinishWindowSeconds", 90, 0, 14400);
        MAX_BUY_IN = b.comment("Largest entry an event creator can ask for, in items. Every participant pays it",
                        "and the winner takes it all. 0: events have no entry.")
                .defineInRange("maxBuyIn", 64, 0, 10000);
        TREASURE_MIN_DISTANCE = b.comment("The treasure is hidden at least this far from the creator.")
                .defineInRange("treasureMinDistance", 50, 0, 256);
        TREASURE_MAX_DISTANCE = b.comment("And at most this far. Only already loaded chunks are used, so it never goes past generatorRadius.")
                .defineInRange("treasureMaxDistance", 140, 1, 256);
        TREASURE_CAVE_DEPTH = b.comment("How deep under the surface the caves preset looks for a cave.")
                .defineInRange("treasureCaveDepth", 48, 4, 384);
        TREASURE_CLUE_SECONDS = b.comment("The first clue comes when the hunt starts, then one more every this many seconds (4 in all).")
                .defineInRange("treasureClueSeconds", 120, 5, 3600);
        TREASURE_MAX_SECONDS = b.comment("Nobody found the chest in this long: the hunt ends and every entry goes back to its owner.")
                .defineInRange("treasureMaxSeconds", 720, 30, 14400);
        BOUNTY_MAX_SECONDS = b.comment("The target wins a bounty by surviving this long.")
                .defineInRange("bountyMaxSeconds", 600, 30, 14400);
        BOUNTY_CAMP_SECONDS = b.comment("A target that stays this long in the same chunk glows for every hunter until it moves on.")
                .defineInRange("bountyCampSeconds", 120, 10, 3600);
        BOUNTY_DEATH_EXPOSE_SECONDS = b.comment("A target that dies without being caught stays in the hunt, back where it joined, and glows for",
                        "every hunter this long (0: it just goes on). With keepInventoryOnDeath off it dies for real and leaves instead.")
                .defineInRange("bountyDeathExposeSeconds", 30, 0, 600);
        AIRDROP_MIN_DISTANCE = b.comment("The airdrop lands at least this far from the creator.")
                .defineInRange("airdropMinDistance", 40, 0, 256);
        AIRDROP_MAX_DISTANCE = b.comment("And at most this far (never past generatorRadius: only loaded chunks are used).")
                .defineInRange("airdropMaxDistance", 120, 1, 256);
        AIRDROP_FALL_SECONDS = b.comment("How long the crate takes to come down. Players see where it will land meanwhile.")
                .defineInRange("airdropFallSeconds", 60, 0, 600);
        AIRDROP_OPEN_SECONDS = b.comment("A participant opens the crate by staying next to it, alone and unhurt, this long.")
                .defineInRange("airdropOpenSeconds", 15, 1, 120);
        AIRDROP_GROUND_SECONDS = b.comment("Nobody opened the crate this long after it landed: the event ends and every entry goes back.")
                .defineInRange("airdropGroundSeconds", 300, 10, 3600);
        AIRDROP_LOOT = b.comment("Loot tables the crate draws from (one at random each time), vanilla or from any mod, plus the entries",
                        "if there was a buy-in. Empty: only the entries.")
                .defineListAllowEmpty("airdropLoot", List.of("minecraft:chests/simple_dungeon"), () -> "minecraft:chests/simple_dungeon",
                        o -> o instanceof String s && net.minecraft.resources.ResourceLocation.tryParse(s) != null);
        LASTMAN_ZONE_SIZES = b.comment("Last one standing: half the side of the square zone at each stage, largest first. Players see it as the",
                        "world border, only for them (the real border is never changed).")
                .defineListAllowEmpty("lastmanZoneSizes", List.of(60, 35, 18, 6), () -> 10, o -> o instanceof Integer i && i >= 1 && i <= 1000);
        LASTMAN_HOLD_SECONDS = b.comment("How long the zone stays still before each shrink.")
                .defineInRange("lastmanHoldSeconds", 60, 0, 3600);
        LASTMAN_SHRINK_SECONDS = b.comment("How long each shrink takes.")
                .defineInRange("lastmanShrinkSeconds", 30, 0, 3600);
        LASTMAN_OUTSIDE_SECONDS = b.comment("A participant outside the zone for this long is out.")
                .defineInRange("lastmanOutsideSeconds", 10, 0, 600);
        LASTMAN_FINAL_SECONDS = b.comment("Once the zone reaches its last size, the event ends after this long with no winner (every entry goes back).")
                .defineInRange("lastmanFinalSeconds", 180, 10, 3600);
        LASTMAN_TRUCE_SECONDS = b.comment("Right after the spread, participants cannot hurt each other for this long.")
                .defineInRange("lastmanTruceSeconds", 10, 0, 600);
        LASTMAN_KIT = b.comment("Items lent to every participant (\"namespace:item\" or \"namespace:item count\"). Participants join with an empty",
                        "inventory (only the entry, if there is one) and the kit is taken back when they leave the event. Armor and shields are worn.")
                .defineListAllowEmpty("lastmanKit", List.of("minecraft:iron_sword", "minecraft:bow", "minecraft:arrow 16", "minecraft:shield",
                                "minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings", "minecraft:iron_boots",
                                "minecraft:cooked_beef 8"), () -> "minecraft:bread 4", o -> o instanceof String s && !s.isBlank());
        LASTMAN_LOOT = b.comment("Loot tables for the winner (one at random), vanilla or from any mod, plus the entries if there was a buy-in.",
                        "Empty: only the entries.")
                .defineListAllowEmpty("lastmanLoot", List.of("minecraft:chests/simple_dungeon"), () -> "minecraft:chests/simple_dungeon",
                        o -> o instanceof String s && net.minecraft.resources.ResourceLocation.tryParse(s) != null);
        b.push("events");
        for (EventTypes.Type t : EventTypes.ALL) {
            TYPES.put(t.id(), new TypeValues(b, t));
        }
        b.pop();
        SPEC = b.build();
    }

    private HuddleConfig() {
    }
}
