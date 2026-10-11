package dev.huddle.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// kit de un evento escrito por el admin, una entrada por ítem: "minecraft:arrow 16" o "minecraft:iron_sword".
// Las entradas mal escritas se descartan y se informan; si el ítem existe lo decide el loader
public final class Kit {
    public static final int MAX_COUNT = 256;
    public static final int MAX_ENTRIES = 36;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public record Entry(String id, int count) {
    }

    public record Parsed(List<Entry> entries, List<String> bad) {
    }

    private Kit() {
    }

    public static Parsed parse(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (String line : lines) {
            Entry e = line == null ? null : entry(line.trim());
            if (e == null || entries.size() >= MAX_ENTRIES) {
                bad.add(String.valueOf(line));
            } else {
                entries.add(e);
            }
        }
        return new Parsed(List.copyOf(entries), List.copyOf(bad));
    }

    private static Entry entry(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length > 2 || !ID.matcher(parts[0]).matches()) {
            return null;
        }
        int count = 1;
        if (parts.length == 2) {
            if (!parts[1].matches("[0-9]{1,4}")) {
                return null;
            }
            count = Integer.parseInt(parts[1]);
            if (count < 1 || count > MAX_COUNT) {
                return null;
            }
        }
        return new Entry(parts[0], count);
    }
}
