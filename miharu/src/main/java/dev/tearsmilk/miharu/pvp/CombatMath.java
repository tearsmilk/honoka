package dev.tearsmilk.miharu.pvp;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/** Формулы урона и отдачи. Общие для игроков и пакетных ботов. */
public final class CombatMath {

    private CombatMath() {
    }

    /** Может ли игрок сейчас нанести крит (падает, не на земле, не в воде, не слепой, не на лестнице). */
    public static boolean canCrit(Player p, CombatProfile profile) {
        if (p.isOnGround() || p.getFallDistance() <= 0 || p.isInWater() || p.isClimbing() || p.isInsideVehicle()
                || p.hasPotionEffect(PotionEffectType.BLINDNESS) || p.isGliding()) {
            return false;
        }
        return profile.critWhileSprinting || !p.isSprinting();
    }

    /**
     * Урон игрока по цели.
     * 1.8: таблица оружия + острота + сила, крит x1.5 (можно в спринте).
     * 1.9: атрибут урона (уже с силой) * зарядка, острота * зарядка, крит только при полном ударе.
     */
    /** Свап атрибутов (1.8): сменил слот за 1–2 тика до удара — база урона от прежнего предмета. null — нет свапа. */
    public static java.util.function.Function<Player, org.bukkit.Material> swapBase = p -> null;

    public static double playerDamage(Player attacker, CombatProfile profile, float cooldown, boolean crit) {
        ItemStack hand = attacker.getInventory().getItemInMainHand();
        int sharp = hand.getType().isAir() ? 0 : hand.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (profile.legacyDamage) {
            org.bukkit.Material swapped = swapBase.apply(attacker);
            double dmg = profile.baseDamage(swapped != null ? swapped : hand.getType());
            dmg += effectBonus(attacker, profile);
            if (crit) {
                dmg *= profile.critMultiplier;
            }
            if (sharp > 0) {
                dmg += sharp * profile.sharpnessPerLevel + profile.sharpnessBase;
            }
            return Math.max(0, dmg);
        }
        AttributeInstance attr = attacker.getAttribute(Attribute.ATTACK_DAMAGE);
        double base = attr != null ? attr.getValue() : profile.baseDamage(hand.getType());
        float c = profile.cooldownDamage ? cooldown : 1f;
        double dmg = base * (0.2 + c * c * 0.8);
        if (crit && c > 0.9f) {
            dmg *= profile.critMultiplier;
        }
        if (sharp > 0) {
            dmg += (sharp * profile.sharpnessPerLevel + profile.sharpnessBase) * c;
        }
        return Math.max(0, dmg);
    }

    /** Урон бота: оружие по таблице, сила зарядки (1.9), крит. */
    public static double botDamage(Material weapon, int sharpness, CombatProfile profile, double charge, boolean crit) {
        double base = profile.baseDamage(weapon);
        double c = profile.cooldownDamage ? Math.max(0, Math.min(1, charge)) : 1.0;
        double dmg = profile.cooldownDamage ? base * (0.2 + c * c * 0.8) : base;
        if (crit && (!profile.cooldownDamage || c > 0.9)) {
            dmg *= profile.critMultiplier;
        }
        if (sharpness > 0) {
            dmg += (sharpness * profile.sharpnessPerLevel + profile.sharpnessBase) * c;
        }
        return dmg;
    }

    private static double effectBonus(LivingEntity e, CombatProfile profile) {
        double bonus = 0;
        PotionEffect str = e.getPotionEffect(PotionEffectType.STRENGTH);
        if (str != null) {
            bonus += (str.getAmplifier() + 1) * profile.strengthPerLevel;
        }
        PotionEffect weak = e.getPotionEffect(PotionEffectType.WEAKNESS);
        if (weak != null) {
            bonus -= (weak.getAmplifier() + 1) * profile.weaknessPerLevel;
        }
        return bonus;
    }

    /** Ванильная формула брони (без чар защиты). */
    public static double applyArmor(double damage, double armor, double toughness) {
        double f = 2.0 + toughness / 4.0;
        double eff = Math.min(20.0, Math.max(armor / 5.0, armor - damage / f));
        return damage * (1.0 - eff / 25.0);
    }

    /**
     * Итоговая скорость жертвы после удара.
     *
     * @param victimVelocity текущая скорость жертвы
     * @param victimOnGround стоит ли жертва на земле
     * @param ax,az позиция атакующего
     * @param vx,vz позиция жертвы
     * @param attackerYaw   поворот атакующего (для доп. отдачи от спринта)
     * @param extra         уровень доп. отдачи: 1 за спринт + уровень чар «Отдача»
     */
    public static Vector knockback(CombatProfile.Knockback kb, Vector victimVelocity, boolean victimOnGround,
                                   double ax, double az, double vx, double vz, float attackerYaw, int extra) {
        Vector v = kb.useVictimVelocity() ? victimVelocity.clone() : new Vector(0, victimOnGround ? 0 : victimVelocity.getY(), 0);
        double dx = ax - vx;
        double dz = az - vz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-4) {
            dx = (Math.random() - Math.random()) * 0.01;
            dz = (Math.random() - Math.random()) * 0.01;
            dist = Math.sqrt(dx * dx + dz * dz);
        }
        v.setX(v.getX() / kb.friction() - dx / dist * kb.horizontal());
        v.setZ(v.getZ() / kb.friction() - dz / dist * kb.horizontal());
        if (!kb.verticalOnlyOnGround() || victimOnGround) {
            v.setY(v.getY() / kb.friction() + kb.vertical());
            if (v.getY() > kb.verticalLimit()) {
                v.setY(kb.verticalLimit());
            }
        }
        if (extra > 0) {
            double rad = Math.toRadians(attackerYaw);
            v.setX(v.getX() - Math.sin(rad) * extra * kb.extraHorizontal());
            v.setZ(v.getZ() + Math.cos(rad) * extra * kb.extraHorizontal());
            v.setY(v.getY() + kb.extraVertical());
        }
        return v;
    }

    public static boolean isSword(Material m) {
        return m != null && Tag.ITEMS_SWORDS.isTagged(m);
    }
}
