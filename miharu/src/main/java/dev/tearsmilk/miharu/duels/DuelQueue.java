package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.GameModule;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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

    public record Entry(UUID id, String kit, long since) {
        String key() {
            return kit;
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

    public int total() {
        return entries.size();
    }

    public void join(Player p, KitSpec spec) {
        GameModule games = module.plugin().module(GameModule.class);
        if (games.games().busy(p)) {
            module.plugin().messages().send(p, "game.already-in-game");
            return;
        }
        Entry cur = entries.get(p.getUniqueId());
        if (cur != null && cur.kit().equals(spec.id())) {
            module.plugin().messages().send(p, "duels.queue.already", "kit", spec.display());
            return;
        }
        leave(p, true);
        if (module.lateJoin(p, spec)) {
            return; // зашёл в игру, которая ждёт игроков (SkyControl)
        }
        Entry e = new Entry(p.getUniqueId(), spec.id(), System.currentTimeMillis());
        byKey.computeIfAbsent(e.key(), k -> new ArrayDeque<>()).addLast(p.getUniqueId());
        entries.put(p.getUniqueId(), e);
        module.plugin().messages().send(p, "duels.queue.joined", "kit", spec.display());
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
        module.meetups().unvote(p.getUniqueId());
        removeStartVoteItem(p);
        module.changed(p);
    }

    private static void removeStartVoteItem(Player p) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            if (Meetups.isStartVoteItem(p.getInventory().getItem(i))) {
                p.getInventory().setItem(i, null);
            }
        }
    }

    /** Быстрый старт: секунд до старта по очереди key. */
    private final Map<String, Integer> quickLeft = new HashMap<>();

    int quickLeft(String key) {
        return quickLeft.getOrDefault(key, 0);
    }

    /**
     * Быстрый старт во всех групповых режимах: набралась половина участников — идёт таймер (quick-start.seconds),
     * по истечении игра стартует с теми, кто есть; голосование предметом запускает сразу. Набрались все — обычный старт.
     */
    private void updateStartVoteItems() {
        quickLeft.keySet().removeIf(k -> !module.meetups().startVoteReady(k, waiting(k)));
        for (String key : List.copyOf(byKey.keySet())) {
            if (module.meetups().groupSize(key) == 0) {
                continue;
            }
            int n = waiting(key);
            boolean ready = module.meetups().startVoteReady(key, n);
            if (!ready) {
                module.meetups().clearVotes(key);
            } else {
                int total = Math.max(5, module.meetups().quick().getInt("seconds", 30));
                Integer left = quickLeft.get(key);
                if (left == null) {
                    left = total;
                    for (UUID id : players(key)) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) {
                            module.plugin().messages().send(p, "duels.quick-start.started", "seconds", total, "waiting", n,
                                    "players", module.meetups().groupSize(key));
                        }
                    }
                } else {
                    left--;
                }
                quickLeft.put(key, left);
                if (left <= 0) {
                    quickLeft.remove(key);
                    module.meetups().clearVotes(key);
                    KitSpec spec = module.meetups().mode(key) != null ? module.meetups().spec(key) : module.spec(key);
                    if (spec != null && forceStart(key, spec) > 0) {
                        continue;
                    }
                } else if (left <= 5 || left % 10 == 0) {
                    for (UUID id : players(key)) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) {
                            module.plugin().messages().send(p, "duels.quick-start.countdown", "seconds", left, "waiting", n,
                                    "players", module.meetups().groupSize(key));
                        }
                    }
                }
            }
            for (UUID id : players(key)) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) {
                    continue;
                }
                removeStartVoteItem(p);
                if (ready) {
                    int slot = module.meetups().quick().getInt("slot", -1);
                    ItemStack item = module.meetups().startVoteItem(key, n);
                    if (slot >= 0 && slot < 36 && p.getInventory().getItem(slot) == null) {
                        p.getInventory().setItem(slot, item);
                    } else {
                        int free = p.getInventory().firstEmpty();
                        if (free >= 0 && free < 36) {
                            p.getInventory().setItem(free, item);
                        }
                    }
                }
            }
        }
    }

    /** Ищет пару в очереди key. В рейтинговой — только если разница ELO в допустимом окне. */
    private void tryMatch(String key) {
        Deque<UUID> q = byKey.get(key);
        if (q == null || q.size() < 2) {
            return;
        }
        Entry first = entries.get(q.peekFirst());
        KitSpec fs = first == null ? null : module.spec(first.kit());
        if (fs != null && (fs.rules().players() > 2 || module.meetups().mode(fs.id()) != null)) {
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
            module.plugin().messages().send(p, "duels.queue.found", "opponent", op.getName(), "kit", spec.display());
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            module.changed(p);
        }
        module.startDuel(a, b, spec);
    }

    /** Кто ждёт в очереди key. */
    List<UUID> players(String key) {
        Deque<UUID> q = byKey.get(key);
        return q == null ? List.of() : List.copyOf(q);
    }

    /** Сколько ждут в очереди key (без рейтинговых). */
    int waiting(String key) {
        Deque<UUID> q = byKey.get(key);
        return q == null ? 0 : q.size();
    }

    /**
     * Принудительный старт (/force-start): все, кто сейчас в очереди key, сразу в игру (минимум двое).
     *
     * @return сколько игроков запущено (0 — не хватило)
     */
    int forceStart(String key, KitSpec spec) {
        Deque<UUID> q = byKey.get(key);
        if (q == null) {
            return 0;
        }
        List<Entry> group = new java.util.ArrayList<>();
        for (UUID id : List.copyOf(q)) {
            Entry e = entries.get(id);
            if (e != null && Bukkit.getPlayer(id) != null && group.size() < spec.rules().players()) {
                group.add(e);
            }
        }
        if (group.size() < 2) {
            return 0;
        }
        matchGroup(group, spec);
        return group.size();
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
            {
                boolean group = spec != null && (spec.rules().players() > 2 || module.meetups().mode(spec.id()) != null);
                int full = module.meetups().groupSize(e.kit());
                String size = group ? size(e.kit()) + "/" + (full > 0 ? full : spec.rules().players()) : String.valueOf(size(e.kit()));
                if (quickLeft.containsKey(e.key())) {
                    size += module.plugin().messages().raw("duels.quick-start.actionbar").replace("{seconds}", String.valueOf(quickLeft.get(e.key())));
                }
                module.plugin().messages().actionBar(p, "duels.queue.actionbar", "kit", spec == null ? e.kit() : spec.display(),
                        "time", time, "size", size);
            }
        }
        for (String key : List.copyOf(byKey.keySet())) {
            tryMatch(key);
        }
        updateStartVoteItems();
    }

    void clear() {
        entries.clear();
        byKey.clear();
    }
}
