package dev.huddle.core;

// requisitos para unirse; el equipo se mide, nunca se toca
public final class JoinCheck {
    public enum Verdict { OK, ON_COOLDOWN, TOO_MUCH_ARMOR, ENCHANT_TOO_HIGH }

    // negativos: sin tope
    public record Limits(int maxArmor, int maxEnchantLevel) {
        public static final Limits NONE = new Limits(-1, -1);
    }

    private JoinCheck() {
    }

    public static Verdict check(Limits limits, long cooldownLeft, int armor, int highestEnchant) {
        if (cooldownLeft > 0) {
            return Verdict.ON_COOLDOWN;
        }
        if (limits.maxArmor() >= 0 && armor > limits.maxArmor()) {
            return Verdict.TOO_MUCH_ARMOR;
        }
        if (limits.maxEnchantLevel() >= 0 && highestEnchant > limits.maxEnchantLevel()) {
            return Verdict.ENCHANT_TOO_HIGH;
        }
        return Verdict.OK;
    }
}
