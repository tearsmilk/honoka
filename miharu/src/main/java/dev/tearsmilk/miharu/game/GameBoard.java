package dev.tearsmilk.miharu.game;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;

/** Своё табло на каждую игру: боковая панель, сердечки под ником, команды с префиксами. */
public final class GameBoard {

    private final Scoreboard scoreboard;
    private final Objective sidebar;
    private Objective belowName;
    private int lastSize;

    public GameBoard(Component title) {
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        sidebar = scoreboard.registerNewObjective("miharu_side", Criteria.DUMMY, title);
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        sidebar.numberFormat(NumberFormat.blank());
    }

    public Scoreboard scoreboard() {
        return scoreboard;
    }

    public void title(Component title) {
        sidebar.displayName(title);
    }

    public void lines(List<Component> lines) {
        int n = Math.min(15, lines.size());
        for (int i = 0; i < n; i++) {
            Score score = sidebar.getScore("§" + Integer.toHexString(i) + "§r");
            score.setScore(n - i);
            score.customName(lines.get(i));
        }
        for (int i = n; i < lastSize; i++) {
            scoreboard.resetScores("§" + Integer.toHexString(i) + "§r");
        }
        lastSize = n;
    }

    /** Значение под ником (например, ❤ бота). */
    public void belowName(String entry, int value, Component label) {
        if (belowName == null) {
            belowName = scoreboard.registerNewObjective("miharu_below", Criteria.DUMMY, label == null ? Component.text("❤") : label);
            belowName.setDisplaySlot(DisplaySlot.BELOW_NAME);
        }
        belowName.getScore(entry).setScore(value);
    }

    public Team team(String name, Component prefix) {
        Team t = scoreboard.getTeam(name);
        if (t == null) {
            t = scoreboard.registerNewTeam(name);
        }
        t.prefix(prefix);
        return t;
    }

    public void show(Player p) {
        p.setScoreboard(scoreboard);
    }
}
