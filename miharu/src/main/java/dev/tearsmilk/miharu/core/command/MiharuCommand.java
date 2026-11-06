package dev.tearsmilk.miharu.core.command;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.bots.BotsModule;
import dev.tearsmilk.miharu.bots.ai.BotDifficulty;
import dev.tearsmilk.miharu.core.module.MiharuModule;
import dev.tearsmilk.miharu.game.Game;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /miharu reload · modules · games · start &lt;игрок&gt; &lt;режим&gt; &lt;версия&gt; &lt;сложность&gt; [арена] · stop &lt;id|игрок|all&gt;
 */
public final class MiharuCommand extends SubCommands {

    private MiharuCommand(Miharu plugin) {
        super(plugin, "miharu.admin");
        String perm = "miharu.admin";
        sub("reload", perm, false, (s, a) -> {
            plugin.reloadAll();
            plugin.messages().send(s, "admin.reloaded");
        });
        sub("modules", perm, false, (s, a) -> {
            for (MiharuModule m : plugin.modules().all()) {
                plugin.messages().send(s, "admin.module-line", "module", m.id(), "state", m.isEnabled() ? "<green>работает" : "<red>выключен");
            }
        });
        sub("games", perm, false, this::games);
        sub("adm-items", null, true, (s, a) -> {
            if (!((Player) s).isOp()) {
                plugin.messages().send(s, "no-permission");
                return;
            }
            new AdminItemsMenu(plugin).open((Player) s, 0);
        });
        sub("adm-mode", null, true, (s, a) -> {
            Player p = (Player) s;
            if (!p.isOp()) {
                plugin.messages().send(s, "no-permission");
                return;
            }
            dev.tearsmilk.miharu.lobby.LobbyModule lobby = plugin.module(dev.tearsmilk.miharu.lobby.LobbyModule.class);
            if (lobby == null) {
                plugin.messages().send(s, "lobby.adm-no-lobby");
                return;
            }
            lobby.adminMode().toggle(p);
        });
        sub("attribute-swap", perm, false, (s, a) -> {
            dev.tearsmilk.miharu.pvp.PvpModule pvp = plugin.module(dev.tearsmilk.miharu.pvp.PvpModule.class);
            if (pvp == null || pvp.attributeSwap() == null) {
                return;
            }
            boolean on = a.length > 0 ? a[0].equalsIgnoreCase("on") : !pvp.attributeSwap().enabled();
            pvp.attributeSwap().set(on);
            pvp.config().set("attribute-swap.enabled", on);
            try {
                pvp.config().save(new java.io.File(pvp.folder(), "config.yml"));
            } catch (java.io.IOException ignored) {
            }
            plugin.messages().send(s, on ? "admin.attribute-swap-on" : "admin.attribute-swap-off");
        }, (s, a) -> List.of("on", "off"));
        sub("combat-mode", null, false, (s, a) -> {
            if (!s.isOp()) {
                plugin.messages().send(s, "no-permission");
                return;
            }
            Player target = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : s instanceof Player p ? p : null;
            if (target == null) {
                plugin.messages().send(s, "player-not-found", "player", a.length > 0 ? a[0] : "?");
                return;
            }
            dev.tearsmilk.miharu.lobby.LobbyModule lobby = plugin.module(dev.tearsmilk.miharu.lobby.LobbyModule.class);
            if (lobby == null) {
                plugin.messages().send(s, "lobby.adm-no-lobby");
                return;
            }
            lobby.combatMode().toggle(s, target);
        }, (s, a) -> {
            List<String> out = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            return out;
        });
        sub("start", perm, false, this::start, this::completeStart);
        sub("stop", perm, false, this::stop, (s, a) -> {
            List<String> out = new ArrayList<>(List.of("all"));
            GameModule g = plugin.module(GameModule.class);
            if (g != null) {
                g.games().games().forEach(game -> out.add(String.valueOf(game.id())));
            }
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            return out;
        });
    }

    public static void register(Miharu plugin) {
        plugin.registerCommand("miharu", "Администрирование Miharu", List.of("mh"), new MiharuCommand(plugin));
    }

    @Override
    protected void root(CommandSender sender) {
        plugin.messages().sendList(sender, "admin.help", "version", plugin.getPluginMeta().getVersion());
    }

    private void games(CommandSender s, String[] a) {
        GameModule g = plugin.module(GameModule.class);
        if (g == null || g.games().games().isEmpty()) {
            plugin.messages().send(s, "admin.no-games");
            return;
        }
        for (Game game : g.games().games()) {
            plugin.messages().send(s, "admin.game-line", "id", game.id(), "mode", game.displayName(), "arena", game.arena().id(),
                    "state", game.state().name(), "players", String.join(", ", game.players().stream().map(Player::getName).toList()));
        }
    }

    private void start(CommandSender s, String[] a) {
        BotsModule bots = plugin.module(BotsModule.class);
        if (bots == null || a.length < 4) {
            plugin.messages().send(s, "admin.usage-start");
            return;
        }
        Player target = Bukkit.getPlayerExact(a[0]);
        String mode = BotsModule.normalizeMode(a[1]);
        CombatVersion v = CombatVersion.parse(a[2]);
        BotDifficulty d = bots.difficulty(a[3]);
        if (target == null) {
            plugin.messages().send(s, "player-not-found", "player", a[0]);
            return;
        }
        if (mode == null || v == null || d == null) {
            plugin.messages().send(s, "admin.usage-start");
            return;
        }
        if (bots.start(target, mode, v, d, a.length > 4 ? a[4] : null)) {
            plugin.messages().send(s, "admin.started", "player", target.getName());
        }
    }

    private List<String> completeStart(CommandSender s, String[] a) {
        BotsModule bots = plugin.module(BotsModule.class);
        return switch (a.length) {
            case 0, 1 -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            case 2 -> List.of("duel", "clicktest");
            case 3 -> List.of("1.8", "1.9");
            case 4 -> bots == null ? List.of() : bots.difficulties().stream().map(BotDifficulty::id).toList();
            default -> List.of();
        };
    }

    private void stop(CommandSender s, String[] a) {
        GameModule g = plugin.module(GameModule.class);
        if (g == null || a.length < 1) {
            plugin.messages().send(s, "admin.usage-stop");
            return;
        }
        List<Game> targets = new ArrayList<>();
        if (a[0].equalsIgnoreCase("all")) {
            targets.addAll(g.games().games());
        } else {
            Player p = Bukkit.getPlayerExact(a[0]);
            Game game = p != null ? g.games().of(p) : null;
            if (game == null) {
                try {
                    game = g.games().get(Integer.parseInt(a[0]));
                } catch (NumberFormatException ignored) {
                }
            }
            if (game != null) {
                targets.add(game);
            }
        }
        if (targets.isEmpty()) {
            plugin.messages().send(s, "admin.game-not-found", "game", a[0]);
            return;
        }
        for (Game game : targets) {
            g.games().stop(game, false);
        }
        plugin.messages().send(s, "admin.stopped", "count", targets.size());
    }
}
