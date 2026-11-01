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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Очередь на дуэль: двое в одном ките — сразу в игру. */
public final class DuelQueue {

    public record Entry(UUID id, String kit, long since) {
    }

    private final DuelsModule module;
    private final Map<String, Deque<UUID>> byKit = new LinkedHashMap<>();
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

    public int size(String kit) {
        Deque<UUID> q = byKit.get(kit);
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
        Deque<UUID> q = byKit.computeIfAbsent(spec.id(), k -> new ArrayDeque<>());
        // ищем соперника, который ещё онлайн и свободен
        Iterator<UUID> it = q.iterator();
        while (it.hasNext()) {
            UUID otherId = it.next();
            Player other = Bukkit.getPlayer(otherId);
            if (other == null || games.games().busy(other)) {
                it.remove();
                entries.remove(otherId);
                continue;
            }
            it.remove();
            entries.remove(otherId);
            module.plugin().messages().send(p, "duels.queue.found", "opponent", other.getName(), "kit", spec.display());
            module.plugin().messages().send(other, "duels.queue.found", "opponent", p.getName(), "kit", spec.display());
            other.playSound(other.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            module.changed(other);
            if (!module.startDuel(other, p, spec)) {
                module.changed(p);
            }
            return;
        }
        q.addLast(p.getUniqueId());
        entries.put(p.getUniqueId(), new Entry(p.getUniqueId(), spec.id(), System.currentTimeMillis()));
        module.plugin().messages().send(p, "duels.queue.joined", "kit", spec.display());
        module.changed(p);
    }

    public void leave(Player p, boolean silent) {
        Entry e = entries.remove(p.getUniqueId());
        if (e == null) {
            return;
        }
        Deque<UUID> q = byKit.get(e.kit());
        if (q != null) {
            q.remove(p.getUniqueId());
        }
        if (!silent) {
            module.plugin().messages().send(p, "duels.queue.left");
        }
        module.changed(p);
    }

    void tick() {
        for (Entry e : java.util.List.copyOf(entries.values())) {
            Player p = Bukkit.getPlayer(e.id());
            GameModule games = module.plugin().module(GameModule.class);
            if (p == null || games.games().busy(p)) {
                entries.remove(e.id());
                Deque<UUID> q = byKit.get(e.kit());
                if (q != null) {
                    q.remove(e.id());
                }
                continue;
            }
            KitSpec spec = module.kits().spec(e.kit());
            module.plugin().messages().actionBar(p, "duels.queue.actionbar", "kit", spec == null ? e.kit() : spec.display(),
                    "time", Text.time((System.currentTimeMillis() - e.since()) / 1000), "size", size(e.kit()));
        }
    }

    void clear() {
        entries.clear();
        byKit.clear();
    }
}
