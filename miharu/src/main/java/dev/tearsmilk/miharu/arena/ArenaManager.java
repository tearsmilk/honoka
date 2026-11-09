package dev.tearsmilk.miharu.arena;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

public final class ArenaManager {

    private final ArenaModule module;
    private final Map<String, Arena> arenas = new TreeMap<>();

    ArenaManager(ArenaModule module) {
        this.module = module;
    }

    public File arenasDir() {
        File dir = new File(module.folder(), "arenas");
        dir.mkdirs();
        return dir;
    }

    public File schematicsDir() {
        File dir = new File(module.folder(), "schematics");
        dir.mkdirs();
        return dir;
    }

    void load() {
        arenas.clear();
        File[] files = arenasDir().listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
            try {
                arenas.put(id, Arena.load(id, f));
            } catch (Exception e) {
                module.plugin().getLogger().warning("Арена " + f.getName() + " не загрузилась: " + e.getMessage());
            }
        }
        module.plugin().getLogger().info("Арен загружено: " + arenas.size());
    }

    public Arena get(String id) {
        return id == null ? null : arenas.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<Arena> all() {
        return arenas.values();
    }

    public List<String> ids() {
        return new ArrayList<>(arenas.keySet());
    }

    /** Включённые арены с этим режимом и нужными точками. */
    public List<Arena> forMode(String mode) {
        ArenaMode m = module.mode(mode);
        return arenas.values().stream()
                .filter(Arena::enabled)
                .filter(a -> a.supports(mode))
                .filter(a -> m == null || a.spawns().keySet().containsAll(m.requiredSpawns()))
                .filter(a -> a.schematic() != null && new File(schematicsDir(), a.schematic()).exists())
                .toList();
    }

    /** Включённые арены с этим режимом и схематикой, без проверки точек (её делает сам режим). */
    public List<Arena> forModeAnyPoints(String mode) {
        return arenas.values().stream()
                .filter(Arena::enabled)
                .filter(a -> a.supports(mode))
                .filter(a -> a.schematic() != null && new File(schematicsDir(), a.schematic()).exists())
                .toList();
    }

    public Arena random(String mode) {
        List<Arena> list = forMode(mode);
        return list.isEmpty() ? null : list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    public void save(Arena arena) throws IOException {
        arena.save(new File(arenasDir(), arena.id() + ".yml"));
        arenas.put(arena.id(), arena);
    }

    public boolean delete(String id) {
        Arena a = arenas.remove(id.toLowerCase(Locale.ROOT));
        if (a == null) {
            return false;
        }
        new File(arenasDir(), a.id() + ".yml").delete();
        if (a.schematic() != null && module.config().getBoolean("delete-schematic-with-arena", true)) {
            new File(schematicsDir(), a.schematic()).delete();
        }
        return true;
    }
}
