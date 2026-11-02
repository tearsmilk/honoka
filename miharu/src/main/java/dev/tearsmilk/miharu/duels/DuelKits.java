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

    /** Кит + как он выглядит в меню. variants — варианты матча (Bridge до 3/5/7 голов), пусто — без выбора. */
    public record Entry(KitSpec spec, boolean enabled, int slot, ConfigurationSection icon, List<KitSpec> variants) {
    }

    static final List<String> LEGACY_DEFAULTS = List.of("nodebuff", "debuff", "gapple", "builduhc", "combo", "boxing", "classic",
            "archer", "soup", "leather", "opdiamond", "diamond", "uhc");
    static final List<String> MINIGAME_DEFAULTS = List.of("bedwars", "skywars", "bridge", "fireball", "sumo", "mlgrush");
    /** Режимы, которые переехали из дуэлей 1.8 в мини-игры. */
    static final List<String> MOVED_TO_MINIGAMES = List.of("sumo", "bridge", "mlgrush");
    static final List<String> MODERN_DEFAULTS = List.of("swordshield", "diamond", "opdiamond", "heavy", "axeshield", "mace", "opmace",
            "trident", "spear", "netheritespear", "lungespear", "crossbow", "archer", "spearshield", "survival", "crystal",
            "elytramace", "tntminecart", "anchor");

    private final DuelsModule module;
    /** Папка в modules/duels (kits — 1.8, kits-1.9 — медленное PvP). */
    private final String folder;
    /** Приставка к id, чтобы киты разных категорий не путались в статистике и очереди. */
    private final String prefix;
    private final CombatVersion defaultVersion;
    private final List<String> defaults;
    private final Map<String, KitSpec> variantSpecs = new LinkedHashMap<>();
    /** Мини-игры: всегда 1.8 и без бота. */
    private boolean minigames;
    private final Map<String, Entry> kits = new LinkedHashMap<>();
    private YamlConfiguration menuConfig;

    DuelKits(DuelsModule module, String folder, String prefix, CombatVersion defaultVersion, List<String> defaults) {
        this.module = module;
        this.folder = folder;
        this.prefix = prefix;
        this.defaultVersion = defaultVersion;
        this.defaults = defaults;
    }

    public CombatVersion version() {
        return defaultVersion;
    }

    DuelKits minigames() {
        this.minigames = true;
        return this;
    }

    public boolean isMinigames() {
        return minigames;
    }

    File dir() {
        File d = new File(module.folder(), folder);
        d.mkdirs();
        return d;
    }

    void load(CombatService combat) {
        File dir = new File(module.folder(), folder);
        boolean fresh = !dir.exists();
        dir.mkdirs();
        // новые встроенные киты докладываются один раз (удалённые вручную не возвращаются)
        java.util.Set<String> known = new java.util.HashSet<>();
        File marker = new File(dir, ".installed");
        try {
            if (marker.exists()) {
                known.addAll(java.nio.file.Files.readAllLines(marker.toPath()));
            } else if (!fresh) {
                File[] existing = dir.listFiles((d, n) -> n.endsWith(".yml"));
                if (existing != null) {
                    for (File f : existing) {
                        known.add(name(f));
                    }
                }
                if (folder.equals("kits")) {
                    known.addAll(LEGACY_DEFAULTS);
                    known.addAll(MOVED_TO_MINIGAMES);
                } else if (folder.equals("kits-1.9")) {
                    known.addAll(MODERN_DEFAULTS.subList(0, 16));
                }
            }
            for (String id : defaults) {
                if (known.add(id) && !new File(dir, id + ".yml").exists()) {
                    module.plugin().saveResource("modules/duels/" + folder + "/" + id + ".yml", false);
                }
            }
            java.nio.file.Files.write(marker.toPath(), known);
        } catch (java.io.IOException e) {
            module.plugin().getLogger().warning("Не удалось обновить список китов: " + e.getMessage());
        }
        menuConfig = module.loadFile(folder + "/config.yml");
        kits.clear();
        variantSpecs.clear();
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
            String file = name(f);
            String id = prefix + file;
            if (folder.equals("kits") && MOVED_TO_MINIGAMES.contains(file)) {
                continue; // теперь в мини-играх (modules/duels/minigames)
            }
            try {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                CombatVersion version = CombatVersion.parse(y.getString("version", defaultVersion.id()));
                if (version == null || minigames) {
                    version = defaultVersion;
                }
                KitRules rules = KitRules.load(y.getConfigurationSection("rules"), y.getInt("rounds", 1));
                KitSpec.Bot bot = !minigames && y.getBoolean("bot.enabled", true)
                        ? new KitSpec.Bot(true, y.getInt("bot.heals", 0), y.getDouble("bot.heal-amount", 8), y.getDouble("bot.heal-below", 8),
                        y.getInt("bot.heal-cooldown-ticks", 30))
                        : KitSpec.Bot.DISABLED;
                KitSpec spec = new KitSpec(id, y.getString("display", id), version,
                        combat.derive(version, y.getConfigurationSection("combat")), new Kit(id, y), rules,
                        y.getString("arena-mode", "duel").toLowerCase(Locale.ROOT), y.getStringList("arenas"), bot);
                ConfigurationSection m = menuConfig.getConfigurationSection("kits." + file);
                boolean enabled = m == null || m.getBoolean("enabled", true);
                int slot = m != null && m.contains("slot") ? m.getInt("slot") : autoSlot;
                ConfigurationSection icon = m != null && m.isConfigurationSection("icon") ? m.getConfigurationSection("icon")
                        : y.getConfigurationSection("icon");
                List<KitSpec> variants = new ArrayList<>();
                boolean byPlayers = "players".equalsIgnoreCase(y.getString("variants-type", "rounds"));
                for (int n : y.getIntegerList("variants")) {
                    String format = n <= 2 ? "1x1" : "1x" + n;
                    String suffix = y.getString("variant-suffix", byPlayers ? "<gray>· {format}" : "<gray>· {count}")
                            .replace("{count}", String.valueOf(n)).replace("{format}", format)
                            .replace("{opponents}", String.valueOf(Math.max(1, n - 1)));
                    KitSpec v = new KitSpec(id + "-" + n, spec.display() + " " + suffix,
                            spec.version(), spec.profile(), spec.kit(), byPlayers ? rules.withPlayers(n) : rules.withRounds(n),
                            spec.arenaMode(), spec.arenas(), spec.bot());
                    variants.add(v);
                    variantSpecs.put(v.id(), v);
                }
                kits.put(id, new Entry(spec, enabled, slot, icon, variants));
                autoSlot++;
                if (autoSlot % 9 == 8) {
                    autoSlot += 2;
                }
            } catch (Exception e) {
                module.plugin().getLogger().warning("Кит " + f.getName() + " не загрузился: " + e.getMessage());
            }
        }
        module.plugin().getLogger().info("Китов дуэлей (" + folder + "): " + kits.size());
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
        if (e != null) {
            return e.enabled() ? e.spec() : null;
        }
        KitSpec v = id == null ? null : variantSpecs.get(id.toLowerCase(Locale.ROOT));
        if (v != null) {
            Entry base = get(v.id().substring(0, v.id().lastIndexOf('-')));
            return base != null && base.enabled() ? v : null;
        }
        return null;
    }

    public Collection<Entry> all() {
        return kits.values();
    }

    public List<String> enabledIds() {
        List<String> out = new ArrayList<>();
        for (Entry e : kits.values()) {
            if (!e.enabled()) {
                continue;
            }
            if (e.variants().isEmpty()) {
                out.add(e.spec().id());
            } else {
                e.variants().forEach(v -> out.add(v.id()));
            }
        }
        return out;
    }
}
