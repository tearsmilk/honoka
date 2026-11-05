package dev.tearsmilk.miharu.game;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.arena.Arena;
import dev.tearsmilk.miharu.arena.world.ArenaInstance;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Базовая игра. Каждая игра живёт в своём временном мире со своим табло и табом.
 * Наследники: игры с ботами (дуэль, клик-тест), дальше — дуэли, митапы, мини-игры.
 */
public abstract class Game {

    public enum State { STARTING, COUNTDOWN, RUNNING, ENDING, CLOSED }

    protected final Miharu plugin;
    protected final GameModule module;
    protected final String mode;
    protected final Arena arena;
    protected final List<Player> players = new ArrayList<>();
    final List<Player> spectators = new ArrayList<>();
    protected ArenaInstance instance;
    /** Своё табло у каждого игрока (строки зависят от того, кто смотрит). */
    protected final Map<java.util.UUID, GameBoard> boards = new HashMap<>();
    protected State state = State.STARTING;
    protected int ticks;
    protected int runningTicks;
    private int countdownLeft;
    int id;

    protected Game(GameModule module, String mode, Arena arena) {
        this.plugin = module.plugin();
        this.module = module;
        this.mode = mode;
        this.arena = arena;
    }

    public int id() {
        return id;
    }

    public String mode() {
        return mode;
    }

    public Arena arena() {
        return arena;
    }

    public State state() {
        return state;
    }

    public ArenaInstance instance() {
        return instance;
    }

    public List<Player> players() {
        return Collections.unmodifiableList(players);
    }

    public List<Player> spectators() {
        return Collections.unmodifiableList(spectators);
    }

    /** Зритель вошёл (показать ботов и т.п.). */
    /** Можно ли сейчас зайти в игру из очереди (по умолчанию — нет). */
    public boolean canLateJoin() {
        return false;
    }

    /** Игрок зашёл в уже идущую игру (после телепорта на spawnFor). */
    protected void onLateJoin(Player p) {
    }

    protected void onSpectate(Player p) {
    }

    /** Зритель вышел. */
    protected void onSpectatorLeave(Player p) {
    }

    public boolean isActive() {
        return state == State.COUNTDOWN || state == State.RUNNING;
    }

    /** Версия боя игры (null — не трогать). */
    public abstract @Nullable CombatVersion combatVersion();

    /** Готовый профиль боя (например, с настройками кита). null — профиль версии combatVersion(). */
    public dev.tearsmilk.miharu.pvp.@Nullable CombatProfile combatProfile() {
        return null;
    }

    public GameBoard board(Player p) {
        return boards.get(p.getUniqueId());
    }

    protected void eachBoard(java.util.function.Consumer<GameBoard> action) {
        boards.values().forEach(action);
    }

    /** Повторный отсчёт (новый раунд). */
    protected void restartCountdown(int seconds) {
        countdownLeft = Math.max(0, seconds);
        state = countdownLeft > 0 ? State.COUNTDOWN : State.RUNNING;
        if (state == State.RUNNING) {
            onRunning();
        } else {
            onCountdown(countdownLeft);
        }
    }

    /** Движение игрока в RUNNING (цели Bridge и т.п.). */
    protected void onMove(Player p, Location to) {
    }

    /** Можно ли поставить блок. replaced — что было на месте. */
    protected boolean canPlace(Player p, org.bukkit.block.Block block, org.bukkit.block.BlockState replaced) {
        return module.config().getBoolean("allow-build", false);
    }

    /** Можно ли сломать блок. */
    protected boolean canBreak(Player p, org.bukkit.block.Block block) {
        return module.config().getBoolean("allow-build", false);
    }

    /** Точка появления игрока. */
    protected abstract Location spawnFor(Player p);

    /** Название режима для табло/сообщений. */
    public abstract String displayName();

    /** Подготовка после телепорта: киты, боты. */
    protected void onSetup() {
    }

    protected int countdownSeconds() {
        return module.config().getInt("countdown-seconds", 3);
    }

    protected void onCountdown(int secondsLeft) {
        for (Player p : players) {
            plugin.messages().title(p, "game.countdown", "seconds", secondsLeft);
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
        }
    }

    protected void onRunning() {
        for (Player p : players) {
            plugin.messages().title(p, "game.go");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
        }
    }

    /** Каждый тик в любом состоянии, кроме CLOSED. */
    protected void onTickAlways() {
    }

    /** Каждый тик в RUNNING. */
    protected void onTick() {
    }

    /** Игрок вышел (/leave, выход с сервера) пока игра идёт. */
    protected void onPlayerLeave(Player p, boolean quit) {
    }

    /** Смертельный урон по игроку (сам урон уже отменён). */
    protected void onLethal(Player p, EntityDamageEvent e) {
    }

    /** Игрок упал ниже void-y арены. */
    protected void onVoid(Player p) {
        p.teleport(spawnFor(p));
    }

    /** Взмах рукой (клик ЛКМ). */
    protected void onSwing(Player p) {
    }

    /** Клик по пакетной сущности (боту). */
    protected void onUnknownEntity(Player p, int entityId, boolean attack) {
    }

    /** Любой урон по игроку в RUNNING. Вернуть true, если игра сама решила судьбу события. */
    protected boolean onDamage(Player p, EntityDamageEvent e) {
        return false;
    }

    protected boolean allowHunger() {
        return true;
    }

    /** Конец игры: вызвать один раз, дальше GameManager вернёт игроков и удалит мир. */
    protected void onEnd() {
    }

    /** Уборка (боты, задачи) перед удалением мира. */
    protected void onClose() {
    }

    /** Плейсхолдеры для табло и таба. */
    public Map<String, Object> placeholders(Player viewer) {
        Map<String, Object> map = new HashMap<>();
        map.put("mode", displayName());
        map.put("arena", arena.display());
        map.put("time", Text.time(runningTicks / 20));
        map.put("game_id", id);
        map.put("player", viewer.getName());
        map.put("ping", viewer.getPing());
        map.put("online", players.size());
        return map;
    }

    public final void end() {
        module.games().end(this);
    }

    // --- для GameManager ---

    final void startCountdown() {
        countdownLeft = Math.max(0, countdownSeconds());
        state = countdownLeft > 0 ? State.COUNTDOWN : State.RUNNING;
        if (state == State.RUNNING) {
            onRunning();
        } else {
            onCountdown(countdownLeft);
        }
    }

    final void tick() {
        ticks++;
        if (state == State.COUNTDOWN && ticks % 20 == 0) {
            countdownLeft--;
            if (countdownLeft <= 0) {
                state = State.RUNNING;
                onRunning();
            } else {
                onCountdown(countdownLeft);
            }
        }
        if (state == State.RUNNING) {
            runningTicks++;
            onTick();
        }
        if (state != State.CLOSED) {
            onTickAlways();
        }
    }

    protected Component text(String key, Object... kv) {
        return plugin.messages().get(key, kv);
    }
}
