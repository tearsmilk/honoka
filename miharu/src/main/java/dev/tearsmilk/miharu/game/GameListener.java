package dev.tearsmilk.miharu.game;

import com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Locale;

final class GameListener implements Listener {

    private final GameModule module;
    private final GameManager games;

    GameListener(GameModule module) {
        this.module = module;
        this.games = module.games();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        Game game = games.of(p);
        if (game == null) {
            return;
        }
        if (game.state() != Game.State.RUNNING) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
                p.teleport(game.spawnFor(p));
            }
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
            e.setCancelled(true);
            game.onVoid(p);
            return;
        }
        if (game.onDamage(p, e)) {
            return;
        }
        if (e.getFinalDamage() >= p.getHealth() + p.getAbsorptionAmount()) {
            // тотем бессмертия срабатывает как в ванили
            if (p.getInventory().getItemInMainHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING
                    || p.getInventory().getItemInOffHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING) {
                return;
            }
            e.setCancelled(true);
            game.onLethal(p, e);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        // страховка: если смерть всё же случилась внутри игры — ничего не выпадает
        if (games.inGame(e.getPlayer())) {
            e.setKeepInventory(true);
            e.getDrops().clear();
            e.setKeepLevel(true);
            e.setDroppedExp(0);
            e.deathMessage(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Game game = games.of(p);
        if (game == null || game.instance() == null) {
            return;
        }
        if (game.state() == Game.State.COUNTDOWN && module.config().getBoolean("freeze-on-countdown", true)
                && (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ())) {
            var to = e.getFrom().clone();
            to.setYaw(e.getTo().getYaw());
            to.setPitch(e.getTo().getPitch());
            e.setTo(to);
            return;
        }
        if (game.state() == Game.State.RUNNING) {
            game.onMove(p, e.getTo());
            if (games.of(p) != game || game.state() != Game.State.RUNNING) {
                return;
            }
        }
        if (e.getTo().getY() < game.instance().voidY()) {
            if (game.state() == Game.State.RUNNING) {
                game.onVoid(p);
            } else {
                p.teleport(game.spawnFor(p));
            }
        }
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Game game = games.of(e.getPlayer());
        if (game != null && game.state() == Game.State.RUNNING) {
            game.onSwing(e.getPlayer());
        }
    }

    @EventHandler
    public void onUnknownEntity(PlayerUseUnknownEntityEvent e) {
        Game game = games.of(e.getPlayer());
        if (game != null) {
            game.onUnknownEntity(e.getPlayer(), e.getEntityId(), e.isAttack());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p) {
            Game game = games.of(p);
            if (game != null && (!game.allowHunger() || game.state() != Game.State.RUNNING) && e.getFoodLevel() < p.getFoodLevel()) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (games.inGame(e.getPlayer()) && !module.config().getBoolean("allow-drop", false)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Game game = games.of(e.getPlayer());
        if (game != null && (game.state() != Game.State.RUNNING || !game.canBreak(e.getPlayer(), e.getBlock()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Game game = games.of(e.getPlayer());
        if (game != null && (game.state() != Game.State.RUNNING || !game.canPlace(e.getPlayer(), e.getBlockPlaced(), e.getBlockReplacedState()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(org.bukkit.event.player.PlayerBucketEmptyEvent e) {
        Game game = games.of(e.getPlayer());
        if (game != null && (game.state() != Game.State.RUNNING || !game.canPlace(e.getPlayer(), e.getBlock(), e.getBlock().getState()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(org.bukkit.event.player.PlayerBucketFillEvent e) {
        Game game = games.of(e.getPlayer());
        if (game != null && (game.state() != Game.State.RUNNING || !game.canBreak(e.getPlayer(), e.getBlock()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!games.busy(p) || p.hasPermission("miharu.bypass.commands")) {
            return;
        }
        String label = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (label.contains(":")) {
            label = label.substring(label.indexOf(':') + 1);
        }
        List<String> allowed = module.config().getStringList("allowed-commands");
        if (!allowed.contains(label)) {
            e.setCancelled(true);
            module.plugin().messages().send(p, "game.command-blocked");
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Game game = games.contextOf(p);
        if (game != null && game.instance() != null && !p.getWorld().equals(game.instance().world())) {
            games.leave(p, false);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        games.leave(e.getPlayer(), true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        games.restoreAfterCrash(e.getPlayer());
        module.visibility().update(e.getPlayer());
    }
}
