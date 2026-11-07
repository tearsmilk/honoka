package dev.tearsmilk.miharu.pvp;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Механики боя между настоящими игроками (боты обрабатываются в модуле bots через CombatMath). */
final class PvpListener implements Listener {

    private record AttackSnapshot(float cooldown, boolean sprinting, int tick) {
    }

    private final PvpModule module;
    private final CombatService combat;
    private final Map<UUID, AttackSnapshot> attacks = new HashMap<>();
    private final Map<UUID, Integer> lastRegen = new HashMap<>();

    PvpListener(PvpModule module) {
        this.module = module;
        this.combat = module.combat();
    }

    // --- снимок зарядки и спринта до того, как ваниль сбросит их ---
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent e) {
        Player p = e.getPlayer();
        attacks.put(p.getUniqueId(), new AttackSnapshot(p.getAttackCooldown(), p.isSprinting(), Bukkit.getCurrentTick()));
    }

    private AttackSnapshot snapshot(Player p) {
        AttackSnapshot s = attacks.get(p.getUniqueId());
        if (s == null || Bukkit.getCurrentTick() - s.tick() > 1) {
            return new AttackSnapshot(p.getAttackCooldown(), p.isSprinting(), Bukkit.getCurrentTick());
        }
        return s;
    }

    // --- урон ---
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) {
            applyBlocking(e);
            return;
        }
        CombatProfile ap = combat.profile(attacker);
        if (ap != null) {
            if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK && ap.disableSweep) {
                e.setCancelled(true);
                return;
            }
            if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && ap.legacyDamage) {
                boolean crit = CombatMath.canCrit(attacker, ap);
                e.setDamage(CombatMath.playerDamage(attacker, ap, 1f, crit));
                if (crit && attacker.isSprinting()) {
                    // ваниль не рисует крит в спринте — рисуем сами, как в 1.8
                    e.getEntity().getWorld().spawnParticle(Particle.CRIT, e.getEntity().getLocation().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.1);
                }
            }
        }
        applyBlocking(e);
    }

    private void applyBlocking(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player victim) {
            CombatProfile vp = combat.profile(victim);
            if (vp != null && combat.isSwordBlocking(victim, vp)) {
                e.setDamage(e.getDamage() * (1.0 - vp.blockReduction));
            }
        }
    }

    // --- своя отдача ---
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageKnockback(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker) || !(e.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        CombatProfile profile = victim instanceof Player vp ? combat.profile(vp) : combat.profile(attacker);
        if (profile == null || !profile.knockback.custom()) {
            return;
        }
        AttackSnapshot snap = snapshot(attacker);
        int extra = 0;
        boolean charged = !profile.cooldownDamage || snap.cooldown() > 0.9f;
        if (snap.sprinting() && charged) {
            extra++;
        }
        ItemStack hand = attacker.getInventory().getItemInMainHand();
        if (!hand.getType().isAir()) {
            extra += hand.getEnchantmentLevel(Enchantment.KNOCKBACK);
        }
        AttributeInstance resist = victim.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        double resistance = resist == null ? 0 : resist.getValue();
        var v = CombatMath.knockback(profile.knockback, victim.getVelocity(), victim.isOnGround(),
                attacker.getX(), attacker.getZ(), victim.getX(), victim.getZ(), attacker.getYaw(), extra);
        if (resistance > 0) {
            v.multiply(Math.max(0, 1 - resistance));
        }
        victim.setVelocity(v);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVanillaKnockback(EntityKnockbackEvent e) {
        if (e.getCause() != EntityKnockbackEvent.Cause.DAMAGE && e.getCause() != EntityKnockbackEvent.Cause.ENTITY_ATTACK
                && e.getCause() != EntityKnockbackEvent.Cause.SWEEP_ATTACK) {
            return;
        }
        // снаряды (снежки, яйца, стрелы) — оставляем ванильную отдачу: своя считается только для ударов
        if (e instanceof com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent kb && kb.getHitBy() instanceof org.bukkit.entity.Projectile) {
            return;
        }
        CombatProfile profile = e.getEntity() instanceof Player p ? combat.profile(p) : null;
        if (profile != null && profile.knockback.custom()) {
            e.setCancelled(true);
        }
    }

    // --- левая рука ---
    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        CombatProfile p = combat.profile(e.getPlayer());
        if (p != null && p.disableOffhand) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) {
            return;
        }
        CombatProfile p = combat.profile(player);
        if (p == null || !p.disableOffhand) {
            return;
        }
        if (e.getClick() == ClickType.SWAP_OFFHAND || (e.getClickedInventory() instanceof PlayerInventory && e.getSlot() == 40)) {
            e.setCancelled(true);
        }
    }

    // --- блок мечом и золотые яблоки ---
    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        CombatProfile p = combat.profile(e.getPlayer());
        if (p == null || !p.swordBlocking) {
            return;
        }
        ItemStack item = e.getPlayer().getInventory().getItem(e.getNewSlot());
        if (item != null && CombatMath.isSword(item.getType())) {
            e.getPlayer().getInventory().setItem(e.getNewSlot(), combat.prepare(item, p));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Material type = e.getItem().getType();
        if (CombatMath.isSword(type)) {
            e.setCancelled(true);
            return;
        }
        CombatProfile p = combat.profile(e.getPlayer());
        if (p == null || !p.legacyGoldenApple || dev.tearsmilk.miharu.core.util.Keys.has(e.getItem(), dev.tearsmilk.miharu.core.util.Keys.GOLDEN_HEAD)) {
            return;
        }
        if (type != Material.GOLDEN_APPLE && type != Material.ENCHANTED_GOLDEN_APPLE) {
            return;
        }
        Player player = e.getPlayer();
        var effects = type == Material.GOLDEN_APPLE ? p.goldenAppleEffects : p.enchantedAppleEffects;
        Bukkit.getScheduler().runTask(module.plugin(), () -> {
            for (PotionEffect eff : effects) {
                player.removePotionEffect(eff.getType());
            }
            if (type == Material.GOLDEN_APPLE) {
                player.removePotionEffect(PotionEffectType.REGENERATION);
                player.removePotionEffect(PotionEffectType.ABSORPTION);
            }
            effects.forEach(player::addPotionEffect);
        });
    }

    // --- регенерация 1.8 ---
    @EventHandler(ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent e) {
        if (e.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED || !(e.getEntity() instanceof Player p)) {
            return;
        }
        CombatProfile profile = combat.profile(p);
        if (profile != null && profile.legacyRegen) {
            e.setCancelled(true);
        }
    }

    void tickRegen() {
        int now = Bukkit.getCurrentTick();
        for (Player p : Bukkit.getOnlinePlayers()) {
            CombatProfile profile = combat.profile(p);
            if (profile == null || !profile.legacyRegen || p.isDead()
                    || Boolean.FALSE.equals(p.getWorld().getGameRuleValue(org.bukkit.GameRules.NATURAL_HEALTH_REGENERATION))) {
                continue;
            }
            Integer last = lastRegen.get(p.getUniqueId());
            if (last != null && now - last < profile.regenInterval) {
                continue;
            }
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            double maxHp = max == null ? 20 : max.getValue();
            if (p.getFoodLevel() >= profile.regenMinFood && p.getHealth() < maxHp && p.getHealth() > 0) {
                p.setHealth(Math.min(maxHp, p.getHealth() + 1));
                p.setExhaustion(p.getExhaustion() + profile.regenExhaustion);
                lastRegen.put(p.getUniqueId(), now);
            }
        }
    }

    // --- жизненный цикл ---
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        combat.refresh(e.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        combat.refresh(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        attacks.remove(id);
        lastRegen.remove(id);
        combat.reset(e.getPlayer());
        combat.forget(e.getPlayer());
    }
}
