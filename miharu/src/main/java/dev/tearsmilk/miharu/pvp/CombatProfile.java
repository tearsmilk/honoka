package dev.tearsmilk.miharu.pvp;

import dev.tearsmilk.miharu.core.util.Items;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.potion.PotionEffect;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Все настройки одной версии боя (modules/pvp/config.yml → versions.&lt;id&gt;). */
public final class CombatProfile {

    public record Knockback(
            boolean custom,
            double friction,
            double horizontal,
            double vertical,
            double extraHorizontal,
            double extraVertical,
            double verticalLimit,
            boolean verticalOnlyOnGround,
            boolean useVictimVelocity
    ) {
    }

    public final CombatVersion version;
    public final double attackSpeed;
    public final int maxNoDamageTicks;
    public final boolean cooldownDamage;
    public final boolean disableSweep;
    public final boolean disableOffhand;
    public final boolean legacyDamage;
    public final Map<Material, Double> weaponDamage = new EnumMap<>(Material.class);
    public final Map<Material, Double> weaponSpeed = new EnumMap<>(Material.class);
    public final double fistDamage;
    public final double fistSpeed;
    public final double sharpnessPerLevel;
    public final double sharpnessBase;
    public final double strengthPerLevel;
    public final double weaknessPerLevel;
    public final double critMultiplier;
    public final boolean critWhileSprinting;
    public final boolean swordBlocking;
    public final double blockReduction;
    public final boolean legacyGoldenApple;
    public final List<PotionEffect> goldenAppleEffects;
    public final List<PotionEffect> enchantedAppleEffects;
    public final boolean legacyRegen;
    public final int regenInterval;
    public final int regenMinFood;
    public final float regenExhaustion;
    public final double maxReach;
    public final Knockback knockback;

    public CombatProfile(CombatVersion version, ConfigurationSection s) {
        this.version = version;
        attackSpeed = s.getDouble("attack-speed", version == CombatVersion.LEGACY ? 1024.0 : 4.0);
        maxNoDamageTicks = s.getInt("max-no-damage-ticks", 20);
        cooldownDamage = s.getBoolean("cooldown-damage", version == CombatVersion.MODERN);
        disableSweep = s.getBoolean("disable-sweep", version == CombatVersion.LEGACY);
        disableOffhand = s.getBoolean("disable-offhand", version == CombatVersion.LEGACY);
        legacyDamage = s.getBoolean("legacy-damage", version == CombatVersion.LEGACY);

        ConfigurationSection dmg = s.getConfigurationSection("weapon-damage");
        if (dmg != null) {
            for (String key : dmg.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m != null) {
                    weaponDamage.put(m, dmg.getDouble(key));
                }
            }
        }
        ConfigurationSection spd = s.getConfigurationSection("weapon-speed");
        if (spd != null) {
            for (String key : spd.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m != null) {
                    weaponSpeed.put(m, spd.getDouble(key));
                }
            }
        }
        fistDamage = s.getDouble("fist-damage", 1.0);
        fistSpeed = s.getDouble("fist-speed", 4.0);
        sharpnessPerLevel = s.getDouble("sharpness.per-level", version == CombatVersion.LEGACY ? 1.25 : 0.5);
        sharpnessBase = s.getDouble("sharpness.base", version == CombatVersion.LEGACY ? 0.0 : 0.5);
        strengthPerLevel = s.getDouble("strength-per-level", 3.0);
        weaknessPerLevel = s.getDouble("weakness-per-level", 4.0);
        critMultiplier = s.getDouble("critical.multiplier", 1.5);
        critWhileSprinting = s.getBoolean("critical.while-sprinting", version == CombatVersion.LEGACY);
        swordBlocking = s.getBoolean("sword-blocking.enabled", version == CombatVersion.LEGACY);
        blockReduction = s.getDouble("sword-blocking.reduction", 0.5);
        legacyGoldenApple = s.getBoolean("golden-apple.legacy", version == CombatVersion.LEGACY);
        goldenAppleEffects = Items.effects(s.getStringList("golden-apple.effects"));
        enchantedAppleEffects = Items.effects(s.getStringList("golden-apple.enchanted-effects"));
        legacyRegen = s.getBoolean("regen.legacy", version == CombatVersion.LEGACY);
        regenInterval = s.getInt("regen.interval-ticks", 80);
        regenMinFood = s.getInt("regen.min-food", 18);
        regenExhaustion = (float) s.getDouble("regen.exhaustion", 3.0);
        maxReach = s.getDouble("bot-hit.max-reach", 3.6);

        ConfigurationSection kb = s.getConfigurationSection("knockback");
        if (kb == null) {
            kb = s.createSection("knockback");
        }
        knockback = new Knockback(
                kb.getBoolean("custom", version == CombatVersion.LEGACY),
                Math.max(0.0001, kb.getDouble("friction", 2.0)),
                kb.getDouble("horizontal", 0.4),
                kb.getDouble("vertical", 0.4),
                kb.getDouble("extra-horizontal", 0.5),
                kb.getDouble("extra-vertical", 0.1),
                kb.getDouble("vertical-limit", 0.4),
                kb.getBoolean("vertical-only-on-ground", version == CombatVersion.MODERN),
                kb.getBoolean("use-victim-velocity", false)
        );
    }

    /** Базовый урон оружия по таблице версии (для ботов и legacy-урона). */
    public double baseDamage(Material weapon) {
        if (weapon == null || weapon.isAir()) {
            return fistDamage;
        }
        Double v = weaponDamage.get(weapon);
        if (v != null) {
            return v;
        }
        // нет в таблице (булава, копья...) — берём ванильные атрибуты предмета
        return version == CombatVersion.MODERN ? 1.0 + attribute(weapon, org.bukkit.attribute.Attribute.ATTACK_DAMAGE) : fistDamage;
    }

    private static double attribute(Material m, org.bukkit.attribute.Attribute attr) {
        try {
            var mods = m.getDefaultAttributeModifiers(org.bukkit.inventory.EquipmentSlot.HAND).get(attr);
            double sum = 0;
            for (var mod : mods) {
                if (mod.getOperation() == org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER) {
                    sum += mod.getAmount();
                }
            }
            return sum;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Скорость атаки оружия (ударов в секунду) — для кулдауна ботов в 1.9. */
    public double attackSpeedOf(Material weapon) {
        if (weapon == null || weapon.isAir()) {
            return fistSpeed;
        }
        Double v = weaponSpeed.get(weapon);
        if (v != null) {
            return v;
        }
        double speed = 4.0 + attribute(weapon, org.bukkit.attribute.Attribute.ATTACK_SPEED);
        return version == CombatVersion.MODERN && speed > 0 ? speed : fistSpeed;
    }
}
