package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.arena.Arena;
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
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Каждый сам за себя на N игроков (SkyWars 1x3, 1x4): точки p1…pN, последний живой побеждает.
 * Выбывшие досматривают наблюдателями.
 */
public final class FfaGame extends Game implements KitGame {

    public static final String MODE = "ffa";

    private final DuelsModule duels;
    private final KitSpec spec;
    private final KitRules rules;
    private final CombatService combat;
    private final List<Player> order;
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, UUID> lastHit = new HashMap<>();
    private final BuildTracker build = new BuildTracker();
    private boolean finished;
    private Player winner;

    FfaGame(DuelsModule duels, GameModule games, Arena arena, KitSpec spec, CombatService combat, List<Player> players) {
        super(games, MODE, arena);
        this.duels = duels;
        this.spec = spec;
        this.rules = spec.rules();
        this.combat = combat;
        this.order = new ArrayList<>(players);
        players.forEach(p -> alive.add(p.getUniqueId()));
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
        int i = order.indexOf(p);
        return instance.spawn("p" + (Math.max(0, i) + 1), "p1");
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
            for (PotionEffect e : p.getActivePotionEffects()) {
                p.removePotionEffect(e.getType());
            }
            p.setGameMode(rules.build() ? GameMode.SURVIVAL : GameMode.ADVENTURE);
            spec.kit().apply(p, combat, spec.profile(), null);
        }
        if (rules.chestLoot() != null && !rules.chestLoot().isEmpty()) {
            duels.loot().fill(instance, rules.chestLoot());
        }
    }

    // --- выбывание ---

    @Override
    protected boolean onDamage(Player p, EntityDamageEvent e) {
        if (!alive.contains(p.getUniqueId())) {
            e.setCancelled(true);
            return true;
        }
        if (e instanceof EntityDamageByEntityEvent ed) {
            Player attacker = ed.getDamager() instanceof Player dp ? dp
                    : ed.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player sp ? sp : null;
            if (attacker != null && attacker != p) {
                lastHit.put(p.getUniqueId(), attacker.getUniqueId());
            }
        }
        return false;
    }

    @Override
    protected void onLethal(Player p, EntityDamageEvent e) {
        eliminate(p);
    }

    @Override
    protected void onVoid(Player p) {
        if (state != State.RUNNING || !alive.contains(p.getUniqueId())) {
            p.teleport(spawnFor(p).add(0, 5, 0));
            return;
        }
        eliminate(p);
    }

    @Override
    protected void onPlayerLeave(Player p, boolean quit) {
        if (alive.contains(p.getUniqueId()) && (state == State.RUNNING || state == State.COUNTDOWN)) {
            eliminate(p);
        }
    }

    private void eliminate(Player p) {
        if (finished || !alive.remove(p.getUniqueId())) {
            return;
        }
        UUID killerId = lastHit.get(p.getUniqueId());
        Player killer = killerId == null ? null : org.bukkit.Bukkit.getPlayer(killerId);
        if (killer != null && alive.contains(killerId)) {
            kills.merge(killerId, 1, Integer::sum);
            broadcast("minigames.ffa-killed", "player", p.getName(), "killer", killer.getName(), "alive", alive.size());
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
            Player w = alive.isEmpty() ? null : org.bukkit.Bukkit.getPlayer(alive.iterator().next());
            finish(w);
        }
    }

    private void finish(Player w) {
        if (finished) {
            return;
        }
        finished = true;
        winner = w;
        if (w != null) {
            duels.stats().add(w, spec.id(), true);
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
            // по времени — побеждает тот, у кого больше убийств
            Player best = null;
            int bestKills = -1;
            for (UUID id : alive) {
                int k = kills.getOrDefault(id, 0);
                if (k > bestKills) {
                    bestKills = k;
                    best = org.bukkit.Bukkit.getPlayer(id);
                }
            }
            finish(best);
        }
    }

    // --- стройка ---

    @Override
    protected boolean canPlace(Player p, Block block, BlockState replaced) {
        if (!rules.build() || !alive.contains(p.getUniqueId())) {
            return false;
        }
        build.place(block, replaced);
        return true;
    }

    @Override
    protected boolean canBreak(Player p, Block block) {
        if (!rules.build() || !alive.contains(p.getUniqueId())) {
            return false;
        }
        if (rules.breakPlacedOnly() && !build.isPlaced(block)) {
            return false;
        }
        build.broke(block);
        return true;
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
        map.put("status", plugin.messages().raw(alive.contains(viewer.getUniqueId()) ? "minigames.ffa-alive" : "minigames.ffa-dead"));
        map.put("winner", winner == null ? "-" : winner.getName());
        map.put("player_hp", Text.num(Math.round(viewer.getHealth() * 10) / 10.0));
        map.put("time_left", Text.time(Math.max(0, rules.maxDurationSeconds() - runningTicks / 20)));
        return map;
    }
}
