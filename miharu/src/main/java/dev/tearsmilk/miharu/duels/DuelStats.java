package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.core.db.Database;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Победы/поражения в дуэлях по китам (data.db → miharu_duel_stats). */
public final class DuelStats {

    private final Database db;
    /** uuid → кит → {победы, поражения}. */
    private final Map<UUID, Map<String, int[]>> cache = new ConcurrentHashMap<>();

    DuelStats(Database db) {
        this.db = db;
    }

    void createTables() {
        db.schema("CREATE TABLE IF NOT EXISTS miharu_duel_stats (uuid VARCHAR(36) NOT NULL, name VARCHAR(16) NOT NULL, kit VARCHAR(32) NOT NULL, "
                + "wins INT NOT NULL DEFAULT 0, losses INT NOT NULL DEFAULT 0, PRIMARY KEY (uuid, kit))");
    }

    void load(UUID id) {
        Map<String, int[]> m = cache.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        db.query("SELECT kit, wins, losses FROM miharu_duel_stats WHERE uuid = ?", rs -> {
            while (rs.next()) {
                m.put(rs.getString(1), new int[]{rs.getInt(2), rs.getInt(3)});
            }
            return null;
        }, id.toString());
    }

    void unload(UUID id) {
        cache.remove(id);
    }

    public int wins(UUID id, String kit) {
        return sum(id, kit, 0);
    }

    public int losses(UUID id, String kit) {
        return sum(id, kit, 1);
    }

    private int sum(UUID id, String kit, int idx) {
        Map<String, int[]> m = cache.get(id);
        if (m == null) {
            return 0;
        }
        if (kit != null && !kit.isEmpty()) {
            int[] v = m.get(kit);
            return v == null ? 0 : v[idx];
        }
        return m.values().stream().mapToInt(v -> v[idx]).sum();
    }

    void record(Player winner, Player loser, String kit) {
        add(winner, kit, true);
        add(loser, kit, false);
    }

    private void add(Player p, String kit, boolean win) {
        int[] v = cache.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>()).computeIfAbsent(kit, k -> new int[2]);
        v[win ? 0 : 1]++;
        String col = win ? "wins" : "losses";
        db.upsert("UPDATE miharu_duel_stats SET " + col + " = " + col + " + 1, name = ? WHERE uuid = ? AND kit = ?",
                new Object[]{p.getName(), p.getUniqueId().toString(), kit},
                "INSERT INTO miharu_duel_stats (uuid, name, kit, wins, losses) VALUES (?, ?, ?, ?, ?)",
                new Object[]{p.getUniqueId().toString(), p.getName(), kit, win ? 1 : 0, win ? 0 : 1});
    }
}
