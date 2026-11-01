package dev.tearsmilk.miharu.bots;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.arena.Arena;
import dev.tearsmilk.miharu.arena.ArenaModule;
import dev.tearsmilk.miharu.bots.ai.BotDifficulty;
import dev.tearsmilk.miharu.bots.game.ClickTestGame;
import dev.tearsmilk.miharu.bots.stats.BotStats;
import dev.tearsmilk.miharu.core.command.SubCommands;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /bot — меню выбора.
 * /bot duel [1.8|1.9] [easy|medium|hard] [арена]
 * /bot clicktest [1.8|1.9] [easy|medium|hard] [арена]
 * /bot stats [игрок] · /bot top · /bot leave
 * Если версия или сложность не указаны — откроется меню на нужном шаге.
 */
final class BotCommand extends SubCommands {

    private final BotsModule module;

    BotCommand(Miharu plugin, BotsModule module) {
        super(plugin, "miharu.bot");
        this.module = module;
        sub("duel", "miharu.bot", true, (s, a) -> play(s, "bot-duel", a), this::completePlay);
        sub("clicktest", "miharu.bot", true, (s, a) -> play(s, ClickTestGame.MODE, a), this::completePlay);
        sub("stats", "miharu.bot", false, this::stats, (s, a) -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        sub("top", "miharu.bot", false, this::top);
        sub("leave", "miharu.bot", true, (s, a) -> {
            GameModule games = plugin.module(GameModule.class);
            if (games.games().inGame((Player) s)) {
                games.games().leave((Player) s, false);
                plugin.messages().send(s, "game.left");
            } else {
                plugin.messages().send(s, "game.not-in-game");
            }
        });
    }

    @Override
    protected void root(CommandSender sender) {
        if (sender instanceof Player p) {
            module.menu().openModes(p);
        } else {
            plugin.messages().sendList(sender, "bots.help");
        }
    }

    @Override
    protected void unknown(CommandSender sender, String[] args) {
        plugin.messages().sendList(sender, "bots.help");
    }

    private void play(CommandSender s, String mode, String[] a) {
        Player p = (Player) s;
        CombatVersion version = a.length > 0 ? CombatVersion.parse(a[0]) : null;
        if (a.length > 0 && version == null) {
            plugin.messages().send(s, "bots.bad-version");
            return;
        }
        if (version == null) {
            module.menu().openVersions(p, mode);
            return;
        }
        BotDifficulty d = a.length > 1 ? module.difficulty(a[1]) : null;
        if (a.length > 1 && d == null) {
            plugin.messages().send(s, "bots.bad-difficulty", "list", String.join(", ", module.difficulties().stream().map(BotDifficulty::id).toList()));
            return;
        }
        if (d == null) {
            module.menu().openDifficulties(p, mode, version);
            return;
        }
        module.start(p, mode, version, d, a.length > 2 ? a[2] : null);
    }

    private List<String> completePlay(CommandSender s, String[] a) {
        return switch (a.length) {
            case 0, 1 -> List.of("1.8", "1.9");
            case 2 -> module.difficulties().stream().map(BotDifficulty::id).toList();
            case 3 -> {
                ArenaModule arenas = plugin.module(ArenaModule.class);
                yield arenas == null ? List.of() : arenas.arenas().all().stream().map(Arena::id).toList();
            }
            default -> List.of();
        };
    }

    private void stats(CommandSender s, String[] a) {
        Player target = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : s instanceof Player p ? p : null;
        if (target == null) {
            plugin.messages().send(s, "player-not-found", "player", a.length > 0 ? a[0] : "?");
            return;
        }
        BotStats.Summary sum = module.stats().summary(target.getUniqueId());
        plugin.messages().send(s, "bots.stats.header", "player", target.getName());
        for (BotDifficulty d : module.difficulties()) {
            plugin.messages().send(s, "bots.stats.line", "difficulty", d.display(), "wins", sum.wins(d.id()), "losses", sum.losses(d.id()));
        }
        plugin.messages().send(s, "bots.stats.clicktest", "best", sum.bestClickTest, "cps", sum.bestCps);
    }

    private void top(CommandSender s, String[] a) {
        int limit = a.length > 0 ? Integer.parseInt(a[0]) : 10;
        module.stats().topClickTest(limit).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.messages().send(s, "bots.top.header");
            if (list.isEmpty()) {
                plugin.messages().send(s, "bots.top.empty");
            }
            int i = 1;
            for (BotStats.TopEntry e : list) {
                plugin.messages().send(s, "bots.top.line", "place", i++, "player", e.name(), "score", e.score(), "cps", e.maxCps());
            }
        }));
    }
}
