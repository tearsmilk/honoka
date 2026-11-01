package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.core.command.SubCommands;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /duel — меню китов · /duel queue &lt;кит&gt; · /duel leave · /duel kits · /duel stats [игрок]. */
final class DuelCommand extends SubCommands {

    private final DuelsModule module;

    DuelCommand(Miharu plugin, DuelsModule module) {
        super(plugin, "miharu.duel");
        this.module = module;
        sub("queue", "miharu.duel", true, (s, a) -> {
            KitSpec spec = a.length > 0 ? module.kits().spec(a[0]) : null;
            if (spec == null) {
                plugin.messages().send(s, "duels.unknown-kit", "kits", String.join(", ", module.kits().enabledIds()));
                return;
            }
            module.queue().join((Player) s, spec);
        }, (s, a) -> module.kits().enabledIds());
        sub("leave", "miharu.duel", true, (s, a) -> {
            if (module.queue().inQueue((Player) s)) {
                module.queue().leave((Player) s, false);
            } else {
                plugin.messages().send(s, "duels.queue.not-in-queue");
            }
        });
        sub("kits", "miharu.duel", false, (s, a) -> {
            plugin.messages().send(s, "duels.kits-header", "count", module.kits().enabledIds().size());
            for (DuelKits.Entry e : module.kits().all()) {
                plugin.messages().send(s, "duels.kits-line", "id", e.spec().id(), "kit", e.spec().display(),
                        "mode", e.spec().arenaMode(), "state", e.enabled() ? "<green>вкл" : "<red>выкл",
                        "bot", e.spec().bot().enabled() ? plugin.messages().raw("duels.bot-yes") : plugin.messages().raw("duels.bot-no"));
            }
        });
        sub("stats", "miharu.duel", false, (s, a) -> {
            Player t = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : s instanceof Player p ? p : null;
            if (t == null) {
                plugin.messages().send(s, "player-not-found", "player", a.length > 0 ? a[0] : "?");
                return;
            }
            plugin.messages().send(s, "duels.stats-header", "player", t.getName(),
                    "wins", module.stats().wins(t.getUniqueId(), null), "losses", module.stats().losses(t.getUniqueId(), null));
            for (DuelKits.Entry e : module.kits().all()) {
                int w = module.stats().wins(t.getUniqueId(), e.spec().id());
                int l = module.stats().losses(t.getUniqueId(), e.spec().id());
                if (w + l > 0) {
                    plugin.messages().send(s, "duels.stats-line", "kit", e.spec().display(), "wins", w, "losses", l);
                }
            }
        }, (s, a) -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
    }

    @Override
    protected void root(CommandSender sender) {
        if (sender instanceof Player p) {
            module.menus().openKits(p);
        } else {
            plugin.messages().sendList(sender, "duels.help");
        }
    }

    @Override
    protected void unknown(CommandSender sender, String[] args) {
        KitSpec spec = module.kits().spec(args[0]);
        if (spec != null && sender instanceof Player p) {
            module.menus().openOpponent(p, spec);
            return;
        }
        plugin.messages().sendList(sender, "duels.help");
    }
}
