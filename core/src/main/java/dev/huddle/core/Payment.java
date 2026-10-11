package dev.huddle.core;

// cobro de una entrada en ítems: cuánto sacar de cada espacio del inventario.
// Nunca saca más de lo que hay en un espacio ni más de lo pedido; si no alcanza, no saca nada.
public final class Payment {
    private Payment() {
    }

    // available: cantidad que sirve en cada espacio (0 si no sirve). null si no alcanza.
    public static int[] plan(int[] available, int need) {
        if (available == null || need <= 0) {
            return null;
        }
        int[] take = new int[available.length];
        int left = need;
        for (int i = 0; i < available.length && left > 0; i++) {
            int n = Math.min(Math.max(0, available[i]), left);
            take[i] = n;
            left -= n;
        }
        return left == 0 ? take : null;
    }

    // una entrega grande partida en pilas que entren en un espacio
    public static int[] stacks(int count, int maxStack) {
        if (count <= 0 || maxStack <= 0) {
            return new int[0];
        }
        int full = count / maxStack;
        int rest = count % maxStack;
        int[] out = new int[full + (rest > 0 ? 1 : 0)];
        java.util.Arrays.fill(out, 0, full, maxStack);
        if (rest > 0) {
            out[full] = rest;
        }
        return out;
    }
}
