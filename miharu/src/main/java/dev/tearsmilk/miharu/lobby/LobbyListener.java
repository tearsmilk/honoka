package dev.tearsmilk.miharu.lobby;

import dev.tearsmilk.miharu.core.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

final class LobbyListener implements Listener {

    private final LobbyModule module;

    LobbyListener(LobbyModule module) {
        this.module = module;
    }

    private boolean protect(Player p, String key) {
        return module.isLobby(p) && module.config().getBoolean("protect." + key, true) && !p.hasPermission("miharu.lobby.bypass");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(module.plugin(), () -> {
            if (p.isOnline() && module.isLobby(p)) {
                module.apply(p, module.config().getBoolean("teleport-on-join", true));
            }
        }, 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        module.forget(e.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(module.plugin(), () -> {
            if (p.isOnline() && module.isLobby(p)) {
                module.apply(p, false);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL) {
            return;
        }
        String action = Keys.get(e.getItem(), Keys.LOBBY_ITEM);
        if (action == null || !module.isLobby(e.getPlayer())) {
            return;
        }
        e.setCancelled(true);
        module.runAction(e.getPlayer(), action);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !protect(p, "inventory")) {
            return;
        }
        if (e.getClickedInventory() == p.getInventory() || e.getClick().isKeyboardClick()) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (protect(e.getPlayer(), "drop")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (protect(e.getPlayer(), "inventory")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && module.isLobby(p) && module.config().getBoolean("protect.damage", true)) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
                Location spawn = module.spawn();
                p.teleport(spawn != null ? spawn : p.getWorld().getSpawnLocation());
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && module.isLobby(p) && module.config().getBoolean("protect.hunger", true)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        int y = module.config().getInt("protect.void-y", -64);
        if (e.getTo().getY() < y && module.isLobby(e.getPlayer())) {
            Location spawn = module.spawn();
            e.getPlayer().teleport(spawn != null ? spawn : e.getPlayer().getWorld().getSpawnLocation());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (protect(e.getPlayer(), "build")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (protect(e.getPlayer(), "build")) {
            e.setCancelled(true);
        }
    }
}
