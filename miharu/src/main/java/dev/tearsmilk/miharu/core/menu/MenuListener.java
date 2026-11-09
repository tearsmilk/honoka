package dev.tearsmilk.miharu.core.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Клики по меню Miharu: предметы не вытащить, слот запускает действие. */
public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder(false) instanceof Menu menu) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p && e.getClickedInventory() == e.getInventory()) {
                menu.click(p, e.getRawSlot());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof Menu) {
            e.setCancelled(true);
        }
    }
}
