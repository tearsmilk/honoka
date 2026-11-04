package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.arena.Arena;
import dev.tearsmilk.miharu.core.kit.Kit;
import dev.tearsmilk.miharu.core.kit.KitGame;
import dev.tearsmilk.miharu.core.kit.KitRules;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.Game;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatProfile;
import dev.tearsmilk.miharu.pvp.CombatService;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Каждый сам за себя на N игроков (1x3, 1x4): точки p1…pN.
 * SkyWars/Sumo — последний живой; BedWars/Fireball — свои кровати bed1…bedN, без кровати смерть финальная;
 * The Bridge — свои ворота goal1…goalN, гол в чужие = очко, первым до rounds.
 */
public final class FfaGame extends Game implements KitGame, BuildGame {

    public static final String MODE = "ffa";

    private final DuelsModule duels;
    private final KitSpec spec;
    private final KitRules rules;
    private final CombatService combat;
    private final List<Player> order;
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Set<UUID> beds = new HashSet<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Integer> score = new HashMap<>();
    private final Map<UUID, UUID> lastHit = new HashMap<>();
    private final BuildTracker build = new BuildTracker();
    private boolean finished;
    private int round = 1;
    private Player winner;

    FfaGame(DuelsModule duels, GameModule games, Arena arena, KitSpec spec, CombatService combat, List<Player> players) {
        super(games, switch (spec.rules().objective()) {
            case BEDWARS, BED -> "ffa-beds";
            case GOAL -> "ffa-goals";
            default -> MODE;
        }, arena);
        this.duels = duels;
        this.spec = spec;
        this.rules = spec.rules();
        this.combat = combat;
        this.order = new ArrayList<>(players);
        players.forEach(p -> {
            alive.add(p.getUniqueId());
            beds.add(p.getUniqueId());
        });
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
    public CombatProfile combatProfile() {
        return spec.profile();
    }

    @Override
    public String displayName() {
        return Text.plain(Text.mm(spec.display()));
    }

    private int index(Player p) {
        return Math.max(0, order.indexOf(p));
    }

    private int index(UUID id) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).getUniqueId().equals(id)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    protected Location spawnFor(Player p) {
        return instance.spawn("p" + (index(p) + 1), "p1");
    }

    @Override
    protected boolean allowHunger() {
        return rules.hunger();
    }

    @Override
    protected void onSetup() {
        instance.world().setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, rules.naturalRegen());
        instance.world().setGameRule(GameRules.FALL_DAMAGE, rules.fallDamage());
        for (Player p : order) {
            giveKit(p);
        }
        if (rules.chestLoot() != null && !rules.chestLoot().isEmpty()) {
            duels.loot().fill(instance, rules.chestLoot());
        }
    }

    private void giveKit(Player p) {
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setGameMode(rules.build() ? GameMode.SURVIVAL : GameMode.ADVENTURE);
        Kit.Team[] teams = Kit.Team.values();
        spec.kit().apply(p, combat, spec.profile(), teams[index(p) % teams.length]);
    }

    // --- урон, смерть, выбывание ---

    @Override
    protected boolean onDamage(Player p, EntityDamageEvent e) {
        if (!alive.contains(p.getUniqueId())) {
            e.setCancelled(true);
            return true;
        }
        Player attacker = null;
        if (e instanceof EntityDamageByEntityEvent ed) {
            attacker = ed.getDamager() instanceof Player dp ? dp
                    : ed.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player sp ? sp : null;
            if (attacker != null && attacker != p) {
                lastHit.put(p.getUniqueId(), attacker.getUniqueId());
            }
        }
        if (!rules.damage()) {
            // сумо: урона нет, только отдача от удара
            if (attacker != null && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
                e.setDamage(0);
            } else {
                e.setCancelled(true);
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onLethal(Player p, EntityDamageEvent e) {
        died(p);
    }

    @Override
    protected void onVoid(Player p) {
        if (state != State.RUNNING || !alive.contains(p.getUniqueId())) {
            p.teleport(spawnFor(p).add(0, 5, 0));
            return;
        }
        if (rules.voidLose()) {
            died(p);
        } else {
            p.teleport(spawnFor(p));
        }
    }

    @Override
    protected void onPlayerLeave(Player p, boolean quit) {
        if (alive.contains(p.getUniqueId()) && (state == State.RUNNING || state == State.COUNTDOWN)) {
            eliminate(p);
        }
    }

    private Player killerOf(Player p) {
        UUID id = lastHit.get(p.getUniqueId());
        Player k = id == null ? null : Bukkit.getPlayer(id);
        return k != null && alive.contains(k.getUniqueId()) ? k : null;
    }

    private void died(Player p) {
        boolean respawn = rules.objective() == KitRules.Objective.BEDWARS ? beds.contains(p.getUniqueId())
                : rules.onDeath() == KitRules.DeathAction.RESPAWN;
        if (!respawn) {
            eliminate(p);
            return;
        }
        Player killer = killerOf(p);
        if (killer != null) {
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
            broadcast("duels.killed", "player", p.getName(), "killer", killer.getName());
        }
        lastHit.remove(p.getUniqueId());
        respawn(p);
    }

    private void respawn(Player p) {
        int sec = rules.respawnSeconds();
        if (sec <= 0) {
            p.teleport(spawnFor(p));
            giveKit(p);
            return;
        }
        p.getInventory().clear();
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(spawnFor(p).add(0, 6, 0));
        for (int i = 0; i < sec; i++) {
            int left = sec - i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (players.contains(p) && state == State.RUNNING && alive.contains(p.getUniqueId())) {
                    plugin.messages().title(p, "minigames.respawning", "seconds", left);
                }
            }, i * 20L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (players.contains(p) && !finished && state == State.RUNNING && alive.contains(p.getUniqueId()) && p.isOnline()) {
                p.teleport(spawnFor(p));
                giveKit(p);
            }
        }, sec * 20L);
    }

    private void eliminate(Player p) {
        if (finished || !alive.remove(p.getUniqueId())) {
            return;
        }
        Player killer = killerOf(p);
        if (killer != null) {
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
            broadcast(rules.objective() == KitRules.Objective.BEDWARS ? "minigames.final-kill" : "minigames.ffa-killed",
                    "player", p.getName(), "killer", killer.getName(), "alive", alive.size());
        } else {
            broadcast("minigames.ffa-died", "player", p.getName(), "alive", alive.size());
        }
        duels.stats().add(p, spec.id(), false);
        if (p.isOnline() && players.contains(p)) {
            var max = p.getAttribute(Attribute.MAX_HEALTH);
            p.setHealth(max == null ? 20 : max.getValue());
            p.getInventory().clear();
            p.setGameMode(GameMode.SPECTATOR);
            p.teleport(instance.origin().clone().add(instance.arena().size().getX() / 2, instance.arena().size().getY() + 5, instance.arena().size().getZ() / 2));
            plugin.messages().title(p, "minigames.ffa-out", "place", alive.size() + 1);
        }
        if (alive.size() <= 1) {
            finish(alive.isEmpty() ? null : Bukkit.getPlayer(alive.iterator().next()));
        }
    }

    // --- ворота (Bridge) ---

    @Override
    protected void onMove(Player p, Location to) {
        if (!alive.contains(p.getUniqueId())) {
            return;
        }
        if (rules.liquidLose() && to.getBlock().isLiquid()) {
            onVoid(p);
            return;
        }
        if (rules.objective() != KitRules.Objective.GOAL) {
            return;
        }
        int own = index(p) + 1;
        for (int i = 1; i <= order.size(); i++) {
            String key = "goal" + i;
            if (!instance.has(key) || !near(to, instance.spawn(key), rules.goalRadius())) {
                continue;
            }
            if (i == own) {
                p.teleport(spawnFor(p));
            } else {
                broadcast("duels.goal", "player", p.getName());
                scored(p);
            }
            return;
        }
    }

    private static boolean near(Location at, Location point, double radius) {
        double dx = at.getX() - point.getX();
        double dz = at.getZ() - point.getZ();
        double dy = at.getY() - point.getY();
        return dx * dx + dz * dz <= radius * radius && Math.abs(dy) <= Math.max(2.0, radius);
    }

    private void scored(Player p) {
        if (finished || state != State.RUNNING) {
            return;
        }
        int s = score.merge(p.getUniqueId(), 1, Integer::sum);
        if (s >= rules.scoreToWin()) {
            finish(p);
            return;
        }
        round++;
        build.revert(instance.world());
        for (Player pl : order) {
            if (players.contains(pl) && alive.contains(pl.getUniqueId())) {
                pl.teleport(spawnFor(pl));
                pl.setVelocity(new org.bukkit.util.Vector());
                giveKit(pl);
            }
        }
        restartCountdown(rules.roundCountdown());
    }

    // --- стройка и кровати ---

    @Override
    protected boolean canPlace(Player p, Block block, BlockState replaced) {
        if (!rules.build() || !alive.contains(p.getUniqueId())) {
            return false;
        }
        for (int i = 1; i <= order.size(); i++) {
            Location s = instance.spawn("p" + i);
            if (s.getBlockX() == block.getX() && s.getBlockZ() == block.getZ() && Math.abs(s.getBlockY() - block.getY()) <= 1) {
                return false;
            }
        }
        build.place(block, replaced);
        return true;
    }

    @Override
    protected boolean canBreak(Player p, Block block) {
        if (!alive.contains(p.getUniqueId())) {
            return false;
        }
        if (rules.objective() == KitRules.Objective.BEDWARS && Tag.BEDS.isTagged(block.getType())) {
            Location at = block.getLocation().add(0.5, 0, 0.5);
            for (int i = 1; i <= order.size(); i++) {
                String key = "bed" + i;
                if (!instance.has(key) || !near(at, instance.spawn(key), rules.goalRadius() + 1)) {
                    continue;
                }
                Player owner = order.get(i - 1);
                if (owner == p) {
                    plugin.messages().send(p, "duels.own-bed");
                    return false;
                }
                if (beds.remove(owner.getUniqueId())) {
                    build.broke(block);
                    if (block.getBlockData() instanceof Bed bed) {
                        build.broke(bed.getPart() == Bed.Part.HEAD ? block.getRelative(bed.getFacing().getOppositeFace()) : block.getRelative(bed.getFacing()));
                    }
                    broadcast("minigames.bed-destroyed", "player", p.getName(), "victim", owner.getName());
                    if (owner.isOnline()) {
                        plugin.messages().title(owner, "minigames.bed-lost");
                        owner.playSound(owner.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.6f, 1.2f);
                    }
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
                }
                return true;
            }
        }
        if (!rules.build()) {
            return false;
        }
        if (rules.breakPlacedOnly() && !build.isPlaced(block)) {
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
        blocks.forEach(build::broke);
    }

    // --- конец ---

    private void finish(Player w) {
        if (finished) {
            return;
        }
        finished = true;
        winner = w;
        if (w != null) {
            duels.stats().add(w, spec.id(), true);
            for (Player p : order) {
                if (p != w && alive.contains(p.getUniqueId())) {
                    duels.stats().add(p, spec.id(), false);
                }
            }
        }
        for (Player p : List.copyOf(players)) {
            Object[] kv = placeholders(p).entrySet().stream().flatMap(en -> Stream.of(en.getKey(), en.getValue())).toArray();
            plugin.messages().title(p, p == w ? "duels.win" : "minigames.ffa-end", kv);
            plugin.messages().sendList(p, "minigames.ffa-summary", kv);
            p.playSound(p.getLocation(), p == w ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
        end();
    }

    @Override
    protected void onTick() {
        if (rules.maxDurationSeconds() > 0 && runningTicks >= rules.maxDurationSeconds() * 20) {
            // по времени — больше очков, потом больше убийств
            Player best = null;
            long bestValue = Long.MIN_VALUE;
            for (UUID id : alive) {
                long v = score.getOrDefault(id, 0) * 1000L + kills.getOrDefault(id, 0);
                if (v > bestValue) {
                    bestValue = v;
                    best = Bukkit.getPlayer(id);
                }
            }
            finish(best);
        }
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
        map.put("kit", spec.display());
        map.put("alive", alive.size());
        map.put("total", order.size());
        map.put("kills", kills.getOrDefault(viewer.getUniqueId(), 0));
        map.put("score", score.getOrDefault(viewer.getUniqueId(), 0));
        map.put("score_to_win", rules.scoreToWin());
        map.put("round", round);
        int best = score.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        map.put("best_score", best);
        map.put("bed", plugin.messages().raw(beds.contains(viewer.getUniqueId()) ? "minigames.bed-yes" : "minigames.bed-no"));
        map.put("beds_alive", beds.size());
        map.put("status", plugin.messages().raw(alive.contains(viewer.getUniqueId()) ? "minigames.ffa-alive" : "minigames.ffa-dead"));
        map.put("winner", winner == null ? "-" : winner.getName());
        map.put("player_hp", Text.num(Math.round(viewer.getHealth() * 10) / 10.0));
        map.put("time_left", Text.time(Math.max(0, rules.maxDurationSeconds() - runningTicks / 20)));
        return map;
    }
}
