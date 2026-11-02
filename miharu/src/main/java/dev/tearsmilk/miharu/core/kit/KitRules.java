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
        ExplosionMode explosions,
        int respawnSeconds,
        boolean fireball,
        boolean autoTnt,
        String chestLoot,
        int players
) {

    /**
     * NONE — до смерти; GOAL — гол в ворота (Bridge); BED — сломал кровать = очко (MLG Rush);
     * BEDWARS — сломал кровать = соперник больше не возрождается (BedWars, Fireball Fight).
     */
    public enum Objective { NONE, GOAL, BED, BEDWARS }

    /** lose-round — смерть отдаёт очко сопернику; respawn — просто возрождение (Bridge, MLG). */
    public enum DeathAction { LOSE_ROUND, RESPAWN }

    /** Что ломают взрывы: всё, ничего или только поставленные игроками блоки. */
    public enum ExplosionMode { ALL, NONE, PLACED }

    public static final KitRules DEFAULT = load(null, 1);

    /** Правила для старых режимов бота (дуэль без кита дуэлей, клик-тест). */
    public static KitRules botDefault(boolean liquidLose, int maxDurationSeconds) {
        KitRules d = DEFAULT;
        return new KitRules(d.damage, d.hunger, d.naturalRegen, d.fallDamage, false, true, false, liquidLose, true, 0,
                Objective.NONE, 2.5, DeathAction.LOSE_ROUND, 1, 3, false, 7, 0, true, maxDurationSeconds,
                ExplosionMode.ALL, 0, false, false, "", 2);
    }

    /** Та же игра, но до другого числа очков (варианты 3/5/7). */
    public KitRules withRounds(int rounds) {
        return new KitRules(damage, hunger, naturalRegen, fallDamage, build, breakPlacedOnly, infiniteBlocks, liquidLose, voidLose,
                hitTarget, objective, goalRadius, onDeath, Math.max(1, rounds), roundCountdown, soup, soupHeal, pearlCooldownTicks,
                rodKnockback, maxDurationSeconds, explosions, respawnSeconds, fireball, autoTnt, chestLoot, players);
    }

    /** Та же игра на другое число игроков (SkyWars 1x3, 1x4 — каждый сам за себя). */
    public KitRules withPlayers(int n) {
        return new KitRules(damage, hunger, naturalRegen, fallDamage, build, breakPlacedOnly, infiniteBlocks, liquidLose, voidLose,
                hitTarget, objective, goalRadius, onDeath, scoreToWin, roundCountdown, soup, soupHeal, pearlCooldownTicks,
                rodKnockback, maxDurationSeconds, explosions, respawnSeconds, fireball, autoTnt, chestLoot, Math.max(2, n));
    }

    public static KitRules load(ConfigurationSection s, int rounds) {
        if (s == null) {
            s = new org.bukkit.configuration.MemoryConfiguration();
        }
        Objective obj;
        try {
            obj = Objective.valueOf(s.getString("objective", "none").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            obj = Objective.NONE;
        }
        DeathAction death = "respawn".equalsIgnoreCase(s.getString("on-death", "lose-round")) ? DeathAction.RESPAWN : DeathAction.LOSE_ROUND;
        String ex = String.valueOf(s.get("explosion-block-damage", "true")).toLowerCase(Locale.ROOT);
        ExplosionMode explosions = switch (ex) {
            case "false", "none", "off" -> ExplosionMode.NONE;
            case "placed" -> ExplosionMode.PLACED;
            default -> ExplosionMode.ALL;
        };
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
                explosions,
                s.getInt("respawn-seconds", 0),
                s.getBoolean("fireball", false),
                s.getBoolean("auto-tnt", false),
                s.getString("chest-loot", ""),
                Math.max(2, s.getInt("players", 2))
        );
    }
}
