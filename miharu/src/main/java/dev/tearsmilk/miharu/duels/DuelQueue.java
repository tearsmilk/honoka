package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.GameModule;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Очередь на дуэль. Обычная: двое в одном ките — сразу в игру.
 * Рейтинговая: отдельная очередь по киту, соперник подбирается по ELO (окно растёт со временем).
 */
public final class DuelQueue {

    public record Entry(UUID id, String kit, boolean ranked, long since) {
        String key() {
            return (ranked ? "ranked:" : "") + kit;
        }
    }

    private final DuelsModule module;
    private final Map<String, Deque<UUID>> byKey = new LinkedHashMap<>();
    private final Map<UUID, Entry> entries = new HashMap<>();

    DuelQueue(DuelsModule module) {
        this.module = module;
    }

    public Entry entry(Player p) {
        return entries.get(p.getUniqueId());
    }

    public boolean inQueue(Player p) {
        return entries.containsKey(p.getUniqueId());
    }

    /** Сколько ждут в обычной очереди кита. */
    public int size(String kit) {
        Deque<UUID> q = byKey.get(kit);
        return q == null ? 0 : q.size();
    }

    /** Сколько ждут в рейтинговой очереди кита. */
    public int rankedSize(String kit) {
        Deque<UUID> q = byKey.get("ranked:" + kit);
        return q == null ? 0 : q.size();
    }

    public int total() {
        return entries.size();
    }

    public void join(Player p, KitSpec spec) {
        join(p, spec, false);
    }

    public void join(Player p, KitSpec spec, boolean ranked) {
        GameModule games = module.plugin().module(GameModule.class);
        if (games.games().busy(p)) {
            module.plugin().messages().send(p, "game.already-in-game");
            return;
        }
        Entry cur = entries.get(p.getUniqueId());
        if (cur != null && cur.kit().equals(spec.id()) && cur.ranked() == ranked) {
            module.plugin().messages().send(p, "duels.queue.already", "kit", spec.display());
            return;
        }
        leave(p, true);
        Entry e = new Entry(p.getUniqueId(), spec.id(), ranked, System.currentTimeMillis());
        byKey.computeIfAbsent(e.key(), k -> new ArrayDeque<>()).addLast(p.getUniqueId());
        entries.put(p.getUniqueId(), e);
        if (ranked) {
            module.plugin().messages().send(p, "ranked.joined", "kit", spec.display(), "elo", module.elo().elo(p.getUniqueId(), spec.id()));
        } else {
            module.plugin().messages().send(p, "duels.queue.joined", "kit", spec.display());
        }
        module.changed(p);
        tryMatch(e.key());
    }

    public void leave(Player p, boolean silent) {
        Entry e = entries.remove(p.getUniqueId());
        if (e == null) {
            return;
        }
        Deque<UUID> q = byKey.get(e.key());
        if (q != null) {
            q.remove(p.getUniqueId());
        }
        if (!silent) {
            module.plugin().messages().send(p, "duels.queue.left");
        }
        module.changed(p);
    }

    /** Ищет пару в очереди key. В рейтинговой — только если разница ELO в допустимом окне. */
    private void tryMatch(String key) {
        Deque<UUID> q = byKey.get(key);
        if (q == null || q.size() < 2) {
            return;
        }
        Entry first = entries.get(q.peekFirst());
        KitSpec fs = first == null ? null : module.spec(first.kit());
        if (fs != null && fs.rules().players() > 2 && !first.ranked()) {
            if (q.size() >= fs.rules().players()) {
                List<Entry> group = new java.util.ArrayList<>();
                for (UUID id : List.copyOf(q)) {
                    Entry e = entries.get(id);
                    if (e != null && group.size() < fs.rules().players()) {
                        group.add(e);
                    }
                }
                if (group.size() == fs.rules().players()) {
                    matchGroup(group, fs);
                }
            }
            return;
        }
        List<UUID> list = List.copyOf(q);
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                Entry a = entries.get(list.get(i));
                Entry b = entries.get(list.get(j));
                if (a == null || b == null) {
                    continue;
                }
                if (a.ranked() && !module.ranked().canMatch(a, b)) {
                    continue;
                }
                match(a, b);
                return;
            }
        }
    }

    private void match(Entry ea, Entry eb) {
        Player a = Bukkit.getPlayer(ea.id());
        Player b = Bukkit.getPlayer(eb.id());
        KitSpec spec = module.spec(ea.kit());
        if (a == null || b == null || spec == null) {
            return;
        }
        for (Entry e : List.of(ea, eb)) {
            entries.remove(e.id());
            Deque<UUID> q = byKey.get(e.key());
            if (q != null) {
                q.remove(e.id());
            }
        }
        for (Player p : List.of(a, b)) {
            Player op = p == a ? b : a;
            if (ea.ranked()) {
                module.plugin().messages().send(p, "ranked.found", "opponent", op.getName(), "kit", spec.display(),
                        "elo", module.elo().elo(op.getUniqueId(), spec.id()));
            } else {
                module.plugin().messages().send(p, "duels.queue.found", "opponent", op.getName(), "kit", spec.display());
            }
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            module.changed(p);
        }
        module.startDuel(a, b, spec, ea.ranked());
    }

    private void matchGroup(List<Entry> group, KitSpec spec) {
        List<Player> players = new java.util.ArrayList<>();
        for (Entry e : group) {
            Player p = Bukkit.getPlayer(e.id());
            if (p == null) {
                return;
            }
            players.add(p);
        }
        for (Entry e : group) {
            entries.remove(e.id());
            Deque<UUID> q = byKey.get(e.key());
            if (q != null) {
                q.remove(e.id());
            }
        }
        for (Player p : players) {
            module.plugin().messages().send(p, "minigames.ffa-found", "kit", spec.display(), "count", players.size());
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            module.changed(p);
        }
        module.startFfa(players, spec);
    }

    void tick() {
        GameModule games = module.plugin().module(GameModule.class);
        for (Entry e : List.copyOf(entries.values())) {
            Player p = Bukkit.getPlayer(e.id());
            if (p == null || games.games().busy(p)) {
                entries.remove(e.id());
                Deque<UUID> q = byKey.get(e.key());
                if (q != null) {
                    q.remove(e.id());
                }
                continue;
            }
            KitSpec spec = module.spec(e.kit());
            String time = Text.time((System.currentTimeMillis() - e.since()) / 1000);
            if (e.ranked()) {
                module.plugin().messages().actionBar(p, "ranked.actionbar", "kit", spec == null ? e.kit() : spec.display(), "time", time,
                        "size", rankedSize(e.kit()), "range", module.ranked().range(e), "elo", module.elo().elo(p.getUniqueId(), e.kit()));
            } else {
                String size = spec != null && spec.rules().players() > 2 ? size(e.kit()) + "/" + spec.rules().players() : String.valueOf(size(e.kit()));
                module.plugin().messages().actionBar(p, "duels.queue.actionbar", "kit", spec == null ? e.kit() : spec.display(),
                        "time", time, "size", size);
            }
        }
        for (String key : List.copyOf(byKey.keySet())) {
            tryMatch(key);
        }
    }

    void clear() {
        entries.clear();
        byKey.clear();
    }
}
