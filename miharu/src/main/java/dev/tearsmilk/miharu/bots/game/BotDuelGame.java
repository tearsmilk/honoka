package dev.tearsmilk.miharu.bots.game;

import dev.tearsmilk.miharu.arena.Arena;
import dev.tearsmilk.miharu.bots.BotsModule;
import dev.tearsmilk.miharu.bots.ai.BotDifficulty;
import dev.tearsmilk.miharu.bots.kit.Kit;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatService;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Map;

/** Дуэль против бота: до смерти или падения в пустоту. */
public final class BotDuelGame extends BotGame {

    public static final String MODE = "bot-duel";

    public enum Result { WIN, LOSS, DRAW }

    private Result result;
    private final int maxSeconds;

    public BotDuelGame(BotsModule bots, GameModule games, Arena arena, Player player, CombatVersion version,
                       BotDifficulty difficulty, Kit kit, CombatService combat) {
        super(bots, games, MODE, arena, player, version, difficulty, kit, combat);
        this.maxSeconds = bots.config().getInt("modes.bot-duel.max-duration-seconds", 300);
    }

    @Override
    public String displayName() {
        return bots.config().getString("modes.bot-duel.display", "Дуэль с ботом");
    }

    @Override
    protected int countdownSeconds() {
        return bots.config().getInt("modes.bot-duel.countdown-seconds", super.countdownSeconds());
    }

    @Override
    protected void onTick() {
        if (maxSeconds > 0 && runningTicks >= maxSeconds * 20) {
            finish(Result.DRAW);
        }
    }

    @Override
    protected void onLethal(Player p, EntityDamageEvent e) {
        finish(Result.LOSS);
    }

    @Override
    protected void onVoid(Player p) {
        if (state == State.RUNNING) {
            finish(Result.LOSS);
        } else {
            super.onVoid(p);
        }
    }

    @Override
    protected void onBotKilled() {
        finish(Result.WIN);
    }

    @Override
    protected void onBotVoid() {
        botAlive = false;
        finish(Result.WIN);
    }

    @Override
    protected void onPlayerLeave(Player p, boolean quit) {
        if (state == State.RUNNING) {
            result = Result.LOSS;
            bots.stats().recordDuel(player, version, difficulty, Result.LOSS);
        }
    }

    private void finish(Result r) {
        if (result != null) {
            return;
        }
        result = r;
        bots.stats().recordDuel(player, version, difficulty, r);
        Map<String, Object> ph = placeholders(player);
        String key = switch (r) {
            case WIN -> "bots.duel.win";
            case LOSS -> "bots.duel.loss";
            case DRAW -> "bots.duel.draw";
        };
        plugin.messages().title(player, key, ph.entrySet().stream().flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue())).toArray());
        plugin.messages().sendList(player, "bots.duel.summary", ph.entrySet().stream().flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue())).toArray());
        player.playSound(player.getLocation(), r == Result.WIN ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        var max = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(max == null ? 20 : max.getValue());
        player.setFireTicks(0);
        if (r == Result.LOSS && bots.config().getBoolean("modes.bot-duel.spectate-on-loss", true)) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        end();
    }

    @Override
    public Map<String, Object> placeholders(Player viewer) {
        Map<String, Object> map = super.placeholders(viewer);
        map.put("time_left", Text.time(Math.max(0, maxSeconds - runningTicks / 20)));
        map.put("result", result == null ? "" : result.name().toLowerCase());
        return map;
    }
}
