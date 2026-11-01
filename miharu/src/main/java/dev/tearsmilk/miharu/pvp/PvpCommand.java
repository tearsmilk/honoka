package dev.tearsmilk.miharu.pvp;

import dev.tearsmilk.miharu.Miharu;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * /pvp — показать свою версию боя.
 * /pvp &lt;1.8|1.9|off&gt; [игрок] — поставить версию вне игр (miharu.pvp.admin).
 */
final class PvpCommand implements BasicCommand {

    private final Miharu plugin;
    private final PvpModule module;

    PvpCommand(Miharu plugin, PvpModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        CombatService combat = module.combat();
        if (args.length == 0) {
            if (sender instanceof Player p) {
                CombatVersion v = combat.version(p);
                plugin.messages().send(p, "pvp.current", "version", v == null ? "vanilla" : v.id());
            } else {
                plugin.messages().send(sender, "players-only");
            }
            return;
        }
        if (!sender.hasPermission("miharu.pvp.admin")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
        if (target == null) {
            plugin.messages().send(sender, "player-not-found", "player", args.length > 1 ? args[1] : "?");
            return;
        }
        if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("reset")) {
            combat.force(target, (CombatVersion) null);
            plugin.messages().send(sender, "pvp.reset", "player", target.getName());
            return;
        }
        CombatVersion v = CombatVersion.parse(args[0]);
        if (v == null) {
            plugin.messages().send(sender, "pvp.usage");
            return;
        }
        combat.force(target, v);
        plugin.messages().send(sender, "pvp.set", "player", target.getName(), "version", v.id());
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!source.getSender().hasPermission("miharu.pvp.admin")) {
            return List.of();
        }
        if (args.length <= 1) {
            return List.of("1.8", "1.9", "off");
        }
        if (args.length == 2) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return names;
        }
        return List.of();
    }
}
