package dev.tearsmilk.miharu.duels;

import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * PvP-тиры HT1 … LT5. Отдельно на каждый кит (режим): можно быть HT1 в Sword и LT3 в Mace.
 * Тир берётся из ручной установки (/tier set), иначе — из ELO по порогам ranked.tiers.
 */
public final class Tiers {

    public record Tier(String id, String name, String display, int minElo) {
    }

    private final DuelsModule module;

    Tiers(DuelsModule module) {
        this.module = module;
    }

    private ConfigurationSection cfg() {
        return module.config().getConfigurationSection("ranked.tiers");
    }

    /** Все тиры сверху вниз. */
    public List<Tier> all() {
        List<Tier> out = new ArrayList<>();
        ConfigurationSection s = cfg();
        if (s == null) {
            return out;
        }
        for (Map<?, ?> m : s.getMapList("list")) {
            String id = String.valueOf(m.get("id"));
            Object min = m.get("min-elo");
            out.add(new Tier(id.toUpperCase(Locale.ROOT), String.valueOf(m.containsKey("name") ? m.get("name") : id),
                    String.valueOf(m.containsKey("display") ? m.get("display") : id), min instanceof Number n ? n.intValue() : Integer.MIN_VALUE));
        }
        return out;
    }

    public @Nullable Tier byId(String id) {
        if (id == null) {
            return null;
        }
        for (Tier t : all()) {
            if (t.id().equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }

    /** Тир игрока в ките или null — «без тира» (мало игр). */
    public @Nullable Tier of(UUID id, String kit) {
        String manual = module.elo().override(id, kit);
        if (manual != null) {
            Tier t = byId(manual);
            if (t != null) {
                return t;
            }
        }
        int games = module.elo().wins(id, kit) + module.elo().losses(id, kit);
        if (games < cfg().getInt("min-games", 3)) {
            return null;
        }
        int elo = module.elo().elo(id, kit);
        Tier last = null;
        for (Tier t : all()) {
            last = t;
            if (elo >= t.minElo()) {
                return t;
            }
        }
        return last;
    }

    /** Строка для табло/меню. */
    public String display(UUID id, String kit) {
        Tier t = of(id, kit);
        return t == null ? cfg().getString("unranked", "<gray>Без тира") : t.display();
    }

    public boolean manual(UUID id, String kit) {
        return module.elo().override(id, kit) != null;
    }
}
