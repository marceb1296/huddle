package dev.huddle.core;

// copia del terreno alrededor de un punto, una columna por bloque, para armar pistas sin tocar el mundo.
// height es la altura de los pies: el primer bloque libre sobre el suelo o sobre el agua.
// headroom: bloques libres sobre el suelo (0 a 3). cave: altura de los pies en un hueco bajo la superficie, si hay.
// busy: la columna está en un chunk muy habitado (una base, seguramente)
public final class TerrainGrid {
    public static final byte UNKNOWN = 0;
    public static final byte BLOCKED = 1;
    public static final byte WATER = 2;
    public static final byte LAND = 3;

    public static final int MAX_RADIUS = 256;
    public static final int NO_CAVE = Integer.MIN_VALUE;

    private final int minX;
    private final int minZ;
    private final int size;
    private final byte[] kind;
    private final int[] height;
    private final byte[] headroom;
    private final int[] cave;
    private final boolean[] busy;

    public TerrainGrid(int centerX, int centerZ, int radius) {
        int r = Math.max(1, Math.min(radius, MAX_RADIUS));
        this.minX = centerX - r;
        this.minZ = centerZ - r;
        this.size = 2 * r + 1;
        this.kind = new byte[size * size];
        this.height = new int[size * size];
        this.headroom = new byte[size * size];
        this.cave = new int[size * size];
        this.busy = new boolean[size * size];
        java.util.Arrays.fill(cave, NO_CAVE);
    }

    public int minX() {
        return minX;
    }

    public int minZ() {
        return minZ;
    }

    public int size() {
        return size;
    }

    public int cells() {
        return size * size;
    }

    public boolean contains(int x, int z) {
        return x >= minX && z >= minZ && x < minX + size && z < minZ + size;
    }

    public int index(int x, int z) {
        return (z - minZ) * size + (x - minX);
    }

    public int x(int index) {
        return minX + index % size;
    }

    public int z(int index) {
        return minZ + index / size;
    }

    public void set(int x, int z, byte k, int h, int free) {
        if (!contains(x, z)) {
            return;
        }
        int i = index(x, z);
        kind[i] = k < UNKNOWN || k > LAND ? BLOCKED : k;
        height[i] = h;
        headroom[i] = (byte) Math.max(0, Math.min(free, 3));
    }

    public void setCave(int x, int z, int h) {
        if (contains(x, z)) {
            cave[index(x, z)] = h;
        }
    }

    public void markBusy(int x, int z) {
        if (contains(x, z)) {
            busy[index(x, z)] = true;
        }
    }

    public boolean busy(int index) {
        return busy[index];
    }

    public int cave(int index) {
        return cave[index];
    }

    public byte kind(int index) {
        return kind[index];
    }

    public int height(int index) {
        return height[index];
    }

    public int headroom(int index) {
        return headroom[index];
    }
}
