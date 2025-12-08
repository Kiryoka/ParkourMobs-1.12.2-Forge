package Kiryoka.Intelligence.Parkour;

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
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.config.IConfigElement;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Collections;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.monster.IMob;
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
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.IModGuiFactory.RuntimeOptionCategoryElement;
import net.minecraft.entity.EntityList;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MobParkourMod — tuned & synchronized version for 1.12.2
 * ... (doc shortened)
 */
@Mod(modid = "parkourmobs", name = "parkourmobs", version = "1.1.0", guiFactory = "Kiryoka.Intelligence.Parkour.MobParkourMod$ConfigGuiFactory")
public class MobParkourMod {

    // --- Tunable settings ---
    public static final double MAX_JUMP_DISTANCE = 3.6; // slightly reduced horizontal range
    public static final double MIN_JUMP_DISTANCE = 1.2;
    public static final int JUMP_COOLDOWN = 20; // cooldown in ticks
    public static final int JUMP_PATHFINDING_RANGE = 8;
    public static final double MAX_FALL_DAMAGE_HEIGHT = 5.6;
    public static final double URGENT_JUMP_DISTANCE = 4.6;

    // after landing block before new jumps (ticks)
    public static final int POST_LAND_BLOCK_TICKS = 20;

    // speed effect settings
    public static final int SPEED_EFFECT_TICKS = 28; // short, a bit less than 1s
    public static final int SPEED_EFFECT_AMPLIFIER = 5; // ~+120%

    // how many ticks to wait after applying the speed effect before executing the jump
    public static final int SPEED_PREPARE_TICKS = 5; // <- changed to 5 ticks as requested

    // debug
    private static final boolean DEBUG = false;

    // handlers
    private static final Map<EntityLiving, JumpAIHandler> aiHandlers = new ConcurrentHashMap<>();
    private static final Set<Class<? extends EntityLiving>> BLACKLISTED_MOBS = new HashSet<>();
    private static final Map<Class<? extends EntityLiving>, Double> MAX_JUMP_HEIGHT_MAP = new HashMap<>();
    private static final Map<Class<? extends EntityLiving>, Double> JUMP_FACTOR_MAP = new HashMap<>();
    private static Configuration CONFIG;

    // Default blacklist names (will be used as default values in config)
    private static final String[] DEFAULT_BLACKLIST_NAMES = new String[] {
            "EntityGhast",
            "EntityEndermite",
            "EntitySilverfish",
            "EntityGuardian",
            "EntityElderGuardian",
            "EntitySquid",
            "EntitySkeleton",
            "EntitySnowman",
            "EntityShulker",
            "EntityStray",
            "EntityWither",
            "EntityDragon",
            "EntitySlime",
            "EntityMagmaCube",
            "EntityBat",
            "EntityEnderman",
            "EntityBlaze"
    };

    static {
        // NOTE: BLACKLISTED_MOBS will be populated from config during preInit.
        // Keep MAX_JUMP_HEIGHT_MAP and JUMP_FACTOR_MAP initial values as before.
        MAX_JUMP_HEIGHT_MAP.put(EntityRabbit.class, 1.2);
        MAX_JUMP_HEIGHT_MAP.put(EntityMule.class, 1.0);
        MAX_JUMP_HEIGHT_MAP.put(EntityDonkey.class, 0.9);
        MAX_JUMP_HEIGHT_MAP.put(EntityHorse.class, 0.95);
        MAX_JUMP_HEIGHT_MAP.put(EntityZombieHorse.class, 0.95);
        MAX_JUMP_HEIGHT_MAP.put(EntitySkeletonHorse.class, 0.95);
        MAX_JUMP_HEIGHT_MAP.put(EntityZombie.class, 0.65);
        MAX_JUMP_HEIGHT_MAP.put(EntityPigZombie.class, 0.65);
        MAX_JUMP_HEIGHT_MAP.put(EntityHusk.class, 0.68);
        MAX_JUMP_HEIGHT_MAP.put(EntityWitherSkeleton.class, 0.7);
        MAX_JUMP_HEIGHT_MAP.put(EntityOcelot.class, 0.7);
        MAX_JUMP_HEIGHT_MAP.put(EntityZombieVillager.class, 0.65);
        MAX_JUMP_HEIGHT_MAP.put(EntityWolf.class, 0.6);
        MAX_JUMP_HEIGHT_MAP.put(EntityCreeper.class, 0.5);
        MAX_JUMP_HEIGHT_MAP.put(EntityChicken.class, 0.5);
        MAX_JUMP_HEIGHT_MAP.put(EntityCow.class, 0.4);
        MAX_JUMP_HEIGHT_MAP.put(EntityPig.class, 0.4);
        MAX_JUMP_HEIGHT_MAP.put(EntitySheep.class, 0.4);
        MAX_JUMP_HEIGHT_MAP.put(EntityVillager.class, 0.55);
        MAX_JUMP_HEIGHT_MAP.put(EntityIronGolem.class, 0.32);

        JUMP_FACTOR_MAP.put(EntityRabbit.class, 1.45);
        JUMP_FACTOR_MAP.put(EntityDonkey.class, 1.25);
        JUMP_FACTOR_MAP.put(EntityMule.class, 1.35);
        JUMP_FACTOR_MAP.put(EntityHorse.class, 1.32);
        JUMP_FACTOR_MAP.put(EntityZombieHorse.class, 1.32);
        JUMP_FACTOR_MAP.put(EntitySkeletonHorse.class, 1.32);
        JUMP_FACTOR_MAP.put(EntityZombie.class, 1.15);
        JUMP_FACTOR_MAP.put(EntityPigZombie.class, 1.15);
        JUMP_FACTOR_MAP.put(EntityHusk.class, 1.18);
        JUMP_FACTOR_MAP.put(EntityWitherSkeleton.class, 1.2);
        JUMP_FACTOR_MAP.put(EntityOcelot.class, 1.2);
        JUMP_FACTOR_MAP.put(EntityZombieVillager.class, 1.15);
        JUMP_FACTOR_MAP.put(EntityWolf.class, 1.1);
        JUMP_FACTOR_MAP.put(EntityCreeper.class, 1.1);
        JUMP_FACTOR_MAP.put(EntityCow.class, 1.0);
        JUMP_FACTOR_MAP.put(EntityPig.class, 1.0);
        JUMP_FACTOR_MAP.put(EntityChicken.class, 1.0);
        JUMP_FACTOR_MAP.put(EntitySheep.class, 1.0);
        JUMP_FACTOR_MAP.put(EntityVillager.class, 1.1);
        JUMP_FACTOR_MAP.put(EntityIronGolem.class, 0.8);
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent evt) {
        try {
            File cfgFile = evt.getSuggestedConfigurationFile();
            CONFIG = new Configuration(cfgFile);
            CONFIG.load();

            // загрузим blacklist из конфигурации (парсер parseBlacklist у тебя уже есть)
            String[] list = CONFIG.getStringList(
                    "blacklist",
                    "general",
                    DEFAULT_BLACKLIST_NAMES,
                    "List of entity identifiers to blacklist from parkour behavior."
            );
            parseBlacklist(Arrays.asList(list));

            if (CONFIG.hasChanged()) CONFIG.save();
        } catch (Exception e) {
            System.err.println("[ParkourMobs] Failed to load config in preInit: " + e.getMessage());
            // fallback: parse defaults
            parseBlacklist(Arrays.asList(DEFAULT_BLACKLIST_NAMES));
        }
    }

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
            EntityCreature creature = (EntityCreature) event.getEntity();
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
        if (entity instanceof IMob) return 0.68;
        if (entity instanceof EntityAnimal) return 0.5;
        return 0.62;
    }

    private static double getJumpFactorForEntity(EntityLiving entity) {
        for (Map.Entry<Class<? extends EntityLiving>, Double> e : JUMP_FACTOR_MAP.entrySet()) {
            if (e.getKey().isAssignableFrom(entity.getClass())) return e.getValue();
        }
        return 1.16;
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
            try {
                int lvl = state.getValue(BlockLiquid.LEVEL);
                if (lvl == 0) isDeepWater = true;
            } catch (Exception ignored) { }
        }
        if (below.getBlock() instanceof BlockLiquid) {
            try {
                int lvl = below.getValue(BlockLiquid.LEVEL);
                if (lvl == 0) isDeepWater = true;
            } catch (Exception ignored) { }
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
        if (!belowState.getMaterial().isSolid() && !(belowState.getBlock() instanceof BlockFence) && !(belowState.getBlock() instanceof BlockFenceGate) && !(belowState.getBlock() instanceof BlockWall)) return false;

        // Count solid neighbors at same level or one below
        int solidNeighbors = 0;
        for (EnumFacing f : EnumFacing.HORIZONTALS) {
            BlockPos adj = below.offset(f);
            if (!world.isBlockLoaded(adj)) continue;
            IBlockState st = world.getBlockState(adj);
            if (st.getMaterial().isSolid() || st.getBlock() instanceof BlockFence || st.getBlock() instanceof BlockFenceGate || st.getBlock() instanceof BlockWall) solidNeighbors++;
            else {
                // also count if block under adjacent is solid (step)
                BlockPos underAdj = adj.down();
                if (world.isBlockLoaded(underAdj) && world.getBlockState(underAdj).getMaterial().isSolid()) solidNeighbors++;
            }
        }
        // If there are no neighbors solid, treat as unstable
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

        // expose the navigation goal so JumpAIHandler can align priorities
        private BlockPos navGoal = null;

        public ParkourPathNavigate(EntityLiving entityIn, World worldIn) {
            super(entityIn, worldIn);
            this.entity = entityIn;
        }

        public BlockPos getNavGoal() {
            return navGoal;
        }

        @Override
        public Path getPathToPos(BlockPos pos) {
            // record nav goal
            this.navGoal = pos;
            JumpOpportunity jump = getJumpOpportunity(entity, pos, false);
            if (jump != null) {
                Path p = createJumpPath(jump);
                // ensure the AI handler gets early prepare signal
                JumpAIHandler handler = aiHandlers.get(entity);
                if (handler != null) handler.startPreparePhase();
                return p;
            }
            return super.getPathToPos(pos);
        }

        @Override
        public Path getPathToEntityLiving(Entity entityIn) {
            // If entity already has an attack target (higher priority), don't create a jump path for some other entity
            if (this.entity instanceof EntityCreature) {
                EntityCreature c = (EntityCreature) this.entity;
                EntityLivingBase atk = c.getAttackTarget();
                if (atk != null && atk != entityIn) {
                    // record nav goal but defer to vanilla pathing for other targets
                    this.navGoal = new BlockPos(MathHelper.floor(entityIn.posX), MathHelper.floor(entityIn.posY), MathHelper.floor(entityIn.posZ));
                    return super.getPathToEntityLiving(entityIn);
                }
            }

            // Otherwise, this is the navigator's intended goal
            this.navGoal = new BlockPos(MathHelper.floor(entityIn.posX), MathHelper.floor(entityIn.posY), MathHelper.floor(entityIn.posZ));
            JumpOpportunity jump = getJumpOpportunity(entity, navGoal, false);
            if (jump != null) {
                Path p = createJumpPath(jump);
                JumpAIHandler handler = aiHandlers.get(entity);
                if (handler != null) handler.startPreparePhase();
                return p;
            }
            return super.getPathToEntityLiving(entityIn);
        }

        @Override
        public boolean tryMoveToXYZ(double x, double y, double z, double speedIn) {
            this.navGoal = new BlockPos(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
            JumpOpportunity jump = getJumpOpportunity(entity, navGoal, false);
            if (jump != null) {
                Path p = createJumpPath(jump);
                if (p != null) {
                    JumpAIHandler handler = aiHandlers.get(entity);
                    if (handler != null) handler.startPreparePhase();
                    return this.setPath(p, speedIn);
                }
            }
            return super.tryMoveToXYZ(x, y, z, speedIn);
        }

        @Override
        public boolean tryMoveToEntityLiving(Entity entityIn, double speedIn) {
            this.navGoal = new BlockPos(MathHelper.floor(entityIn.posX), MathHelper.floor(entityIn.posY), MathHelper.floor(entityIn.posZ));
            // If entity already chasing someone else, defer
            if (this.entity instanceof EntityCreature) {
                EntityCreature c = (EntityCreature) this.entity;
                EntityLivingBase atk = c.getAttackTarget();
                if (atk != null && atk != entityIn) return super.tryMoveToEntityLiving(entityIn, speedIn);
            }
            JumpOpportunity jump = getJumpOpportunity(entity, this.navGoal, false);
            if (jump != null) {
                Path p = createJumpPath(jump);
                if (p != null) {
                    JumpAIHandler handler = aiHandlers.get(entity);
                    if (handler != null) handler.startPreparePhase();
                    return this.setPath(p, speedIn);
                }
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
            prepareTimer = 5; // shorter prepare time
            return path;
        }

        @Override
        public void onUpdateNavigation() {
            super.onUpdateNavigation();
            if (plannedJump) {
                if (prepareTimer > 0) {
                    prepareTimer--;
                }
                // When timer expires and entity is on ground, try to issue forced jump. If not ready, retry shortly.
                if (prepareTimer <= 0 && entity.onGround) {
                    JumpAIHandler handler = aiHandlers.get(entity);
                    if (handler != null) {
                        boolean executed = handler.forceJumpTo(plannedJumpTarget);
                        if (executed) {
                            plannedJump = false;
                            plannedJumpTarget = null;
                        } else {
                            // not prepared yet (speed effect still ramping), try again next tick
                            prepareTimer = 1;
                        }
                    } else {
                        plannedJump = false;
                        plannedJumpTarget = null;
                    }
                }
                // Lock orientation: gently nudge horizontal motion towards target while preparing
                if (entity.onGround && plannedJumpTarget != null) {
                    double dx = plannedJumpTarget.getX() + 0.5 - entity.posX;
                    double dz = plannedJumpTarget.getZ() + 0.5 - entity.posZ;
                    double dist = Math.sqrt(dx*dx + dz*dz);
                    if (dist > 0.001) {
                        double factor = 0.08; // small nudge
                        entity.motionX = entity.motionX * 0.6 + (dx / dist) * factor;
                        entity.motionZ = entity.motionZ * 0.6 + (dz / dist) * factor;
                    }
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

        // stepHeight restore
        private float originalStepHeight = -1f;

        // preparation: allow a few ticks for speed effect to take effect before executing the jump
        private int prepareToJumpTicks = 0;

        public JumpAIHandler(EntityLiving entity) {
            this.entity = entity;
            this.jumpAI = new EntityAIJump(entity, this);

            // --- stationary tracking init (for emergency scan) ---
            this.lastPosX = entity.posX;
            this.lastPosY = entity.posY;
            this.lastPosZ = entity.posZ;
        }

        // --- EMERGENCY fields ---
        // stationary tracking
        private double lastPosX, lastPosY, lastPosZ;
        private int stationaryTicks = 0;
        private static final int STATIONARY_TICKS_THRESHOLD = 8; // as requested

        private static final double STATIONARY_MOVE_EPS = 0.04; // movement threshold per tick (squared compared to movement)
        private static final int EMERGENCY_SCAN_RADIUS = 8; // scan radius
        private static final double MAX_EMERGENCY_JUMP_DISTANCE = 4.0; // mobs won't jump more than this in emergency

        // emergency plan: landing target and approach point
        private BlockPos emergencyLanding = null;
        private BlockPos emergencyApproach = null;
        private boolean emergencyActive = false;

        // small debounce for scanning
        private int emergencyScanCooldown = 0;
        private static final int EMERGENCY_SCAN_COOLDOWN_TICKS = 10;

        // --------------------

        public void startPreparePhase() {
            // idempotent: only start if not already preparing
            if (prepareToJumpTicks <= 0) {
                if (!entity.world.isRemote) {
                    entity.addPotionEffect(new PotionEffect(MobEffects.SPEED, SPEED_EFFECT_TICKS, SPEED_EFFECT_AMPLIFIER, false, false));
                }
                prepareToJumpTicks = SPEED_PREPARE_TICKS;
                if (DEBUG) System.out.println("[JUMPDBG] startPreparePhase for " + entity.getName() + " ticks=" + prepareToJumpTicks);
            }
        }

        public void update() {
            tickCounter++;
            if (jumpCooldown > 0) jumpCooldown--;
            if (urgentTimer > 0) {
                urgentTimer--;
                if (urgentTimer <= 0) isUrgent = false;
            }
            if (postLandBlockUntil > 0) postLandBlockUntil = Math.max(0, postLandBlockUntil - 1);

            // decrement prepare ticks if set
            if (prepareToJumpTicks > 0) prepareToJumpTicks--;

            // --- update stationary tracker ---
            double dxp = entity.posX - lastPosX;
            double dyp = entity.posY - lastPosY;
            double dzp = entity.posZ - lastPosZ;
            double movedSq = dxp * dxp + dyp * dyp + dzp * dzp;
            if (movedSq <= STATIONARY_MOVE_EPS * STATIONARY_MOVE_EPS) {
                stationaryTicks++;
            } else {
                stationaryTicks = 0;
            }
            lastPosX = entity.posX;
            lastPosY = entity.posY;
            lastPosZ = entity.posZ;

            if (emergencyScanCooldown > 0) emergencyScanCooldown = Math.max(0, emergencyScanCooldown - 1);

            currentTarget = acquireTarget();
            isUrgent = checkUrgency();

            // --- EMERGENCY logic: if panicking and stationary for threshold -> search gap and approach/jump ---
            boolean panicking = isPanicking();
            if (panicking && stationaryTicks >= STATIONARY_TICKS_THRESHOLD && jumpCooldown <= 0 && entity.onGround && postLandBlockUntil <= 0) {
                if (!emergencyActive && emergencyScanCooldown == 0) {
                    // attempt to find nearest gap candidate within radius
                    BlockPos foundLanding = findNearestGapCandidateForEmergency(EMERGENCY_SCAN_RADIUS);
                    emergencyScanCooldown = EMERGENCY_SCAN_COOLDOWN_TICKS;
                    if (foundLanding != null) {
                        double dx = (foundLanding.getX() + 0.5) - entity.posX;
                        double dz = (foundLanding.getZ() + 0.5) - entity.posZ;
                        double horiz = Math.sqrt(dx * dx + dz * dz);
                        if (horiz <= MAX_EMERGENCY_JUMP_DISTANCE) {
                            this.emergencyLanding = foundLanding;
                            // compute approach point: one block back from landing toward entity
                            Vec3d from = new Vec3d(entity.posX, entity.posY, entity.posZ);
                            Vec3d land = new Vec3d(foundLanding.getX() + 0.5, foundLanding.getY() + 0.5, foundLanding.getZ() + 0.5);
                            Vec3d dir = new Vec3d(land.x - from.x, 0, land.z - from.z);
                            double dirLen = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
                            Vec3d approachVec;
                            if (dirLen < 0.001) {
                                // fallback: approach from current pos (rare)
                                approachVec = new Vec3d(entity.posX, entity.posY, entity.posZ);
                            } else {
                                Vec3d nd = new Vec3d(dir.x / dirLen, 0, dir.z / dirLen);
                                // place approach 0.9 blocks from landing along negative direction (just before the gap)
                                approachVec = new Vec3d(land.x - nd.x * 0.9, land.y, land.z - nd.z * 0.9);
                            }
                            BlockPos appPos = new BlockPos(MathHelper.floor(approachVec.x), MathHelper.floor(approachVec.y), MathHelper.floor(approachVec.z));
                            // ensure approach has solid ground; if not, try to step further back
                            if (!hasSolidGroundBelow(entity.world, appPos, entity)) {
                                // try to find nearest previous block along line up to 2 blocks back
                                boolean foundApp = false;
                                for (int back = 1; back <= 2; back++) {
                                    Vec3d appTry = new Vec3d(land.x - dir.x / Math.max(dirLen, 0.0001) * (0.9 + back), land.y, land.z - dir.z / Math.max(dirLen, 0.0001) * (0.9 + back));
                                    BlockPos tryPos = new BlockPos(MathHelper.floor(appTry.x), MathHelper.floor(appTry.y), MathHelper.floor(appTry.z));
                                    if (hasSolidGroundBelow(entity.world, tryPos, entity)) {
                                        appPos = tryPos;
                                        foundApp = true;
                                        break;
                                    }
                                }
                                if (!foundApp) {
                                    // can't find approach safely -> abort emergency
                                    emergencyLanding = null;
                                    emergencyApproach = null;
                                    emergencyActive = false;
                                } else {
                                    emergencyApproach = appPos;
                                }
                            } else {
                                emergencyApproach = appPos;
                            }

                            if (emergencyLanding != null && emergencyApproach != null) {
                                // instruct navigator to move to approach point
                                if (entity.getNavigator() != null) {
                                    // use center of block for movement
                                    double ax = emergencyApproach.getX() + 0.5;
                                    double ay = emergencyApproach.getY();
                                    double az = emergencyApproach.getZ() + 0.5;
                                    entity.getNavigator().tryMoveToXYZ(ax, ay, az, 1.0D);
                                    emergencyActive = true;
                                    if (DEBUG) System.out.println("[JUMPDBG] Emergency plan activated for " + entity.getName() +
                                            " landing=" + emergencyLanding + " approach=" + emergencyApproach);
                                }
                            }
                        }
                    }
                }
            }

            // If emergency is active, check approach progress and attempt prepare+jump when close
            if (emergencyActive) {
                // cancel emergency if no longer panicking or if we moved significantly
                if (!isPanicking() || stationaryTicks < STATIONARY_TICKS_THRESHOLD) {
                    if (DEBUG) System.out.println("[JUMPDBG] Emergency cancelled (no longer panicking or moved) for " + entity.getName());
                    emergencyActive = false;
                    emergencyLanding = null;
                    emergencyApproach = null;
                } else {
                    // if we're near approach point and on ground, prepare/jump
                    double dax = (emergencyApproach.getX() + 0.5) - entity.posX;
                    double daz = (emergencyApproach.getZ() + 0.5) - entity.posZ;
                    double distSq = dax * dax + daz * daz;
                    if (distSq <= 1.44) { // within ~1.2 blocks
                        // set currentJump to emergency landing if not already
                        if (currentJump == null) {
                            double horiz = Math.sqrt(Math.pow(emergencyLanding.getX() + 0.5 - entity.posX, 2) +
                                    Math.pow(emergencyLanding.getZ() + 0.5 - entity.posZ, 2));
                            currentJump = new JumpOpportunity(new BlockPos(entity.posX, entity.posY, entity.posZ), emergencyLanding, horiz);
                        }
                        // start immediate prepare if not preparing
                        if (prepareToJumpTicks <= 0) {
                            // super-fast prepare for emergency
                            if (!entity.world.isRemote) {
                                entity.addPotionEffect(new PotionEffect(MobEffects.SPEED, SPEED_EFFECT_TICKS, SPEED_EFFECT_AMPLIFIER, false, false));
                            }
                            prepareToJumpTicks = 1; // nearly instant
                            if (DEBUG) System.out.println("[JUMPDBG] Emergency prepare started for " + entity.getName());
                        }
                        // if can jump now, execute
                        if (canJumpNow()) {
                            executeJump();
                            emergencyActive = false;
                            emergencyLanding = null;
                            emergencyApproach = null;
                            return; // we jumped - skip normal planning this tick
                        }
                    } else {
                        // still approaching; optionally we could nudge faster if urgent
                        // if approach path is stuck or too far, cancel after some time (optional enhancement)
                    }
                }
            }

            // Align with navigator goal if no explicit target (fleeing animals, moveToXYZ calls etc.)
            BlockPos navGoal = null;
            if (entity.getNavigator() instanceof ParkourPathNavigate) {
                navGoal = ((ParkourPathNavigate) entity.getNavigator()).getNavGoal();
            }

            // Do not plan jumps if we are in post-land blocking window (skip when emergency active)
            if (!emergencyActive && entity.onGround && jumpCooldown <= 0 && postLandBlockUntil <= 0) {
                JumpOpportunity potential = null;

                // prefer attack/explicit target (higher priority)
                if (currentTarget != null) {
                    BlockPos targetPos = new BlockPos(currentTarget.posX, currentTarget.posY, currentTarget.posZ);
                    potential = getJumpOpportunity(entity, targetPos, isUrgent);
                }

                // if no explicit target or jump not found, try navigator goal (keeps fleeing mobs moving and respects path priorities)
                if (potential == null && navGoal != null) {
                    potential = getJumpOpportunity(entity, navGoal, isUrgent);
                }

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

                // If we discovered a new potential and didn't have one before, start prepare phase (apply speed buff)
                if (potential != null && currentJump == null) {
                    startPreparePhase();
                }

                currentJump = potential;
            } else if (!emergencyActive) {
                currentJump = null;
            }
        }

        public boolean canJumpNow() {
            if (jumpCooldown > 0) return false;
            if (currentJump == null) return false;
            if (!entity.onGround || entity.isInWater() || entity.isInLava()) return false;

            // require that preparation phase completed (potion took effect)
            if (prepareToJumpTicks > 0) return false;

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

            // --- Dynamic jump calculation ---
            // gravity per tick in MC ~0.08
            double g = 0.08;

            // current horizontal speed
            double currentVx = Math.sqrt(entity.motionX * entity.motionX + entity.motionZ * entity.motionZ);

            // attribute movement speed (vanilla value ~0.2-0.3 depending on mob)
            double attrSpeed = 0.0;
            try {
                if (entity.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED) != null) {
                    attrSpeed = entity.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
                }
            } catch (Exception ignored) { }

            // desired horizontal speed: base + distance dependent
            double baseSpeed = 0.24; // baseline 16>24
            double distanceFactor = 0.32 * Math.min(1.0, horizontal / MAX_JUMP_DISTANCE);
            double urgencyFactor = isUrgent ? 0.06 : 0.0;

            double desiredHorizontal = baseSpeed + distanceFactor + urgencyFactor;
            // incorporate entity's attribute as multiplier (keeps pigs faster than zombies naturally)
            if (attrSpeed > 0) desiredHorizontal = Math.max(desiredHorizontal, attrSpeed * 0.9);

            // cap based on entity size (wider mobs slower horizontally)
            double sizeModifier = Math.min(1.0, 2.0 / (entity.width + entity.height));
            double maxHorizontalCap = 0.64 * sizeModifier;
            desiredHorizontal = Math.min(desiredHorizontal, maxHorizontalCap);

            // final horizontal to use: prefer actual currentVx if bigger, otherwise desiredHorizontal
            double horizToUse = Math.max(currentVx, desiredHorizontal);

            // compute time to traverse horizontally
            double t = horizontal / Math.max(horizToUse, 0.0001);
            if (t < 0.05) t = 0.05;

            // compute required vertical initial speed to reach deltaY
            double requiredVy = (dy + 0.5 * g * t * t) / t;

            // add small safety buffer and clamp
            requiredVy += 0.064; // buffer to avoid underjumping
            double minVy = 0.36; // ensure minimum vertical impulse
            double maxVy = 0.64; // avoid launching too high
            requiredVy = Math.max(minVy, Math.min(maxVy, requiredVy));

            double nx = dx / horizontal;
            double nz = dz / horizontal;

            // apply motions simultaneously
            entity.motionX = nx * horizToUse;
            entity.motionZ = nz * horizToUse;
            entity.motionY = requiredVy;

            // temporarily increase stepHeight to prevent slipping on fences/iron bars small blocks
            if (originalStepHeight < 0f) originalStepHeight = entity.stepHeight;
            entity.stepHeight = Math.max(entity.stepHeight, 1.0f);

            if (!entity.world.isRemote) {
                entity.addPotionEffect(new PotionEffect(MobEffects.JUMP_BOOST, 12, 0, false, false));
                float pitch = 1.0F + (entity.world.rand.nextFloat() - entity.world.rand.nextFloat()) * 0.2F;
                entity.world.playSound(null, entity.posX, entity.posY, entity.posZ, SoundEvents.ENTITY_RABBIT_JUMP, SoundCategory.NEUTRAL, 0.4F, pitch);
            }

            if (DEBUG) {
                System.out.println("[JUMPDBG] mob=" + entity.getName() + " from=(" + entity.posX + "," + entity.posY + "," + entity.posZ + ") to=(" + tgt.getX() + "," + tgt.getY() + "," + tgt.getZ() + ")" +
                        " horiz=" + horizontal + " horizUsed=" + horizToUse + " vy=" + requiredVy + " attrSpeed=" + attrSpeed + " prepTicks=" + prepareToJumpTicks);
            }

            // cooldowns and bookkeeping
            jumpCooldown = JUMP_COOLDOWN;
            lastJumpPos = tgt;
            lastJumpTick = tickCounter;

            // when landing, block new jumps briefly to avoid jitter
            postLandBlockUntil = POST_LAND_BLOCK_TICKS;

            // reset prepare ticks (we're jumping now)
            prepareToJumpTicks = 0;

            entity.setJumping(true);
            currentJump = null;
        }

        public boolean forceJumpTo(BlockPos pos) {
            double dist = entity.getDistance(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (dist < 1.0) return false;
            currentJump = new JumpOpportunity(new BlockPos(entity.posX, entity.posY, entity.posZ), pos, dist);
            lastJumpPos = pos;
            lastJumpTick = tickCounter;
            // try to execute immediately only if prepared
            if (canJumpNow()) {
                executeJump();
                return true;
            }
            return false;
        }

        public void onLanded() {
            // restore stepHeight
            if (originalStepHeight >= 0f) {
                entity.stepHeight = originalStepHeight;
                originalStepHeight = -1f;
            }

            // small extra cooldown after landing to avoid immediate retry
            jumpCooldown = Math.max(jumpCooldown, JUMP_COOLDOWN / 2);
            postLandBlockUntil = POST_LAND_BLOCK_TICKS;

            // resume navigation to target
            if (currentTarget != null && currentTarget.isEntityAlive()) {
                if (entity.getNavigator() != null) {
                    entity.getNavigator().tryMoveToEntityLiving(currentTarget, 1.0D);
                }
            }

            // reset prepare phase
            prepareToJumpTicks = 0;

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

        // --- EMERGENCY helper methods ---

        // Heuristic: panicking if recently hurt OR no attack target and has an active path (fleeing)
        private boolean isPanicking() {
            if (entity.hurtTime > 0) return true;
            if (entity instanceof EntityCreature) {
                EntityCreature c = (EntityCreature) entity;
                EntityLivingBase atk = c.getAttackTarget();
                try {
                    Path p = entity.getNavigator().getPath();
                    if (atk == null && p != null && !p.isFinished()) return true;
                } catch (Exception ignored) {}
            }
            return false;
        }

        // Find nearest landing block candidate within radius that is a gap from current position
        private BlockPos findNearestGapCandidateForEmergency(int radius) {
            World world = entity.world;
            BlockPos origin = new BlockPos(MathHelper.floor(entity.posX), MathHelper.floor(entity.posY), MathHelper.floor(entity.posZ));
            double bestDist = Double.MAX_VALUE;
            BlockPos best = null;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos cand = origin.add(dx, dy, dz);
                        if (!world.isBlockLoaded(cand)) continue;
                        // landing must have solid ground below
                        if (!hasSolidGroundBelow(world, cand, entity)) continue;
                        // must be safe-ish landing (we're in emergency so we allow isUrgent=true)
                        if (!isSafeLandingSpot(world, cand, entity, true)) continue;
                        // must be a gap from current position
                        if (!isGapBetweenPoints(world, new BlockPos(entity.posX, entity.posY, entity.posZ), cand)) continue;
                        // horizontal distance check
                        double horiz = Math.sqrt(Math.pow(cand.getX() + 0.5 - entity.posX, 2) + Math.pow(cand.getZ() + 0.5 - entity.posZ, 2));
                        if (horiz < 1.0 || horiz > MAX_EMERGENCY_JUMP_DISTANCE) continue;

                        // choose nearest landing (smallest horiz)
                        if (horiz < bestDist) {
                            bestDist = horiz;
                            best = cand;
                        }
                    }
                }
            }
            return best;
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

    // -------------------------
    // Configuration / blacklist loader
    // -------------------------
    private void loadBlacklistFromConfig(File cfgFile) {
        try {
            Configuration cfg = new Configuration(cfgFile);
            cfg.load();
            String[] list = cfg.getStringList(
                    "blacklist",
                    "general",
                    DEFAULT_BLACKLIST_NAMES,
                    "List of entity identifiers to blacklist from parkour behavior. " +
                            "Accepted forms: full class name (net.minecraft.entity.monster.EntityGhast), " +
                            "simple class name (EntityGhast), short name (Ghast), or registered entity name."
            );
            cfg.save();
            parseBlacklist(Arrays.asList(list));
        } catch (Exception e) {
            System.err.println("[ParkourMobs] Failed to load config: " + e.getMessage());
            // fall back to defaults if config failing
            parseBlacklist(Arrays.asList(DEFAULT_BLACKLIST_NAMES));
        }
    }

    @SuppressWarnings("unchecked")
    private static void parseBlacklist(List<String> entries) {
        BLACKLISTED_MOBS.clear();
        if (entries == null) entries = Collections.emptyList();

        String[] commonPkgs = new String[] {
                "net.minecraft.entity.monster.",
                "net.minecraft.entity.passive.",
                "net.minecraft.entity.boss.",
                "net.minecraft.entity.",
        };

        for (String raw : entries) {
            if (raw == null) continue;
            String s = raw.trim();
            if (s.isEmpty()) continue;

            Class<?> clazz = null;

            // 1) try as given (fully qualified)
            try { clazz = Class.forName(s); } catch (Throwable ignored) {}

            // 2) try common packages with variants: s and "Entity"+s
            if (clazz == null) {
                String base = s;
                String[] variants = new String[] { base, "Entity" + base };
                for (String v : variants) {
                    for (String pkg : commonPkgs) {
                        if (clazz != null) break;
                        try { clazz = Class.forName(pkg + v); } catch (Throwable ignored) {}
                    }
                    if (clazz != null) break;
                }
            }

            // 3) try EntityList lookup (registered name like "Ghast" or "ghast")
            if (clazz == null) {
                try { clazz = EntityList.getClassFromName(s); } catch (Throwable ignored) {}
                if (clazz == null) {
                    try { clazz = EntityList.getClassFromName(s.toLowerCase(java.util.Locale.ROOT)); } catch (Throwable ignored) {}
                }
            }

            if (clazz == null) {
                System.out.println("[ParkourMobs] Unknown blacklist entry: '" + s + "' (skipped).");
                continue;
            }

            if (!EntityLiving.class.isAssignableFrom(clazz)) {
                System.out.println("[ParkourMobs] Blacklist class " + clazz.getName() + " is not an EntityLiving (skipped).");
                continue;
            }

            try {
                BLACKLISTED_MOBS.add((Class<? extends EntityLiving>) clazz);
                if (DEBUG) System.out.println("[ParkourMobs] Blacklisted: " + clazz.getName());
            } catch (Throwable t) {
                System.out.println("[ParkourMobs] Failed to add blacklist class " + clazz.getName() + ": " + t.getMessage());
            }
        }

        // safety: if still empty, add a few safe defaults to avoid surprising behavior
        if (BLACKLISTED_MOBS.isEmpty()) {
            try {
                for (String def : DEFAULT_BLACKLIST_NAMES) {
                    Class<?> c = null;
                    try { c = Class.forName("net.minecraft.entity.monster." + def.replace("Entity","")); } catch (Throwable ignored) {}
                    if (c == null) {
                        try { c = Class.forName("net.minecraft.entity." + def.replace("Entity","")); } catch (Throwable ignored) {}
                    }
                    if (c != null && EntityLiving.class.isAssignableFrom(c)) BLACKLISTED_MOBS.add((Class<? extends EntityLiving>) c);
                }
            } catch (Throwable ignored) {}
        }
    }

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (!"parkourmobs".equals(event.getModID())) return;
        if (CONFIG == null) return;

        // повторно распарсим blacklist из текущей конфигурации
        String[] list = CONFIG.getStringList(
                "blacklist",
                "general",
                DEFAULT_BLACKLIST_NAMES,
                "List of entity identifiers to blacklist from parkour behavior."
        );
        parseBlacklist(Arrays.asList(list));
        if (CONFIG.hasChanged()) CONFIG.save();
        System.out.println("[ParkourMobs] Config changed — blacklist reloaded (" + BLACKLISTED_MOBS.size() + " classes)");
    }

    public static class ConfigGuiFactory implements IModGuiFactory {
        @Override
        public void initialize(Minecraft minecraftInstance) { }

        @Override
        public boolean hasConfigGui() {
            return true;
        }

        @Override
        public GuiScreen createConfigGui(GuiScreen parentScreen) {
            return new ConfigGui(parentScreen);
        }

        @Override
        public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
            return Collections.emptySet();
        }
    }

    public static class ConfigGui extends GuiConfig {
        public ConfigGui(GuiScreen parent) {
            super(parent, getConfigElements(), "parkourmobs", false, false, "Parkour Mobs Configuration");
        }

        private static List<IConfigElement> getConfigElements() {
            List<IConfigElement> list = new ArrayList<>();
            if (CONFIG == null) {
                // safety: try load default file if possible
                try {
                    File f = new File("config/parkourmobs.cfg");
                    CONFIG = new Configuration(f);
                    CONFIG.load();
                } catch (Exception ignored) { }
            }
            if (CONFIG != null) {
                // добавляем всю категорию "general" — в ней есть наша строка blacklist
                list.addAll(new ConfigElement(CONFIG.getCategory("general")).getChildElements());
            }
            return list;
        }

        @Override
        public void onGuiClosed() {
            super.onGuiClosed();
            if (CONFIG != null && CONFIG.hasChanged()) {
                CONFIG.save();
                // немедленно применим изменения (парсим blacklist)
                String[] list = CONFIG.getStringList(
                        "blacklist",
                        "general",
                        DEFAULT_BLACKLIST_NAMES,
                        "List of entity identifiers to blacklist from parkour behavior."
                );
                parseBlacklist(Arrays.asList(list));
                System.out.println("[ParkourMobs] GUI saved — blacklist reloaded (" + BLACKLISTED_MOBS.size() + " classes)");
            }
        }
    }

}