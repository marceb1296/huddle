package dev.huddle.neoforge;

import dev.huddle.core.CombatRule;
import dev.huddle.core.EventTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;

// el modo votado se aplica al daño entre participantes del mismo evento; canHarmPlayer no ve con qué se pega
final class CombatModes {
    private CombatModes() {
    }

    static boolean blocks(ServerPlayer victim, DamageSource source) {
        EventTag v = Participants.of(victim);
        if (v == null || v.combat() == null) {
            return false;
        }
        Entity cause = source.getEntity();
        // un lobo o un gato con dueño pega por su dueño
        if (cause instanceof OwnableEntity pet && pet.getOwner() instanceof ServerPlayer owner) {
            cause = owner;
        }
        if (!(cause instanceof ServerPlayer attacker) || attacker == victim) {
            return false;
        }
        EventTag a = Participants.of(attacker);
        if (a == null || !a.id().equals(v.id())) {
            return false;
        }
        Entity direct = source.getDirectEntity();
        CombatRule.Hit hit = direct == attacker && source.is(DamageTypes.PLAYER_ATTACK) ? CombatRule.Hit.MELEE
                : direct instanceof AbstractArrow && !(direct instanceof ThrownTrident) ? CombatRule.Hit.ARROW
                : CombatRule.Hit.OTHER;
        return !CombatRule.allows(v.combat(), hit, BuiltInRegistries.ITEM.getKey(attacker.getMainHandItem().getItem()).toString());
    }
}
