package dev.tearsmilk.miharu.core.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Метки предметов Miharu (PersistentDataContainer). */
public final class Keys {

    public static final NamespacedKey GOLDEN_HEAD = new NamespacedKey("miharu", "golden_head");
    public static final NamespacedKey LOBBY_ITEM = new NamespacedKey("miharu", "lobby_item");

    private Keys() {
    }

    public static boolean has(ItemStack item, NamespacedKey key) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key);
    }

    public static String get(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }
}
