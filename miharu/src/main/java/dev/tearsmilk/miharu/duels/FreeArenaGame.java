package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.arena.Arena;
import dev.tearsmilk.miharu.core.kit.Kit;
import dev.tearsmilk.miharu.core.kit.KitGame;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.Game;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatService;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Свободная арена (Sword / SpearMace / ElytraMace): одна точка spawn, игра не начинается и не кончается.
 * Заходят со своим китом через NPC, умер — обратно в лобби. Ломаются только блоки игроков.
 * Выйти — /lobby, но не в бою: пока не прошло combat-seconds без урона, сверху боссбар «Ты в PvP».
 * Мир не удаляется и не чистится, пока сервер работает.
 */
public final class FreeArenaGame extends Game implements KitGame, BuildGame {

    public static final String MODE = "freearena";

    private final FreeArenas arenas;
    private final String type;
    private final KitSpec spec;
    private final CombatService combat;
    private final Map<UUID, Kit> kits = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, UUID> lastHit = new HashMap<>();
    private final Map<UUID, Long> lastHitAt = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Map<UUID, Integer> streak = new HashMap<>();
    private final BuildTracker build = new BuildTracker();

    FreeArenaGame(FreeArenas arenas, GameModule games, Arena arena, String type, KitSpec spec, CombatService combat) {
        super(games, MODE, arena);
        this.arenas = arenas;
        this.type = type;
        this.spec = spec;
        this.combat = combat;
    }

    String type() {
        return type;
    }

    @Override
    public boolean keepAlive() {
        return true;
    }

    @Override
    protected int countdownSeconds() {
        return 0;
    }

    @Override
    public boolean canLateJoin() {
        return state == State.RUNNING || state == State.COUNTDOWN;
    }

    @Override
    public KitSpec kitSpec() {
        return spec;
    }

    @Override
    public CombatVersion combatVersion() {
        return spec.version();
    }

    @Override
    public String displayName() {
        return Text.plain(Text.mm(spec.display()));
    }

    @Override
    protected Location spawnFor(Player p) {
        return instance.spawn("spawn", "p1");
    }

    @Override
    protected boolean allowHunger() {
        return false;
    }

    @Override
    protected void onSetup() {
        instance.world().setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, true);
        instance.world().setGameRule(GameRules.FALL_DAMAGE, true);
        for (Player p : List.copyOf(players)) {
            enter(p);
        }
    }

    @Override
    protected void onLateJoin(Player p) {
        enter(p);
    }

    private void enter(Player p) {
        Kit k = arenas.takeKit(p);
        if (k != null) {
            kits.put(p.getUniqueId(), k);
        }
        equip(p);
        plugin.messages().send(p, "free-arena.joined", "arena", spec.display(), "seconds", arenas.combatSeconds());
        for (Player o : players) {
            if (o != p) {
                plugin.messages().send(o, "free-arena.player-joined", "player", p.getName());
            }
        }
    }

    /** Свой кит заново: здоровье, предметы, версия PvP арены. */
    private void equip(Player p) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        p.setGameMode(GameMode.SURVIVAL);
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
        if (arenas.vanilla(type)) {
            combat.force(p, (CombatVersion) null); // ванильное PvP 1.21.11 — без правок плагина
        } else {
            combat.force(p, spec.profile());
        }
        Kit k = kits.get(p.getUniqueId());
        if (k != null) {
            k.apply(p, combat, spec.profile(), null);
        }
    }

    // --- бой ---

    @Override
    public int combatSeconds(Player p) {
        long left = combatUntil.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    private void tag(Player p) {
        combatUntil.put(p.getUniqueId(), System.currentTimeMillis() + arenas.combatSeconds() * 1000L);
    }

    @Override
    protected boolean onDamage(Player p, EntityDamageEvent e) {
        tag(p); // любой урон — «в бою»
        if (!spec.rules().damage() && e.getCause() != EntityDamageEvent.DamageCause.VOID) {
            // без урона (KnockbackFFA): удары и стрелы только откидывают, остальной урон отменён
            if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK || e.getCause() == EntityDamageEvent.DamageCause.PROJECTILE) {
                e.setDamage(0);
            } else {
                e.setCancelled(true);
            }
        }
        if (e instanceof EntityDamageByEntityEvent ed) {
            Player attacker = ed.getDamager() instanceof Player dp ? dp
                    : ed.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player sp ? sp
                    : ed.getDamager() instanceof TNTPrimed tnt && tnt.getSource() instanceof Player tp ? tp : null;
            if (attacker != null && attacker != p) {
                lastHit.put(p.getUniqueId(), attacker.getUniqueId());
                lastHitAt.put(p.getUniqueId(), System.currentTimeMillis());
                tag(attacker);
            }
        }
        return false;
    }

    @Override
    protected void onLethal(Player p, EntityDamageEvent e) {
        died(p);
    }

    @Override
    protected void onVoid(Player p) {
        died(p);
    }

    private void died(Player p) {
        UUID kid = lastHit.remove(p.getUniqueId());
        long at = lastHitAt.getOrDefault(p.getUniqueId(), 0L);
        Player killer = kid == null || System.currentTimeMillis() - at > 15_000 ? null : Bukkit.getPlayer(kid);
        deaths.merge(p.getUniqueId(), 1, Integer::sum);
        streak.put(p.getUniqueId(), 0);
        if (killer != null && players.contains(killer) && killer != p) {
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
            int s = streak.merge(killer.getUniqueId(), 1, Integer::sum);
            broadcast("free-arena.killed", "player", p.getName(), "killer", killer.getName(), "streak", s);
            killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
        } else {
            broadcast("free-arena.died", "player", p.getName());
        }
        combatUntil.remove(p.getUniqueId());
        if (arenas.respawn(type)) {
            // арены с готовым китом (KnockbackFFA, BowFFA, SwordFFA): сразу снова на spawn
            p.teleport(spawnFor(p));
            equip(p);
            return;
        }
        // умер — обратно в лобби (арена живёт дальше); заново — через NPC
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.getInventory().clear();
        plugin.messages().send(p, "free-arena.you-died", "arena", spec.display());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (players.contains(p)) {
                module.games().leave(p, false);
            }
        });
    }

    @Override
    protected void onTick() {
        if (runningTicks % 5 != 0) {
            return;
        }
        int total = Math.max(1, arenas.combatSeconds());
        for (Player p : players) {
            int left = combatSeconds(p);
            BossBar bar = bars.get(p.getUniqueId());
            if (left <= 0) {
                if (bar != null) {
                    p.hideBossBar(bar);
                    bars.remove(p.getUniqueId());
                    plugin.messages().actionBar(p, "free-arena.out-of-combat");
                }
                continue;
            }
            var title = Text.mm(plugin.messages().raw("free-arena.bossbar"), Map.of("seconds", left));
            float prog = Math.max(0f, Math.min(1f, left / (float) total));
            if (bar == null) {
                bar = BossBar.bossBar(title, prog, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
                bars.put(p.getUniqueId(), bar);
                p.showBossBar(bar);
            } else {
                bar.name(title);
                bar.progress(prog);
            }
        }
    }

    @Override
    protected void onPlayerLeave(Player p, boolean quit) {
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) {
            p.hideBossBar(bar);
        }
        kits.remove(p.getUniqueId());
        combatUntil.remove(p.getUniqueId());
        for (Player o : players) {
            if (o != p) {
                plugin.messages().send(o, "free-arena.player-left", "player", p.getName());
            }
        }
    }

    // --- стройка: арену не ломать, только поставленное игроками ---

    private boolean nearSpawn(Block b) {
        Location s = spawnFor(null);
        int r = arenas.spawnProtection();
        return r > 0 && Math.abs(s.getBlockX() - b.getX()) <= r && Math.abs(s.getBlockZ() - b.getZ()) <= r && Math.abs(s.getBlockY() - b.getY()) <= r;
    }

    @Override
    protected boolean canPlace(Player p, Block block, BlockState replaced) {
        if (nearSpawn(block)) {
            return false;
        }
        build.place(block, replaced);
        return true;
    }

    @Override
    protected boolean canBreak(Player p, Block block) {
        if (!build.isPlaced(block)) {
            return false;
        }
        build.broke(block);
        return true;
    }

    @Override
    public void filterExplosion(List<Block> blocks) {
        blocks.removeIf(bl -> !build.isPlaced(bl));
        blocks.forEach(build::broke);
    }

    @Override
    public void trackExplosion(List<Block> blocks) {
        filterExplosion(blocks);
    }

    private void broadcast(String key, Object... kv) {
        for (Player p : players) {
            plugin.messages().send(p, key, kv);
        }
    }

    @Override
    public Map<String, Object> placeholders(Player viewer) {
        Map<String, Object> map = super.placeholders(viewer);
        map.put("kit", spec.display());
        map.put("kills", kills.getOrDefault(viewer.getUniqueId(), 0));
        map.put("deaths", deaths.getOrDefault(viewer.getUniqueId(), 0));
        map.put("streak", streak.getOrDefault(viewer.getUniqueId(), 0));
        map.put("players", players.size());
        int left = combatSeconds(viewer);
        map.put("combat", left > 0 ? plugin.messages().raw("free-arena.status-combat").replace("{seconds}", String.valueOf(left))
                : plugin.messages().raw("free-arena.status-free"));
        map.put("player_hp", Text.num(Math.round(viewer.getHealth() * 10) / 10.0));
        return map;
    }
}
