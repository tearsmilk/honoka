package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.arena.Arena;
import dev.tearsmilk.miharu.core.kit.KitGame;
import dev.tearsmilk.miharu.core.kit.KitRules;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.Game;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatProfile;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import dev.tearsmilk.miharu.pvp.PvpModule;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Митап: старт в коробках подготовки (pre&lt;N&gt;) с двумя шалкерами лута, минута на сборы и голосование за версию PvP,
 * потом все на точки боя (p&lt;N&gt;). Последний живой игрок (или команда в 2xN) побеждает.
 */
public final class MeetupGame extends Game implements KitGame, BuildGame {

    public static final String MODE = "meetup";

    private final DuelsModule duels;
    private final KitSpec spec;
    private final KitRules rules;
    private final Meetups.Mode meetup;
    private final List<Player> order;
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Map<UUID, CombatVersion> votes = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, UUID> lastHit = new HashMap<>();
    private final BuildTracker build = new BuildTracker();
    private final List<Location> shulkers = new ArrayList<>();
    private boolean prep = true;
    private int prepLeft;
    private int fightTicks;
    private CombatVersion chosen;
    private boolean finished;
    private String winnerName = "-";

    MeetupGame(DuelsModule duels, GameModule games, Arena arena, KitSpec spec, Meetups.Mode meetup, List<Player> players) {
        super(games, MODE, arena);
        this.duels = duels;
        this.spec = spec;
        this.rules = spec.rules();
        this.meetup = meetup;
        this.order = new ArrayList<>(players);
        players.forEach(p -> alive.add(p.getUniqueId()));
    }

    @Override
    public KitSpec kitSpec() {
        return spec;
    }

    @Override
    public CombatVersion combatVersion() {
        return chosen == null ? CombatVersion.LEGACY : chosen;
    }

    @Override
    public String displayName() {
        return Text.plain(Text.mm(spec.display()));
    }

    boolean inPrep() {
        return prep && (state == State.RUNNING || state == State.COUNTDOWN);
    }

    private int index(Player p) {
        return Math.max(0, order.indexOf(p));
    }

    private int team(Player p) {
        return index(p) / meetup.teamSize();
    }

    @Override
    protected Location spawnFor(Player p) {
        int n = index(p) + 1;
        return prep ? instance.spawn("pre" + n, "p" + n) : instance.spawn("p" + n, "p1");
    }

    @Override
    protected boolean allowHunger() {
        return !prep && rules.hunger();
    }

    // --- голосование ---

    void vote(Player p, CombatVersion v) {
        if (inPrep()) {
            votes.put(p.getUniqueId(), v);
        }
    }

    CombatVersion voteOf(Player p) {
        return votes.get(p.getUniqueId());
    }

    int votes(CombatVersion v) {
        return (int) votes.values().stream().filter(x -> x == v).count();
    }

    // --- подготовка ---

    @Override
    protected void onSetup() {
        instance.world().setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, rules.naturalRegen());
        instance.world().setGameRule(GameRules.FALL_DAMAGE, rules.fallDamage());
        Material box = duels.meetups().shulker();
        for (int i = 0; i < order.size(); i++) {
            Player p = order.get(i);
            duels.meetups().unvote(p.getUniqueId());
            p.getInventory().clear();
            for (PotionEffect e : p.getActivePotionEffects()) {
                p.removePotionEffect(e.getType());
            }
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().setItem(duels.meetups().cfg().getInt("vote.slot", 8), duels.meetups().voteItem());
            List<ItemStack>[] loot = duels.meetups().rollLoot();
            int n = i + 1;
            placeShulker("loot" + n + "a", box, loot[0]);
            placeShulker("loot" + n + "b", box, loot[1]);
        }
        prepLeft = Math.max(5, duels.meetups().cfg().getInt("prep-seconds", 60)) * 20;
    }

    private void placeShulker(String key, Material type, List<ItemStack> items) {
        if (!instance.has(key)) {
            return;
        }
        Block b = instance.spawn(key).getBlock();
        b.setType(type, false);
        if (b.getState() instanceof ShulkerBox sb) {
            for (int i = 0; i < items.size() && i < 27; i++) {
                sb.getInventory().setItem(i, items.get(i));
            }
        }
        shulkers.add(b.getLocation());
    }

    @Override
    protected void onRunning() {
        super.onRunning();
        for (Player p : players) {
            plugin.messages().send(p, "meetups.prep-started", "seconds", prepLeft / 20);
        }
    }

    @Override
    protected void onTick() {
        if (prep) {
            prepLeft--;
            int sec = prepLeft / 20;
            if (prepLeft % 20 == 0) {
                for (Player p : players) {
                    plugin.messages().actionBar(p, "meetups.prep-actionbar", "seconds", sec,
                            "votes_18", votes(CombatVersion.LEGACY), "votes_19", votes(CombatVersion.MODERN));
                    if (sec <= 5 && sec > 0) {
                        plugin.messages().title(p, "meetups.prep-countdown", "seconds", sec);
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.4f);
                    }
                }
            }
            if (prepLeft <= 0) {
                startFight();
            }
            return;
        }
        fightTicks++;
        if (rules.maxDurationSeconds() > 0 && fightTicks >= rules.maxDurationSeconds() * 20) {
            Player best = null;
            int bestKills = -1;
            for (UUID id : alive) {
                int k = kills.getOrDefault(id, 0);
                if (k > bestKills) {
                    bestKills = k;
                    best = Bukkit.getPlayer(id);
                }
            }
            finish(best == null ? -1 : team(best));
        }
    }

    /** /force-start на подготовке — сразу в бой. */
    public boolean skipPrep() {
        if (!prep || state != State.RUNNING) {
            return false;
        }
        startFight();
        return true;
    }

    private void startFight() {
        prep = false;
        int v18 = votes(CombatVersion.LEGACY);
        int v19 = votes(CombatVersion.MODERN);
        CombatVersion def = CombatVersion.parse(duels.meetups().cfg().getString("default-version", "1.8"));
        chosen = v19 > v18 ? CombatVersion.MODERN : v18 > v19 ? CombatVersion.LEGACY : def == null ? CombatVersion.LEGACY : def;
        for (Location l : shulkers) {
            l.getBlock().setType(Material.AIR, false);
        }
        PvpModule pvp = plugin.module(PvpModule.class);
        CombatProfile profile = pvp.combat().profile(chosen);
        for (Player p : List.copyOf(players)) {
            if (!alive.contains(p.getUniqueId())) {
                continue;
            }
            for (int i = 0; i < p.getInventory().getSize(); i++) {
                ItemStack it = p.getInventory().getItem(i);
                if (it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(Meetups.VOTE)) {
                    p.getInventory().setItem(i, null);
                }
            }
            p.setGameMode(rules.build() ? GameMode.SURVIVAL : GameMode.ADVENTURE);
            p.teleport(spawnFor(p));
            p.setFallDistance(0);
            pvp.combat().force(p, profile);
            plugin.messages().title(p, "meetups.fight", "version", chosen.id(), "votes_18", v18, "votes_19", v19);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.2f);
        }
    }

    // --- бой ---

    @Override
    protected boolean onDamage(Player p, EntityDamageEvent e) {
        if (prep || !alive.contains(p.getUniqueId())) {
            e.setCancelled(true);
            return true;
        }
        if (e instanceof EntityDamageByEntityEvent ed) {
            Player attacker = ed.getDamager() instanceof Player dp ? dp
                    : ed.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player sp ? sp : null;
            if (attacker != null && attacker != p) {
                if (meetup.teamSize() > 1 && team(attacker) == team(p)) {
                    e.setCancelled(true);   // по своим не бьём
                    return true;
                }
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
        if (prep || state != State.RUNNING || !alive.contains(p.getUniqueId())) {
            p.teleport(spawnFor(p));
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
        UUID kid = lastHit.get(p.getUniqueId());
        Player killer = kid == null ? null : Bukkit.getPlayer(kid);
        if (killer != null && alive.contains(kid)) {
            kills.merge(kid, 1, Integer::sum);
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
        Set<Integer> teams = new LinkedHashSet<>();
        for (UUID id : alive) {
            Player a = Bukkit.getPlayer(id);
            if (a != null) {
                teams.add(team(a));
            }
        }
        if (teams.size() <= 1) {
            finish(teams.isEmpty() ? -1 : teams.iterator().next());
        }
    }

    private void finish(int winningTeam) {
        if (finished) {
            return;
        }
        finished = true;
        List<String> names = new ArrayList<>();
        for (Player p : order) {
            if (winningTeam >= 0 && team(p) == winningTeam) {
                names.add(p.getName());
                duels.stats().add(p, spec.id(), true);
            }
        }
        winnerName = names.isEmpty() ? "-" : String.join(", ", names);
        for (Player p : List.copyOf(players)) {
            boolean won = winningTeam >= 0 && team(p) == winningTeam;
            Object[] kv = placeholders(p).entrySet().stream().flatMap(en -> Stream.of(en.getKey(), en.getValue())).toArray();
            plugin.messages().title(p, won ? "minigames.win" : "minigames.ffa-end", kv);
            plugin.messages().sendList(p, "minigames.ffa-summary", kv);
            p.playSound(p.getLocation(), won ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
        end();
    }

    // --- стройка ---

    @Override
    protected boolean canPlace(Player p, Block block, BlockState replaced) {
        if (prep || !rules.build() || !alive.contains(p.getUniqueId())) {
            return false;
        }
        build.place(block, replaced);
        return true;
    }

    @Override
    protected boolean canBreak(Player p, Block block) {
        if (prep || !rules.build() || !alive.contains(p.getUniqueId())) {
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
    }

    @Override
    public void trackExplosion(List<Block> blocks) {
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
        map.put("format", meetup.display());
        map.put("alive", alive.size());
        map.put("total", order.size());
        map.put("kills", kills.getOrDefault(viewer.getUniqueId(), 0));
        map.put("team", meetup.teamSize() > 1 && order.contains(viewer) ? String.valueOf(team(viewer) + 1) : "-");
        map.put("phase", plugin.messages().raw(prep ? "meetups.phase-prep" : "meetups.phase-fight"));
        map.put("prep_left", Text.time(Math.max(0, prepLeft / 20)));
        map.put("version", chosen == null ? "?" : chosen.id());
        map.put("votes_18", votes(CombatVersion.LEGACY));
        map.put("votes_19", votes(CombatVersion.MODERN));
        map.put("status", plugin.messages().raw(alive.contains(viewer.getUniqueId()) ? "minigames.ffa-alive" : "minigames.ffa-dead"));
        map.put("winner", winnerName);
        map.put("player_hp", Text.num(Math.round(viewer.getHealth() * 10) / 10.0));
        return map;
    }
}
