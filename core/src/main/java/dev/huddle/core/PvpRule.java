package dev.huddle.core;

public final class PvpRule {
    public enum Verdict { VANILLA, ALLOW, DENY }

    private PvpRule() {
    }

    // null: no participa en ningún evento; now: tick del servidor, para la tregua
    public static Verdict between(EventTag a, EventTag b, long now) {
        if (a == null && b == null) {
            return Verdict.VANILLA;
        }
        if (a == null || b == null || !a.id().equals(b.id())) {
            return Verdict.DENY;
        }
        boolean teammates = a.team() != 0 && a.team() == b.team();
        boolean truce = now < Math.max(a.pvpFrom(), b.pvpFrom());
        return a.pvp() && b.pvp() && !teammates && !truce ? Verdict.ALLOW : Verdict.DENY;
    }
}
