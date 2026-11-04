package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.core.kit.Kit;
import dev.tearsmilk.miharu.core.util.Text;
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
    static final List<String> MINIGAME_DEFAULTS = List.of("bedwars", "skywars", "bridge", "fireball", "sumo", "mlgrush", "meetup", "boxpvp", "bowspleef", "gungame");
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
            // BoxPvP без классов (0.2.15–0.2.16) — заменяем на версию с классами
            File box = new File(dir, "boxpvp.yml");
            if (minigames && box.exists() && !YamlConfiguration.loadConfiguration(box).contains("classes")) {
                module.plugin().saveResource("modules/duels/" + folder + "/boxpvp.yml", true);
            }
            // 0.2.17 → командные режимы 2x4 / 3x4 / 4x4 (классы игрока не трогаем)
            if (minigames && box.exists()) {
                YamlConfiguration by = YamlConfiguration.loadConfiguration(box);
                if (!by.contains("team-sizes")) {
                    by.set("players", null);
                    by.set("team-sizes", List.of(1, 2, 3, 4));
                    by.set("teams", 4);
                    by.save(box);
                }
            }
            // 0.2.21: Танк без щита и с Защитой II; удержание квадрата 3 с
            if (minigames) {
                YamlConfiguration by = YamlConfiguration.loadConfiguration(box);
                if (by.isConfigurationSection("classes.tank") && by.getInt("classes.tank.revision", 1) < 2) {
                    if ("SHIELD".equalsIgnoreCase(by.getString("classes.tank.armor.offhand.material", ""))) {
                        by.set("classes.tank.armor.offhand", null);
                    }
                    for (String part : List.of("helmet", "chestplate", "leggings", "boots")) {
                        if (by.getInt("classes.tank.armor." + part + ".enchants.protection", 0) == 3) {
                            by.set("classes.tank.armor." + part + ".enchants.protection", 2);
                        }
                    }
                    List<String> lore = new ArrayList<>(by.getStringList("classes.tank.icon.lore"));
                    if (lore.size() > 1) {
                        lore.set(1, "<gray>Алмазная броня (Защита II), +4 сердца, Медлительность I");
                        by.set("classes.tank.icon.lore", lore);
                    }
                    if ("SHIELD".equalsIgnoreCase(by.getString("classes.tank.icon.material", ""))) {
                        by.set("classes.tank.icon.material", "DIAMOND_CHESTPLATE");
                    }
                    by.set("classes.tank.revision", 2);
                    by.save(box);
                }
                // 0.2.23: GunGame из шаблона (0.2.21–0.2.22) → полноценный режим на 30 уровней
                File gg = new File(dir, "gungame.yml");
                if (gg.exists() && !YamlConfiguration.loadConfiguration(gg).isConfigurationSection("levels")) {
                    module.plugin().saveResource("modules/duels/" + folder + "/gungame.yml", true);
                }
                // 0.2.22: BowSpleef — перо двойного прыжка в 5-м слоте, лук без Откидывания
                File bs = new File(dir, "bowspleef.yml");
                if (bs.exists()) {
                    YamlConfiguration sy = YamlConfiguration.loadConfiguration(bs);
                    if (!sy.contains("items.4")) {
                        sy.set("items.4.material", "FEATHER");
                        sy.set("items.4.amount", 3);
                        sy.set("items.4.name", "<white><b>Двойной прыжок</b> <gray>(2× пробел)");
                        sy.set("items.4.lore", List.of("<gray>Дважды нажми пробел — подкинет вверх", "<gray>Перьев = оставшихся прыжков"));
                        sy.set("items.0.enchants.punch", null);
                        sy.save(bs);
                    }
                }
                File cfgFile = new File(module.folder(), "config.yml");
                YamlConfiguration dc = YamlConfiguration.loadConfiguration(cfgFile);
                if (dc.getInt("boxpvp.hold-time", 3) == 10) {
                    dc.set("boxpvp.hold-time", 3);
                    dc.save(cfgFile);
                    module.config().set("boxpvp.hold-time", 3);
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
                if (version == null || (minigames && !"boxpvp".equals(file))) { // BoxPvP — только PvP 1.9
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
                // варианты: число игроков (players: [2, 3, 4]) и/или длина матча (variants: [3, 5, 7])
                List<KitSpec> variants = new ArrayList<>();
                boolean legacyPlayers = "players".equalsIgnoreCase(y.getString("variants-type", "rounds"));
                List<Integer> playerOpts = legacyPlayers ? y.getIntegerList("variants") : y.getIntegerList("players");
                // командные варианты: team-sizes: [1, 2, 3, 4] и teams: 4 → 1x4, 2x4, 3x4, 4x4 (точки p1…p4 — по одной на команду)
                List<Integer> teamSizes = y.getIntegerList("team-sizes");
                int teamsCount = Math.max(2, y.getInt("teams", 4));
                if (!legacyPlayers && !teamSizes.isEmpty()) {
                    playerOpts = teamSizes.stream().map(t -> Math.max(1, t) * teamsCount).distinct().toList();
                }
                List<Integer> roundOpts = legacyPlayers ? List.of() : y.getIntegerList("variants");
                String playersSuffix = y.getString(legacyPlayers ? "variant-suffix" : "players-suffix", "<gray>· {format}");
                String roundsSuffix = legacyPlayers ? "" : y.getString("variant-suffix", "<gray>· {count}");
                if (!playerOpts.isEmpty() || !roundOpts.isEmpty()) {
                    List<Integer> pList = playerOpts.isEmpty() ? List.of(2) : playerOpts;
                    List<Integer> rList = roundOpts.isEmpty() ? java.util.Collections.singletonList(null) : roundOpts;
                    for (int n : pList) {
                        for (Integer r : rList) {
                            int ts = teamSizes.isEmpty() ? 1 : Math.max(1, n / teamsCount);
                            String format = ts > 1 ? ts + "x" + teamsCount : n <= 2 ? "1x1" : "1x" + n;
                            String vid;
                            if (ts > 1) {
                                vid = id + "-" + format + (r == null ? "" : "-" + r);
                            } else if (playerOpts.isEmpty()) {
                                vid = id + "-" + r;
                            } else if (roundOpts.isEmpty()) {
                                vid = id + "-" + n;
                            } else {
                                vid = n <= 2 ? id + "-" + r : id + "-p" + n + "-" + r;
                            }
                            StringBuilder name = new StringBuilder(spec.display());
                            if (!playerOpts.isEmpty()) {
                                name.append(' ').append(playersSuffix.replace("{format}", format)
                                        .replace("{count}", String.valueOf(n)).replace("{opponents}", String.valueOf(Math.max(1, n - 1))));
                            }
                            if (r != null) {
                                name.append(' ').append(roundsSuffix.replace("{count}", String.valueOf(r)));
                            }
                            KitRules vr = rules.withPlayers(n);
                            if (r != null) {
                                vr = vr.withRounds(r);
                            }
                            // свой режим арены на каждое число игроков: sw-duo, sw-trio, sw-quatro…
                            String arenaMode = spec.arenaMode();
                            if (!playerOpts.isEmpty()) {
                                String shortName = y.getString("arena-short", module.arenaShort(spec.arenaMode()));
                                arenaMode = shortName + "-" + (ts > 1 ? format : module.sizeName(n));
                                module.registerVariantArenaMode(arenaMode, spec.arenaMode(), n / ts, rules.objective(), Text.plain(Text.mm(spec.display())),
                                        y.getStringList("player-points"), y.getStringList("extra-points"), format);
                            }
                            KitSpec v = new KitSpec(vid, name.toString(), spec.version(), spec.profile(), spec.kit(), vr,
                                    arenaMode, spec.arenas(), spec.bot());
                            module.setTeamSize(v.id(), ts);
                            module.setQuickMin(v.id(), y.getInt("quick-start-min", 0));
                            variants.add(v);
                            variantSpecs.put(v.id(), v);
                        }
                    }
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
            for (Entry base : kits.values()) {
                if (base.variants().contains(v)) {
                    return base.enabled() ? v : null;
                }
            }
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
