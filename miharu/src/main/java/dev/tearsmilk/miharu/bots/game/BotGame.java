package dev.tearsmilk.miharu.bots.game;

import dev.tearsmilk.miharu.arena.Arena;
import dev.tearsmilk.miharu.bots.BotsModule;
import dev.tearsmilk.miharu.bots.ai.BotBody;
import dev.tearsmilk.miharu.bots.ai.BotBrain;
import dev.tearsmilk.miharu.bots.ai.BotDifficulty;
import dev.tearsmilk.miharu.bots.kit.Kit;
import dev.tearsmilk.miharu.bots.nms.PacketBot;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.Game;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatMath;
import dev.tearsmilk.miharu.pvp.CombatProfile;
import dev.tearsmilk.miharu.pvp.CombatService;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Общая часть игр против пакетного бота: физика, ИИ, удары в обе стороны. */
public abstract class BotGame extends Game {

    protected final BotsModule bots;
    protected final Player player;
    protected final CombatVersion version;
    protected final CombatProfile profile;
    protected final CombatService combat;
    protected final BotDifficulty difficulty;
    protected final Kit kit;

    protected PacketBot bot;
    protected BotBody body;
    protected BotBrain brain;
    protected double botHealth;
    protected boolean botAlive = true;
    protected int botHurtTicks;
    protected int botAttackTicks = 100;
    protected int botSprintLock;
    protected int playerHurtLock;
    protected boolean botBlockingNow;

    protected int playerHits;
    protected int botHits;
    protected int combo;
    protected int bestCombo;
    protected double lastReach;

    protected BotGame(BotsModule bots, GameModule games, String mode, Arena arena, Player player,
                      CombatVersion version, BotDifficulty difficulty, Kit kit, CombatService combat) {
        super(games, mode, arena);
        this.bots = bots;
        this.player = player;
        this.version = version;
        this.combat = combat;
        this.profile = combat.profile(version);
        this.difficulty = difficulty;
        this.kit = kit;
    }

    @Override
    public CombatVersion combatVersion() {
        return version;
    }

    @Override
    protected Location spawnFor(Player p) {
        return instance.spawn("player");
    }

    protected Location botSpawn() {
        return instance.spawn("bot");
    }

    /** Наносит ли бой урон (в клик-тесте — нет). */
    protected boolean damageEnabled() {
        return true;
    }

    @Override
    protected void onSetup() {
        if (kit != null) {
            kit.apply(player, combat, profile);
        }
        botHealth = difficulty.health();
        body = new BotBody(botSpawn());
        brain = new BotBrain(difficulty);
        String name = bots.botName(difficulty);
        BotsModule.Skin skin = bots.skin(player);
        bot = new PacketBot(name, skin == null ? null : skin.value(), skin == null ? null : skin.signature(), bots.config().getBoolean("bot.show-in-tab", false));
        bot.position(body.x, body.y, body.z, body.yaw, body.pitch, true);
        if (kit != null) {
            for (Map.Entry<EquipmentSlot, ItemStack> e : kit.armor().entrySet()) {
                bot.equip(e.getKey(), e.getValue());
            }
            if (kit.weapon() != null) {
                bot.equip(EquipmentSlot.HAND, combat.prepare(kit.weapon().clone(), profile));
            }
        }
        board.team("miharu_bot", Text.mm(difficulty.namePrefix())).addEntry(bot.name());
        if (damageEnabled()) {
            board.belowName(bot.name(), (int) Math.ceil(botHealth), Text.mm(bots.config().getString("bot.below-name", "<red>❤")));
        }
        bot.show(player);
    }

    @Override
    protected void onTickAlways() {
        if (bot == null || body == null) {
            return;
        }
        BotBrain.Output out = BotBrain.Output.IDLE;
        boolean running = state == State.RUNNING && botAlive && player.isOnline();
        double charge = charge();
        if (running) {
            out = brain.tick(body, player, charge, version == CombatVersion.MODERN);
        } else if (botAlive && player.isOnline()) {
            // во время отсчёта бот просто смотрит на игрока
            Location t = player.getLocation();
            body.yaw = (float) Math.toDegrees(-Math.atan2(t.getX() - body.x, t.getZ() - body.z));
        }
        boolean sprint = out.sprint() && botSprintLock <= 0;
        if (botAlive) {
            body.tick(out.forward(), out.strafe(), out.jump(), sprint, difficulty.speed());
        }
        bot.position(body.x, body.y, body.z, body.yaw, body.pitch, body.onGround);
        bot.sprinting(sprint && out.forward() > 0);
        bot.blocking(out.block());
        botBlockingNow = out.block();
        bot.sync();

        if (botHurtTicks > 0) {
            botHurtTicks--;
        }
        if (botSprintLock > 0) {
            botSprintLock--;
        }
        if (playerHurtLock > 0) {
            playerHurtLock--;
        }
        botAttackTicks++;

        if (running && out.click()) {
            botClick(sprint, out.block());
        }
        if (running && botAlive) {
            if (body.y < instance.voidY() || (bots.liquidIsVoid(mode) && body.inLiquid())) {
                onBotVoid();
            } else if (bots.liquidIsVoid(mode) && player.isInWater()) {
                onVoid(player);
            }
        }
    }

    /** Зарядка удара бота 0..1 (1.9). */
    protected double charge() {
        if (!profile.cooldownDamage) {
            return 1;
        }
        double needed = 20.0 / Math.max(0.1, profile.attackSpeedOf(kit == null ? Material.AIR : kit.weaponType()));
        return Math.min(1, botAttackTicks / needed);
    }

    // --- бот бьёт игрока ---

    private void botClick(boolean sprinting, boolean blocking) {
        bot.swing();
        double charge = charge();
        botAttackTicks = 0;
        if (blocking) {
            return;
        }
        Location eye = body.eye();
        BoundingBox target = player.getBoundingBox();
        double dist = distanceToBox(eye, target);
        if (dist > difficulty.reach()) {
            return;
        }
        Vector look = eye.getDirection();
        Vector toTarget = target.getCenter().subtract(eye.toVector());
        if (Math.toDegrees(look.angle(toTarget)) > difficulty.hitAngle()) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() < difficulty.missChance()) {
            return;
        }
        if (!lineOfSight(eye, target.getCenter())) {
            return;
        }
        if (damageEnabled()) {
            if (player.getNoDamageTicks() > player.getMaximumNoDamageTicks() / 2) {
                return;
            }
        } else if (playerHurtLock > 0) {
            return;
        }
        boolean crit = body.falling() && body.fallDistance > 0 && !body.inLiquid()
                && (profile.critWhileSprinting || !sprinting);
        boolean charged = !profile.cooldownDamage || charge > 0.9;
        int extra = (sprinting && charged ? 1 : 0) + (kit == null ? 0 : kit.weaponKnockback());
        Vector kb = CombatMath.knockback(profile.knockback, player.getVelocity(), player.isOnGround(),
                body.x, body.z, player.getX(), player.getZ(), body.yaw, extra);

        // щит в 1.9 / блок мечом в 1.8
        if (player.isBlocking() && player.getActiveItem().getType() == Material.SHIELD) {
            Vector facing = player.getLocation().getDirection().setY(0);
            Vector fromBot = new Vector(body.x - player.getX(), 0, body.z - player.getZ());
            if (facing.lengthSquared() > 0 && fromBot.lengthSquared() > 0 && facing.angle(fromBot) < Math.PI / 2) {
                player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
                return;
            }
        }

        if (damageEnabled()) {
            double dmg = CombatMath.botDamage(kit == null ? Material.AIR : kit.weaponType(), kit == null ? 0 : kit.weaponSharpness(), profile, charge, crit);
            if (combat.isSwordBlocking(player, profile)) {
                dmg *= 1.0 - profile.blockReduction;
            }
            bots.suppressKnockback(player);
            try {
                player.damage(dmg, DamageSource.builder(DamageType.MOB_ATTACK).withDamageLocation(body.location()).build());
            } finally {
                bots.releaseKnockback(player);
            }
            if (state != State.RUNNING) {
                return;
            }
        } else {
            float yaw = (float) Math.toDegrees(Math.atan2(body.z - player.getZ(), body.x - player.getX())) - player.getYaw();
            player.playHurtAnimation(yaw);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
            playerHurtLock = profile.maxNoDamageTicks / 2;
        }
        player.setVelocity(kb);
        if (crit) {
            bot.critEffect();
            player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.1);
        }
        attackSounds(player.getLocation(), crit, extra > 0, charged);
        if (sprinting && extra > 0) {
            body.velocity.multiply(new Vector(0.6, 1, 0.6));
            botSprintLock = difficulty.wtap() ? 0 : 8;
        }
        brain.onLandedHit();
        botHits++;
        combo = 0;
        onBotHitPlayer();
    }

    // --- игрок бьёт бота ---

    @Override
    protected void onUnknownEntity(Player p, int entityId, boolean attack) {
        if (!attack || bot == null || entityId != bot.id() || p != player || state != State.RUNNING || !botAlive) {
            return;
        }
        double dist = distanceToBox(p.getEyeLocation(), body.box());
        if (dist > profile.maxReach) {
            return;
        }
        if (botHurtTicks > profile.maxNoDamageTicks / 2) {
            return;
        }
        float cooldown = p.getAttackCooldown();
        boolean charged = !profile.cooldownDamage || cooldown > 0.9f;
        boolean crit = CombatMath.canCrit(p, profile) && charged;
        ItemStack hand = p.getInventory().getItemInMainHand();
        int extra = (p.isSprinting() && charged ? 1 : 0) + (hand.getType().isAir() ? 0 : hand.getEnchantmentLevel(Enchantment.KNOCKBACK));

        Vector kb = CombatMath.knockback(profile.knockback, body.velocity, body.onGround, p.getX(), p.getZ(), body.x, body.z, p.getYaw(), extra);
        body.velocity.copy(kb);
        body.onGround = false;
        botHurtTicks = profile.maxNoDamageTicks;
        float hurtYaw = BotBrain.wrap((float) Math.toDegrees(Math.atan2(p.getZ() - body.z, p.getX() - body.x)) - body.yaw);
        bot.hurt(hurtYaw);
        Location at = body.location();
        at.getWorld().playSound(at, Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        if (crit) {
            bot.critEffect();
        }
        attackSounds(at, crit, extra > 0, charged);
        brain.onHurt();
        playerHits++;
        combo++;
        bestCombo = Math.max(bestCombo, combo);
        lastReach = dist;

        if (damageEnabled()) {
            double dmg = CombatMath.playerDamage(p, profile, cooldown, crit);
            dmg = CombatMath.applyArmor(dmg, kit == null ? 0 : kit.armorPoints(), kit == null ? 0 : kit.toughness());
            if (botBlockingNow && profile.swordBlocking) {
                dmg *= 1.0 - profile.blockReduction;
            }
            botHealth -= dmg;
            board.belowName(bot.name(), (int) Math.ceil(Math.max(0, botHealth)), null);
            if (botHealth <= 0) {
                botHealth = 0;
                botAlive = false;
                bot.health(0f);
                at.getWorld().playSound(at, Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
                onBotKilled();
                return;
            }
        }
        onPlayerHitBot();
    }

    private void attackSounds(Location at, boolean crit, boolean knockback, boolean charged) {
        if (version != CombatVersion.MODERN) {
            return;
        }
        if (crit) {
            at.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
        } else if (knockback) {
            at.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 1f);
        } else {
            at.getWorld().playSound(at, charged ? Sound.ENTITY_PLAYER_ATTACK_STRONG : Sound.ENTITY_PLAYER_ATTACK_WEAK, 1f, 1f);
        }
    }

    protected void resetBot() {
        body.teleport(botSpawn());
        brain.reset();
        botHurtTicks = 0;
        bot.position(body.x, body.y, body.z, body.yaw, body.pitch, false);
        bot.sync();
    }

    // --- хуки для наследников ---

    protected void onBotKilled() {
    }

    protected void onBotVoid() {
    }

    protected void onPlayerHitBot() {
    }

    protected void onBotHitPlayer() {
    }

    @Override
    protected void onClose() {
        if (bot != null) {
            bot.hideAll();
        }
    }

    @Override
    public Map<String, Object> placeholders(Player viewer) {
        Map<String, Object> map = super.placeholders(viewer);
        map.put("version", version.id());
        map.put("difficulty", difficulty.display());
        map.put("bot_name", bot == null ? "" : bot.name());
        map.put("bot_hp", Text.num(Math.max(0, Math.round(botHealth * 10) / 10.0)));
        map.put("bot_max_hp", Text.num(difficulty.health()));
        map.put("player_hp", Text.num(Math.round(viewer.getHealth() * 10) / 10.0));
        var max = viewer.getAttribute(Attribute.MAX_HEALTH);
        map.put("player_max_hp", Text.num(max == null ? 20 : max.getValue()));
        map.put("hits", playerHits);
        map.put("bot_hits", botHits);
        map.put("combo", combo);
        map.put("best_combo", bestCombo);
        map.put("reach", String.format(java.util.Locale.ROOT, "%.2f", lastReach));
        return map;
    }

    protected static double distanceToBox(Location eye, BoundingBox b) {
        double cx = Math.max(b.getMinX(), Math.min(eye.getX(), b.getMaxX()));
        double cy = Math.max(b.getMinY(), Math.min(eye.getY(), b.getMaxY()));
        double cz = Math.max(b.getMinZ(), Math.min(eye.getZ(), b.getMaxZ()));
        double dx = eye.getX() - cx;
        double dy = eye.getY() - cy;
        double dz = eye.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean lineOfSight(Location from, Vector to) {
        Vector dir = to.clone().subtract(from.toVector());
        double len = dir.length();
        if (len < 0.01) {
            return true;
        }
        RayTraceResult hit = from.getWorld().rayTraceBlocks(from, dir.normalize(), len, FluidCollisionMode.NEVER, true);
        return hit == null;
    }

    protected List<Player> viewers() {
        return List.of(player);
    }
}
