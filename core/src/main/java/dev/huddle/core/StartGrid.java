package dev.huddle.core;

import java.util.ArrayList;
import java.util.List;

// lugares de largada: filas perpendiculares a la dirección del primer punto, la primera sobre la línea y las demás
// detrás. El loader descarta los que caen en un bloque o fuera del agua, por eso hay más lugares que jugadores
public final class StartGrid {
    public static final int PER_ROW = 4;
    public static final int MAX = 256;

    private StartGrid() {
    }

    // orden: la primera fila del centro hacia afuera, alternando lados, después la fila siguiente
    public static List<Pos> spots(Pos start, Pos ahead, double spacing, int count) {
        List<Pos> out = new ArrayList<>();
        int n = Math.min(Math.max(count, 0), MAX);
        if (start == null || !start.finite() || n == 0) {
            return out;
        }
        double gap = Double.isFinite(spacing) ? Math.max(1, Math.min(spacing, 8)) : 1;
        double fx = 0, fz = 1;
        if (ahead != null && ahead.finite()) {
            double dx = ahead.x() - start.x(), dz = ahead.z() - start.z();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1e-6) {
                fx = dx / len;
                fz = dz / len;
            }
        }
        // a la derecha mirando hacia adelante
        double rx = -fz, rz = fx;
        for (int i = 0; i < n; i++) {
            int row = i / PER_ROW;
            int k = i % PER_ROW;
            double side = (k / 2 + 0.5) * (k % 2 == 0 ? -1 : 1) * gap;
            double back = row * gap;
            out.add(new Pos(start.x() + rx * side - fx * back, start.y(), start.z() + rz * side - fz * back));
        }
        return out;
    }

    // el giro de Minecraft para mirar de from hacia to (0 es el sur, +z)
    public static float yaw(Pos from, Pos to) {
        double dx = to.x() - from.x(), dz = to.z() - from.z();
        if (!Double.isFinite(dx) || !Double.isFinite(dz) || dx * dx + dz * dz < 1e-12) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
