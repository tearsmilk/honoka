package dev.tearsmilk.miharu.core.util;

import dev.tearsmilk.miharu.Miharu;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Предметы из конфига.
 * <pre>
 * material: DIAMOND_SWORD     # или "nexo:my_sword"
 * amount: 1
 * name: "&lt;aqua&gt;Меч"
 * lore: ["..."]
 * enchants: {sharpness: 1}
 * unbreakable: true
 * glow: false
 * model: "namespace:key"      # item_model
 * </pre>
 */
public final class Items {

    private Items() {
    }

    public static ItemStack parse(ConfigurationSection s, Map<String, ?> placeholders) {
        if (s == null) {
            return null;
        }
        ItemStack item = base(s.getString("material", "STONE"));
        item.setAmount(Math.max(1, s.getInt("amount", 1)));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (s.contains("name")) {
            meta.displayName(Text.item(s.getString("name"), placeholders));
        }
        if (s.contains("lore")) {
            meta.lore(Text.lore(s.getStringList("lore"), placeholders));
        }
        ConfigurationSection ench = s.getConfigurationSection("enchants");
        if (ench != null) {
            for (String key : ench.getKeys(false)) {
                Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
                if (e != null) {
                    meta.addEnchant(e, ench.getInt(key), true);
                }
            }
        }
        if (s.getBoolean("unbreakable", false)) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }
        if (s.getBoolean("glow", false)) {
            meta.setEnchantmentGlintOverride(true);
        }
        if (s.contains("model")) {
            NamespacedKey key = NamespacedKey.fromString(s.getString("model", ""));
            if (key != null) {
                meta.setItemModel(key);
            }
        }
        if (s.getBoolean("hide-attributes", false)) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        }
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack base(String material) {
        if (material != null && material.toLowerCase(Locale.ROOT).startsWith("nexo:")) {
            ItemStack nexo = Miharu.get().hooks().nexoItem(material.substring(5));
            if (nexo != null) {
                return nexo;
            }
            material = "BARRIER";
        }
        Material m = material == null ? null : Material.matchMaterial(material);
        return new ItemStack(m == null || m.isAir() ? Material.STONE : m);
    }

    /** "REGENERATION:1:100" → эффект (тип:уровень-1:тики). */
    public static PotionEffect effect(String spec) {
        String[] p = spec.split(":");
        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(p[0].toLowerCase(Locale.ROOT)));
        if (type == null) {
            return null;
        }
        int amp = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
        int dur = p.length > 2 ? Integer.parseInt(p[2].trim()) : 200;
        return new PotionEffect(type, dur, amp);
    }

    public static List<PotionEffect> effects(List<String> specs) {
        return specs.stream().map(Items::effect).filter(java.util.Objects::nonNull).toList();
    }
}
