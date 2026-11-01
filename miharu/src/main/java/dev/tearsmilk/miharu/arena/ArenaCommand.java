package dev.tearsmilk.miharu.arena;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.arena.schematic.SchematicEngine;
import dev.tearsmilk.miharu.arena.world.ArenaInstance;
import dev.tearsmilk.miharu.core.command.SubCommands;
import dev.tearsmilk.miharu.core.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * /arena create &lt;id&gt; · pos1 · pos2 · spawn &lt;точка&gt; · void · mode &lt;режим&gt; · display &lt;текст&gt; · icon [материал]
 * /arena info · save · cancel · edit &lt;id&gt; · delete &lt;id&gt; · list · enable/disable &lt;id&gt;
 */
final class ArenaCommand extends SubCommands {

    private final ArenaModule module;

    ArenaCommand(Miharu plugin, ArenaModule module) {
        super(plugin, "miharu.arena");
        this.module = module;
        String perm = "miharu.arena";
        sub("create", perm, true, this::create);
        sub("pos1", perm, true, (s, a) -> pos(s, a, true));
        sub("pos2", perm, true, (s, a) -> pos(s, a, false));
        sub("spawn", perm, true, this::spawn, (s, a) -> a.length <= 1 ? spawnKeys(s) : List.of());
        sub("void", perm, true, this::voidY);
        sub("mode", perm, true, this::mode, (s, a) -> a.length <= 1 ? module.modes().stream().map(ArenaMode::id).toList() : List.of());
        sub("display", perm, true, this::display);
        sub("icon", perm, true, this::icon, (s, a) -> List.of("GRASS_BLOCK", "DIAMOND_SWORD", "SANDSTONE", "END_STONE"));
        sub("info", perm, false, this::info, (s, a) -> module.arenas().ids());
        sub("save", perm, true, this::save);
        sub("cancel", perm, true, this::cancel);
        sub("edit", perm, true, this::edit, (s, a) -> module.arenas().ids());
        sub("delete", perm, false, this::delete, (s, a) -> module.arenas().ids());
        sub("list", perm, false, this::list);
        sub("enable", perm, false, (s, a) -> toggle(s, a, true), (s, a) -> module.arenas().ids());
        sub("disable", perm, false, (s, a) -> toggle(s, a, false), (s, a) -> module.arenas().ids());
    }

    @Override
    protected void root(CommandSender sender) {
        plugin.messages().sendList(sender, "arena.help");
    }

    private ArenaSetup need(CommandSender s) {
        ArenaSetup setup = module.setup((Player) s);
        if (setup == null) {
            plugin.messages().send(s, "arena.no-setup");
        }
        return setup;
    }

    private void create(CommandSender s, String[] a) {
        if (a.length < 1 || !a[0].matches("[a-zA-Z0-9_-]{1,32}")) {
            plugin.messages().send(s, "arena.usage-create");
            return;
        }
        String id = a[0].toLowerCase(Locale.ROOT);
        if (module.arenas().get(id) != null) {
            plugin.messages().send(s, "arena.exists", "arena", id);
            return;
        }
        Player p = (Player) s;
        module.closeSetup(p.getUniqueId(), p);
        module.setup(p, new ArenaSetup(id));
        plugin.messages().send(s, "arena.created", "arena", id);
    }

    private void pos(CommandSender s, String[] a, boolean first) {
        ArenaSetup setup = need(s);
        if (setup == null) {
            return;
        }
        Location loc = ((Player) s).getLocation().toBlockLocation();
        if (a.length >= 3) {
            loc = new Location(loc.getWorld(), Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
        }
        if (first) {
            setup.pos1 = loc;
        } else {
            setup.pos2 = loc;
        }
        plugin.messages().send(s, "arena.pos-set", "pos", first ? "1" : "2",
                "x", loc.getBlockX(), "y", loc.getBlockY(), "z", loc.getBlockZ());
    }

    private List<String> spawnKeys(CommandSender s) {
        Set<String> keys = new LinkedHashSet<>(List.of("player", "bot", "p1", "p2", "spectator"));
        if (s instanceof Player p && module.setup(p) != null) {
            for (String m : module.setup(p).modes) {
                ArenaMode mode = module.mode(m);
                if (mode != null) {
                    keys.addAll(mode.requiredSpawns());
                }
            }
        }
        return new ArrayList<>(keys);
    }

    private void spawn(CommandSender s, String[] a) {
        ArenaSetup setup = need(s);
        if (setup == null) {
            return;
        }
        if (a.length < 1 || !a[0].matches("[a-zA-Z0-9_-]{1,24}")) {
            plugin.messages().send(s, "arena.usage-spawn");
            return;
        }
        Location loc = ((Player) s).getLocation().clone();
        setup.spawns.put(a[0].toLowerCase(Locale.ROOT), loc);
        plugin.messages().send(s, "arena.spawn-set", "spawn", a[0].toLowerCase(Locale.ROOT),
                "x", Text.num(loc.getX()), "y", Text.num(loc.getY()), "z", Text.num(loc.getZ()));
    }

    private void voidY(CommandSender s, String[] a) {
        ArenaSetup setup = need(s);
        if (setup == null) {
            return;
        }
        setup.voidY = a.length > 0 ? Double.parseDouble(a[0]) : ((Player) s).getLocation().getY();
        plugin.messages().send(s, "arena.void-set", "y", Text.num(setup.voidY));
    }

    private void mode(CommandSender s, String[] a) {
        ArenaSetup setup = need(s);
        if (setup == null) {
            return;
        }
        if (a.length < 1 || module.mode(a[0].toLowerCase(Locale.ROOT)) == null) {
            plugin.messages().send(s, "arena.unknown-mode", "modes", String.join(", ", module.modes().stream().map(ArenaMode::id).toList()));
            return;
        }
        String m = a[0].toLowerCase(Locale.ROOT);
        if (!setup.modes.remove(m)) {
            setup.modes.add(m);
            ArenaMode mode = module.mode(m);
            plugin.messages().send(s, "arena.mode-added", "mode", m, "spawns", String.join(", ", mode.requiredSpawns()));
        } else {
            plugin.messages().send(s, "arena.mode-removed", "mode", m);
        }
    }

    private void display(CommandSender s, String[] a) {
        ArenaSetup setup = need(s);
        if (setup == null) {
            return;
        }
        if (a.length == 0) {
            plugin.messages().send(s, "arena.usage-display");
            return;
        }
        setup.display = String.join(" ", a);
        plugin.messages().send(s, "arena.display-set", "display", setup.display);
    }

    private void icon(CommandSender s, String[] a) {
        ArenaSetup setup = need(s);
        if (setup == null) {
            return;
        }
        Material m = a.length > 0 ? Material.matchMaterial(a[0]) : ((Player) s).getInventory().getItemInMainHand().getType();
        if (m == null || m.isAir() || !m.isItem()) {
            plugin.messages().send(s, "arena.bad-material");
            return;
        }
        setup.icon = m;
        plugin.messages().send(s, "arena.icon-set", "icon", m.name());
    }

    private void info(CommandSender s, String[] a) {
        if (a.length > 0) {
            Arena arena = module.arenas().get(a[0]);
            if (arena == null) {
                plugin.messages().send(s, "arena.not-found", "arena", a[0]);
                return;
            }
            plugin.messages().sendList(s, "arena.info", "arena", arena.id(), "display", arena.display(),
                    "modes", String.join(", ", arena.modes()), "spawns", String.join(", ", arena.spawns().keySet()),
                    "schematic", String.valueOf(arena.schematic()),
                    "size", arena.size().getBlockX() + "x" + arena.size().getBlockY() + "x" + arena.size().getBlockZ(),
                    "void", Text.num(arena.voidY()), "enabled", arena.enabled() ? "да" : "нет");
            return;
        }
        if (!(s instanceof Player p) || module.setup(p) == null) {
            plugin.messages().send(s, "arena.no-setup");
            return;
        }
        ArenaSetup setup = module.setup(p);
        plugin.messages().sendList(s, "arena.setup-info", "arena", setup.id, "display", setup.display,
                "pos1", fmt(setup.pos1), "pos2", fmt(setup.pos2),
                "modes", setup.modes.isEmpty() ? "-" : String.join(", ", setup.modes),
                "spawns", setup.spawns.isEmpty() ? "-" : String.join(", ", setup.spawns.keySet()),
                "void", setup.voidY == null ? "низ - 5" : Text.num(setup.voidY), "missing", String.join(", ", missing(setup)));
    }

    private static String fmt(Location l) {
        return l == null ? "-" : l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    private List<String> missing(ArenaSetup setup) {
        Set<String> need = new LinkedHashSet<>();
        for (String m : setup.modes) {
            ArenaMode mode = module.mode(m);
            if (mode != null) {
                need.addAll(mode.requiredSpawns());
            }
        }
        need.removeAll(setup.spawns.keySet());
        return new ArrayList<>(need);
    }

    private void save(CommandSender s, String[] a) {
        ArenaSetup setup = need(s);
        if (setup == null) {
            return;
        }
        Player p = (Player) s;
        if (setup.pos1 == null || setup.pos2 == null || !setup.pos1.getWorld().equals(setup.pos2.getWorld())) {
            plugin.messages().send(s, "arena.need-region");
            return;
        }
        if (setup.modes.isEmpty()) {
            plugin.messages().send(s, "arena.need-mode");
            return;
        }
        List<String> missing = missing(setup);
        if (!missing.isEmpty()) {
            plugin.messages().send(s, "arena.missing-spawns", "spawns", String.join(", ", missing));
            return;
        }
        Location min = setup.min();
        Location max = setup.max();
        for (var e : setup.spawns.entrySet()) {
            if (!e.getValue().getWorld().equals(min.getWorld())) {
                plugin.messages().send(s, "arena.spawn-other-world", "spawn", e.getKey());
                return;
            }
        }
        Arena old = module.arenas().get(setup.id);
        SchematicEngine engine = module.schematics().preferred();
        String fileName = setup.id + engine.extension();
        File file = new File(module.arenas().schematicsDir(), fileName);
        try {
            engine.save(file, min, max);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Не удалось сохранить схематику " + fileName, ex);
            plugin.messages().send(s, "arena.save-failed", "error", String.valueOf(ex.getMessage()));
            return;
        }
        if (old != null && old.schematic() != null && !old.schematic().equals(fileName)) {
            new File(module.arenas().schematicsDir(), old.schematic()).delete();
        }
        Arena arena = new Arena(setup.id);
        arena.display(setup.display);
        arena.enabled(setup.enabled);
        arena.modes().addAll(setup.modes);
        arena.schematic(fileName);
        arena.icon(setup.icon);
        arena.size(new Vector(max.getBlockX() - min.getBlockX() + 1, max.getBlockY() - min.getBlockY() + 1, max.getBlockZ() - min.getBlockZ() + 1));
        Vector minV = min.toVector();
        setup.spawns.forEach((k, loc) -> arena.spawns().put(k, Arena.Spawn.relative(loc, minV)));
        arena.voidY(setup.voidY == null ? -5 : setup.voidY - min.getY());
        try {
            module.arenas().save(arena);
        } catch (Exception ex) {
            plugin.messages().send(s, "arena.save-failed", "error", String.valueOf(ex.getMessage()));
            return;
        }
        plugin.messages().send(s, "arena.saved", "arena", arena.id(), "engine", engine.name(),
                "size", arena.size().getBlockX() + "x" + arena.size().getBlockY() + "x" + arena.size().getBlockZ());
        module.closeSetup(p.getUniqueId(), p);
    }

    private void cancel(CommandSender s, String[] a) {
        Player p = (Player) s;
        if (module.setup(p) == null) {
            plugin.messages().send(s, "arena.no-setup");
            return;
        }
        module.closeSetup(p.getUniqueId(), p);
        plugin.messages().send(s, "arena.cancelled");
    }

    private void edit(CommandSender s, String[] a) {
        Arena arena = a.length > 0 ? module.arenas().get(a[0]) : null;
        if (arena == null) {
            plugin.messages().send(s, "arena.not-found", "arena", a.length > 0 ? a[0] : "?");
            return;
        }
        Player p = (Player) s;
        module.closeSetup(p.getUniqueId(), p);
        try {
            ArenaInstance inst = module.worlds().createIn(arena, module.worlds().editPrefix() + arena.id());
            ArenaSetup setup = ArenaSetup.from(arena, inst.world(), inst.origin());
            setup.returnTo = p.getLocation();
            module.setup(p, setup);
            Arena.Spawn first = arena.spawns().values().stream().findFirst().orElse(null);
            p.teleport(first != null ? first.at(inst.world(), inst.origin()) : inst.origin().clone().add(0.5, 1, 0.5));
            plugin.messages().send(s, "arena.editing", "arena", arena.id());
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Не удалось открыть арену " + arena.id(), ex);
            plugin.messages().send(s, "arena.save-failed", "error", String.valueOf(ex.getMessage()));
        }
    }

    private void delete(CommandSender s, String[] a) {
        if (a.length < 1 || !module.arenas().delete(a[0])) {
            plugin.messages().send(s, "arena.not-found", "arena", a.length > 0 ? a[0] : "?");
            return;
        }
        plugin.messages().send(s, "arena.deleted", "arena", a[0]);
    }

    private void list(CommandSender s, String[] a) {
        if (module.arenas().all().isEmpty()) {
            plugin.messages().send(s, "arena.list-empty");
            return;
        }
        plugin.messages().send(s, "arena.list-header", "count", module.arenas().all().size());
        for (Arena arena : module.arenas().all()) {
            plugin.messages().send(s, "arena.list-line", "arena", arena.id(), "display", arena.display(),
                    "modes", String.join(", ", arena.modes()), "state", arena.enabled() ? "<green>вкл" : "<red>выкл");
        }
    }

    private void toggle(CommandSender s, String[] a, boolean on) {
        Arena arena = a.length > 0 ? module.arenas().get(a[0]) : null;
        if (arena == null) {
            plugin.messages().send(s, "arena.not-found", "arena", a.length > 0 ? a[0] : "?");
            return;
        }
        arena.enabled(on);
        try {
            module.arenas().save(arena);
        } catch (Exception ignored) {
        }
        plugin.messages().send(s, on ? "arena.enabled" : "arena.disabled", "arena", arena.id());
    }
}
