package dev.tearsmilk.miharu.arena;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.arena.schematic.SchematicService;
import dev.tearsmilk.miharu.arena.world.ArenaWorlds;
import dev.tearsmilk.miharu.core.module.MiharuModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Арены: настройка в мире, хранение схематик, создание временных миров под игры. */
public final class ArenaModule extends MiharuModule implements Listener {

    private final Map<String, ArenaMode> modes = new LinkedHashMap<>();
    private final Map<UUID, ArenaSetup> setups = new HashMap<>();
    private ArenaManager arenas;
    private SchematicService schematics;
    private ArenaWorlds worlds;

    public ArenaModule(Miharu plugin) {
        super(plugin, "arena");
    }

    @Override
    protected void onEnable() {
        arenas = new ArenaManager(this);
        schematics = new SchematicService(plugin.getLogger());
        worlds = new ArenaWorlds(this);
        // WorldEdit может включиться позже нас — выбираем движок после старта сервера
        Bukkit.getScheduler().runTask(plugin, () -> schematics.configure(config().getString("schematic-engine", "auto")));
        schematics.configure(config().getString("schematic-engine", "auto"));
        if (config().getBoolean("instance.cleanup-on-start", true)) {
            worlds.cleanupLeftovers();
        }
        arenas.load();
        listen(this);
        plugin.registerCommand("arena", "Настройка арен Miharu", List.of("arenas"), new ArenaCommand(plugin, this));
    }

    @Override
    protected void onReload() {
        schematics.configure(config().getString("schematic-engine", "auto"));
        arenas.load();
    }

    @Override
    protected void onDisable() {
        for (UUID id : List.copyOf(setups.keySet())) {
            Player p = Bukkit.getPlayer(id);
            closeSetup(id, p);
        }
    }

    public void registerMode(ArenaMode mode) {
        modes.put(mode.id(), mode);
    }

    public ArenaMode mode(String id) {
        return modes.get(id);
    }

    public Collection<ArenaMode> modes() {
        return modes.values();
    }

    public ArenaManager arenas() {
        return arenas;
    }

    public SchematicService schematics() {
        return schematics;
    }

    public ArenaWorlds worlds() {
        return worlds;
    }

    ArenaSetup setup(Player p) {
        return setups.get(p.getUniqueId());
    }

    void setup(Player p, ArenaSetup s) {
        setups.put(p.getUniqueId(), s);
    }

    void closeSetup(UUID id, Player p) {
        ArenaSetup s = setups.remove(id);
        if (s == null) {
            return;
        }
        if (s.editWorld != null) {
            if (p != null && p.getWorld().equals(s.editWorld)) {
                p.teleport(s.returnTo != null ? s.returnTo : Bukkit.getWorlds().getFirst().getSpawnLocation());
            }
            worlds.delete(s.editWorld, 0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        closeSetup(e.getPlayer().getUniqueId(), e.getPlayer());
    }
}
