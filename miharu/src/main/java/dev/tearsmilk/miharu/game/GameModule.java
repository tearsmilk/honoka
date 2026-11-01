package dev.tearsmilk.miharu.game;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.arena.ArenaModule;
import dev.tearsmilk.miharu.core.module.MiharuModule;
import dev.tearsmilk.miharu.pvp.PvpModule;
import io.papermc.paper.command.brigadier.BasicCommand;
import org.bukkit.entity.Player;

import java.util.List;

/** Инфраструктура игр: миры-инстансы, табло, таб, изоляция, возврат игроков. */
public final class GameModule extends MiharuModule {

    private GameManager games;
    private VisibilityService visibility;

    public GameModule(Miharu plugin) {
        super(plugin, "game");
    }

    @Override
    public List<Class<? extends MiharuModule>> dependencies() {
        return List.of(ArenaModule.class, PvpModule.class);
    }

    @Override
    protected void onEnable() {
        games = new GameManager(this);
        visibility = new VisibilityService(this);
        listen(new GameListener(this));
        games.start();
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");

        BasicCommand leave = (source, args) -> {
            if (source.getSender() instanceof Player p) {
                if (!games.inGame(p)) {
                    plugin.messages().send(p, "game.not-in-game");
                    return;
                }
                games.leave(p, false);
                plugin.messages().send(p, "game.left");
            }
        };
        plugin.registerCommand("leave", "Выйти из игры", List.of("l", "hub", "quit-game"), leave);

        plugin.hooks().placeholder("ingame", (p, rest) -> p != null && p.isOnline() && games.inGame(p.getPlayer()) ? "true" : "false");
        plugin.hooks().placeholder("game_mode", (p, rest) -> {
            Game g = p != null && p.isOnline() ? games.of(p.getPlayer()) : null;
            return g == null ? "" : g.displayName();
        });
        plugin.hooks().placeholder("games_running", (p, rest) -> String.valueOf(games.games().size()));
        // игроки, уже стоящие на сервере при /reload
        plugin.getServer().getOnlinePlayers().forEach(games::restoreAfterCrash);
    }

    @Override
    protected void onDisable() {
        games.shutdown();
        visibility.showAll();
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, "BungeeCord");
    }

    public GameManager games() {
        return games;
    }

    public VisibilityService visibility() {
        return visibility;
    }
}
