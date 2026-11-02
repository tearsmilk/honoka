package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.core.command.SubCommands;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;

/** /ranked — меню · /ranked 1.8|1.9 · /ranked queue &lt;кит&gt; · /ranked top [кит]. */
final class RankedCommand extends SubCommands {

    private final DuelsModule module;

    RankedCommand(Miharu plugin, DuelsModule module) {
        super(plugin, "miharu.duel");
        this.module = module;
        sub("1.8", "miharu.duel", true, (s, a) -> module.ranked().open((Player) s, CombatVersion.LEGACY));
        sub("1.9", "miharu.duel", true, (s, a) -> module.ranked().open((Player) s, CombatVersion.MODERN));
        sub("queue", "miharu.duel", true, (s, a) -> {
            KitSpec spec = a.length > 0 && module.ranked().isRanked(a[0]) ? module.spec(a[0]) : null;
            if (spec == null) {
                plugin.messages().send(s, "ranked.unknown-kit", "kits", String.join(", ", module.ranked().allIds()));
                return;
            }
            module.queue().join((Player) s, spec, true);
        }, (s, a) -> module.ranked().allIds());
        sub("top", "miharu.duel", false, (s, a) -> {
            String kit = a.length > 0 ? a[0] : module.ranked().allIds().isEmpty() ? "diamond" : module.ranked().allIds().getFirst();
            KitSpec spec = module.spec(kit);
            module.elo().top(kit, a.length > 1 ? Integer.parseInt(a[1]) : 10).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.messages().send(s, "ranked.top-header", "kit", spec == null ? kit : spec.display());
                if (list.isEmpty()) {
                    plugin.messages().send(s, "ranked.top-empty");
                }
                int i = 1;
                for (EloStore.Top t : list) {
                    plugin.messages().send(s, "ranked.top-line", "place", i++, "player", t.name(), "elo", t.elo(), "wins", t.wins(), "losses", t.losses());
                }
            }));
        }, (s, a) -> module.ranked().allIds());
    }

    @Override
    protected void root(CommandSender sender) {
        if (sender instanceof Player p) {
            module.ranked().open(p);
        }
    }

    /** /elo [игрок] — рейтинг по всем рейтинговым китам. */
    static final class EloCommand implements BasicCommand {

        private final Miharu plugin;
        private final DuelsModule module;

        EloCommand(Miharu plugin, DuelsModule module) {
            this.plugin = plugin;
            this.module = module;
        }

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            CommandSender s = source.getSender();
            Player t = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : s instanceof Player p ? p : null;
            if (t == null) {
                plugin.messages().send(s, "player-not-found", "player", args.length > 0 ? args[0] : "?");
                return;
            }
            plugin.messages().send(s, "ranked.elo-header", "player", t.getName());
            for (String id : module.ranked().allIds()) {
                KitSpec spec = module.spec(id);
                plugin.messages().send(s, "ranked.elo-line", "kit", spec == null ? id : spec.display(), "version", spec == null ? "" : spec.version().id(),
                        "elo", module.elo().elo(t.getUniqueId(), id), "wins", module.elo().wins(t.getUniqueId(), id), "losses", module.elo().losses(t.getUniqueId(), id));
            }
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
    }
}
