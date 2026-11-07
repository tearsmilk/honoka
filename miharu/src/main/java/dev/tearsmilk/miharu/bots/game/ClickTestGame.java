package dev.tearsmilk.miharu.bots.game;

import dev.tearsmilk.miharu.arena.Arena;
import dev.tearsmilk.miharu.bots.BotsModule;
import dev.tearsmilk.miharu.bots.ai.BotDifficulty;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatService;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Клик-тест: бесконечное сумо с ботом на время (по умолчанию 10 минут).
 * Урона нет, только отдача. Падения не заканчивают игру — обоих возвращает на точки.
 * Цель — максимум кликов (или ударов, см. rank-by).
 */
public final class ClickTestGame extends BotGame {

    public static final String MODE = "clicktest";

    private final int durationSeconds;
    private final Deque<Integer> recentClicks = new ArrayDeque<>();
    private int clicks;
    private int maxCps;
    private int knockoffs;
    private int falls;
    private boolean finished;

    public ClickTestGame(BotsModule bots, GameModule games, Arena arena, Player player, KitSpec spec,
                         BotDifficulty difficulty, CombatService combat) {
        super(bots, games, MODE, arena, player, spec, difficulty, combat);
        this.durationSeconds = Math.max(10, bots.config().getInt("modes.clicktest.duration-seconds", 600));
    }

    @Override
    public String displayName() {
        return bots.config().getString("modes.clicktest.display", "Клик-тест");
    }

    @Override
    protected boolean damageEnabled() {
        return false;
    }

    @Override
    protected boolean allowHunger() {
        return false;
    }

    @Override
    protected boolean liquidLose() {
        return bots.liquidIsVoid(MODE);
    }

    @Override
    protected int countdownSeconds() {
        return bots.config().getInt("modes.clicktest.countdown-seconds", super.countdownSeconds());
    }

    @Override
    protected boolean onDamage(Player p, EntityDamageEvent e) {
        e.setCancelled(true);
        return true;
    }

    @Override
    protected void onSwing(Player p) {
        clicks++;
        recentClicks.addLast(ticks);
        trim();
        maxCps = Math.max(maxCps, recentClicks.size());
    }

    private void trim() {
        while (!recentClicks.isEmpty() && ticks - recentClicks.peekFirst() >= 20) {
            recentClicks.removeFirst();
        }
    }

    @Override
    protected void onTick() {
        trim();
        if (runningTicks >= durationSeconds * 20) {
            finish(true);
        }
    }

    @Override
    protected void onVoid(Player p) {
        if (state == State.RUNNING) {
            falls++;
            combo = 0;
            plugin.messages().actionBar(p, "bots.clicktest.fell");
        }
        resetRound();
    }

    @Override
    protected void onBotVoid() {
        knockoffs++;
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
        plugin.messages().actionBar(player, "bots.clicktest.knockoff", "knockoffs", knockoffs);
        resetRound();
    }

    private void resetRound() {
        player.teleport(spawnFor(player));
        player.setVelocity(new org.bukkit.util.Vector());
        player.setFallDistance(0);
        if (body != null) {
            resetBot();
        }
    }

    @Override
    protected void onPlayerLeave(Player p, boolean quit) {
        if (state == State.RUNNING && bots.config().getBoolean("modes.clicktest.save-unfinished", false)) {
            finish(false);
        }
    }

    private void finish(boolean complete) {
        if (finished) {
            return;
        }
        finished = true;
        int seconds = Math.max(1, runningTicks / 20);
        Map<String, Object> ph = placeholders(player);
        Object[] kv = ph.entrySet().stream().flatMap(e -> Stream.of(e.getKey(), e.getValue())).toArray();
        bots.stats().recordClickTest(player, version, difficulty, clicks, playerHits, maxCps, knockoffs, falls, seconds)
                .thenAccept(prev -> org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    int score = bots.clickTestScore(clicks, playerHits);
                    if (player.isOnline() && (prev == null || score > prev)) {
                        plugin.messages().send(player, "bots.clicktest.new-best", "score", score, "previous", prev == null ? "-" : prev);
                    }
                }));
        if (complete) {
            plugin.messages().title(player, "bots.clicktest.finished", kv);
            plugin.messages().sendList(player, "bots.clicktest.summary", kv);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            end();
        }
    }

    @Override
    public Map<String, Object> placeholders(Player viewer) {
        Map<String, Object> map = super.placeholders(viewer);
        int seconds = Math.max(1, runningTicks / 20);
        map.put("time_left", Text.time(Math.max(0, durationSeconds - runningTicks / 20)));
        map.put("clicks", clicks);
        map.put("cps", recentClicks.size());
        map.put("max_cps", maxCps);
        map.put("avg_cps", String.format(java.util.Locale.ROOT, "%.1f", clicks / (double) seconds));
        map.put("knockoffs", knockoffs);
        map.put("falls", falls);
        map.put("score", bots.clickTestScore(clicks, playerHits));
        map.put("accuracy", clicks == 0 ? "0" : String.valueOf(Math.round(playerHits * 100.0 / clicks)));
        return map;
    }
}
