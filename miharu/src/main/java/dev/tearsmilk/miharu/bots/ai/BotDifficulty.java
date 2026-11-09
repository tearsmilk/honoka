package dev.tearsmilk.miharu.bots.ai;

import org.bukkit.configuration.ConfigurationSection;

/** Сложность бота (modules/bots/config.yml → difficulties.&lt;id&gt;). */
public record BotDifficulty(
        String id,
        String display,
        String namePrefix,
        double health,
        double reach,
        double cpsMin,
        double cpsMax,
        double missChance,
        double hitAngle,
        double turnSpeed,
        double aimError,
        int reactionTicks,
        double lead,
        double speed,
        double keepDistance,
        double strafeChance,
        int strafeMinTicks,
        int strafeMaxTicks,
        double jumpChance,
        boolean wtap,
        int wtapMinTicks,
        int wtapMaxTicks,
        boolean critJump,
        boolean cooldownAware,
        double cooldownThreshold,
        boolean blockHit,
        double blockChance,
        boolean swingOutOfRange,
        int chargeDelayMin,
        int chargeDelayMax
) {

    public static BotDifficulty load(String id, ConfigurationSection s) {
        return new BotDifficulty(
                id,
                s.getString("display", id),
                s.getString("name-prefix", ""),
                s.getDouble("health", 20),
                s.getDouble("reach", 3.0),
                s.getDouble("cps.min", 6),
                s.getDouble("cps.max", 9),
                s.getDouble("miss-chance", 0.2),
                s.getDouble("aim.hit-angle", 30),
                s.getDouble("aim.turn-speed", 20),
                s.getDouble("aim.error", 4),
                s.getInt("aim.reaction-ticks", 3),
                s.getDouble("aim.lead", 0),
                s.getDouble("movement.speed", 1.0),
                s.getDouble("movement.keep-distance", 0.5),
                s.getDouble("movement.strafe-chance", 0.3),
                s.getInt("movement.strafe-ticks.min", 15),
                s.getInt("movement.strafe-ticks.max", 35),
                s.getDouble("movement.jump-chance", 0.0),
                s.getBoolean("movement.w-tap", false),
                s.getInt("movement.w-tap-ticks.min", 2),
                s.getInt("movement.w-tap-ticks.max", 4),
                s.getBoolean("combat.crit-jump", false),
                s.getBoolean("combat.cooldown-aware", false),
                s.getDouble("combat.cooldown-threshold", 0.95),
                s.getBoolean("combat.block-hit", false),
                s.getDouble("combat.block-chance", 0.0),
                s.getBoolean("combat.swing-out-of-range", true),
                s.getInt("combat.charge-delay-ticks.min", 0),
                s.getInt("combat.charge-delay-ticks.max", 2)
        );
    }
}
