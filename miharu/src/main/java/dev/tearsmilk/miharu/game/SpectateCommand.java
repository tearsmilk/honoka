package dev.tearsmilk.miharu.game;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

/** /spectate — меню игр, /spectate &lt;игрок&gt; — смотреть его игру. */
final class SpectateCommand implements BasicCommand {

    private final GameModule module;

    SpectateCommand(GameModule module) {
        this.module = module;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player p)) {
            return;
        }
        if (args.length == 0) {
            module.spectateMenu().open(p);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        Game game = target == null ? null : module.games().of(target);
        if (game == null || !game.isActive()) {
            module.plugin().messages().send(p, "game.spectate-unavailable");
            return;
        }
        module.games().spectate(p, game);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return module.games().games().stream().flatMap(g -> g.players().stream()).map(Player::getName).toList();
    }

    @Override
    public String permission() {
        return "miharu.spectate";
    }
}
