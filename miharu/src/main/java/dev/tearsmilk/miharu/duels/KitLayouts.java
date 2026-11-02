package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.core.db.Database;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Раскладки китов игроков (/kit edit) — data.db → miharu_kit_layout. */
public final class KitLayouts {

    private final Database db;
    private final Map<UUID, Map<String, int[]>> cache = new ConcurrentHashMap<>();

    KitLayouts(Database db) {
        this.db = db;
    }

    void createTables() {
        db.schema("CREATE TABLE IF NOT EXISTS miharu_kit_layout (uuid VARCHAR(36) NOT NULL, kit VARCHAR(48) NOT NULL, "
                + "layout VARCHAR(512) NOT NULL, PRIMARY KEY (uuid, kit))");
    }

    void load(UUID id) {
        Map<String, int[]> m = cache.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        db.query("SELECT kit, layout FROM miharu_kit_layout WHERE uuid = ?", rs -> {
            while (rs.next()) {
                int[] l = decode(rs.getString(2));
                if (l != null) {
                    m.put(rs.getString(1), l);
                }
            }
            return null;
        }, id.toString());
    }

    void unload(UUID id) {
        cache.remove(id);
    }

    public int[] get(UUID id, String kit) {
        Map<String, int[]> m = cache.get(id);
        return m == null ? null : m.get(kit);
    }

    public void set(UUID id, String kit, int[] layout) {
        Map<String, int[]> m = cache.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        if (layout == null) {
            m.remove(kit);
            db.update("DELETE FROM miharu_kit_layout WHERE uuid = ? AND kit = ?", id.toString(), kit);
            return;
        }
        m.put(kit, layout);
        String enc = encode(layout);
        db.upsert("UPDATE miharu_kit_layout SET layout = ? WHERE uuid = ? AND kit = ?", new Object[]{enc, id.toString(), kit},
                "INSERT INTO miharu_kit_layout (uuid, kit, layout) VALUES (?, ?, ?)", new Object[]{id.toString(), kit, enc});
    }

    private static String encode(int[] l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(l[i]);
        }
        return sb.toString();
    }

    private static int[] decode(String s) {
        String[] p = s.split(",");
        if (p.length != 36) {
            return null;
        }
        int[] l = new int[36];
        try {
            for (int i = 0; i < 36; i++) {
                l[i] = Integer.parseInt(p[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return l;
    }
}
