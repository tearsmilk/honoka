package dev.tearsmilk.miharu.arena.schematic;

import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.io.File;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Выбирает движок: config «schematic-engine: auto|worldedit|structure».
 * Вставка по расширению файла: .schem/.schematic — WorldEdit, .nbt — структуры.
 */
public final class SchematicService {

    private final Logger log;
    private final SchematicEngine structure = new StructureEngine();
    private SchematicEngine worldEdit;
    private SchematicEngine preferred = structure;

    public SchematicService(Logger log) {
        this.log = log;
    }

    public void configure(String mode) {
        boolean weInstalled = Bukkit.getPluginManager().getPlugin("WorldEdit") != null
                || Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
        if (weInstalled && worldEdit == null) {
            try {
                worldEdit = new WorldEditEngine();
            } catch (Throwable t) {
                log.warning("WorldEdit найден, но не подключился: " + t.getMessage());
            }
        }
        String m = mode == null ? "auto" : mode.toLowerCase(Locale.ROOT);
        preferred = switch (m) {
            case "worldedit" -> worldEdit != null ? worldEdit : structure;
            case "structure" -> structure;
            default -> worldEdit != null ? worldEdit : structure;
        };
        log.info("Схематики: сохраняю через " + preferred.name() + (worldEdit != null ? " (WorldEdit доступен)" : ""));
    }

    public SchematicEngine preferred() {
        return preferred;
    }

    public SchematicEngine engineFor(File file) {
        String n = file.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".nbt")) {
            return structure;
        }
        if (worldEdit == null) {
            throw new IllegalStateException("Для " + file.getName() + " нужен WorldEdit/FAWE");
        }
        return worldEdit;
    }

    public void paste(File file, Location origin) throws Exception {
        engineFor(file).paste(file, origin);
    }
}
