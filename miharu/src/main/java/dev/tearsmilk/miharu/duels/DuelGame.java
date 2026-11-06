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

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Дуэль 1 на 1 по киту. Раунды до rounds побед, правила кита:
 * без урона (сумо/бокс), бокс до N ударов, цели Bridge, кровати MLG, стройка с откатом между раундами.
 */
public final class DuelGame extends Game implements KitGame, BuildGame {

    public static final String MODE = "duel";

    private final DuelsModule duels;
    private final KitSpec spec;
    private final KitRules rules;
    private final CombatService combat;
    private final Player a;
    private final Player b;
    private final BuildTracker build = new BuildTracker();
    private int scoreA;
    private int scoreB;
    private int hitsA;
    private int hitsB;
    private int comboA;
    private int comboB;
    private int bestComboA;
    private int bestComboB;
    private int round = 1;
    private boolean finished;
    private Player winner;
    private final java.util.Map<java.util.UUID, Kit> kitOverrides = new java.util.HashMap<>();
    private boolean bedA = true;
    private boolean bedB = true;

    DuelGame(DuelsModule duels, GameModule games, Arena arena, KitSpec spec, CombatService combat, Player a, Player b) {
        super(games, spec.id().startsWith("mg-") ? "minigame" : MODE, arena);
        this.duels = duels;
        this.spec = spec;
        this.rules = spec.rules();
        this.combat = combat;
        this.a = a;
        this.b = b;
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

    @Override
    protected Location spawnFor(Player p) {
        return instance.spawn(p == b ? "p2" : "p1");
    }

    @Override
    protected int countdownSeconds() {
        return duels.config().getInt("countdown-seconds", super.countdownSeconds());
    }

    @Override
    protected boolean allowHunger() {
        return rules.hunger();
    }

    /** Свой кит игрока (очередь «Свой кит»): у каждого в игре — его собственный кит. */
    void overrideKit(Player p, Kit kit) {
        if (kit != null) {
            kitOverrides.put(p.getUniqueId(), kit);
        }
    }

    private Player opponent(Player p) {
        return p == a ? b : a;
    }

    @Override
    protected void onSetup() {
        instance.world().setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, rules.naturalRegen());
        instance.world().setGameRule(GameRules.FALL_DAMAGE, rules.fallDamage());
        giveKit(a);
        giveKit(b);
        if (rules.chestLoot() != null && !rules.chestLoot().isEmpty()) {
            int filled = duels.loot().fill(instance, rules.chestLoot());
            if (plugin.debug()) {
                plugin.getLogger().info("[debug] лут " + rules.chestLoot() + ": сундуков " + filled);
            }
        }
    }

    private void giveKit(Player p) {
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(5);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setGameMode(rules.build() ? GameMode.SURVIVAL : GameMode.valueOf(module.config().getString("gamemode", "ADVENTURE").toUpperCase(java.util.Locale.ROOT)));
        kitOverrides.getOrDefault(p.getUniqueId(), spec.kit()).apply(p, combat, spec.profile(), p == a ? Kit.Team.RED : Kit.Team.BLUE);
    }

    // --- смерть, пустота, урон ---

    @Override
    protected void onLethal(Player p, EntityDamageEvent e) {
        died(p);
    }

    @Override
    protected void onVoid(Player p) {
        if (state != State.RUNNING) {
            super.onVoid(p);
            return;
        }
        if (rules.voidLose()) {
            died(p);
        } else {
            p.teleport(spawnFor(p));
        }
    }

    private void died(Player p) {
        Player killer = opponent(p);
        if (rules.objective() == KitRules.Objective.BEDWARS) {
            if (p == a ? bedA : bedB) {
                broadcast("duels.killed", "player", p.getName(), "killer", killer.getName());
                respawn(p);
            } else {
                broadcast("minigames.final-kill", "player", p.getName(), "killer", killer.getName());
                roundWon(killer);
            }
            return;
        }
        broadcast("duels.killed", "player", p.getName(), "killer", killer.getName());
        if (rules.onDeath() == KitRules.DeathAction.RESPAWN) {
            respawn(p);
            return;
        }
        roundWon(killer);
    }

    /** Возрождение: сразу или через respawn-seconds (наблюдателем над своей точкой). */
    private void respawn(Player p) {
        int sec = rules.respawnSeconds();
        if (sec <= 0) {
            p.teleport(spawnFor(p));
            giveKit(p);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 0.8f);
            return;
        }
        p.getInventory().clear();
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(spawnFor(p).add(0, 6, 0));
        for (int i = 0; i < sec; i++) {
            int left = sec - i;
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (players.contains(p) && state == State.RUNNING) {
                    plugin.messages().title(p, "minigames.respawning", "seconds", left);
                }
            }, i * 20L);
        }
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (players.contains(p) && !finished && state == State.RUNNING && p.isOnline()) {
                p.teleport(spawnFor(p));
                giveKit(p);
            }
        }, sec * 20L);
    }

    /** Взрыв при explosion-block-damage: placed — ломаются только блоки игроков. */
    @Override
    public void filterExplosion(List<Block> blocks) {
        blocks.removeIf(bl -> !build.isPlaced(bl));
        blocks.forEach(build::broke);
    }

    /** Взрыв, который ломает всё: запоминаем блоки для отката раунда. */
    @Override
    public void trackExplosion(List<Block> blocks) {
        blocks.forEach(build::broke);
    }

    @Override
    protected boolean onDamage(Player p, EntityDamageEvent e) {
        Player attacker = null;
        if (e instanceof EntityDamageByEntityEvent ed) {
            if (ed.getDamager() instanceof Player dp) {
                attacker = dp;
            } else if (ed.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player sp) {
                attacker = sp;
            }
        }
        boolean melee = attacker != null && attacker == opponent(p) && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK;
        if (melee) {
            if (attacker == a) {
                hitsA++;
                comboA++;
                comboB = 0;
                bestComboA = Math.max(bestComboA, comboA);
            } else {
                hitsB++;
                comboB++;
                comboA = 0;
                bestComboB = Math.max(bestComboB, comboB);
            }
        }
        if (!rules.damage()) {
            if (melee) {
                e.setDamage(0);
                if (rules.hitTarget() > 0 && (attacker == a ? hitsA : hitsB) >= rules.hitTarget()) {
                    Player w = attacker;
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> roundWon(w));
                }
            } else {
                e.setCancelled(true);
            }
            return true;
        }
        if (melee && rules.hitTarget() > 0 && (attacker == a ? hitsA : hitsB) >= rules.hitTarget()) {
            e.setCancelled(true);
            roundWon(attacker);
            return true;
        }
        return false;
    }

    // --- цели (Bridge) и кровати (MLG) ---

    @Override
    protected void onMove(Player p, Location to) {
        if (rules.liquidLose() && to.getBlock().isLiquid()) {
            onVoid(p);
            return;
        }
        if (rules.objective() != KitRules.Objective.GOAL) {
            return;
        }
        String target = p == a ? "goal2" : "goal1";
        if (!instance.has(target)) {
            return;
        }
        if (near(to, instance.spawn(target), rules.goalRadius())) {
            broadcast("duels.goal", "player", p.getName());
            roundWon(p);
        } else if (near(to, instance.spawn(p == a ? "goal1" : "goal2"), rules.goalRadius())) {
            p.teleport(spawnFor(p));
        }
    }

    private static boolean near(Location at, Location point, double radius) {
        double dx = at.getX() - point.getX();
        double dz = at.getZ() - point.getZ();
        double dy = at.getY() - point.getY();
        return dx * dx + dz * dz <= radius * radius && Math.abs(dy) <= Math.max(2.0, radius);
    }

    @Override
    protected boolean canPlace(Player p, Block block, BlockState replaced) {
        if (!rules.build()) {
            return false;
        }
        for (String key : List.of("p1", "p2")) {
            Location s = instance.spawn(key);
            if (s.getBlockX() == block.getX() && s.getBlockZ() == block.getZ() && Math.abs(s.getBlockY() - block.getY()) <= 1) {
                return false;
            }
        }
        build.place(block, replaced);
        return true;
    }

    @Override
    protected boolean canBreak(Player p, Block block) {
        if (rules.objective() == KitRules.Objective.BEDWARS && Tag.BEDS.isTagged(block.getType())) {
            String own = p == a ? "bed1" : "bed2";
            String enemy = p == a ? "bed2" : "bed1";
            Location at = block.getLocation().add(0.5, 0, 0.5);
            if (instance.has(own) && near(at, instance.spawn(own), rules.goalRadius() + 1)) {
                plugin.messages().send(p, "duels.own-bed");
                return false;
            }
            if (instance.has(enemy) && near(at, instance.spawn(enemy), rules.goalRadius() + 1)) {
                Player victim = opponent(p);
                if (victim == a) {
                    bedA = false;
                } else {
                    bedB = false;
                }
                rememberBed(block);
                broadcast("minigames.bed-destroyed", "player", p.getName(), "victim", victim.getName());
                plugin.messages().title(victim, "minigames.bed-lost");
                victim.playSound(victim.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.6f, 1.2f);
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
                return true;
            }
        }
        if (rules.objective() == KitRules.Objective.BED && Tag.BEDS.isTagged(block.getType())) {
            String own = p == a ? "bed1" : "bed2";
            String enemy = p == a ? "bed2" : "bed1";
            if (instance.has(own) && near(block.getLocation().add(0.5, 0, 0.5), instance.spawn(own), rules.goalRadius() + 1)) {
                plugin.messages().send(p, "duels.own-bed");
                return false;
            }
            if (instance.has(enemy) && near(block.getLocation().add(0.5, 0, 0.5), instance.spawn(enemy), rules.goalRadius() + 1)) {
                rememberBed(block);
                broadcast("duels.bed-broken", "player", p.getName());
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> roundWon(p));
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

    private void rememberBed(Block block) {
        build.broke(block);
        if (block.getBlockData() instanceof Bed bed) {
            Block other = bed.getPart() == Bed.Part.HEAD ? block.getRelative(bed.getFacing().getOppositeFace()) : block.getRelative(bed.getFacing());
            build.broke(other);
        }
    }

    // --- раунды ---

    private void roundWon(Player w) {
        if (finished || state != State.RUNNING) {
            return;
        }
        if (w == a) {
            scoreA++;
        } else {
            scoreB++;
        }
        int score = w == a ? scoreA : scoreB;
        if (score >= rules.scoreToWin()) {
            finish(w);
            return;
        }
        round++;
        broadcast("duels.round-won", "player", w.getName(), "score_a", scoreA, "score_b", scoreB, "round", round);
        build.revert(instance.world());
        hitsA = hitsB = comboA = comboB = 0;
        for (Player p : List.of(a, b)) {
            p.teleport(spawnFor(p));
            p.setVelocity(new org.bukkit.util.Vector());
            giveKit(p);
        }
        restartCountdown(rules.roundCountdown());
    }

    @Override
    protected void onTick() {
        if (rules.maxDurationSeconds() > 0 && runningTicks >= rules.maxDurationSeconds() * 20) {
            finish(null);
        }
    }

    @Override
    protected void onPlayerLeave(Player p, boolean quit) {
        if (!finished && (state == State.RUNNING || state == State.COUNTDOWN)) {
            finish(opponent(p));
        }
    }

    private void finish(Player w) {
        if (finished) {
            return;
        }
        finished = true;
        winner = w;
        if (w != null) {
            Player loser = opponent(w);
            duels.stats().record(w, loser, spec.id());
            for (Player p : List.of(a, b)) {
                if (!p.isOnline() || !players.contains(p)) {
                    continue;
                }
                Object[] kv = kv(p);
                plugin.messages().title(p, p == w ? "duels.win" : "duels.loss", kv);
                plugin.messages().sendList(p, "duels.summary", kv);
                p.playSound(p.getLocation(), p == w ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            }
            if (loser.isOnline() && players.contains(loser) && duels.config().getBoolean("spectate-on-loss", true)) {
                loser.setGameMode(GameMode.SPECTATOR);
            }
        } else {
            for (Player p : List.of(a, b)) {
                if (p.isOnline() && players.contains(p)) {
                    plugin.messages().title(p, "duels.draw", kv(p));
                }
            }
        }
        for (Player p : List.of(a, b)) {
            if (p.isOnline()) {
                var max = p.getAttribute(Attribute.MAX_HEALTH);
                p.setHealth(max == null ? 20 : max.getValue());
                p.setFireTicks(0);
            }
        }
        end();
    }

    private Object[] kv(Player viewer) {
        return placeholders(viewer).entrySet().stream().flatMap(e -> Stream.of(e.getKey(), e.getValue())).toArray();
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
        Player me = viewer == b ? b : a;
        Player op = opponent(me);
        map.put("kit", spec.display());
        map.put("version", spec.version().id());
        map.put("me", me.getName());
        map.put("opponent", op.getName());
        map.put("player_a", a.getName());
        map.put("player_b", b.getName());
        map.put("player_hp", Text.num(Math.round(me.getHealth() * 10) / 10.0));
        map.put("opponent_hp", Text.num(Math.round(op.getHealth() * 10) / 10.0));
        map.put("opponent_ping", op.getPing());
        map.put("score", me == a ? scoreA : scoreB);
        map.put("opponent_score", me == a ? scoreB : scoreA);
        map.put("score_a", scoreA);
        map.put("score_b", scoreB);
        map.put("score_to_win", rules.scoreToWin());
        map.put("hits", me == a ? hitsA : hitsB);
        map.put("opponent_hits", me == a ? hitsB : hitsA);
        map.put("combo", me == a ? comboA : comboB);
        map.put("best_combo", me == a ? bestComboA : bestComboB);
        map.put("hit_target", rules.hitTarget());
        map.put("round", round);
        map.put("time_left", Text.time(Math.max(0, rules.maxDurationSeconds() - runningTicks / 20)));
        map.put("winner", winner == null ? "-" : winner.getName());
        String yes = plugin.messages().raw("minigames.bed-yes");
        String no = plugin.messages().raw("minigames.bed-no");
        map.put("bed", (me == a ? bedA : bedB) ? yes : no);
        map.put("opponent_bed", (me == a ? bedB : bedA) ? yes : no);
        return map;
    }
}
