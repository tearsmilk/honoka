package dev.tearsmilk.miharu.core.kit;

import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.pvp.CombatProfile;
import dev.tearsmilk.miharu.pvp.CombatService;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Набор вещей: armor, items (слот → предмет), fill (заполнить пустые слоты), effects.
 * team-color: true — кожаная броня и WHITE_* блоки красятся в цвет команды (Bridge).
 * Бот берёт ту же броню и оружие из bot-weapon (или первый меч/топор).
 */
public final class Kit {

    /** Команда для окраски: красные и синие. */
    public enum Team {
        RED(Color.fromRGB(0xD43B3B), "RED_"),
        BLUE(Color.fromRGB(0x3B6FD4), "BLUE_");

        final Color color;
        final String prefix;

        Team(Color color, String prefix) {
            this.color = color;
            this.prefix = prefix;
        }
    }

    private final String id;
    private final Map<EquipmentSlot, ItemStack> armor = new LinkedHashMap<>();
    private final Map<Integer, ItemStack> items = new LinkedHashMap<>();
    private final ItemStack fill;
    private final List<PotionEffect> effects;
    private final ItemStack botWeapon;
    private final boolean teamColor;

    public Kit(String id, ConfigurationSection s) {
        this.id = id;
        ConfigurationSection a = s.getConfigurationSection("armor");
        if (a != null) {
            put(a, "helmet", EquipmentSlot.HEAD);
            put(a, "chestplate", EquipmentSlot.CHEST);
            put(a, "leggings", EquipmentSlot.LEGS);
            put(a, "boots", EquipmentSlot.FEET);
            put(a, "offhand", EquipmentSlot.OFF_HAND);
        }
        ConfigurationSection it = s.getConfigurationSection("items");
        if (it != null) {
            for (String key : it.getKeys(false)) {
                try {
                    items.put(Integer.parseInt(key), Items.parse(it.getConfigurationSection(key), Map.of()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        fill = s.isConfigurationSection("fill") ? Items.parse(s.getConfigurationSection("fill"), Map.of()) : null;
        effects = Items.effects(s.getStringList("effects"));
        teamColor = s.getBoolean("team-color", false);
        ConfigurationSection bw = s.getConfigurationSection("bot-weapon");
        if (bw != null) {
            botWeapon = Items.parse(bw, Map.of());
        } else {
            ItemStack found = null;
            for (ItemStack i : items.values()) {
                String n = i.getType().name();
                if (n.endsWith("_SWORD") || n.endsWith("_AXE")) {
                    found = i;
                    break;
                }
            }
            botWeapon = found != null ? found : items.get(0);
        }
    }

    private void put(ConfigurationSection a, String key, EquipmentSlot slot) {
        ConfigurationSection s = a.getConfigurationSection(key);
        if (s != null) {
            armor.put(slot, Items.parse(s, Map.of()));
        }
    }

    public String id() {
        return id;
    }

    public void apply(Player p, CombatService combat, CombatProfile profile) {
        apply(p, combat, profile, null);
    }

    public void apply(Player p, CombatService combat, CombatProfile profile, @Nullable Team team) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        armor.forEach((slot, item) -> inv.setItem(slot, paint(item.clone(), team)));
        items.forEach((slot, item) -> inv.setItem(slot, combat.prepare(paint(item.clone(), team), profile)));
        if (fill != null) {
            for (int i = 0; i < 36; i++) {
                ItemStack cur = inv.getItem(i);
                if (cur == null || cur.getType().isAir()) {
                    inv.setItem(i, fill.clone());
                }
            }
        }
        inv.setHeldItemSlot(0);
        effects.forEach(p::addPotionEffect);
        p.updateInventory();
    }

    private ItemStack paint(ItemStack item, @Nullable Team team) {
        if (!teamColor || team == null) {
            return item;
        }
        if (item.getItemMeta() instanceof LeatherArmorMeta lm) {
            lm.setColor(team.color);
            item.setItemMeta(lm);
            return item;
        }
        String n = item.getType().name();
        if (n.startsWith("WHITE_")) {
            Material m = Material.matchMaterial(team.prefix + n.substring(6));
            if (m != null) {
                return item.withType(m);
            }
        }
        return item;
    }

    public Map<EquipmentSlot, ItemStack> armor() {
        return armor;
    }

    public ItemStack weapon() {
        return botWeapon;
    }

    public Material weaponType() {
        return botWeapon == null ? Material.AIR : botWeapon.getType();
    }

    public int weaponSharpness() {
        return botWeapon == null ? 0 : botWeapon.getEnchantmentLevel(Enchantment.SHARPNESS);
    }

    public int weaponKnockback() {
        return botWeapon == null ? 0 : botWeapon.getEnchantmentLevel(Enchantment.KNOCKBACK);
    }

    /** Очки брони бота по ванильной таблице. */
    public double armorPoints() {
        double sum = 0;
        for (ItemStack i : armor.values()) {
            sum += armorValue(i.getType());
        }
        return sum;
    }

    public double toughness() {
        double sum = 0;
        for (ItemStack i : armor.values()) {
            String n = i.getType().name();
            if (n.startsWith("DIAMOND_")) {
                sum += 2;
            } else if (n.startsWith("NETHERITE_")) {
                sum += 3;
            }
        }
        return sum;
    }

    /** Суммарный уровень «Защиты» на броне — бот режет урон как ваниль (4% за уровень, максимум 80%). */
    public int protectionLevels() {
        int sum = 0;
        for (ItemStack i : armor.values()) {
            sum += i.getEnchantmentLevel(Enchantment.PROTECTION);
        }
        return sum;
    }

    private static double armorValue(Material m) {
        String n = m.name().toUpperCase(Locale.ROOT);
        int idx = n.endsWith("_HELMET") ? 0 : n.endsWith("_CHESTPLATE") ? 1 : n.endsWith("_LEGGINGS") ? 2 : n.endsWith("_BOOTS") ? 3 : -1;
        if (idx < 0) {
            return 0;
        }
        int[] v;
        if (n.startsWith("LEATHER_")) {
            v = new int[]{1, 3, 2, 1};
        } else if (n.startsWith("GOLDEN_")) {
            v = new int[]{2, 5, 3, 1};
        } else if (n.startsWith("CHAINMAIL_")) {
            v = new int[]{2, 5, 4, 1};
        } else if (n.startsWith("IRON_")) {
            v = new int[]{2, 6, 5, 2};
        } else if (n.startsWith("DIAMOND_") || n.startsWith("NETHERITE_")) {
            v = new int[]{3, 8, 6, 3};
        } else if (n.startsWith("TURTLE_")) {
            v = new int[]{2, 0, 0, 0};
        } else {
            return 0;
        }
        return v[idx];
    }
}
