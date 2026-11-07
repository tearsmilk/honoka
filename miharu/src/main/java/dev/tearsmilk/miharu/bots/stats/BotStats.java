package dev.tearsmilk.miharu.bots.stats;

import dev.tearsmilk.miharu.bots.BotsModule;
import dev.tearsmilk.miharu.bots.ai.BotDifficulty;
import dev.tearsmilk.miharu.bots.game.BotDuelGame;
import dev.tearsmilk.miharu.core.db.Database;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Статистика игр с ботами (data.db): победы/поражения по сложностям и рекорды клик-теста. */
public final class BotStats {

    /** Сводка игрока в памяти (для плейсхолдеров). */
    public static final class Summary {
        public final Map<String, int[]> duels = new ConcurrentHashMap<>();
        public volatile int bestClickTest;
        public volatile int bestCps;

        public int wins(String difficulty) {
            return sum(difficulty, 0);
        }

        public int losses(String difficulty) {
            return sum(difficulty, 1);
        }

        private int sum(String difficulty, int idx) {
            int total = 0;
            for (Map.Entry<String, int[]> e : duels.entrySet()) {
                if (difficulty == null || e.getKey().endsWith(":" + difficulty)) {
                    total += e.getValue()[idx];
                }
            }
            return total;
        }
    }

    public record TopEntry(String name, int score, int maxCps, String version) {
    }

    private static final String BOT_STATS_TABLE = "CREATE TABLE IF NOT EXISTS miharu_bot_stats (" +
            "uuid VARCHAR(36) NOT NULL, name VARCHAR(16) NOT NULL, kit VARCHAR(32) NOT NULL, version VARCHAR(4) NOT NULL, difficulty VARCHAR(32) NOT NULL, " +
            "wins INT NOT NULL DEFAULT 0, losses INT NOT NULL DEFAULT 0, draws INT NOT NULL DEFAULT 0, " +
            "PRIMARY KEY (uuid, kit, version, difficulty))";

    private final BotsModule module;
    private final Database db;
    private final Map<UUID, Summary> cache = new ConcurrentHashMap<>();

    public BotStats(BotsModule module, Database db) {
        this.module = module;
        this.db = db;
    }

    public void createTables() {
        // data.db от 0.1.0: в miharu_bot_stats ещё не было колонки kit — переносим записи с kit = 'default'
        if (db.hasTable("miharu_bot_stats") && !db.hasColumn("miharu_bot_stats", "kit")) {
            db.schema("ALTER TABLE miharu_bot_stats RENAME TO miharu_bot_stats_old");
            db.schema(BOT_STATS_TABLE,
                    "INSERT INTO miharu_bot_stats (uuid, name, kit, version, difficulty, wins, losses, draws) "
                            + "SELECT uuid, name, 'default', version, difficulty, wins, losses, draws FROM miharu_bot_stats_old",
                    "DROP TABLE miharu_bot_stats_old");
            module.plugin().getLogger().info("data.db: таблица miharu_bot_stats обновлена (добавлена колонка kit)");
        }
        db.schema(
                BOT_STATS_TABLE,
                "CREATE TABLE IF NOT EXISTS miharu_clicktest (" +
                        "id " + db.autoIncrementPk() + ", uuid VARCHAR(36) NOT NULL, name VARCHAR(16) NOT NULL, version VARCHAR(4) NOT NULL, " +
                        "difficulty VARCHAR(32) NOT NULL, clicks INT NOT NULL, hits INT NOT NULL, score INT NOT NULL, max_cps INT NOT NULL, " +
                        "knockoffs INT NOT NULL, falls INT NOT NULL, seconds INT NOT NULL, created BIGINT NOT NULL)"
        );
        try {
            db.schema("CREATE INDEX IF NOT EXISTS miharu_clicktest_score ON miharu_clicktest (score)");
        } catch (Exception ignored) {
            // MySQL без IF NOT EXISTS для индексов — не критично
        }
    }

    public Summary summary(UUID id) {
        return cache.computeIfAbsent(id, k -> new Summary());
    }

    public void load(UUID id) {
        Summary s = summary(id);
        db.query("SELECT kit, version, difficulty, wins, losses, draws FROM miharu_bot_stats WHERE uuid = ?", rs -> {
            while (rs.next()) {
                s.duels.put(rs.getString(1) + ":" + rs.getString(2) + ":" + rs.getString(3), new int[]{rs.getInt(4), rs.getInt(5), rs.getInt(6)});
            }
            return null;
        }, id.toString());
        db.query("SELECT MAX(score), MAX(max_cps) FROM miharu_clicktest WHERE uuid = ?", rs -> {
            if (rs.next()) {
                s.bestClickTest = rs.getInt(1);
                s.bestCps = rs.getInt(2);
            }
            return null;
        }, id.toString());
    }

    public void unload(UUID id) {
        cache.remove(id);
    }

    public void recordDuel(Player p, String kit, CombatVersion version, BotDifficulty difficulty, BotDuelGame.Result result) {
        String col = switch (result) {
            case WIN -> "wins";
            case LOSS -> "losses";
            case DRAW -> "draws";
        };
        int idx = result.ordinal();
        int[] arr = summary(p.getUniqueId()).duels.computeIfAbsent(kit + ":" + version.id() + ":" + difficulty.id(), k -> new int[3]);
        arr[idx]++;
        String uuid = p.getUniqueId().toString();
        db.upsert(
                "UPDATE miharu_bot_stats SET " + col + " = " + col + " + 1, name = ? WHERE uuid = ? AND kit = ? AND version = ? AND difficulty = ?",
                new Object[]{p.getName(), uuid, kit, version.id(), difficulty.id()},
                "INSERT INTO miharu_bot_stats (uuid, name, kit, version, difficulty, wins, losses, draws) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[]{uuid, p.getName(), kit, version.id(), difficulty.id(), result == BotDuelGame.Result.WIN ? 1 : 0,
                        result == BotDuelGame.Result.LOSS ? 1 : 0, result == BotDuelGame.Result.DRAW ? 1 : 0});
    }

    /** Сохраняет результат и возвращает прошлый рекорд (или null). */
    public CompletableFuture<Integer> recordClickTest(Player p, CombatVersion version, BotDifficulty difficulty,
                                                      int clicks, int hits, int maxCps, int knockoffs, int falls, int seconds) {
        int score = module.clickTestScore(clicks, hits);
        Summary s = summary(p.getUniqueId());
        int prev = s.bestClickTest;
        s.bestClickTest = Math.max(prev, score);
        s.bestCps = Math.max(s.bestCps, maxCps);
        return db.update("INSERT INTO miharu_clicktest (uuid, name, version, difficulty, clicks, hits, score, max_cps, knockoffs, falls, seconds, created) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.getUniqueId().toString(), p.getName(), version.id(), difficulty.id(), clicks, hits, score, maxCps, knockoffs, falls, seconds,
                System.currentTimeMillis()).thenApply(n -> prev == 0 ? null : prev);
    }

    public CompletableFuture<List<TopEntry>> topClickTest(int limit) {
        return db.query("SELECT name, MAX(score) AS best, MAX(max_cps) AS cps, MAX(version) FROM miharu_clicktest GROUP BY uuid, name ORDER BY best DESC LIMIT " + Math.max(1, Math.min(100, limit)),
                rs -> {
                    List<TopEntry> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new TopEntry(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getString(4)));
                    }
                    return out;
                });
    }
}
