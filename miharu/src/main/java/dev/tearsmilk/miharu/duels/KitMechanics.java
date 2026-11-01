package dev.tearsmilk.miharu.duels;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import dev.tearsmilk.miharu.core.kit.KitGame;
import dev.tearsmilk.miharu.core.kit.KitRules;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Keys;
import dev.tearsmilk.miharu.game.Game;
import dev.tearsmilk.miharu.game.GameModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;

/** Механики китов 1.8: суп, золотые головы, жемчуг без кулдауна, отдача удочки, бесконечные блоки. */
final class KitMechanics implements Listener {

    private final DuelsModule module;

    KitMechanics(DuelsModule module) {
        this.module = module;
    }

    private KitRules rules(Player p) {
        GameModule games = module.plugin().module(GameModule.class);
        Game g = games == null ? null : games.games().of(p);
        return g instanceof KitGame kg && g.state() == Game.State.RUNNING ? kg.kitSpec().rules() : null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSoup(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.MUSHROOM_STEW) {
            return;
        }
        Player p = e.getPlayer();
        KitRules r = rules(p);
        if (r == null || !r.soup()) {
            return;
        }
        var maxAttr = p.getAttribute(Attribute.MAX_HEALTH);
        double max = maxAttr == null ? 20 : maxAttr.getValue();
        if (p.getHealth() >= max && p.getFoodLevel() >= 20) {
            return;
        }
        e.setCancelled(true);
        if (p.getHealth() < max) {
            p.setHealth(Math.min(max, p.getHealth() + r.soupHeal()));
        } else {
            p.setFoodLevel(Math.min(20, p.getFoodLevel() + 6));
            p.setSaturation(Math.min(20, p.getSaturation() + 7.2f));
        }
        p.getInventory().setItemInMainHand(new ItemStack(Material.BOWL));
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.7f, 1.2f);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDropBowl(PlayerDropItemEvent e) {
        KitRules r = rules(e.getPlayer());
        if (r != null && r.soup() && e.getItemDrop().getItemStack().getType() == Material.BOWL) {
            e.setCancelled(false);
            e.getItemDrop().remove();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGoldenHead(PlayerItemConsumeEvent e) {
        if (!Keys.has(e.getItem(), Keys.GOLDEN_HEAD)) {
            return;
        }
        Player p = e.getPlayer();
        List<PotionEffect> effects = Items.effects(module.config().getStringList("golden-head.effects"));
        Bukkit.getScheduler().runTask(module.plugin(), () -> {
            p.removePotionEffect(PotionEffectType.REGENERATION);
            p.removePotionEffect(PotionEffectType.ABSORPTION);
            effects.forEach(p::addPotionEffect);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onPearl(PlayerLaunchProjectileEvent e) {
        if (!(e.getProjectile() instanceof EnderPearl)) {
            return;
        }
        Player p = e.getPlayer();
        KitRules r = rules(p);
        if (r == null) {
            return;
        }
        int cd = Math.max(0, r.pearlCooldownTicks());
        Bukkit.getScheduler().runTask(module.plugin(), () -> p.setCooldown(Material.ENDER_PEARL, cd));
    }

    @EventHandler(ignoreCancelled = true)
    public void onRod(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof FishHook hook) || !(hook.getShooter() instanceof Player shooter) || !(e.getHitEntity() instanceof Player victim)) {
            return;
        }
        KitRules r = rules(shooter);
        if (r == null || !r.rodKnockback() || rules(victim) == null || victim == shooter) {
            return;
        }
        if (victim.getNoDamageTicks() > victim.getMaximumNoDamageTicks() / 2) {
            return;
        }
        Vector dir = hook.getVelocity().setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            dir = victim.getLocation().toVector().subtract(shooter.getLocation().toVector()).setY(0);
        }
        dir.normalize().multiply(module.config().getDouble("rod.horizontal", 0.4));
        dir.setY(module.config().getDouble("rod.vertical", 0.36));
        victim.playHurtAnimation((float) Math.toDegrees(Math.atan2(-dir.getZ(), -dir.getX())) - victim.getYaw());
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        victim.setVelocity(dir);
        victim.setNoDamageTicks(victim.getMaximumNoDamageTicks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        KitRules r = rules(e.getPlayer());
        if (r == null || !r.infiniteBlocks()) {
            return;
        }
        ItemStack copy = e.getItemInHand().clone();
        EquipmentSlot hand = e.getHand();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(module.plugin(), () -> p.getInventory().setItem(hand, copy));
    }
}
