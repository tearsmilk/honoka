package dev.tearsmilk.miharu.core.db;

import dev.tearsmilk.miharu.Miharu;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Хранилище. По умолчанию SQLite (plugins/Miharu/data.db), для сети под Velocity — MySQL/MariaDB.
 * Все запросы идут в одном фоновом потоке, чтобы не блокировать сервер.
 */
public final class Database {

    public enum Type { SQLITE, MYSQL }

    @FunctionalInterface
    public interface Mapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final Miharu plugin;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Miharu-DB");
        t.setDaemon(true);
        return t;
    });
    private Connection connection;
    private Type type = Type.SQLITE;
    private String url;
    private String user;
    private String password;

    public Database(Miharu plugin) {
        this.plugin = plugin;
    }

    public Type type() {
        return type;
    }

    public void connect(ConfigurationSection section) {
        String t = section == null ? "sqlite" : section.getString("type", "sqlite");
        type = "mysql".equalsIgnoreCase(t) || "mariadb".equalsIgnoreCase(t) ? Type.MYSQL : Type.SQLITE;
        if (type == Type.SQLITE) {
            File file = new File(plugin.getDataFolder(), section == null ? "data.db" : section.getString("sqlite-file", "data.db"));
            file.getParentFile().mkdirs();
            url = "jdbc:sqlite:" + file.getAbsolutePath();
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException ignored) {
            }
        } else {
            ConfigurationSection m = section.getConfigurationSection("mysql");
            url = "jdbc:mysql://" + m.getString("host", "localhost") + ":" + m.getInt("port", 3306) + "/" + m.getString("database", "miharu")
                    + "?" + m.getString("params", "useSSL=false&characterEncoding=utf8");
            user = m.getString("user", "root");
            password = m.getString("password", "");
        }
        run(() -> {
            open();
            return null;
        }).join();
    }

    private void open() throws SQLException {
        if (connection != null && !connection.isClosed() && connection.isValid(2)) {
            return;
        }
        connection = type == Type.SQLITE ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
        if (type == Type.SQLITE) {
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
            }
        }
    }

    /** Фрагмент для автоинкремента первичного ключа под текущую СУБД. */
    public String autoIncrementPk() {
        return type == Type.SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT AUTO_INCREMENT PRIMARY KEY";
    }

    /** Выполняет DDL синхронно (на старте модуля). */
    public void schema(String... statements) {
        run(() -> {
            open();
            try (Statement st = connection.createStatement()) {
                for (String sql : statements) {
                    st.execute(sql);
                }
            }
            return null;
        }).join();
    }

    /** Есть ли колонка в таблице (для миграций старых data.db). */
    public boolean hasColumn(String table, String column) {
        return run(() -> {
            open();
            try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, null)) {
                while (rs.next()) {
                    if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
            return false;
        }).join();
    }

    /** Есть ли таблица. */
    public boolean hasTable(String table) {
        return run(() -> {
            open();
            try (ResultSet rs = connection.getMetaData().getTables(null, null, table, null)) {
                return rs.next();
            }
        }).join();
    }

    public CompletableFuture<Integer> update(String sql, Object... params) {
        return run(() -> {
            open();
            try (PreparedStatement ps = prepare(sql, params)) {
                return ps.executeUpdate();
            }
        });
    }

    /** UPDATE, а если строк не затронуто — INSERT. Работает одинаково в SQLite и MySQL. */
    public CompletableFuture<Void> upsert(String updateSql, Object[] updateParams, String insertSql, Object[] insertParams) {
        return run(() -> {
            open();
            int rows;
            try (PreparedStatement ps = prepare(updateSql, updateParams)) {
                rows = ps.executeUpdate();
            }
            if (rows == 0) {
                try (PreparedStatement ps = prepare(insertSql, insertParams)) {
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    public <T> CompletableFuture<T> query(String sql, Mapper<T> mapper, Object... params) {
        return run(() -> {
            open();
            try (PreparedStatement ps = prepare(sql, params); ResultSet rs = ps.executeQuery()) {
                return mapper.map(rs);
            }
        });
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        return ps;
    }

    @FunctionalInterface
    private interface SqlTask<T> {
        T run() throws Exception;
    }

    private <T> CompletableFuture<T> run(SqlTask<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(task.run());
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Ошибка БД", t);
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }
}
