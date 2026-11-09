package dev.tearsmilk.miharu.pvp;

import java.util.Locale;

/** Версия боя. 1.8 — «быстрое» PvP без кулдауна, 1.9 — «медленное» с зарядкой удара. */
public enum CombatVersion {
    LEGACY("1.8"),
    MODERN("1.9");

    private final String id;

    CombatVersion(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static CombatVersion parse(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "1.8", "18", "legacy", "old", "fast", "быстрое", "1_8" -> LEGACY;
            case "1.9", "19", "modern", "new", "slow", "медленное", "1_9" -> MODERN;
            default -> null;
        };
    }
}
