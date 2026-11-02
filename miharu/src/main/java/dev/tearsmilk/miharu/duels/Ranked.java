package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.menu.Menu;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Рейтинговые дуэли: незеритовый меч в лобби → сверху выбор 1.8 / 1.9 → кит → рейтинговая очередь.
 * Только игроки. Настройки — modules/duels/config.yml → ranked.
 */
public final class Ranked {

    private final DuelsModule module;

    Ranked(DuelsModule module) {
        this.module = module;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = module.config().getConfigurationSection("ranked");
        return s != null ? s : module.config().createSection("ranked");
    }

    /** Рейтинговые киты версии (ids из kits/ и kits-1.9/, только включённые). */
    public List<KitSpec> kits(CombatVersion v) {
        List<String> ids = cfg().getStringList(v == CombatVersion.MODERN ? "kits-1.9" : "kits-1.8");
        List<KitSpec> out = new ArrayList<>();
        for (String id : ids) {
            KitSpec s = module.spec(id);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    public List<String> allIds() {
        List<String> out = new ArrayList<>();
        kits(CombatVersion.LEGACY).forEach(s -> out.add(s.id()));
        kits(CombatVersion.MODERN).forEach(s -> out.add(s.id()));
        return out;
    }

    public boolean isRanked(String kit) {
        return allIds().contains(kit);
    }

    /** Текущее окно подбора по ELO для игрока в очереди. */
    public int range(DuelQueue.Entry e) {
        int start = cfg().getInt("match-range.start", 100);
        int grow = cfg().getInt("match-range.grow-per-10s", 50);
        int max = cfg().getInt("match-range.max", 1000);
        long sec = (System.currentTimeMillis() - e.since()) / 1000;
        return (int) Math.min(max, start + (sec / 10) * grow);
    }

    boolean canMatch(DuelQueue.Entry a, DuelQueue.Entry b) {
        int diff = Math.abs(module.elo().elo(a.id(), a.kit()) - module.elo().elo(b.id(), b.kit()));
        return diff <= Math.max(range(a), range(b));
    }

    public void open(Player p) {
        open(p, CombatVersion.LEGACY);
    }

    public void open(Player p, CombatVersion version) {
        ConfigurationSection m = cfg().getConfigurationSection("menu");
        if (m == null) {
            return;
        }
        Menu menu = new Menu(m.getInt("rows", 4), Text.mm(m.getString("title", "Рейтинг"), Map.of("version", version.id())));
        // переключатель сверху
        for (CombatVersion v : CombatVersion.values()) {
            String key = v == CombatVersion.MODERN ? "tab-1.9" : "tab-1.8";
            ConfigurationSection t = m.getConfigurationSection(key + (v == version ? ".selected" : ".normal"));
            if (t == null) {
                continue;
            }
            int slot = m.getInt(key + ".slot", v == CombatVersion.MODERN ? 5 : 3);
            menu.set(slot, Items.parse(t, Map.of()), pl -> {
                click(pl);
                open(pl, v);
            });
        }
        List<Integer> slots = m.getIntegerList("kit-slots");
        List<KitSpec> kits = kits(version);
        for (int i = 0; i < kits.size() && i < slots.size(); i++) {
            KitSpec spec = kits.get(i);
            Map<String, Object> ph = new HashMap<>();
            ph.put("kit", spec.display());
            ph.put("version", spec.version().id());
            ph.put("elo", module.elo().elo(p.getUniqueId(), spec.id()));
            ph.put("wins", module.elo().wins(p.getUniqueId(), spec.id()));
            ph.put("losses", module.elo().losses(p.getUniqueId(), spec.id()));
            ph.put("queue", module.queue().rankedSize(spec.id()));
            ph.put("playing", module.playing(spec.id()));
            ConfigurationSection icon = m.getConfigurationSection("kit-icons." + spec.id().replace("m-", "m_"));
            if (icon == null) {
                icon = m.getConfigurationSection("kit-icon");
            }
            menu.set(slots.get(i), Items.parse(icon, ph), pl -> {
                click(pl);
                pl.closeInventory();
                module.queue().join(pl, spec, true);
            });
        }
        if (kits.isEmpty() && m.isConfigurationSection("empty")) {
            menu.set(m.getInt("empty.slot", 22), Items.parse(m.getConfigurationSection("empty"), Map.of()), null);
        }
        ConfigurationSection f = m.getConfigurationSection("filler");
        if (f != null && f.getBoolean("enabled", true)) {
            menu.fill(Items.parse(f, Map.of()));
        }
        menu.open(p);
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }
}
