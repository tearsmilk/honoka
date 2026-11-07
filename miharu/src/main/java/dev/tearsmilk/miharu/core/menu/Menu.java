package dev.tearsmilk.miharu.core.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Простое меню-сундук: предметы не вытащить, клик запускает действие. */
public final class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    public Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, Math.max(1, Math.min(6, rows)) * 9, title);
    }

    public void set(int slot, ItemStack item, Consumer<Player> action) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    public void fill(ItemStack item) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, item);
            }
        }
    }

    public void click(Player p, int slot) {
        Consumer<Player> a = actions.get(slot);
        if (a != null) {
            a.accept(p);
        }
    }

    public void open(Player p) {
        p.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
