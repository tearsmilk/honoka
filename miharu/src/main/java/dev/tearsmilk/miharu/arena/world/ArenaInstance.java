package dev.tearsmilk.miharu.arena.world;

import dev.tearsmilk.miharu.arena.Arena;
import org.bukkit.Location;
import org.bukkit.World;

/** Живая копия арены в отдельном временном мире. */
public record ArenaInstance(World world, Location origin, Arena arena) {

    public Location spawn(String key) {
        Arena.Spawn s = arena.spawn(key);
        if (s == null) {
            return origin.clone().add(0.5, 1, 0.5);
        }
        return s.at(world, origin);
    }

    /** Точка key, а если её нет — fallback. */
    public Location spawn(String key, String fallback) {
        return arena.spawn(key) != null ? spawn(key) : spawn(fallback);
    }

    public boolean has(String key) {
        return arena.spawn(key) != null;
    }

    /** Абсолютная высота, ниже которой — падение в пустоту. */
    public double voidY() {
        return origin.getY() + arena.voidY();
    }
}
