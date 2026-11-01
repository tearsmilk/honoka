package dev.tearsmilk.miharu.bots.ai;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Решения бота на каждый тик: куда смотреть, как двигаться, когда кликать.
 * Сложность задаёт реакцию, точность прицела, CPS, стрейфы, w-tap, криты и блок-хит.
 */
public final class BotBrain {

    /** Что бот делает в этом тике. */
    public record Output(double forward, double strafe, boolean jump, boolean sprint, boolean click, boolean block) {
        public static final Output IDLE = new Output(0, 0, false, false, false, false);
    }

    private final BotDifficulty d;
    private final Deque<Vector> history = new ArrayDeque<>();
    private double clickBudget;
    private double cps;
    private int cpsRollIn;
    private int strafeDir;
    private int strafeLeft;
    private int wtapLeft;
    private int blockLeft;
    private double yawNoise;
    private double pitchNoise;
    private int idleTicks;

    public BotBrain(BotDifficulty d) {
        this.d = d;
    }

    public BotDifficulty difficulty() {
        return d;
    }

    /** Вызывается игрой, когда удар бота попал. */
    public void onLandedHit() {
        if (d.wtap()) {
            wtapLeft = rnd(d.wtapMinTicks(), d.wtapMaxTicks());
        }
    }

    /** Вызывается, когда бот получил удар. */
    public void onHurt() {
        if (d.strafeChance() > 0 && ThreadLocalRandom.current().nextDouble() < d.strafeChance() * 0.5) {
            strafeDir = -strafeDir;
        }
    }

    public void reset() {
        history.clear();
        clickBudget = 0;
        wtapLeft = 0;
        blockLeft = 0;
        strafeLeft = 0;
    }

    /**
     * @param charge   зарядка удара бота 0..1 (для 1.9), в 1.8 всегда 1
     * @param modern   идёт бой 1.9
     */
    public Output tick(BotBody body, Player target, double charge, boolean modern) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location t = target.getLocation();
        Vector now = new Vector(t.getX(), t.getY(), t.getZ());
        history.addLast(now);
        while (history.size() > Math.max(1, d.reactionTicks() + 1)) {
            history.removeFirst();
        }
        // бот «видит» цель с задержкой реакции и упреждает по её скорости
        Vector seen = history.peekFirst().clone().add(target.getVelocity().clone().multiply(d.lead()));

        double dx = seen.getX() - body.x;
        double dz = seen.getZ() - body.z;
        double dy = (seen.getY() + 1.2) - (body.y + 1.62);
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double realDist = Math.hypot(t.getX() - body.x, t.getZ() - body.z);

        // --- прицел ---
        yawNoise = yawNoise * 0.85 + r.nextGaussian() * d.aimError() * 0.35;
        pitchNoise = pitchNoise * 0.85 + r.nextGaussian() * d.aimError() * 0.2;
        float wantYaw = (float) (Math.toDegrees(-Math.atan2(dx, dz)) + yawNoise);
        float wantPitch = (float) (Math.toDegrees(-Math.atan2(dy, Math.max(0.01, horiz))) + pitchNoise);
        body.yaw = approach(body.yaw, wantYaw, (float) d.turnSpeed());
        body.pitch = clamp(approach(body.pitch, wantPitch, (float) d.turnSpeed()), -90, 90);

        // --- движение ---
        double forward = realDist > d.keepDistance() ? 1 : 0;
        boolean sprint = forward > 0;
        if (wtapLeft > 0) {
            wtapLeft--;
            forward = wtapLeft > 0 ? 0 : 1;
            sprint = false;
        }
        double strafe = 0;
        if (realDist < 5 && d.strafeChance() > 0) {
            if (--strafeLeft <= 0) {
                strafeLeft = rnd(d.strafeMinTicks(), d.strafeMaxTicks());
                strafeDir = r.nextDouble() < d.strafeChance() ? (r.nextBoolean() ? 1 : -1) : 0;
            }
            strafe = strafeDir;
        }
        boolean jump = false;
        if (body.horizontalCollision && body.onGround) {
            jump = true;
        }
        if (d.jumpChance() > 0 && body.onGround && realDist > 4 && r.nextDouble() < d.jumpChance()) {
            jump = true;
        }
        // крит-прыжок в 1.9: прыгаем, когда удар почти заряжен и цель рядом
        if (modern && d.critJump() && body.onGround && charge > 0.75 && realDist < d.reach() + 1.0) {
            jump = true;
        }

        // --- клики ---
        if (--cpsRollIn <= 0) {
            cps = d.cpsMin() + r.nextDouble() * Math.max(0, d.cpsMax() - d.cpsMin());
            cpsRollIn = 20;
        }
        clickBudget = Math.min(1.5, clickBudget + cps / 20.0);
        boolean inRange = realDist < d.reach() + 0.8;
        boolean click = false;
        if (clickBudget >= 1) {
            boolean want = inRange || (d.swingOutOfRange() && realDist < 6);
            if (modern && d.cooldownAware()) {
                // умный бот в 1.9 бьёт только заряженным ударом и не машет в воздух
                want = inRange && charge >= d.cooldownThreshold();
                if (d.critJump() && !body.falling() && !body.onGround) {
                    want = false;
                }
            }
            if (want) {
                click = true;
                clickBudget -= 1;
            } else if (!modern || !d.cooldownAware()) {
                clickBudget -= 1;
            }
        }

        // --- блок-хит (1.8): блокируем между кликами ---
        boolean block = false;
        if (!modern && d.blockHit() && inRange && !click) {
            if (blockLeft > 0) {
                blockLeft--;
                block = true;
            } else if (r.nextDouble() < d.blockChance()) {
                blockLeft = rnd(2, 4);
                block = true;
            }
        }
        if (click) {
            blockLeft = 0;
        }
        if (block) {
            sprint = false;
        }
        idleTicks = 0;
        return new Output(forward, strafe, jump, sprint, click, block);
    }

    private static float approach(float from, float to, float maxStep) {
        float diff = wrap(to - from);
        if (diff > maxStep) {
            diff = maxStep;
        } else if (diff < -maxStep) {
            diff = -maxStep;
        }
        return wrap(from + diff);
    }

    public static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) {
            deg -= 360f;
        }
        if (deg < -180f) {
            deg += 360f;
        }
        return deg;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int rnd(int min, int max) {
        return min >= max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}
