package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.core.kit.Kit;
import dev.tearsmilk.miharu.core.kit.KitRules;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.pvp.CombatService;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Киты дуэлей: modules/duels/kits/&lt;id&gt;.yml — содержимое и правила,
 * modules/duels/kits/config.yml — включение, слот и вид предмета в меню.
 * Новый кит = новый yml в папке (и при желании строка в config.yml).
 */
public final class DuelKits {

    /** Кит + как он выглядит в меню. */
    public record Entry(KitSpec spec, boolean enabled, int slot, ConfigurationSection icon) {
    }

    static final List<String> DEFAULTS = List.of("nodebuff", "debuff", "gapple", "builduhc", "combo", "boxing", "sumo", "classic",
            "archer", "soup", "bridge", "mlgrush", "leather", "opdiamond", "diamond", "uhc");

    private final DuelsModule module;
    private final Map<String, Entry> kits = new LinkedHashMap<>();
    private YamlConfiguration menuConfig;

    DuelKits(DuelsModule module) {
        this.module = module;
    }

    File dir() {
        File d = new File(module.folder(), "kits");
        d.mkdirs();
        return d;
    }

    void load(CombatService combat) {
        File dir = new File(module.folder(), "kits");
        if (!dir.exists()) {
            dir.mkdirs();
            for (String id : DEFAULTS) {
                module.plugin().saveResource("modules/duels/kits/" + id + ".yml", false);
            }
        }
        menuConfig = module.loadFile("kits/config.yml");
        kits.clear();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml") && !n.equals("config.yml"));
        if (files == null) {
            return;
        }
        List<File> sorted = new ArrayList<>(List.of(files));
        // порядок: как в config.yml, потом остальные по имени
        List<String> order = new ArrayList<>(menuConfig.getConfigurationSection("kits") == null ? List.of()
                : menuConfig.getConfigurationSection("kits").getKeys(false));
        sorted.sort((x, y) -> {
            String a = name(x);
            String b = name(y);
            int ia = order.indexOf(a);
            int ib = order.indexOf(b);
            if (ia < 0) {
                ia = Integer.MAX_VALUE;
            }
            if (ib < 0) {
                ib = Integer.MAX_VALUE;
            }
            return ia != ib ? Integer.compare(ia, ib) : a.compareTo(b);
        });
        int autoSlot = 10;
        for (File f : sorted) {
            String id = name(f);
            try {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                CombatVersion version = CombatVersion.parse(y.getString("version", "1.8"));
                if (version == null) {
                    version = CombatVersion.LEGACY;
                }
                KitRules rules = KitRules.load(y.getConfigurationSection("rules"), y.getInt("rounds", 1));
                KitSpec.Bot bot = y.getBoolean("bot.enabled", true)
                        ? new KitSpec.Bot(true, y.getInt("bot.heals", 0), y.getDouble("bot.heal-amount", 8), y.getDouble("bot.heal-below", 8),
                        y.getInt("bot.heal-cooldown-ticks", 30))
                        : KitSpec.Bot.DISABLED;
                KitSpec spec = new KitSpec(id, y.getString("display", id), version,
                        combat.derive(version, y.getConfigurationSection("combat")), new Kit(id, y), rules,
                        y.getString("arena-mode", "duel").toLowerCase(Locale.ROOT), y.getStringList("arenas"), bot);
                ConfigurationSection m = menuConfig.getConfigurationSection("kits." + id);
                boolean enabled = m == null || m.getBoolean("enabled", true);
                int slot = m != null && m.contains("slot") ? m.getInt("slot") : autoSlot;
                ConfigurationSection icon = m != null && m.isConfigurationSection("icon") ? m.getConfigurationSection("icon")
                        : y.getConfigurationSection("icon");
                kits.put(id, new Entry(spec, enabled, slot, icon));
                autoSlot++;
                if (autoSlot % 9 == 8) {
                    autoSlot += 2;
                }
            } catch (Exception e) {
                module.plugin().getLogger().warning("Кит " + f.getName() + " не загрузился: " + e.getMessage());
            }
        }
        module.plugin().getLogger().info("Китов дуэлей: " + kits.size());
    }

    private static String name(File f) {
        return f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
    }

    public YamlConfiguration menuConfig() {
        return menuConfig;
    }

    public Entry get(String id) {
        return id == null ? null : kits.get(id.toLowerCase(Locale.ROOT));
    }

    public KitSpec spec(String id) {
        Entry e = get(id);
        return e == null || !e.enabled() ? null : e.spec();
    }

    public Collection<Entry> all() {
        return kits.values();
    }

    public List<String> enabledIds() {
        return kits.values().stream().filter(Entry::enabled).map(e -> e.spec().id()).toList();
    }
}
