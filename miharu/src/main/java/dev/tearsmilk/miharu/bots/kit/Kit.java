package dev.tearsmilk.miharu.bots.kit;

import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.pvp.CombatProfile;
import dev.tearsmilk.miharu.pvp.CombatService;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Набор вещей (modules/bots/kits.yml). Бот получает ту же броню и оружие из слота 0. */
public final class Kit {

    private final String id;
    private final Map<EquipmentSlot, ItemStack> armor = new LinkedHashMap<>();
    private final Map<Integer, ItemStack> items = new LinkedHashMap<>();
    private final List<PotionEffect> effects;
    private final ItemStack botWeapon;

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
        effects = Items.effects(s.getStringList("effects"));
        ConfigurationSection bw = s.getConfigurationSection("bot-weapon");
        botWeapon = bw != null ? Items.parse(bw, Map.of()) : items.get(0);
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
        PlayerInventory inv = p.getInventory();
        inv.clear();
        armor.forEach((slot, item) -> inv.setItem(slot, item.clone()));
        items.forEach((slot, item) -> inv.setItem(slot, combat.prepare(item.clone(), profile)));
        inv.setHeldItemSlot(0);
        effects.forEach(p::addPotionEffect);
        p.updateInventory();
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
        for (Map.Entry<EquipmentSlot, ItemStack> e : armor.entrySet()) {
            sum += armorValue(e.getValue().getType());
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
