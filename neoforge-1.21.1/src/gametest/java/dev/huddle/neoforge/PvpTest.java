package dev.huddle.neoforge;

import dev.huddle.core.EventTag;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// jugadores reales del servidor con pvp=false y pvp=true: golpe cuerpo a cuerpo, flecha y la pregunta
// que hacen los lobos y las flechas en vuelo (dueño contra objetivo, al revés que al recibir el daño)
@GameTestHolder(Huddle.MODID)
@PrefixGameTestTemplate(false)
public final class PvpTest {
    private enum Hit { MELEE, ARROW, OWNER_ASKS }

    private static boolean harms(GameTestHelper helper, ServerPlayer attacker, ServerPlayer victim, Hit hit) {
        victim.setHealth(victim.getMaxHealth());
        victim.invulnerableTime = 0;
        switch (hit) {
            case MELEE -> attacker.attack(victim);
            case ARROW -> {
                Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
                arrow.setOwner(attacker);
                victim.hurt(helper.getLevel().damageSources().arrow(arrow, attacker), 4);
            }
            case OWNER_ASKS -> {
                return attacker.canHarmPlayer(victim);
            }
        }
        return victim.getHealth() < victim.getMaxHealth();
    }

    private static void expect(GameTestHelper helper, EventTag a, EventTag b, boolean whenPvpOff, boolean whenPvpOn) {
        ServerPlayer attacker = TestPlayers.join(helper, "attacker");
        ServerPlayer victim = TestPlayers.join(helper, "victim");
        if (a != null) {
            Participants.join(attacker, a);
        }
        if (b != null) {
            Participants.join(victim, b);
        }
        boolean before = helper.getLevel().getServer().isPvpAllowed();
        List<String> wrong = new ArrayList<>();
        try {
            for (boolean pvp : new boolean[] {false, true}) {
                helper.getLevel().getServer().setPvpAllowed(pvp);
                for (Hit hit : Hit.values()) {
                    boolean want = pvp ? whenPvpOn : whenPvpOff;
                    if (harms(helper, attacker, victim, hit) != want) {
                        wrong.add("pvp=" + pvp + " " + hit + (want ? " no dañó" : " dañó"));
                    }
                }
            }
        } finally {
            helper.getLevel().getServer().setPvpAllowed(before);
            TestPlayers.leave(helper, attacker);
            TestPlayers.leave(helper, victim);
        }
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join(", ", wrong));
        }
        helper.succeed();
    }

    // control: sin eventos manda el servidor; si esto falla, la prueba no está midiendo nada
    @GameTest(template = "empty")
    public static void outsidersFollowServer(GameTestHelper helper) {
        expect(helper, null, null, false, true);
    }

    @GameTest(template = "empty")
    public static void bountyFightsWithPvpOff(GameTestHelper helper) {
        EventTag bounty = new EventTag(UUID.randomUUID(), true);
        expect(helper, bounty, bounty, true, true);
    }

    @GameTest(template = "empty")
    public static void outsiderCannotHitParticipant(GameTestHelper helper) {
        expect(helper, null, new EventTag(UUID.randomUUID(), true), false, false);
    }

    @GameTest(template = "empty")
    public static void participantCannotHitOutsider(GameTestHelper helper) {
        expect(helper, new EventTag(UUID.randomUUID(), true), null, false, false);
    }

    @GameTest(template = "empty")
    public static void racersCannotHitEachOther(GameTestHelper helper) {
        EventTag race = new EventTag(UUID.randomUUID(), false);
        expect(helper, race, race, false, false);
    }

    @GameTest(template = "empty")
    public static void differentEventsDoNotFight(GameTestHelper helper) {
        expect(helper, new EventTag(UUID.randomUUID(), true), new EventTag(UUID.randomUUID(), true), false, false);
    }

    private enum Weapon { FIST, WOODEN_SWORD, DIAMOND_SWORD, ARROW, TRIDENT, THORNS, OWNED_TNT, WOLF }

    private static boolean harmsWith(GameTestHelper helper, ServerPlayer attacker, ServerPlayer victim, Weapon w) {
        victim.setHealth(victim.getMaxHealth());
        victim.invulnerableTime = 0;
        attacker.setItemInHand(InteractionHand.MAIN_HAND, switch (w) {
            case WOODEN_SWORD -> new ItemStack(Items.WOODEN_SWORD);
            case DIAMOND_SWORD -> new ItemStack(Items.DIAMOND_SWORD);
            default -> ItemStack.EMPTY;
        });
        switch (w) {
            case FIST, WOODEN_SWORD, DIAMOND_SWORD -> attacker.attack(victim);
            case ARROW -> {
                Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
                arrow.setOwner(attacker);
                victim.hurt(helper.getLevel().damageSources().arrow(arrow, attacker), 4);
            }
            case TRIDENT -> {
                ThrownTrident trident = new ThrownTrident(EntityType.TRIDENT, helper.getLevel());
                trident.setOwner(attacker);
                victim.hurt(helper.getLevel().damageSources().trident(trident, attacker), 4);
            }
            // el que lleva espinas devuelve el golpe sin pegar: no es un puño aunque tenga la mano vacía
            case THORNS -> victim.hurt(helper.getLevel().damageSources().thorns(attacker), 4);
            case OWNED_TNT -> victim.hurt(helper.getLevel().damageSources().explosion(null, attacker), 4);
            case WOLF -> {
                Wolf wolf = EntityType.WOLF.create(helper.getLevel());
                wolf.tame(attacker);
                victim.hurt(helper.getLevel().damageSources().mobAttack(wolf), 4);
            }
        }
        attacker.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return victim.getHealth() < victim.getMaxHealth();
    }

    private static void expectMode(GameTestHelper helper, String mode, List<Weapon> allowed) {
        ServerPlayer attacker = TestPlayers.join(helper, "cattacker");
        ServerPlayer victim = TestPlayers.join(helper, "cvictim");
        EventTag tag = new EventTag(UUID.randomUUID(), true, 0, mode, 0);
        Participants.join(attacker, tag);
        Participants.join(victim, tag);
        List<String> wrong = new ArrayList<>();
        try {
            for (Weapon w : Weapon.values()) {
                boolean want = allowed.contains(w);
                if (harmsWith(helper, attacker, victim, w) != want) {
                    wrong.add(mode + " " + w + (want ? " no dañó" : " dañó"));
                }
            }
        } finally {
            TestPlayers.leave(helper, attacker);
            TestPlayers.leave(helper, victim);
        }
        if (!wrong.isEmpty()) {
            throw new GameTestAssertException(String.join(", ", wrong));
        }
        helper.succeed();
    }

    // control: con todo permitido pega cualquier cosa; si esto falla, las demás no miden nada
    @GameTest(template = "empty")
    public static void anythingGoesHitsWithEverything(GameTestHelper helper) {
        expectMode(helper, "any", List.of(Weapon.values()));
    }

    @GameTest(template = "empty")
    public static void fistsModeOnlyLetsEmptyHandsHit(GameTestHelper helper) {
        expectMode(helper, "fists", List.of(Weapon.FIST));
    }

    @GameTest(template = "empty")
    public static void woodenSwordModeOnlyLetsThatSwordHit(GameTestHelper helper) {
        expectMode(helper, "wooden_sword", List.of(Weapon.WOODEN_SWORD));
    }

    @GameTest(template = "empty")
    public static void bowModeOnlyLetsArrowsHit(GameTestHelper helper) {
        expectMode(helper, "bow", List.of(Weapon.ARROW));
    }
}
