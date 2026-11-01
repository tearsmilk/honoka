package dev.tearsmilk.miharu.arena;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Сессия настройки арены одним админом (/arena create или /arena edit). */
public final class ArenaSetup {

    final String id;
    Location pos1;
    Location pos2;
    final Map<String, Location> spawns = new LinkedHashMap<>();
    Double voidY;
    final Set<String> modes = new LinkedHashSet<>();
    String display;
    Material icon = Material.GRASS_BLOCK;
    boolean enabled = true;
    /** Если редактируем существующую арену — временный мир, куда она вставлена. */
    World editWorld;
    Location returnTo;

    ArenaSetup(String id) {
        this.id = id;
        this.display = id;
    }

    Location min() {
        return new Location(pos1.getWorld(),
                Math.min(pos1.getBlockX(), pos2.getBlockX()),
                Math.min(pos1.getBlockY(), pos2.getBlockY()),
                Math.min(pos1.getBlockZ(), pos2.getBlockZ()));
    }

    Location max() {
        return new Location(pos1.getWorld(),
                Math.max(pos1.getBlockX(), pos2.getBlockX()),
                Math.max(pos1.getBlockY(), pos2.getBlockY()),
                Math.max(pos1.getBlockZ(), pos2.getBlockZ()));
    }

    static ArenaSetup from(Arena arena, World world, Location origin) {
        ArenaSetup s = new ArenaSetup(arena.id());
        s.pos1 = origin.clone();
        s.pos2 = origin.clone().add(Math.max(0, arena.size().getBlockX() - 1), Math.max(0, arena.size().getBlockY() - 1), Math.max(0, arena.size().getBlockZ() - 1));
        arena.spawns().forEach((k, sp) -> s.spawns.put(k, sp.at(world, origin)));
        s.voidY = origin.getY() + arena.voidY();
        s.modes.addAll(arena.modes());
        s.display = arena.display();
        s.icon = arena.icon();
        s.enabled = arena.enabled();
        s.editWorld = world;
        return s;
    }
}
