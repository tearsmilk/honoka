package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.arena.Arena;
import dev.tearsmilk.miharu.arena.ArenaMode;
import dev.tearsmilk.miharu.arena.ArenaModule;
import dev.tearsmilk.miharu.core.kit.Kit;
import dev.tearsmilk.miharu.core.kit.KitRules;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.menu.Menu;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.Game;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import dev.tearsmilk.miharu.pvp.PvpModule;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Свободные арены Sword / SpearMace / ElytraMace (modules/duels/config.yml → free-arenas).
 * NPC в лобби (/ffa npc &lt;тип&gt;) → выбор своего кита → на арену. Одна игра на тип, живёт всё время работы сервера.
 */
public final class FreeArenas implements Listener {

    static final NamespacedKey NPC = new NamespacedKey("miharu", "ffa_npc");

    private final DuelsModule module;
    private final Map<String, KitSpec> specs = new LinkedHashMap<>();
    private final Map<String, FreeArenaGame> running = new HashMap<>();
    private final Map<UUID, Kit> pendingKit = new HashMap<>();
    /** Типы с ванильным PvP 1.21.11 (version: vanilla) — плагин не трогает бой. */
    private final java.util.Set<String> vanilla = new java.util.HashSet<>();

    boolean vanilla(String type) {
        return vanilla.contains(type);
    }

    FreeArenas(DuelsModule module) {
        this.module = module;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = module.config().getConfigurationSection("free-arenas");
        return s != null ? s : module.config().createSection("free-arenas");
    }

    int combatSeconds() {
        return Math.max(1, cfg().getInt("combat-seconds", 10));
    }

    int spawnProtection() {
        return Math.max(0, cfg().getInt("spawn-protection", 2));
    }

    /** Режимы арен и описания игр по типам из конфига. */
    void load() {
        specs.clear();
        vanilla.clear();
        ArenaModule arenas = module.plugin().module(ArenaModule.class);
        ConfigurationSection types = cfg().getConfigurationSection("types");
        if (types == null) {
            return;
        }
        var pvp = module.plugin().module(PvpModule.class).combat();
        for (String id : types.getKeys(false)) {
            ConfigurationSection t = types.getConfigurationSection(id);
            if (t == null || !t.getBoolean("enabled", true)) {
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            String ver = t.getString("version", "vanilla");
            if (ver.equalsIgnoreCase("vanilla")) {
                vanilla.add(key);
            }
            CombatVersion v = CombatVersion.parse(ver);
            if (v == null) {
                v = CombatVersion.MODERN;
            }
            KitRules rules = KitRules.load(t.getConfigurationSection("rules"), 1);
            KitSpec spec = new KitSpec("ffa-" + key, t.getString("display", id), v, pvp.profile(v),
                    new Kit("ffa-" + key, new HashMap<>(), new HashMap<>()), rules, key, List.of(), KitSpec.Bot.DISABLED);
            specs.put(key, spec);
            if (arenas != null) {
                arenas.registerMode(new ArenaMode(key, Text.plain(Text.mm(spec.display())), List.of("spawn")));
            }
        }
    }

    Collection<String> types() {
        return specs.keySet();
    }

    Kit takeKit(Player p) {
        return pendingKit.remove(p.getUniqueId());
    }

    // --- выбор кита и вход ---

    void openKits(Player p, String type) {
        KitSpec spec = specs.get(type);
        if (spec == null) {
            return;
        }
        GameModule games = module.plugin().module(GameModule.class);
        if (games.games().busy(p)) {
            module.plugin().messages().send(p, "game.already-in-game");
            return;
        }
        ConfigurationSection m = cfg().getConfigurationSection("kit-menu");
        Menu menu = new Menu(3, Text.mm(m.getString("title", "Выбери кит"), Map.of("arena", spec.display())));
        List<Integer> slots = m.getIntegerList("slots");
        boolean any = false;
        for (int i = 1; i <= CustomKits.SLOTS && i - 1 < slots.size(); i++) {
            int n = i;
            CustomKits.CustomKit kit = module.customKits().get(p.getUniqueId(), n);
            Map<String, Object> ph = Map.of("slot", n, "name", kit == null ? "" : kit.name(), "items", kit == null ? 0 : kit.size(), "arena", spec.display());
            menu.set(slots.get(i - 1), Items.parse(m.getConfigurationSection(kit == null ? "empty" : "kit"), ph), pl -> {
                pl.playSound(pl.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                if (kit == null) {
                    pl.closeInventory();
                    module.builder().openSlots(pl);
                } else {
                    pl.closeInventory();
                    join(pl, type, kit);
                }
            });
            any |= kit != null;
        }
        ConfigurationSection f = m.getConfigurationSection("filler");
        if (f != null) {
            menu.fill(Items.parse(f, Map.of()));
        }
        menu.open(p);
        if (!any) {
            module.plugin().messages().send(p, "free-arena.no-kits");
        }
    }

    void join(Player p, String type, CustomKits.CustomKit kit) {
        KitSpec spec = specs.get(type);
        GameModule games = module.plugin().module(GameModule.class);
        if (spec == null || games == null) {
            return;
        }
        if (games.games().busy(p)) {
            module.plugin().messages().send(p, "game.already-in-game");
            return;
        }
        module.queue().leave(p, true);
        pendingKit.put(p.getUniqueId(), kit.toKit(p.getUniqueId()));
        FreeArenaGame g = running.get(type);
        if (g != null && games.games().games().contains(g) && g.canLateJoin()) {
            if (!games.games().addPlayer(g, p)) {
                pendingKit.remove(p.getUniqueId());
            }
            return;
        }
        ArenaModule arenas = module.plugin().module(ArenaModule.class);
        List<Arena> list = arenas.arenas().forMode(type);
        if (list.isEmpty()) {
            pendingKit.remove(p.getUniqueId());
            module.plugin().messages().send(p, "duels.no-arena", "kit", spec.display());
            return;
        }
        Arena arena = list.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(list.size()));
        FreeArenaGame game = new FreeArenaGame(this, games, arena, type, spec, module.plugin().module(PvpModule.class).combat());
        if (games.games().start(game, new ArrayList<>(List.of(p)))) {
            running.put(type, game);
        } else {
            pendingKit.remove(p.getUniqueId());
        }
    }

    // --- NPC ---

    private void spawnNpc(Player admin, String type) {
        KitSpec spec = specs.get(type);
        ConfigurationSection t = cfg().getConfigurationSection("types." + type);
        Location l = admin.getLocation();
        l.getWorld().spawn(l, Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setCollidable(false);
            v.setProfession(Villager.Profession.WEAPONSMITH);
            v.customName(Text.mm(t == null ? spec.display() : t.getString("npc-name", spec.display()), Map.of("arena", spec.display())));
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(NPC, PersistentDataType.STRING, type);
        });
        module.plugin().messages().send(admin, "free-arena.npc-placed", "arena", spec.display());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpc(PlayerInteractEntityEvent e) {
        String type = e.getRightClicked().getPersistentDataContainer().get(NPC, PersistentDataType.STRING);
        if (type == null) {
            return;
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        p.closeInventory();
        module.plugin().getServer().getScheduler().runTask(module.plugin(), () -> openKits(p, type));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcAt(PlayerInteractAtEntityEvent e) {
        if (e.getRightClicked().getPersistentDataContainer().has(NPC)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNpcDamage(EntityDamageEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(NPC)) {
            e.setCancelled(true);
        }
    }

    /** /ffa &lt;тип&gt; — выбрать кит и зайти; /ffa npc &lt;тип&gt; | remove — NPC (OP). */
    BasicCommand command() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                if (!(source.getSender() instanceof Player p)) {
                    module.plugin().messages().send(source.getSender(), "players-only");
                    return;
                }
                if (args.length >= 1 && args[0].equalsIgnoreCase("npc")) {
                    if (!p.hasPermission("miharu.admin")) {
                        module.plugin().messages().send(p, "no-permission");
                        return;
                    }
                    if (args.length >= 2 && args[1].equalsIgnoreCase("remove")) {
                        Entity near = null;
                        for (Entity en : p.getNearbyEntities(3, 3, 3)) {
                            if (en.getPersistentDataContainer().has(NPC)) {
                                near = en;
                                break;
                            }
                        }
                        if (near == null) {
                            module.plugin().messages().send(p, "free-arena.npc-none");
                        } else {
                            near.remove();
                            module.plugin().messages().send(p, "free-arena.npc-removed");
                        }
                        return;
                    }
                    String type = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
                    if (!specs.containsKey(type)) {
                        module.plugin().messages().send(p, "free-arena.usage", "types", String.join(", ", specs.keySet()));
                        return;
                    }
                    spawnNpc(p, type);
                    return;
                }
                String type = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "";
                if (!specs.containsKey(type)) {
                    module.plugin().messages().send(p, "free-arena.usage", "types", String.join(", ", specs.keySet()));
                    return;
                }
                openKits(p, type);
            }

            @Override
            public Collection<String> suggest(CommandSourceStack source, String[] args) {
                List<String> out = new ArrayList<>(specs.keySet());
                if (args.length <= 1) {
                    if (source.getSender().hasPermission("miharu.admin")) {
                        out.add("npc");
                    }
                    return out;
                }
                if (args.length == 2 && args[0].equalsIgnoreCase("npc")) {
                    out.add("remove");
                    return out;
                }
                return List.of();
            }
        };
    }
}
