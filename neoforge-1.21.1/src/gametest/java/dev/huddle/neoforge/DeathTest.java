package dev.huddle.neoforge;

import dev.huddle.core.EventTag;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// con o sin mods de tumbas (Corpse, GraveStone, Tombstone): morir en un evento no suelta nada,
// no deja tumba ni cuerpo y el jugador sale del evento con todo su inventario
@GameTestHolder(Huddle.MODID)
@PrefixGameTestTemplate(false)
public final class DeathTest {
    private static final int RADIUS = 3;

    private static void equip(ServerPlayer p) {
        p.getInventory().clearContent();
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        p.getInventory().setItem(5, new ItemStack(Items.GOLDEN_APPLE, 7));
        p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        p.experienceLevel = 10;
    }

    private static Map<BlockPos, BlockState> blocksAround(ServerLevel level, BlockPos center) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS, -RADIUS, -RADIUS), center.offset(RADIUS, RADIUS, RADIUS))) {
            blocks.put(pos.immutable(), level.getBlockState(pos));
        }
        return blocks;
    }

    // lo que la muerte dejó en el mundo: ítems, orbes, cuerpos, bloques de tumba
    private static String leftovers(ServerLevel level, ServerPlayer p, BlockPos center, Map<BlockPos, BlockState> before) {
        StringBuilder found = new StringBuilder();
        List<Entity> entities = level.getEntities(p, new AABB(center).inflate(RADIUS), e -> !(e instanceof ServerPlayer));
        Map<String, Integer> counts = new TreeMap<>();
        for (Entity e : entities) {
            counts.merge(e.getType().builtInRegistryHolder().key().location().toString(), 1, Integer::sum);
        }
        counts.forEach((type, n) -> found.append(' ').append(n).append('x').append(type));
        before.forEach((pos, state) -> {
            BlockState now = level.getBlockState(pos);
            if (now != state) {
                found.append(" block ").append(now.getBlock().builtInRegistryHolder().key().location()).append(" at ").append(pos.toShortString());
            }
        });
        return found.toString();
    }

    private static void die(GameTestHelper helper, EventTag event, boolean expectKept) {
        ServerLevel level = helper.getLevel();
        ServerPlayer p = TestPlayers.join(helper, event == null ? "outsider" : "participant");
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 0, 1.5));
        p.teleportTo(level, at.x, at.y, at.z, 0, 0);
        BlockPos center = p.blockPosition();
        equip(p);
        if (event != null) {
            Participants.join(p, event);
        }
        Map<BlockPos, BlockState> before = blocksAround(level, center);
        p.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);

        helper.runAfterDelay(10, () -> {
            try {
                String left = leftovers(level, p, center, before);
                if (expectKept) {
                    StringBuilder wrong = new StringBuilder();
                    if (p.isDeadOrDying() || p.getHealth() < p.getMaxHealth()) wrong.append(" died (health ").append(p.getHealth()).append(")");
                    if (Participants.of(p) != null) wrong.append(" still in event");
                    if (!p.getInventory().getItem(0).is(Items.DIAMOND_SWORD)) wrong.append(" lost sword");
                    if (p.getInventory().getItem(5).getCount() != 7) wrong.append(" lost apples");
                    if (!p.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)) wrong.append(" lost chestplate");
                    if (!p.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)) wrong.append(" lost shield");
                    if (p.experienceLevel != 10) wrong.append(" lost xp");
                    wrong.append(left.isEmpty() ? "" : " left behind:" + left);
                    if (!wrong.isEmpty()) throw new GameTestAssertException(wrong.toString().trim());
                } else if (left.isEmpty() || !p.isDeadOrDying()) {
                    // control: una muerte normal suelta algo (ítems o tumba); si no, la prueba no mide nada
                    throw new GameTestAssertException("normal death left nothing behind, dead=" + p.isDeadOrDying());
                }
                helper.succeed();
            } finally {
                TestPlayers.leave(helper, p);
            }
        });
    }

    // la muerte se cancela, pero si el jugador queda en el vacío o en la lava muere de nuevo, ya fuera del evento
    private static void dieIn(GameTestHelper helper, boolean lava) {
        ServerLevel level = helper.getLevel();
        ServerPlayer p = TestPlayers.join(helper, lava ? "inLava" : "inVoid");
        Vec3 joined = helper.absoluteVec(new Vec3(4.5, 0, 4.5));
        p.teleportTo(level, joined.x, joined.y, joined.z, 0, 0);
        equip(p);
        Participants.join(p, new EventTag(UUID.randomUUID(), false));
        if (lava) {
            helper.setBlock(1, 0, 1, Blocks.LAVA);
            Vec3 at = helper.absoluteVec(new Vec3(1.5, 0, 1.5));
            p.teleportTo(level, at.x, at.y, at.z, 0, 0);
        } else {
            Vec3 at = helper.absoluteVec(new Vec3(1.5, 0, 1.5));
            p.teleportTo(level, at.x, level.getMinBuildHeight() - 70, at.z, 0, 0);
        }
        // un doTick por tick del servidor: la invulnerabilidad tras cada golpe baja en ServerPlayer.tick
        float[] lowest = {p.getHealth()};
        for (int tick = 1; tick <= 300; tick++) {
            helper.runAfterDelay(tick, () -> {
                p.doTick();
                lowest[0] = Math.min(lowest[0], p.getHealth());
            });
        }
        helper.runAfterDelay(301, () -> {
            try {
                StringBuilder wrong = new StringBuilder();
                // sin esto la prueba no mide nada: el jugador tiene que haber caído dentro del evento
                if (Participants.of(p) != null) wrong.append(" never knocked out (lowest health ").append(lowest[0]).append(')');
                if (p.isDeadOrDying()) wrong.append(" died after leaving the event");
                if (!p.getInventory().getItem(0).is(Items.DIAMOND_SWORD)) wrong.append(" lost sword");
                if (!p.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)) wrong.append(" lost chestplate");
                if (p.getY() < level.getMinBuildHeight() || p.isInLava()) wrong.append(" still in danger at ").append(p.blockPosition().toShortString());
                else if (p.position().distanceTo(joined) > 1) wrong.append(" not back where they joined: ").append(p.blockPosition().toShortString());
                if (!wrong.isEmpty()) throw new GameTestAssertException(wrong.toString().trim());
                helper.succeed();
            } finally {
                TestPlayers.leave(helper, p);
            }
        });
    }

    private static void near(StringBuilder wrong, String what, ServerPlayer p, Vec3 expected) {
        if (p.position().distanceTo(expected) > 1) {
            wrong.append(' ').append(what).append(": at ").append(p.blockPosition().toShortString())
                    .append(", expected ").append(BlockPos.containing(expected).toShortString());
        }
    }

    // la configuración es global: cada prueba la cambia y la restaura dentro del mismo tick
    private static void withConfig(boolean keep, HuddleConfig.DeathDestination destination, Runnable body) {
        boolean keepBefore = HuddleConfig.KEEP_INVENTORY_ON_DEATH.get();
        HuddleConfig.DeathDestination destinationBefore = HuddleConfig.DEATH_DESTINATION.get();
        HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(keep);
        HuddleConfig.DEATH_DESTINATION.set(destination);
        try {
            body.run();
        } finally {
            HuddleConfig.KEEP_INVENTORY_ON_DEATH.set(keepBefore);
            HuddleConfig.DEATH_DESTINATION.set(destinationBefore);
        }
    }

    // se une en A, el evento lo lleva a B y muere ahí
    private static void dieAwayFromJoinPoint(GameTestHelper helper, String name, boolean keep, HuddleConfig.DeathDestination destination,
                                             boolean lavaAtJoinPoint, Check check) {
        ServerLevel level = helper.getLevel();
        ServerPlayer p = TestPlayers.join(helper, name);
        Vec3 joined = helper.absoluteVec(new Vec3(1.5, 0, 1.5));
        Vec3 died = helper.absoluteVec(new Vec3(4.5, 0, 4.5));
        p.teleportTo(level, joined.x, joined.y, joined.z, 0, 0);
        equip(p);
        Participants.join(p, new EventTag(UUID.randomUUID(), true));
        if (lavaAtJoinPoint) {
            helper.setBlock(1, 0, 1, Blocks.LAVA);
        }
        p.teleportTo(level, died.x, died.y, died.z, 0, 0);
        // reaparición fija (como /spawnpoint): el spawn del mundo elige un punto al azar dentro de un radio
        BlockPos home = helper.absolutePos(new BlockPos(7, 0, 1));
        p.setRespawnPosition(level.dimension(), home, 0, true, false);
        Vec3 respawn = p.findRespawnPositionAndUseSpawnBlock(true, DimensionTransition.DO_NOTHING).pos();
        if (respawn.distanceTo(Vec3.atBottomCenterOf(home)) > 1) {
            throw new GameTestAssertException("test setup: respawn point " + p.getRespawnPosition() + " forced " + p.isRespawnForced()
                    + " resolves to " + BlockPos.containing(respawn).toShortString() + ", set " + home.toShortString());
        }
        ServerPlayer[] now = {p};
        StringBuilder wrong = new StringBuilder();
        try {
            withConfig(keep, destination, () -> {
                p.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
                if (p.isDeadOrDying()) {
                    now[0] = level.getServer().getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
                }
            });
            check.run(wrong, p, now[0], joined, respawn);
        } finally {
            TestPlayers.leave(helper, now[0]);
        }
        if (!wrong.isEmpty()) throw new GameTestAssertException(wrong.toString().trim());
        helper.succeed();
    }

    private interface Check {
        void run(StringBuilder wrong, ServerPlayer before, ServerPlayer after, Vec3 joined, Vec3 respawn);
    }

    @GameTest(template = "arena")
    public static void participantReturnsToJoinPoint(GameTestHelper helper) {
        dieAwayFromJoinPoint(helper, "backToJoin", true, HuddleConfig.DeathDestination.JOIN_POINT, false,
                (wrong, before, after, joined, respawn) -> {
                    if (before != after) wrong.append(" died for real");
                    near(wrong, "not back where they joined", after, joined);
                });
    }

    @GameTest(template = "arena")
    public static void respawnDestinationUsesBedOrSpawn(GameTestHelper helper) {
        dieAwayFromJoinPoint(helper, "toRespawn", true, HuddleConfig.DeathDestination.RESPAWN, false,
                (wrong, before, after, joined, respawn) -> {
                    if (before != after) wrong.append(" died for real");
                    near(wrong, "not at the respawn point", after, respawn);
                });
    }

    @GameTest(template = "arena")
    public static void unsafeJoinPointFallsBackToRespawn(GameTestHelper helper) {
        dieAwayFromJoinPoint(helper, "unsafeJoin", true, HuddleConfig.DeathDestination.JOIN_POINT, true,
                (wrong, before, after, joined, respawn) -> {
                    if (after.isInLava()) wrong.append(" sent into lava");
                    near(wrong, "not at the respawn point", after, respawn);
                });
    }

    // modo extremo: muere de verdad, pierde lo que lleva y reaparece donde se unió
    @GameTest(template = "arena")
    public static void hardcoreLosesItemsAndReturnsToJoinPoint(GameTestHelper helper) {
        dieAwayFromJoinPoint(helper, "hardcore", false, HuddleConfig.DeathDestination.JOIN_POINT, false,
                (wrong, before, after, joined, respawn) -> {
                    if (before == after) wrong.append(" did not die");
                    if (Participants.of(after) != null) wrong.append(" still in event");
                    if (after.getInventory().getItem(0).is(Items.DIAMOND_SWORD)) wrong.append(" kept the sword");
                    near(wrong, "not back where they joined", after, joined);
                });
    }

    @GameTest(template = "arena", timeoutTicks = 400)
    public static void participantFallingIntoVoidIsSaved(GameTestHelper helper) {
        dieIn(helper, false);
    }

    @GameTest(template = "arena", timeoutTicks = 400)
    public static void participantInLavaIsSaved(GameTestHelper helper) {
        dieIn(helper, true);
    }

    @GameTest(template = "arena")
    public static void outsiderDiesNormally(GameTestHelper helper) {
        die(helper, null, false);
    }

    @GameTest(template = "arena")
    public static void participantKeepsEverything(GameTestHelper helper) {
        die(helper, new EventTag(UUID.randomUUID(), false), true);
    }

    @GameTest(template = "arena")
    public static void bountyParticipantKeepsEverything(GameTestHelper helper) {
        die(helper, new EventTag(UUID.randomUUID(), true), true);
    }
}
