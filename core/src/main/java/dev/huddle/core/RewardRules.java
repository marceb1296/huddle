package dev.huddle.core;

// premio por defecto del admin, en unidades (cuántas veces se entrega el premio configurado)
public record RewardRules(int baseUnits, int dailyCap, double decay, int minDistinctPlayers, int approvalAbove) {
    static final int HARD_MAX_UNITS = 64 * 36;

    // decay: cada victoria del día multiplica el siguiente premio por este valor
    // approvalAbove: premios de más unidades esperan al admin; negativo, nunca
    public RewardRules {
        baseUnits = Math.max(0, Math.min(baseUnits, HARD_MAX_UNITS));
        dailyCap = Math.max(0, Math.min(dailyCap, HARD_MAX_UNITS));
        decay = Double.isFinite(decay) ? Math.max(0, Math.min(decay, 1)) : 1;
        minDistinctPlayers = Math.max(1, minDistinctPlayers);
    }
}
