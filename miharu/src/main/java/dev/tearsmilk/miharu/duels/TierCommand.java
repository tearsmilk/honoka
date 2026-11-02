package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.core.command.SubCommands;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /tier [игрок] — тиры по всем рейтинговым режимам · /tier list — шкала тиров
 * /tier set &lt;игрок&gt; &lt;кит&gt; &lt;HT1..LT5|auto&gt; — ручной тир (miharu.admin).
 */
final class TierCommand extends SubCommands {

    private final DuelsModule module;

    TierCommand(Miharu plugin, DuelsModule module) {
        super(plugin, null);
        this.module = module;
        sub("list", null, false, (s, a) -> {
            plugin.messages().send(s, "tiers.list-header");
            for (Tiers.Tier t : module.tiers().all()) {
                plugin.messages().send(s, "tiers.list-line", "tier", t.display(), "name", t.name(),
                        "elo", t.minElo() == Integer.MIN_VALUE ? "—" : String.valueOf(t.minElo()));
            }
        });
        sub("set", "miharu.admin", false, this::set, (s, a) -> switch (a.length) {
            case 0, 1 -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            case 2 -> module.ranked().allIds();
            case 3 -> {
                List<String> out = new ArrayList<>(module.tiers().all().stream().map(Tiers.Tier::id).toList());
                out.add("auto");
                yield out;
            }
            default -> List.of();
        });
    }

    @Override
    protected void root(CommandSender sender) {
        if (sender instanceof Player p) {
            show(sender, p);
        }
    }

    @Override
    protected void unknown(CommandSender sender, String[] args) {
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) {
            plugin.messages().send(sender, "player-not-found", "player", args[0]);
            return;
        }
        show(sender, t);
    }

    private void show(CommandSender s, Player t) {
        plugin.messages().send(s, "tiers.header", "player", t.getName());
        for (String id : module.ranked().allIds()) {
            KitSpec spec = module.spec(id);
            plugin.messages().send(s, "tiers.line", "kit", spec == null ? id : spec.display(), "version", spec == null ? "" : spec.version().id(),
                    "tier", module.tiers().display(t.getUniqueId(), id), "elo", module.elo().elo(t.getUniqueId(), id),
                    "manual", module.tiers().manual(t.getUniqueId(), id) ? plugin.messages().raw("tiers.manual") : "");
        }
    }

    private void set(CommandSender s, String[] a) {
        if (a.length < 3) {
            plugin.messages().send(s, "tiers.usage");
            return;
        }
        OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (t == null) {
            plugin.messages().send(s, "player-not-found", "player", a[0]);
            return;
        }
        if (!module.ranked().isRanked(a[1])) {
            plugin.messages().send(s, "ranked.unknown-kit", "kits", String.join(", ", module.ranked().allIds()));
            return;
        }
        if (a[2].equalsIgnoreCase("auto")) {
            module.elo().setOverride(t.getUniqueId(), String.valueOf(t.getName()), a[1], null);
            plugin.messages().send(s, "tiers.reset", "player", t.getName(), "kit", a[1]);
            return;
        }
        Tiers.Tier tier = module.tiers().byId(a[2]);
        if (tier == null) {
            plugin.messages().send(s, "tiers.usage");
            return;
        }
        module.elo().setOverride(t.getUniqueId(), String.valueOf(t.getName()), a[1], tier.id());
        plugin.messages().send(s, "tiers.set", "player", t.getName(), "kit", a[1], "tier", tier.display());
    }
}
