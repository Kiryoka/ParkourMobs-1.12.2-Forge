//Copyright (c) 2025 Кирюока and License MPL2.0

package net.Intelligence.kiryoka;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockWall;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.util.EnumFacing;
import net.minecraft.entity.monster.*;
import net.minecraft.entity.passive.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.MobEffects;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.pathfinding.*;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MobParkourMod — tuned version
 * - reduced jump strength & cooldown
 * - only jump when necessary (actual gap + candidate moves closer to target)
 * - safer landing checks (avoid edges)
 * - avoid jump loops (lastJump memory)
 */
@Mod(modid = "parkourmobs", name = "parkourmobs", version = "1.0.5")
public class MobParkourMod {

    // --- Tunable settings ---
    public static final double MAX_JUMP_DISTANCE = 3.6; // slightly reduced horizontal range
    public static final double MIN_JUMP_DISTANCE = 1.2;
    public static final int JUMP_COOLDOWN = 30; // reduced cooldown (ticks)
    public static final int JUMP_PATHFINDING_RANGE = 8;
    public static final double MAX_FALL_DAMAGE_HEIGHT = 3.5;
    public static final double URGENT_JUMP_DISTANCE = 4.5;

    // after landing block before new jumps (ticks)
    public static final int POST_LAND_BLOCK_TICKS = 20;

    // handlers
    private static final Map<EntityLiving, JumpAIHandler> aiHandlers = new ConcurrentHashMap<>();

    private static final Set<Class<? extends EntityLiving>> BLACKLISTED_MOBS = new HashSet<>();
    private static final Map<Class<? extends EntityLiving>, Double> MAX_JUMP_HEIGHT_MAP = new HashMap<>();
    private static final Map<Class<? extends EntityLiving>, Double> JUMP_FACTOR_MAP = new HashMap<>();

    static {
        BLACKLISTED_MOBS.add(EntityGhast.class);
        BLACKLISTED_MOBS.add(EntityEndermite.class);
        BLACKLISTED_MOBS.add(EntitySilverfish.class);
        BLACKLISTED_MOBS.add(EntityGuardian.class);
        BLACKLISTED_MOBS.add(EntityElderGuardian.class);
        BLACKLISTED_MOBS.add(EntitySquid.class);
        BLACKLISTED_MOBS.add(EntityWither.class);
        BLACKLISTED_MOBS.add(EntityDragon.class);
        BLACKLISTED_MOBS.add(EntitySlime.class);
        BLACKLISTED_MOBS.add(EntityMagmaCube.class);
        BLACKLISTED_MOBS.add(EntityBat.class);
        BLACKLISTED_MOBS.add(EntityEnderman.class);
        BLACKLISTED_MOBS.add(EntityBlaze.class);

        MAX_JUMP_HEIGHT_MAP.put(EntityRabbit.class, 1.0);
        MAX_JUMP_HEIGHT_MAP.put(EntityHorse.class, 0.75);
        MAX_JUMP_HEIGHT_MAP.put(EntityZombie.class, 0.45);
        MAX_JUMP_HEIGHT_MAP.put(EntityCreeper.class, 0.3);
        MAX_JUMP_HEIGHT_MAP.put(EntityCow.class, 0.2);
        MAX_JUMP_HEIGHT_MAP.put(EntitySheep.class, 0.2);
        MAX_JUMP_HEIGHT_MAP.put(EntityVillager.class, 0.35);
        MAX_JUMP_HEIGHT_MAP.put(EntityIronGolem.class, 0.12);

        JUMP_FACTOR_MAP.put(EntityRabbit.class, 1.25);
        JUMP_FACTOR_MAP.put(EntityHorse.class, 1.12);
        JUMP_FACTOR_MAP.put(EntityZombie.class, 0.95);
        JUMP_FACTOR_MAP.put(EntityCreeper.class, 0.9);
        JUMP_FACTOR_MAP.put(EntityCow.class, 0.8);
        JUMP_FACTOR_MAP.put(EntitySheep.class, 0.8);
        JUMP_FACTOR_MAP.put(EntityVillager.class, 0.9);
        JUMP_FACTOR_MAP.put(EntityIronGolem.class, 0.6);
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent evt) { }

    @Mod.EventHandler
    public void init(FMLInitializationEvent evt) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) return;
        if (!(event.getEntity() instanceof EntityLiving)) return;

        EntityLiving entity = (EntityLiving) event.getEntity();
        if (!shouldAddJumpAI(entity)) return;

        JumpAIHandler handler = new JumpAIHandler(entity);
        aiHandlers.put(entity, handler);

        try {
            entity.tasks.addTask(1, handler.jumpAI);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (entity instanceof EntityCreature) {
            EntityCreature creature = (EntityCreature) entity;
            try {
                Field navigatorField = EntityLiving.class.getDeclaredField("navigator");
                navigatorField.setAccessible(true);
                PathNavigate custom = new ParkourPathNavigate(creature, creature.world);
                navigatorField.set(creature, custom);
            } catch (NoSuchFieldException nsfe) {
                // ignore variant mappings
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    private boolean shouldAddJumpAI(EntityLiving entity) {
        for (Class<? extends EntityLiving> c : BLACKLISTED_MOBS) {
            if (c.isAssignableFrom(entity.getClass())) return false;
        }
        if (entity instanceof EntitySnowman || entity instanceof EntityGuardian) return false;
        return true;
    }

    @SubscribeEvent
    public void onLivingUpdate(net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent event) {
        if (event.getEntityLiving().world.isRemote) return;
        if (!(event.getEntityLiving() instanceof EntityLiving)) return;

        EntityLiving entity = (EntityLiving) event.getEntityLiving();
        if (entity.isDead || !entity.isEntityAlive()) {
            aiHandlers.remove(entity);
            return;
        }

        JumpAIHandler handler = aiHandlers.get(entity);
        if (handler != null) handler.update();
    }

    // --- Utilities ---

    public static double getMaxJumpHeightForEntity(EntityLiving entity) {
        for (Map.Entry<Class<? extends EntityLiving>, Double> e : MAX_JUMP_HEIGHT_MAP.entrySet()) {
            if (e.getKey().isAssignableFrom(entity.getClass())) return e.getValue();
        }
        if (entity instanceof IMob) return 0.45;
        if (entity instanceof EntityAnimal) return 0.32;
        return 0.4;
    }

    private static double getJumpFactorForEntity(EntityLiving entity) {
        for (Map.Entry<Class<? extends EntityLiving>, Double> e : JUMP_FACTOR_MAP.entrySet()) {
            if (e.getKey().isAssignableFrom(entity.getClass())) return e.getValue();
        }
        return 1.0;
    }

    public static boolean isSafeLandingSpot(World world, BlockPos pos, EntityLiving entity, boolean isUrgent) {
        if (!world.isBlockLoaded(pos)) return false;

        IBlockState state = world.getBlockState(pos);
        IBlockState below = world.getBlockState(pos.down());

        Block belowBlock = below.getBlock();
        boolean belowSolid = below.getMaterial().isSolid() || belowBlock instanceof BlockFence || belowBlock instanceof BlockFenceGate || belowBlock instanceof BlockWall;
        if (!belowSolid) return false;

        // lava check by blocks
        Block bl = state.getBlock();
        Block blBelow = below.getBlock();
        boolean isLava = bl == Blocks.LAVA || bl == Blocks.FLOWING_LAVA || blBelow == Blocks.LAVA || blBelow == Blocks.FLOWING_LAVA;
        if (isLava && !entity.isEntityUndead() && !isUrgent) return false;

        // deep water
        boolean isDeepWater = false;
        if (state.getBlock() instanceof BlockLiquid) {
            int lvl = state.getValue(BlockLiquid.LEVEL);
            if (lvl == 0) isDeepWater = true;
        }
        if (below.getBlock() instanceof BlockLiquid) {
            int lvl = below.getValue(BlockLiquid.LEVEL);
            if (lvl == 0) isDeepWater = true;
        }
        if (isDeepWater && !entity.isEntityUndead() && !isUrgent) return false;

        double fallHeight = entity.posY - (pos.getY() + 0.5);
        if (fallHeight > MAX_FALL_DAMAGE_HEIGHT && !isUrgent) return false;

        AxisAlignedBB box = new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + entity.height, pos.getZ() + 1.0);
        boolean collision = !world.getCollisionBoxes(entity, box).isEmpty();
        if (collision) return false;

        // stable landing check (avoid landing on a single isolated pillar or extreme edge)
        if (!isStableLandingSpot(world, pos)) return false;

        return true;
    }

    // require that landing pos has some adjacent support (not a single column)
    private static boolean isStableLandingSpot(World world, BlockPos pos) {
        BlockPos below = pos.down();
        if (!world.isBlockLoaded(below)) return false;
        IBlockState belowState = world.getBlockState(below);
        if (!belowState.getMaterial().isSolid()) return false;

        // Count solid neighbors at same level or one below
        int solidNeighbors = 0;
        for (EnumFacing f : EnumFacing.HORIZONTALS) {
            BlockPos adj = below.offset(f);
            if (!world.isBlockLoaded(adj)) continue;
            IBlockState st = world.getBlockState(adj);
            if (st.getMaterial().isSolid()) solidNeighbors++;
            else {
                // also count if block under adjacent is solid (step)
                BlockPos underAdj = adj.down();
                if (world.isBlockLoaded(underAdj) && world.getBlockState(underAdj).getMaterial().isSolid()) solidNeighbors++;
            }
        }
        // If there are no neighbors solid, treat as unstable. Allow 0 for very small mobs? We'll require >=1
        return solidNeighbors >= 1;
    }

    public static boolean isGapBetweenPoints(World world, BlockPos fromPos, BlockPos toPos) {
        if (fromPos == null || toPos == null) return false;
        int dx = toPos.getX() - fromPos.getX();
        int dz = toPos.getZ() - fromPos.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) return false;

        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            int x = MathHelper.floor(fromPos.getX() + dx * t);
            int z = MathHelper.floor(fromPos.getZ() + dz * t);
            int y = fromPos.getY() - 1;
            BlockPos check = new BlockPos(x, y, z);
            if (!world.isBlockLoaded(check)) continue;
            IBlockState st = world.getBlockState(check);
            if (!st.getMaterial().isSolid()) return true;
        }
        return false;
    }

    private static boolean hasSolidGroundBelow(World world, BlockPos pos, EntityLiving entity) {
        for (int i = 0; i <= 3; i++) {
            BlockPos p = pos.down(i);
            if (!world.isBlockLoaded(p)) continue;
            IBlockState st = world.getBlockState(p);
            Block b = st.getBlock();
            if (st.getMaterial().isSolid()) return true;
            if (b instanceof BlockFence || b instanceof BlockFenceGate || b instanceof BlockWall) return true;
        }
        return false;
    }

    /**
     * Return direct jump or candidate landing closer to target (but only if candidate reduces distance to target)
     */
    public static JumpOpportunity getJumpOpportunity(EntityLiving entity, BlockPos targetPos, boolean isUrgent) {
        if (entity == null || targetPos == null) return null;

        Vec3d from = new Vec3d(entity.posX, entity.posY, entity.posZ);
        Vec3d to = new Vec3d(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // don't attempt jumps if target is very close
        if (horizontalDist < 2.0) return null;

        double maxRange = isUrgent ? URGENT_JUMP_DISTANCE : MAX_JUMP_DISTANCE;

        // direct jump to target if gap exists and within range
        if (horizontalDist >= MIN_JUMP_DISTANCE && horizontalDist <= maxRange) {
            if (isGapBetweenPoints(entity.world, new BlockPos(from), targetPos)) {
                if (isSafeLandingSpot(entity.world, targetPos, entity, isUrgent)) {
                    return new JumpOpportunity(new BlockPos(from), targetPos, horizontalDist);
                }
            }
        }

        // search candidates along line, prefer those closer to target but still reachable
        Vec3d dir = new Vec3d(dx, to.y - from.y, dz);
        double len = Math.sqrt(dir.x * dir.x + dir.y * dir.y + dir.z * dir.z);
        if (len == 0) return null;
        dir = new Vec3d(dir.x / len, dir.y / len, dir.z / len);

        int maxCheck = Math.min(JUMP_PATHFINDING_RANGE, (int) Math.ceil(horizontalDist));
        double distEntityToTarget = horizontalDist;
        for (int d = maxCheck; d >= 1; d--) {
            double sampleDist = Math.min(d, maxRange);
            Vec3d candidateVec = new Vec3d(from.x + dir.x * sampleDist, from.y + dir.y * sampleDist, from.z + dir.z * sampleDist);
            BlockPos samplePos = new BlockPos(MathHelper.floor(candidateVec.x), MathHelper.floor(candidateVec.y), MathHelper.floor(candidateVec.z));

            double maxJumpHeight = getMaxJumpHeightForEntity(entity);
            int maxYScan = Math.max(1, (int) Math.ceil(maxJumpHeight + 1.5));
            BlockPos goodPos = null;
            for (int yoff = -1; yoff <= maxYScan; yoff++) {
                BlockPos check = samplePos.up(yoff);
                if (!entity.world.isBlockLoaded(check)) continue;
                if (!hasSolidGroundBelow(entity.world, check, entity)) continue;
                if (!isSafeLandingSpot(entity.world, check, entity, isUrgent)) continue;
                // If no gap between from and check and check isn't the target, skip (no need to jump)
                if (!isGapBetweenPoints(entity.world, new BlockPos(from), check) && !(horizontalDist <= maxRange && check.equals(targetPos))) continue;

                // candidate must move closer to target (avoid jumping away)
                double candToTarget = Math.sqrt(Math.pow(check.getX() + 0.5 - to.x, 2) + Math.pow(check.getZ() + 0.5 - to.z, 2));
                if (candToTarget >= distEntityToTarget) continue;

                goodPos = check;
                break;
            }
            if (goodPos != null) {
                double distToCandidate = Math.sqrt(Math.pow(goodPos.getX() + 0.5 - from.x, 2) + Math.pow(goodPos.getZ() + 0.5 - from.z, 2));
                if (distToCandidate >= MIN_JUMP_DISTANCE && distToCandidate <= maxRange) {
                    return new JumpOpportunity(new BlockPos(from), goodPos, distToCandidate);
                }
            }
        }
        return null;
    }

    // --- Data classes & navigator ---

    public static class JumpOpportunity {
        private final BlockPos fromPos;
        private final BlockPos toPos;
        private final double distance;

        public JumpOpportunity(BlockPos fromPos, BlockPos toPos, double distance) {
            this.fromPos = fromPos;
            this.toPos = toPos;
            this.distance = distance;
        }

        public BlockPos getFromPos() { return fromPos; }
        public BlockPos getToPos() { return toPos; }
        public double getDistance() { return distance; }
    }

    public static class ParkourPathNavigate extends PathNavigateGround {
        private final EntityLiving entity;
        private BlockPos plannedJumpTarget = null;
        private boolean plannedJump = false;
        private int prepareTimer = 0;

        public ParkourPathNavigate(EntityLiving entityIn, World worldIn) {
            super(entityIn, worldIn);
            this.entity = entityIn;
        }

        @Override
        public Path getPathToPos(BlockPos pos) {
            JumpOpportunity jump = getJumpOpportunity(entity, pos, false);
            if (jump != null) return createJumpPath(jump);
            return super.getPathToPos(pos);
        }

        @Override
        public Path getPathToEntityLiving(Entity entityIn) {
            BlockPos tp = new BlockPos(entityIn.posX, entityIn.posY, entityIn.posZ);
            JumpOpportunity jump = getJumpOpportunity(entity, tp, false);
            if (jump != null) return createJumpPath(jump);
            return super.getPathToEntityLiving(entityIn);
        }

        @Override
        public boolean tryMoveToXYZ(double x, double y, double z, double speedIn) {
            JumpOpportunity jump = getJumpOpportunity(entity, new BlockPos(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z)), false);
            if (jump != null) {
                Path p = createJumpPath(jump);
                if (p != null) return this.setPath(p, speedIn);
            }
            return super.tryMoveToXYZ(x, y, z, speedIn);
        }

        @Override
        public boolean tryMoveToEntityLiving(Entity entityIn, double speedIn) {
            JumpOpportunity jump = getJumpOpportunity(entity, new BlockPos(MathHelper.floor(entityIn.posX), MathHelper.floor(entityIn.posY), MathHelper.floor(entityIn.posZ)), false);
            if (jump != null) {
                Path p = createJumpPath(jump);
                if (p != null) return this.setPath(p, speedIn);
            }
            return super.tryMoveToEntityLiving(entityIn, speedIn);
        }

        private Path createJumpPath(JumpOpportunity jump) {
            PathPoint[] points = new PathPoint[2];
            points[0] = new PathPoint(MathHelper.floor(entity.posX), MathHelper.floor(entity.posY), MathHelper.floor(entity.posZ));
            BlockPos t = jump.getToPos();
            points[1] = new PathPoint(t.getX(), t.getY(), t.getZ());
            Path path = new Path(points);
            plannedJumpTarget = jump.getToPos();
            plannedJump = true;
            prepareTimer = 8;
            return path;
        }

        @Override
        public void onUpdateNavigation() {
            super.onUpdateNavigation();
            if (plannedJump && prepareTimer > 0) {
                prepareTimer--;
                if (prepareTimer <= 0 && entity.onGround) {
                    JumpAIHandler handler = aiHandlers.get(entity);
                    if (handler != null) handler.forceJumpTo(plannedJumpTarget);
                    plannedJump = false;
                    plannedJumpTarget = null;
                }
            }
        }
    }

    // --- Jump AI Handler ---

    public static class JumpAIHandler {
        private final EntityLiving entity;
        public final EntityAIJump jumpAI;

        private int tickCounter = 0;
        private int jumpCooldown = 0;
        private Entity currentTarget = null;
        private JumpOpportunity currentJump = null;
        private boolean isUrgent = false;
        private int urgentTimer = 0;

        // anti-loop
        private BlockPos lastJumpPos = null;
        private int lastJumpTick = -9999;
        private int postLandBlockUntil = -1;

        public JumpAIHandler(EntityLiving entity) {
            this.entity = entity;
            this.jumpAI = new EntityAIJump(entity, this);
        }

        public void update() {
            tickCounter++;
            if (jumpCooldown > 0) jumpCooldown--;
            if (urgentTimer > 0) {
                urgentTimer--;
                if (urgentTimer <= 0) isUrgent = false;
            }
            if (postLandBlockUntil > 0) postLandBlockUntil = Math.max(0, postLandBlockUntil - 1);

            currentTarget = acquireTarget();
            isUrgent = checkUrgency();

            // Do not plan jumps if we are in post-land blocking window
            if (currentTarget != null && entity.onGround && jumpCooldown <= 0 && postLandBlockUntil <= 0) {
                BlockPos targetPos = new BlockPos(currentTarget.posX, currentTarget.posY, currentTarget.posZ);
                JumpOpportunity potential = getJumpOpportunity(entity, targetPos, isUrgent);

                // ignore too-close candidates
                if (potential != null) {
                    double candToEntity = Math.sqrt(Math.pow(potential.getToPos().getX() + 0.5 - entity.posX, 2) +
                            Math.pow(potential.getToPos().getZ() + 0.5 - entity.posZ, 2));
                    if (candToEntity < 1.0) potential = null;
                }

                // avoid repeating the same landing multiple times recently
                if (potential != null && lastJumpPos != null && potential.getToPos().equals(lastJumpPos) && (tickCounter - lastJumpTick) < 40) {
                    potential = null;
                }

                currentJump = potential;
            } else {
                currentJump = null;
            }
        }

        public boolean canJumpNow() {
            if (jumpCooldown > 0) return false;
            if (currentJump == null) return false;
            if (!entity.onGround || entity.isInWater() || entity.isInLava()) return false;

            // require actual gap between current pos and landing
            if (!isGapBetweenPoints(entity.world, new BlockPos(entity.posX, entity.posY, entity.posZ), currentJump.getToPos())) return false;

            double dx = currentJump.getToPos().getX() + 0.5 - entity.posX;
            double dz = currentJump.getToPos().getZ() + 0.5 - entity.posZ;
            double horizontalDist = Math.sqrt(dx*dx + dz*dz);
            if (horizontalDist < 1.0) return false;

            return isSafeLandingSpot(entity.world, currentJump.getToPos(), entity, isUrgent);
        }

        public void executeJump() {
            if (currentJump == null) return;
            BlockPos tgt = currentJump.getToPos();
            Vec3d targetVec = new Vec3d(tgt.getX() + 0.5, tgt.getY() + 0.5, tgt.getZ() + 0.5);

            double dx = targetVec.x - entity.posX;
            double dy = targetVec.y - entity.posY;
            double dz = targetVec.z - entity.posZ;

            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal < 0.001) return;

            // tuned jump parameters (reduced)
            double baseSpeed = 0.18; // base horizontal
            double distanceFactor = 0.12 * Math.min(1.0, horizontal / MAX_JUMP_DISTANCE);
            double heightFactor = Math.max(0.0, dy) * 0.28;
            double urgencyFactor = isUrgent ? 0.06 : 0.0;

            double speed = baseSpeed + distanceFactor + urgencyFactor;
            speed *= getJumpFactorForEntity(entity);

            double sizeModifier = Math.min(1.0, 2.0 / (entity.width + entity.height));
            double maxHorizontal = 0.5 * sizeModifier;
            speed = Math.min(speed, maxHorizontal);

            double nx = dx / horizontal;
            double nz = dz / horizontal;

            double jumpY = 0.38 + Math.min(0.18, heightFactor) + (isUrgent ? 0.05 : 0.0);
            if (entity instanceof EntityIronGolem || entity.width > 1.5) jumpY *= 0.85;

            entity.motionX = nx * speed;
            entity.motionZ = nz * speed;
            entity.motionY = jumpY;

            if (!entity.world.isRemote) {
                entity.addPotionEffect(new PotionEffect(MobEffects.JUMP_BOOST, 10, 0, false, false));
                float pitch = 1.0F + (entity.world.rand.nextFloat() - entity.world.rand.nextFloat()) * 0.2F;
                entity.world.playSound(null, entity.posX, entity.posY, entity.posZ, SoundEvents.ENTITY_RABBIT_JUMP, SoundCategory.NEUTRAL, 0.4F, pitch);
            }

            // shorter cooldown overall but still small buffer to avoid immediate re-jump
            jumpCooldown = JUMP_COOLDOWN;
            lastJumpPos = tgt;
            lastJumpTick = tickCounter;

            // when landing, block new jumps briefly to avoid jitter
            postLandBlockUntil = POST_LAND_BLOCK_TICKS;

            entity.setJumping(true);
            currentJump = null;
        }

        public void forceJumpTo(BlockPos pos) {
            double dist = entity.getDistance(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (dist < 1.0) return;
            currentJump = new JumpOpportunity(new BlockPos(entity.posX, entity.posY, entity.posZ), pos, dist);
            lastJumpPos = pos;
            lastJumpTick = tickCounter;
            if (canJumpNow()) executeJump();
        }

        public void onLanded() {
            // small extra cooldown after landing to avoid immediate retry
            jumpCooldown = Math.max(jumpCooldown, JUMP_COOLDOWN / 2);
            postLandBlockUntil = POST_LAND_BLOCK_TICKS;

            // resume navigation to target
            if (currentTarget != null && currentTarget.isEntityAlive()) {
                if (entity.getNavigator() != null) {
                    entity.getNavigator().tryMoveToEntityLiving(currentTarget, 1.0D);
                }
            }

            currentJump = null;
        }

        // --- target selection & urgency ---

        private Entity acquireTarget() {
            if (entity instanceof EntityCreature) {
                EntityCreature c = (EntityCreature) entity;
                EntityLivingBase t = c.getAttackTarget();
                if (t != null && t.isEntityAlive() && entity.getEntitySenses().canSee(t)) return t;
            }

            if (entity instanceof EntityTameable) {
                EntityTameable t = (EntityTameable) entity;
                if (t.isTamed() && t.getOwner() != null && t.getOwner().isEntityAlive()) return t.getOwner();
            }

            if (entity instanceof EntityAnimal) {
                EntityPlayer p = findPlayerWithBreedingItem();
                if (p != null) return p;
            }

            if (entity instanceof IMob) {
                List<EntityPlayer> players = entity.world.playerEntities.stream()
                        .filter(p -> p.isEntityAlive() && !p.isSpectator() && entity.getEntitySenses().canSee(p))
                        .sorted(Comparator.comparingDouble(e -> entity.getDistance(e)))
                        .collect(Collectors.toList());
                if (!players.isEmpty()) return players.get(0);
            }

            return null;
        }

        private EntityPlayer findPlayerWithBreedingItem() {
            for (EntityPlayer p : entity.world.playerEntities) {
                if (!p.isEntityAlive()) continue;
                if (entity.getDistance(p) > 16.0) continue;
                ItemStack main = p.getHeldItemMainhand();
                ItemStack off = p.getHeldItemOffhand();
                if (isBreedingItem(main) || isBreedingItem(off)) return p;
            }
            return null;
        }

        private boolean isBreedingItem(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;
            Item item = stack.getItem();
            if (entity instanceof EntityCow || entity instanceof EntitySheep || entity instanceof EntityMooshroom) {
                return item == Items.WHEAT;
            }
            if (entity instanceof EntityPig) {
                return item == Items.CARROT || item == Items.POTATO || item == Items.BEETROOT;
            }
            if (entity instanceof EntityChicken) {
                return item == Items.WHEAT_SEEDS || item == Items.MELON_SEEDS || item == Items.PUMPKIN_SEEDS;
            }
            if (entity instanceof EntityRabbit) {
                return item == Items.CARROT || item == Items.GOLDEN_CARROT;
            }
            if (entity instanceof EntityHorse) {
                return item == Items.WHEAT || item == Items.APPLE || item == Items.GOLDEN_APPLE || item == Items.GOLDEN_CARROT;
            }
            if (entity instanceof EntityVillager) {
                return item == Items.EMERALD;
            }
            return false;
        }

        private boolean checkUrgency() {
            if (entity.hurtTime > 0) {
                urgentTimer = 60;
                return true;
            }
            if (entity instanceof IMob && currentTarget instanceof EntityPlayer) {
                if (entity.getDistance(currentTarget) < 5.0) {
                    urgentTimer = 100;
                    return true;
                }
            }
            BlockPos p = new BlockPos(entity.posX, entity.posY, entity.posZ);
            for (EnumFacing f : EnumFacing.values()) {
                BlockPos check = p.offset(f);
                if (!entity.world.isBlockLoaded(check)) continue;
                IBlockState st = entity.world.getBlockState(check);
                Block b = st.getBlock();
                if (b == Blocks.LAVA || b == Blocks.FLOWING_LAVA) {
                    urgentTimer = 150;
                    return true;
                }
            }
            return false;
        }
    }

    // --- AI task ---

    public static class EntityAIJump extends EntityAIBase {
        private final EntityLiving entity;
        private final JumpAIHandler handler;
        private boolean running = false;

        public EntityAIJump(EntityLiving entity, JumpAIHandler handler) {
            this.entity = entity;
            this.handler = handler;
            this.setMutexBits(3);
        }

        @Override
        public boolean shouldExecute() {
            boolean can = handler.canJumpNow();
            if (can) running = true;
            return can;
        }

        @Override
        public boolean shouldContinueExecuting() {
            return running && !entity.onGround;
        }

        @Override
        public void startExecuting() {
            handler.executeJump();
        }

        @Override
        public void resetTask() {
            running = false;
            handler.onLanded();
        }

        @Override
        public void updateTask() {
            if (entity.onGround) resetTask();
        }
    }
}
