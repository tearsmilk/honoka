package dev.tearsmilk.miharu.bots.ai;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Физика бота по ванильным формулам движения игрока:
 * разгон 0.1 (спринт ×1.3), трение блока × 0.91, гравитация 0.08, сопротивление 0.98, прыжок 0.42, шаг 0.6.
 */
public final class BotBody {

    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;
    private static final double EYE = 1.62;
    private static final double STEP = 0.6;

    private final World world;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public final Vector velocity = new Vector();
    public boolean onGround;
    public boolean horizontalCollision;
    public double fallDistance;

    public BotBody(Location at) {
        this.world = at.getWorld();
        teleport(at);
    }

    public void teleport(Location at) {
        x = at.getX();
        y = at.getY();
        z = at.getZ();
        yaw = at.getYaw();
        pitch = at.getPitch();
        velocity.zero();
        onGround = false;
        fallDistance = 0;
    }

    public Location location() {
        return new Location(world, x, y, z, yaw, pitch);
    }

    public Location eye() {
        return new Location(world, x, y + EYE, z, yaw, pitch);
    }

    public BoundingBox box() {
        return new BoundingBox(x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + HEIGHT, z + HALF_WIDTH);
    }

    public boolean falling() {
        return !onGround && velocity.getY() < 0;
    }

    public boolean inLiquid() {
        Block feet = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y + 0.1), (int) Math.floor(z));
        return feet.isLiquid();
    }

    /**
     * Один тик движения.
     *
     * @param forward   вперёд (+1) / назад (-1)
     * @param strafe    влево (+1) / вправо (-1)
     * @param speedMul  множитель скорости из сложности
     */
    public void tick(double forward, double strafe, boolean jump, boolean sprint, double speedMul) {
        boolean liquid = inLiquid();
        if (jump && onGround && !liquid) {
            velocity.setY(0.42);
            if (sprint) {
                double rad = Math.toRadians(yaw);
                velocity.setX(velocity.getX() - Math.sin(rad) * 0.2);
                velocity.setZ(velocity.getZ() + Math.cos(rad) * 0.2);
            }
        } else if (jump && liquid) {
            velocity.setY(velocity.getY() + 0.04);
        }

        double blockFriction = onGround ? friction(world.getBlockAt((int) Math.floor(x), (int) Math.floor(y - 0.5), (int) Math.floor(z)).getType()) : 1.0;
        double accel;
        if (liquid) {
            accel = 0.02;
        } else if (onGround) {
            accel = 0.1 * (sprint ? 1.3 : 1.0) * speedMul * (0.21600002 / (blockFriction * blockFriction * blockFriction));
        } else {
            accel = (sprint ? 0.026 : 0.02) * speedMul;
        }
        double len = forward * forward + strafe * strafe;
        if (len > 1.0E-7) {
            len = Math.sqrt(len);
            double f = len < 1 ? 1 : len;
            double fw = forward / f * accel;
            double st = strafe / f * accel;
            double rad = Math.toRadians(yaw);
            double sin = Math.sin(rad);
            double cos = Math.cos(rad);
            velocity.setX(velocity.getX() + st * cos - fw * sin);
            velocity.setZ(velocity.getZ() + fw * cos + st * sin);
        }

        double startY = y;
        move(velocity.getX(), velocity.getY(), velocity.getZ());

        if (liquid) {
            velocity.multiply(0.8);
            velocity.setY(velocity.getY() - 0.02);
        } else {
            velocity.setY((velocity.getY() - 0.08) * 0.98);
            double horizontalDrag = onGround ? blockFriction * 0.91 : 0.91;
            velocity.setX(velocity.getX() * horizontalDrag);
            velocity.setZ(velocity.getZ() * horizontalDrag);
        }
        if (Math.abs(velocity.getX()) < 0.003) {
            velocity.setX(0);
        }
        if (Math.abs(velocity.getZ()) < 0.003) {
            velocity.setZ(0);
        }
        if (onGround) {
            fallDistance = 0;
        } else if (y < startY) {
            fallDistance += startY - y;
        }
    }

    private static double friction(Material m) {
        return switch (m) {
            case ICE, PACKED_ICE, FROSTED_ICE -> 0.98;
            case BLUE_ICE -> 0.989;
            case SLIME_BLOCK -> 0.8;
            default -> 0.6;
        };
    }

    private void move(double dx, double dy, double dz) {
        BoundingBox box = box();
        double ox = dx;
        double oy = dy;
        double oz = dz;
        List<BoundingBox> cols = collisions(box.clone().expandDirectional(dx, dy, dz));

        for (BoundingBox c : cols) {
            dy = clipY(c, box, dy);
        }
        box.shift(0, dy, 0);
        for (BoundingBox c : cols) {
            dx = clipX(c, box, dx);
        }
        box.shift(dx, 0, 0);
        for (BoundingBox c : cols) {
            dz = clipZ(c, box, dz);
        }
        box.shift(0, 0, dz);

        boolean groundNow = oy != dy && oy < 0;
        boolean blocked = ox != dx || oz != dz;

        // подъём на ступеньку (полублоки, лестницы)
        if (blocked && (onGround || groundNow)) {
            BoundingBox step = box();
            List<BoundingBox> sc = collisions(step.clone().expandDirectional(ox, STEP, oz));
            double sy = STEP;
            for (BoundingBox c : sc) {
                sy = clipY(c, step, sy);
            }
            step.shift(0, sy, 0);
            double sx = ox;
            for (BoundingBox c : sc) {
                sx = clipX(c, step, sx);
            }
            step.shift(sx, 0, 0);
            double sz = oz;
            for (BoundingBox c : sc) {
                sz = clipZ(c, step, sz);
            }
            step.shift(0, 0, sz);
            double down = -sy;
            for (BoundingBox c : collisions(step.clone().expandDirectional(0, down, 0))) {
                down = clipY(c, step, down);
            }
            step.shift(0, down, 0);
            if (sx * sx + sz * sz > dx * dx + dz * dz + 1.0E-6) {
                box = step;
                dx = sx;
                dz = sz;
                groundNow = true;
            }
        }

        x = box.getCenterX();
        y = box.getMinY();
        z = box.getCenterZ();
        onGround = groundNow;
        horizontalCollision = ox != dx || oz != dz;
        if (oy != dy) {
            velocity.setY(0);
        }
        if (ox != dx) {
            velocity.setX(0);
        }
        if (oz != dz) {
            velocity.setZ(0);
        }
    }

    private List<BoundingBox> collisions(BoundingBox area) {
        List<BoundingBox> out = new ArrayList<>();
        int minX = (int) Math.floor(area.getMinX() - 1.0E-7);
        int maxX = (int) Math.floor(area.getMaxX() + 1.0E-7);
        int minY = (int) Math.floor(area.getMinY() - 1.0E-7) - 1;
        int maxY = (int) Math.floor(area.getMaxY() + 1.0E-7);
        int minZ = (int) Math.floor(area.getMinZ() - 1.0E-7);
        int maxZ = (int) Math.floor(area.getMaxZ() + 1.0E-7);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                    continue;
                }
                for (int by = minY; by <= maxY; by++) {
                    Block b = world.getBlockAt(bx, by, bz);
                    if (b.getType().isAir() || b.isPassable()) {
                        continue;
                    }
                    for (BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) {
                        BoundingBox shifted = bb.clone().shift(bx, by, bz);
                        if (shifted.overlaps(area)) {
                            out.add(shifted);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static final double EPS = 1.0E-7;

    private static double clipY(BoundingBox o, BoundingBox me, double dy) {
        if (me.getMaxX() <= o.getMinX() + EPS || me.getMinX() >= o.getMaxX() - EPS
                || me.getMaxZ() <= o.getMinZ() + EPS || me.getMinZ() >= o.getMaxZ() - EPS) {
            return dy;
        }
        if (dy > 0 && me.getMaxY() <= o.getMinY() + EPS) {
            double d = o.getMinY() - me.getMaxY();
            if (d < dy) {
                dy = Math.max(0, d);
            }
        } else if (dy < 0 && me.getMinY() >= o.getMaxY() - EPS) {
            double d = o.getMaxY() - me.getMinY();
            if (d > dy) {
                dy = Math.min(0, d);
            }
        }
        return dy;
    }

    private static double clipX(BoundingBox o, BoundingBox me, double dx) {
        if (me.getMaxY() <= o.getMinY() + EPS || me.getMinY() >= o.getMaxY() - EPS
                || me.getMaxZ() <= o.getMinZ() + EPS || me.getMinZ() >= o.getMaxZ() - EPS) {
            return dx;
        }
        if (dx > 0 && me.getMaxX() <= o.getMinX() + EPS) {
            double d = o.getMinX() - me.getMaxX();
            if (d < dx) {
                dx = Math.max(0, d);
            }
        } else if (dx < 0 && me.getMinX() >= o.getMaxX() - EPS) {
            double d = o.getMaxX() - me.getMinX();
            if (d > dx) {
                dx = Math.min(0, d);
            }
        }
        return dx;
    }

    private static double clipZ(BoundingBox o, BoundingBox me, double dz) {
        if (me.getMaxY() <= o.getMinY() + EPS || me.getMinY() >= o.getMaxY() - EPS
                || me.getMaxX() <= o.getMinX() + EPS || me.getMinX() >= o.getMaxX() - EPS) {
            return dz;
        }
        if (dz > 0 && me.getMaxZ() <= o.getMinZ() + EPS) {
            double d = o.getMinZ() - me.getMaxZ();
            if (d < dz) {
                dz = Math.max(0, d);
            }
        } else if (dz < 0 && me.getMinZ() >= o.getMaxZ() - EPS) {
            double d = o.getMaxZ() - me.getMinZ();
            if (d > dz) {
                dz = Math.min(0, d);
            }
        }
        return dz;
    }
}
