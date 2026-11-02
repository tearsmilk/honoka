package dev.tearsmilk.miharu.core.kit;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/** Правила режима кита (секция rules: в файле кита). */
public record KitRules(
        boolean damage,
        boolean hunger,
        boolean naturalRegen,
        boolean fallDamage,
        boolean build,
        boolean breakPlacedOnly,
        boolean infiniteBlocks,
        boolean liquidLose,
        boolean voidLose,
        int hitTarget,
        Objective objective,
        double goalRadius,
        DeathAction onDeath,
        int scoreToWin,
        int roundCountdown,
        boolean soup,
        double soupHeal,
        int pearlCooldownTicks,
        boolean rodKnockback,
        int maxDurationSeconds,
        boolean explosionBlocks
) {

    public enum Objective { NONE, GOAL, BED }

    /** lose-round — смерть отдаёт очко сопернику; respawn — просто возрождение (Bridge, MLG). */
    public enum DeathAction { LOSE_ROUND, RESPAWN }

    public static final KitRules DEFAULT = new KitRules(true, true, true, true, false, true, false, false, true, 0,
            Objective.NONE, 2.5, DeathAction.LOSE_ROUND, 1, 3, false, 7.0, 0, true, 600, true);

    public static KitRules load(ConfigurationSection s, int rounds) {
        if (s == null) {
            return new KitRules(true, true, true, true, false, true, false, false, true, 0, Objective.NONE, 2.5,
                    DeathAction.LOSE_ROUND, Math.max(1, rounds), 3, false, 7.0, 0, true, 600, true);
        }
        Objective obj;
        try {
            obj = Objective.valueOf(s.getString("objective", "none").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            obj = Objective.NONE;
        }
        DeathAction death = "respawn".equalsIgnoreCase(s.getString("on-death", "lose-round")) ? DeathAction.RESPAWN : DeathAction.LOSE_ROUND;
        return new KitRules(
                s.getBoolean("damage", true),
                s.getBoolean("hunger", true),
                s.getBoolean("natural-regen", true),
                s.getBoolean("fall-damage", true),
                s.getBoolean("build", false),
                s.getBoolean("break-placed-only", true),
                s.getBoolean("infinite-blocks", false),
                s.getBoolean("liquid-lose", false),
                s.getBoolean("void-lose", true),
                s.getInt("hit-target", 0),
                obj,
                s.getDouble("goal-radius", 2.5),
                death,
                Math.max(1, rounds),
                s.getInt("round-countdown", 3),
                s.getBoolean("soup.enabled", false),
                s.getDouble("soup.heal", 7.0),
                s.getInt("pearl-cooldown-ticks", 0),
                s.getBoolean("rod-knockback", true),
                s.getInt("max-duration-seconds", 600),
                s.getBoolean("explosion-block-damage", true)
        );
    }
}
