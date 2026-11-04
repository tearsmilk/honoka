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
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * GunGame: за 3–4 убийства (kills-per-level-min…max) — следующий уровень и новый комплект (levels в modules/duels/minigames/gungame.yml).
 * За смерть уровень не понижается. Убийство на последнем уровне — победа. PvP — как в ките (1.8).
 */
public final class GunGameGame extends Game implements KitGame {

    public static final String MODE = "gungame";

    private final DuelsModule duels;
    private final KitSpec spec;
    private final CombatService combat;
    private final List<Player> order;
    private final List<Kit> levels = new ArrayList<>();
    private final List<String> levelNames = new ArrayList<>();
    private final Map<UUID, Integer> level = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    /** Убийств на текущем уровне и сколько нужно для следующего (случайно kills-per-level-min…max). */
    private final Map<UUID, Integer> progress = new HashMap<>();
    private final Map<UUID, Integer> needed = new HashMap<>();
    private final Map<UUID, UUID> lastHit = new HashMap<>();
    private final Map<UUID, Long> lastHitAt = new HashMap<>();
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private final ConfigurationSection cfg;
    private boolean finished;
    private String winnerName = "-";

    GunGameGame(DuelsModule duels, GameModule games, Arena arena, KitSpec spec, CombatService combat, List<Player> players) {
        super(games, MODE, arena);
        this.duels = duels;
        this.spec = spec;
        this.combat = combat;
        this.order = new ArrayList<>(players);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(duels.folder(), "minigames/gungame.yml"));
        ConfigurationSection g = y.getConfigurationSection("gungame");
        this.cfg = g != null ? g : y.createSection("gungame");
        ConfigurationSection lv = y.getConfigurationSection("levels");
        if (lv != null) {
            List<String> keys = new ArrayList<>(lv.getKeys(false));
            keys.sort((a, b) -> Integer.compare(num(a), num(b)));
            for (String k : keys) {
                ConfigurationSection s = lv.getConfigurationSection(k);
                if (s != null) {
                    levels.add(new Kit("gungame-" + k, s));
                    levelNames.add(s.getString("name", ""));
                }
            }
        }
        for (Player p : order) {
            level.put(p.getUniqueId(), 1);
            rollNeeded(p.getUniqueId(), 1);
        }
    }

    private void rollNeeded(UUID id, int lvl) {
        int n;
        if (lvl >= maxLevel()) {
            n = Math.max(1, cfg.getInt("final-kills", 1));
        } else {
            int min = Math.max(1, cfg.getInt("kills-per-level-min", 3));
            int max = Math.max(min, cfg.getInt("kills-per-level-max", 4));
            n = ThreadLocalRandom.current().nextInt(min, max + 1);
        }
        needed.put(id, n);
        progress.put(id, 0);
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private int maxLevel() {
        return Math.max(1, levels.size());
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
        int n = Math.max(0, order.indexOf(p)) + 1;
        return instance.spawn("p" + n, "p1");
    }

    /** Случайная точка появления после смерти. */
    private Location randomSpawn() {
        List<Location> list = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            if (instance.has("p" + i)) {
                list.add(instance.spawn("p" + i));
            }
        }
        return list.isEmpty() ? instance.spawn("p1") : list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    @Override
    protected boolean allowHunger() {
        return false;
    }

    @Override
    protected void onSetup() {
        instance.world().setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, cfg.getBoolean("natural-regen", true));
        instance.world().setGameRule(GameRules.FALL_DAMAGE, true);
        for (Player p : order) {
            giveLevel(p, false);
        }
    }

    private void giveLevel(Player p, boolean announce) {
        int lvl = level.getOrDefault(p.getUniqueId(), 1);
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.setItemOnCursor(null);
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        p.setGameMode(GameMode.SURVIVAL);
        combat.force(p, spec.profile());
        if (!levels.isEmpty()) {
            levels.get(Math.min(lvl, levels.size()) - 1).apply(p, combat, spec.profile(), null);
        }
        p.setLevel(lvl);
        int need = needed.getOrDefault(p.getUniqueId(), 1);
        p.setExp(Math.min(0.99f, progress.getOrDefault(p.getUniqueId(), 0) / (float) need));
        if (announce) {
            plugin.messages().title(p, "gungame.level-up", "level", lvl, "max", maxLevel(), "name", levelNames.get(Math.min(lvl, levelNames.size()) - 1));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        }
    }

    private void heal(Player p) {
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
    }

    // --- урон и убийства ---

    @Override
    protected boolean onDamage(Player p, EntityDamageEvent e) {
        if (protectedUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis()) {
            e.setCancelled(true);
            return true;
        }
        if (e instanceof EntityDamageByEntityEvent ed) {
            Player attacker = ed.getDamager() instanceof Player dp ? dp
                    : ed.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player sp ? sp : null;
            if (attacker != null && attacker != p) {
                protectedUntil.remove(attacker.getUniqueId()); // ударил — защита после появления снята
                lastHit.put(p.getUniqueId(), attacker.getUniqueId());
                lastHitAt.put(p.getUniqueId(), System.currentTimeMillis());
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
        if (finished) {
            return;
        }
        UUID kid = lastHit.remove(p.getUniqueId());
        long at = lastHitAt.getOrDefault(p.getUniqueId(), 0L);
        Player killer = kid == null || System.currentTimeMillis() - at > cfg.getInt("kill-credit-seconds", 10) * 1000L ? null : Bukkit.getPlayer(kid);
        deaths.merge(p.getUniqueId(), 1, Integer::sum);
        respawn(p);
        if (killer != null && players.contains(killer) && killer != p) {
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
            UUID kidU = killer.getUniqueId();
            int lvl = level.getOrDefault(kidU, 1);
            int done = progress.merge(kidU, 1, Integer::sum);
            int need = needed.getOrDefault(kidU, 1);
            broadcast("gungame.killed", "player", p.getName(), "killer", killer.getName(), "level", lvl);
            if (done < need) {
                // уровень ещё не набран — только лечение и счётчик
                plugin.messages().actionBar(killer, "gungame.progress", "done", done, "need", need, "level", lvl);
                killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.3f);
                if (cfg.getBoolean("heal-on-kill", true)) {
                    heal(killer);
                }
                killer.setExp(Math.min(0.99f, done / (float) need));
                return;
            }
            if (lvl >= maxLevel()) {
                finish(killer);
                return;
            }
            level.put(kidU, lvl + 1);
            rollNeeded(kidU, lvl + 1);
            giveLevel(killer, true);
            if (cfg.getBoolean("heal-on-kill", true)) {
                heal(killer);
            }
            if (lvl + 1 == maxLevel()) {
                broadcast("gungame.final-level", "player", killer.getName(), "level", lvl + 1, "need", needed.get(kidU));
            }
        } else {
            broadcast("gungame.died", "player", p.getName());
        }
    }

    /** Мгновенное возрождение на случайной точке с тем же уровнем (за смерть уровень не понижается). */
    private void respawn(Player p) {
        heal(p);
        p.teleport(randomSpawn());
        giveLevel(p, false);
        heal(p);
        int prot = cfg.getInt("spawn-protection-seconds", 2);
        if (prot > 0) {
            protectedUntil.put(p.getUniqueId(), System.currentTimeMillis() + prot * 1000L);
        }
    }

    @Override
    protected void onPlayerLeave(Player p, boolean quit) {
        if (finished || !(state == State.RUNNING || state == State.COUNTDOWN)) {
            return;
        }
        duels.stats().add(p, spec.id(), false);
        List<Player> rest = new ArrayList<>(players);
        rest.remove(p);
        if (rest.size() <= 1) {
            Bukkit.getScheduler().runTask(plugin, () -> finish(rest.isEmpty() ? null : rest.getFirst()));
        }
    }

    @Override
    protected void onTick() {
        if (finished) {
            return;
        }
        if (runningTicks % 20 == 0) {
            for (Player p : players) {
                plugin.messages().actionBar(p, "gungame.level-actionbar", kv(p));
            }
        }
        if (spec.rules().maxDurationSeconds() > 0 && runningTicks >= spec.rules().maxDurationSeconds() * 20) {
            finish(leader());
        }
    }

    private Player leader() {
        Player best = null;
        for (Player p : order) {
            if (!players.contains(p)) {
                continue;
            }
            if (best == null || level.get(p.getUniqueId()) > level.get(best.getUniqueId())
                    || (level.get(p.getUniqueId()).equals(level.get(best.getUniqueId()))
                    && progress.getOrDefault(p.getUniqueId(), 0) > progress.getOrDefault(best.getUniqueId(), 0))) {
                best = p;
            }
        }
        return best;
    }

    private void finish(Player w) {
        if (finished) {
            return;
        }
        finished = true;
        winnerName = w == null ? "-" : w.getName();
        for (Player p : order) {
            if (players.contains(p)) {
                duels.stats().add(p, spec.id(), p == w);
            }
        }
        for (Player p : List.copyOf(players)) {
            Object[] kv = kv(p);
            plugin.messages().title(p, p == w ? "duels.win" : "minigames.ffa-end", kv);
            plugin.messages().sendList(p, "gungame.summary", kv);
            p.playSound(p.getLocation(), p == w ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
        end();
    }

    private Object[] kv(Player p) {
        return placeholders(p).entrySet().stream().flatMap(en -> Stream.of(en.getKey(), en.getValue())).toArray();
    }

    private void broadcast(String key, Object... kv) {
        for (Player p : players) {
            plugin.messages().send(p, key, kv);
        }
        for (Player p : spectators()) {
            plugin.messages().send(p, key, kv);
        }
    }

    @Override
    public Map<String, Object> placeholders(Player viewer) {
        Map<String, Object> map = super.placeholders(viewer);
        int lvl = level.getOrDefault(viewer.getUniqueId(), 1);
        map.put("kit", spec.display());
        map.put("level", lvl);
        map.put("max_level", maxLevel());
        map.put("level_name", levelNames.isEmpty() ? "" : levelNames.get(Math.min(lvl, levelNames.size()) - 1));
        map.put("kills", kills.getOrDefault(viewer.getUniqueId(), 0));
        map.put("level_kills", progress.getOrDefault(viewer.getUniqueId(), 0));
        map.put("level_need", needed.getOrDefault(viewer.getUniqueId(), 1));
        map.put("deaths", deaths.getOrDefault(viewer.getUniqueId(), 0));
        Player lead = leader();
        map.put("leader", lead == null ? "-" : lead.getName());
        map.put("leader_level", lead == null ? 0 : level.get(lead.getUniqueId()));
        map.put("total", order.size());
        map.put("winner", winnerName);
        map.put("player_hp", Text.num(Math.round(viewer.getHealth() * 10) / 10.0));
        map.put("time_left", Text.time(Math.max(0, spec.rules().maxDurationSeconds() - runningTicks / 20)));
        return map;
    }
}
