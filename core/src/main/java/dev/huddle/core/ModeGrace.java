package dev.huddle.core;

// margen para ir un rato fuera del medio de la pista (bajarse del caballo, aterrizar con élitros).
// Cuenta acumulada: sube un tick por cada tick fuera y baja uno por cada tick dentro, así
// bajarse casi todo el margen, subirse un instante y repetir no reinicia la cuenta.
public final class ModeGrace {
    private final int graceTicks;
    private int debt;

    public ModeGrace(int graceTicks) {
        this.graceTicks = Math.max(0, graceTicks);
    }

    // true cuando se pasó del margen
    public boolean update(boolean inMode) {
        if (inMode) {
            debt = Math.max(0, debt - 1);
            return false;
        }
        debt++;
        return debt > graceTicks;
    }

    public int remaining() {
        return Math.max(0, graceTicks - debt);
    }
}
