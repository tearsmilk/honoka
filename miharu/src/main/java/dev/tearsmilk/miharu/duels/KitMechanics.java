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

    private Game gameIn(org.bukkit.World world) {
        GameModule games = module.plugin().module(GameModule.class);
        if (games == null) {
            return null;
        }
        for (Game g : games.games().games()) {
            if (g instanceof KitGame && g.instance() != null && g.instance().world().equals(world)) {
                return g;
            }
        }
        return null;
    }

    /** explosion-block-damage: true — всё, false — ничего, placed — только блоки игроков. */
    private void filterExplosion(org.bukkit.World world, java.util.List<org.bukkit.block.Block> blocks) {
        Game g = gameIn(world);
        if (!(g instanceof KitGame kg)) {
            return;
        }
        switch (kg.kitSpec().rules().explosions()) {
            case NONE -> blocks.clear();
            case PLACED -> {
                if (g instanceof DuelGame dg) {
                    dg.filterExplosion(blocks);
                } else {
                    blocks.clear();
                }
            }
            default -> {
                if (g instanceof DuelGame dg) {
                    dg.trackExplosion(blocks);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent e) {
        filterExplosion(e.getLocation().getWorld(), e.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) {
        filterExplosion(e.getBlock().getWorld(), e.blockList());
    }

    /** Урон от фаерболов и TNT по игрокам — множитель из modules/duels/config.yml. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || rules(p) == null) {
            return;
        }
        if (e.getDamager() instanceof org.bukkit.entity.Fireball) {
            e.setDamage(e.getDamage() * module.config().getDouble("fireball.damage-multiplier", 0.35));
        } else if (e.getDamager() instanceof org.bukkit.entity.TNTPrimed) {
            e.setDamage(e.getDamage() * module.config().getDouble("tnt.damage-multiplier", 0.5));
        }
    }

    private final java.util.Map<java.util.UUID, Long> fireballCooldown = new java.util.HashMap<>();

    /** ПКМ огненным зарядом — бросок фаербола (Fireball Fight). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onFireball(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.FIRE_CHARGE) {
            return;
        }
        Player p = e.getPlayer();
        KitRules r = rules(p);
        if (module.plugin().debug()) {
            module.plugin().getLogger().info("[debug] огненный заряд: " + p.getName() + " action=" + e.getAction() + " rules=" + (r == null ? "нет" : "fireball=" + r.fireball()));
        }
        if (r == null || !r.fireball()) {
            return;
        }
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = fireballCooldown.get(p.getUniqueId());
        if (last != null && now - last < module.config().getLong("fireball.cooldown-ticks", 10) * 50L) {
            return;
        }
        fireballCooldown.put(p.getUniqueId(), now);
        org.bukkit.entity.Fireball fb = p.launchProjectile(org.bukkit.entity.Fireball.class,
                p.getLocation().getDirection().multiply(module.config().getDouble("fireball.speed", 1.2)));
        fb.setYield((float) module.config().getDouble("fireball.yield", 2.0));
        fb.setIsIncendiary(false);
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GHAST_SHOOT, 0.8f, 1.2f);
    }

    /** Поставленный TNT сразу поджигается (Fireball Fight). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTnt(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.TNT) {
            return;
        }
        KitRules r = rules(e.getPlayer());
        if (r == null || !r.autoTnt()) {
            return;
        }
        org.bukkit.block.Block b = e.getBlockPlaced();
        Bukkit.getScheduler().runTask(module.plugin(), () -> {
            if (b.getType() != Material.TNT) {
                return;
            }
            b.setType(Material.AIR);
            org.bukkit.entity.TNTPrimed tnt = b.getWorld().spawn(b.getLocation().add(0.5, 0, 0.5), org.bukkit.entity.TNTPrimed.class);
            tnt.setFuseTicks(module.config().getInt("tnt.fuse-ticks", 40));
            tnt.setYield((float) module.config().getDouble("tnt.yield", 3.0));
            tnt.setSource(e.getPlayer());
        });
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
