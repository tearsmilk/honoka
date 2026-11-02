package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.core.db.Database;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** ELO по рейтинговым китам (data.db → miharu_elo). Старт — ranked.start-elo (0), может уходить в минус. */
public final class EloStore {

    public record Top(String name, int elo, int wins, int losses) {
    }

    private final DuelsModule module;
    private final Database db;
    /** uuid → кит → {elo, победы, поражения}. */
    private final Map<UUID, Map<String, int[]>> cache = new ConcurrentHashMap<>();

    EloStore(DuelsModule module, Database db) {
        this.module = module;
        this.db = db;
    }

    void createTables() {
        db.schema("CREATE TABLE IF NOT EXISTS miharu_elo (uuid VARCHAR(36) NOT NULL, name VARCHAR(16) NOT NULL, kit VARCHAR(32) NOT NULL, "
                + "elo INT NOT NULL, wins INT NOT NULL DEFAULT 0, losses INT NOT NULL DEFAULT 0, PRIMARY KEY (uuid, kit))");
    }

    private int start() {
        return module.config().getInt("ranked.start-elo", 0);
    }

    void load(UUID id) {
        Map<String, int[]> m = cache.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        db.query("SELECT kit, elo, wins, losses FROM miharu_elo WHERE uuid = ?", rs -> {
            while (rs.next()) {
                m.put(rs.getString(1), new int[]{rs.getInt(2), rs.getInt(3), rs.getInt(4)});
            }
            return null;
        }, id.toString());
    }

    void unload(UUID id) {
        cache.remove(id);
    }

    private int[] row(UUID id, String kit) {
        return cache.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).computeIfAbsent(kit, k -> new int[]{start(), 0, 0});
    }

    public int elo(UUID id, String kit) {
        return row(id, kit)[0];
    }

    public int wins(UUID id, String kit) {
        return row(id, kit)[1];
    }

    public int losses(UUID id, String kit) {
        return row(id, kit)[2];
    }

    /**
     * Обновляет ELO после матча. winner == null — ничья.
     *
     * @return {изменение для a, изменение для b}
     */
    int[] apply(Player a, Player b, String kit, Player winner) {
        int[] ra = row(a.getUniqueId(), kit);
        int[] rb = row(b.getUniqueId(), kit);
        double k = module.config().getDouble("ranked.k-factor", 32);
        double ea = 1.0 / (1.0 + Math.pow(10, (rb[0] - ra[0]) / 400.0));
        double sa = winner == null ? 0.5 : winner == a ? 1 : 0;
        int da = (int) Math.round(k * (sa - ea));
        if (winner != null && da == 0) {
            da = winner == a ? 1 : -1;
        }
        int db_ = -da;
        ra[0] += da;
        rb[0] += db_;
        if (winner == a) {
            ra[1]++;
            rb[2]++;
        } else if (winner == b) {
            rb[1]++;
            ra[2]++;
        }
        save(a, kit, ra);
        save(b, kit, rb);
        return new int[]{da, db_};
    }

    private void save(Player p, String kit, int[] r) {
        db.upsert("UPDATE miharu_elo SET elo = ?, wins = ?, losses = ?, name = ? WHERE uuid = ? AND kit = ?",
                new Object[]{r[0], r[1], r[2], p.getName(), p.getUniqueId().toString(), kit},
                "INSERT INTO miharu_elo (uuid, name, kit, elo, wins, losses) VALUES (?, ?, ?, ?, ?, ?)",
                new Object[]{p.getUniqueId().toString(), p.getName(), kit, r[0], r[1], r[2]});
    }

    public CompletableFuture<List<Top>> top(String kit, int limit) {
        return db.query("SELECT name, elo, wins, losses FROM miharu_elo WHERE kit = ? ORDER BY elo DESC LIMIT " + Math.max(1, Math.min(100, limit)), rs -> {
            List<Top> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Top(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
            }
            return out;
        }, kit);
    }
}
