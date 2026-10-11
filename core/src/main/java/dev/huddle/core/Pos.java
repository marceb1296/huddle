package dev.huddle.core;

public record Pos(double x, double y, double z) {
    public boolean finite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    public double distSq(Pos o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double dist(Pos o) {
        return Math.sqrt(distSq(o));
    }
}
