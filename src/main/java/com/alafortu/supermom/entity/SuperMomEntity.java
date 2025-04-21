package com.alafortu.supermom.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel; // Added for ForageCropGoal block breaking
import net.minecraft.sounds.SoundEvents; // Added for ForageCropGoal sound
import net.minecraft.sounds.SoundSource; // Added for ForageCropGoal sound
import net.minecraft.world.Container; // Interface for inventory access if needed, but SimpleContainer is used directly
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.InteractionHand; // Added for swing animation
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob; // Import added for getMeleeAttackRangeSqr
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.MobSpawnType; // Import pour finalizeSpawn
import net.minecraft.world.entity.SpawnGroupData; // Import pour finalizeSpawn
import net.minecraft.world.entity.EquipmentSlot; // Import pour setItemSlot/setDropChance
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions; // Added import
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster; // Import needed for MeleeAttackGoal fix
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item; // Added for ForageCropGoal drop check
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantments; // Import pour finalizeSpawn
// import net.minecraft.world.item.enchantment.EnchantmentHelper; // Not strictly needed for finalizeSpawn logic shown
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.DifficultyInstance; // Import pour finalizeSpawn
import net.minecraft.world.level.ServerLevelAccessor; // CORRECT
import net.minecraft.world.level.block.Block; // Added for ForageCropGoal target check
import net.minecraft.world.level.block.CropBlock; // Added for ForageCropGoal target check
import net.minecraft.world.level.block.state.BlockState; // Added import for ForageCropGoal
import net.minecraft.world.level.storage.loot.LootParams; // Added for ForageCropGoal block drops
import net.minecraft.world.level.storage.loot.parameters.LootContextParams; // Added for ForageCropGoal block drops
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB; // Added import for HuntForMeatGoal drop collection
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.alafortu.supermom.SuperMomMod; // Assuming this import is correct
import com.alafortu.supermom.entity.goal.OwnerHurtByTargetGoal; // Assuming this import is correct

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.function.Predicate; // Added import
import java.util.UUID; // Added for owner tracking
import java.util.Comparator; // For stream().min()
import java.util.List; // Explicit import for List used in ForageCropGoal

public class SuperMomEntity extends PathfinderMob {

    public static final Logger LOGGER = LogManager.getLogger(SuperMomMod.MODID + ".SuperMomEntity"); // Made public for access from Goals
    private static final double TELEPORT_DISTANCE_SQ = 625.0D; // 25*25
    private static final double RESOURCE_GATHERING_RANGE_SQ = 625.0D; // 25*25 Rayon pour chercher ressources autour du joueur
    private static final int INVENTORY_SIZE = 18; // Increased size
    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);

    @Nullable
    private BlockPos homePosition = null;
    private GoHomeGoal goHomeGoal; // Instance of the goal
    private OwnerHurtByTargetGoal ownerHurtByTargetGoal; // Garder une référence pour vérifier son état

    @Nullable // Peut être null si spawnée autrement (ex: commande)
    private java.util.UUID ownerUUID = null;

    // --- Autonomous Action Fields ---
    private static final float AUTONOMOUS_HEAL_THRESHOLD_PERCENT = 0.6f;
    private static final int AUTONOMOUS_ACTION_COOLDOWN_TICKS = 60; // Réduit un peu le cooldown
    private int autonomousActionCooldown = 0;

    // --- Resource Thresholds --- (Déplacés ici pour accès global facile)
    public static final int MIN_MEAT_THRESHOLD = 15; // Abaissé un peu
    public static final int MIN_CROP_THRESHOLD = 15; // Abaissé un peu


    public SuperMomEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        LOGGER.debug("SuperMomEntity created in level: {}", level.dimension().location());
        this.setCanPickUpLoot(true); // Allows the entity to pick up items via vanilla mechanics when close enough
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1000.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.35D)
                .add(Attributes.FOLLOW_RANGE, 32.0D) // Garder une bonne portée de détection générale
                .add(Attributes.ATTACK_DAMAGE, 10.0D) // Dégâts de base, l'épée ajoutera
                .add(Attributes.ARMOR, 10.0D);
    }

    // --- Helper Methods for Resource Needs ---
    public boolean needsMeat() {
        return countMeatItems() < MIN_MEAT_THRESHOLD;
    }

    public boolean needsCrops() {
        return countCropItems() < MIN_CROP_THRESHOLD;
    }

    private int countMeatItems() {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); ++i) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.isEdible() && stack.getFoodProperties(this) != null && stack.getFoodProperties(this).isMeat()) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countCropItems() {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); ++i) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && (
                    stack.is(Items.WHEAT) ||
                    stack.is(Items.CARROT) ||
                    stack.is(Items.POTATO) ||
                    stack.is(Items.BEETROOT) ||
                    stack.is(Items.BREAD)
            )) {
                count += stack.getCount();
            }
        }
        return count;
    }


    // --- Goal Definitions (Inner Classes) ---

    // --- Goal for navigating to the home position ---
    class GoHomeGoal extends MoveToBlockGoal {
       private final SuperMomEntity superMom;
       private boolean pathFound = false;
       private static final double MIN_DISTANCE_SQ = 4.0D; // Minimum distance before considering "home"

       // Store the target position internally within the goal instance
       // This avoids accessing the protected 'blockPos' from outside
       private BlockPos internalTargetPos;

       public GoHomeGoal(SuperMomEntity mob, double speedModifier, int searchRange) {
           super(mob, speedModifier, searchRange, 1); // Use vertical range 1
           this.superMom = mob;
           this.setFlags(EnumSet.of(Goal.Flag.MOVE));
       }

       @Override
       public boolean canUse() {
            // Check if home position is set
            if (this.superMom.getHomePosition() == null) {
                return false;
            }
            this.internalTargetPos = this.superMom.getHomePosition(); // Update internal target

            // Check if already close enough
            if (!this.isFarFromHome()) {
                 return false;
            }
            // Don't go home if defending the player
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) {
                return false;
            }
            // Don't go home if targeting a monster
            if (this.superMom.getTarget() instanceof Monster) {
                 return false;
            }

            // Only activate if explicitly started by goHome() action.
            // Check if this specific goal instance is currently running.
            boolean isActive = this.isGoalActive(); // Use helper method
            if (!isActive) {
                 return false; // Don't start automatically, only via goHome()
            }

            // If it IS active, check if we need to find a path (e.g., after teleport or interruption)
            // Use internalTargetPos for checks
            if (this.mob.getNavigation().isDone() || this.blockPos == null || !this.blockPos.equals(this.internalTargetPos)) {
                 this.pathFound = this.findNearestBlock(); // Try to find path only if needed
                 return this.pathFound;
            }
            return true; // Continue using existing path if active and valid
       }

       @Override
       public boolean canContinueToUse() {
            // Stop if defending the player
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) {
                return false;
            }
            // Stop if acquiring a monster target
            if (this.superMom.getTarget() instanceof Monster) {
                 return false;
            }
            // Stop if home position becomes null
            if (this.superMom.getHomePosition() == null) {
                return false;
            }
            this.internalTargetPos = this.superMom.getHomePosition(); // Update internal target

            // Continue if active, home exists, target block exists, not reached, and target is valid
            // Use internalTargetPos for checks
            return this.internalTargetPos != null
                    && !this.isReachedTarget()
                    && this.isValidTarget(this.superMom.level(), this.internalTargetPos) // Check against internal target
                    && this.isGoalActive(); // Ensure goal is still running
       }

       @Override
       public void start() {
            this.internalTargetPos = this.superMom.getHomePosition(); // Get current home pos on start
            if (this.internalTargetPos != null) {
                // Set the MoveToBlockGoal's target blockPos (protected field) via super method if possible,
                // otherwise rely on moveMobToBlock()
                // super.start(); // This might try to find a block, we want a specific one.

                SuperMomEntity.LOGGER.debug("GoHomeGoal started. Target: {}", this.internalTargetPos);
                this.pathFound = false;
                this.moveMobToBlock(); // Attempt to create and start path to internalTargetPos
            } else {
                 SuperMomEntity.LOGGER.warn("GoHomeGoal started but homePosition is null!");
            }
       }

       @Override
       public void stop() {
            SuperMomEntity.LOGGER.debug("GoHomeGoal stopped. Reached Target: {}", this.isReachedTarget());
            super.stop(); // Stop navigation via MoveToBlockGoal's stop
            this.pathFound = false;
            this.internalTargetPos = null; // Clear internal target on stop
       }

        @Override
        public void tick() {
            // Stop ticking if defending or targeting a monster
            if ((this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) ||
                (this.superMom.getTarget() instanceof Monster)) {
                 return;
            }

            // Update target if home position changes mid-goal
            BlockPos currentHome = this.superMom.getHomePosition();
            if (currentHome != null && this.internalTargetPos != null && !this.internalTargetPos.equals(currentHome)) {
                SuperMomEntity.LOGGER.debug("GoHomeGoal detected home position change. Updating target.");
                this.internalTargetPos = currentHome;
                this.moveMobToBlock(); // Recalculate path to new home
            } else if (currentHome == null && this.internalTargetPos != null) {
                // Home position removed while goal active, stop the goal
                SuperMomEntity.LOGGER.debug("GoHomeGoal stopping because home position removed.");
                // Let canContinueToUse handle stopping
                return;
            }

            // Check if path is needed but not found
            if (!this.pathFound && this.mob.getNavigation().isDone() && this.internalTargetPos != null) {
                this.moveMobToBlock(); // Try finding path again if needed
            }
            super.tick(); // Continue MoveToBlockGoal's tick logic (path following)
        }

       private boolean isFarFromHome() {
           BlockPos home = this.internalTargetPos; // Use internal target for check
           if (home == null) return false;
           // Check distance squared for efficiency
           return this.superMom.position().distanceToSqr(Vec3.atCenterOf(home)) > MIN_DISTANCE_SQ;
       }

       @Override
       protected boolean isValidTarget(LevelReader level, BlockPos pos) {
           // Target is valid only if it's the current internal home position
           return this.internalTargetPos != null && pos.equals(this.internalTargetPos);
       }

       @Override
       protected void moveMobToBlock() {
            // Tries to pathfind to the internal target block (home)
            if (this.internalTargetPos != null) {
                this.blockPos = this.internalTargetPos; // Set the protected blockPos for superclass logic
                Path path = this.mob.getNavigation().createPath(this.blockPos, 0); // Path level 0
                if (path != null && path.canReach()) {
                    this.mob.getNavigation().moveTo(path, this.speedModifier);
                    this.pathFound = true;
                    SuperMomEntity.LOGGER.trace("GoHomeGoal.moveMobToBlock: Path found to {}", this.blockPos);
                } else {
                    this.pathFound = false;
                    SuperMomEntity.LOGGER.warn("GoHomeGoal.moveMobToBlock: Cannot create path to {}", this.blockPos);
                }
            } else {
                 this.pathFound = false; // No target, no path
                 SuperMomEntity.LOGGER.warn("GoHomeGoal.moveMobToBlock: Cannot move, internalTargetPos is null.");
            }
       }

        @Override
        public boolean isReachedTarget() {
            // Use MoveToBlockGoal's check for reaching the target
            // Add a small tolerance, sometimes isReachedTarget is too strict
             return this.internalTargetPos != null && this.mob.blockPosition().distManhattan(this.internalTargetPos) <= 1;
        }

        // Helper to check if this specific goal instance is currently running
        public boolean isGoalActive() {
            return this.superMom.goalSelector.getRunningGoals().anyMatch(goal -> goal.getGoal() == this);
        }
    } // Fin GoHomeGoal

    // --- Helper Class for FollowPlayerGoal --- (No changes needed here based on errors)
    static class FollowPlayerGoal extends Goal {
       private final SuperMomEntity superMom;
       private final double speedModifier;
       private final float stopDistanceSq; // Stop within this distance squared
       private final float startDistanceSq; // Start if further than this distance squared
       private Player targetPlayer;
       private int timeToRecalcPath;
       private final net.minecraft.world.entity.ai.navigation.PathNavigation navigation;

       public FollowPlayerGoal(SuperMomEntity superMom, double speed, float startDist, float stopDist) {
           this.superMom = superMom;
           this.navigation = superMom.getNavigation();
           this.speedModifier = speed;
           this.startDistanceSq = startDist * startDist;
           this.stopDistanceSq = stopDist * stopDist;
           this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
       }

       @Override
       public boolean canUse() {
           // Don't follow if targeting a monster
           if (this.superMom.getTarget() instanceof Monster) {
               return false;
           }
           // Don't follow if defending the player
           if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) {
               return false;
           }
           // Don't follow if GoHomeGoal is active
           if (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive()) {
                return false;
           }

           this.targetPlayer = this.superMom.getOwner(); // Target the owner
           // Activate if owner exists, is not spectator, and is further than stop distance
            return this.targetPlayer != null && !this.targetPlayer.isSpectator() && this.superMom.distanceToSqr(this.targetPlayer) > this.stopDistanceSq;
       }

       @Override
       public boolean canContinueToUse() {
           // Stop if targeting a monster, player is null, or player is spectator
            if (this.superMom.getTarget() instanceof Monster || this.targetPlayer == null || this.targetPlayer.isSpectator()) {
                return false;
            }
            // Stop if player is dead
            if (!this.targetPlayer.isAlive()) {
                return false;
            }
            // Stop if close enough
            if (this.superMom.distanceToSqr(this.targetPlayer) <= this.stopDistanceSq) {
                return false;
            }
            // Stop if defense goal activates
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) {
                return false;
           }
           // Stop if GoHomeGoal activates
           if (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive()) {
                return false;
           }
            // Continue if within a reasonable range (e.g., twice the start distance) to prevent chasing across the world
            return this.superMom.distanceToSqr(this.targetPlayer) <= (this.startDistanceSq * 4.0D);
       }

       @Override
       public void start() {
           this.timeToRecalcPath = 0; // Reset path recalculation timer
           if (targetPlayer != null) {
                LOGGER.debug("Starting FollowPlayerGoal for {}", targetPlayer.getName().getString());
           }
       }

       @Override
       public void stop() {
           // Don't clear targetPlayer here, canUse will refresh it
           this.navigation.stop(); // Stop current path
           LOGGER.debug("Stopping FollowPlayerGoal");
       }

       @Override
       public void tick() {
           if (this.targetPlayer != null) {
                this.superMom.getLookControl().setLookAt(this.targetPlayer, 10.0F, (float)this.superMom.getMaxHeadXRot());
                // Recalculate path periodically
                if (--this.timeToRecalcPath <= 0) {
                    this.timeToRecalcPath = this.adjustedTickDelay(10);
                    // Keep moving if further than stop distance + a small buffer
                    if (this.superMom.distanceToSqr(this.targetPlayer) > (this.stopDistanceSq + 1.0D)) {
                          this.navigation.moveTo(this.targetPlayer, this.speedModifier);
                    } else {
                          this.navigation.stop(); // Stop if close enough
                    }
                }
           }
       }
    } // Fin FollowPlayerGoal

    // --- Goal for Feeding the Player --- (No changes needed here based on errors)
    class FeedPlayerGoal extends Goal {
       private final SuperMomEntity superMom;
       private Player targetPlayer;
       private int timeToRecalcPath;
       private static final double REACH_DISTANCE_SQ = 4.0D; // 2 blocks squared

       public FeedPlayerGoal(SuperMomEntity mob) {
           this.superMom = mob;
           this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
       }

       @Override
       public boolean canUse() {
            // Don't activate if targeting a monster or defending
            if (this.superMom.getTarget() instanceof Monster) return false;
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) return false;
            // Don't run if GoHomeGoal is active
            if (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive()) return false;

           this.targetPlayer = this.superMom.getOwner();
           // Activate if owner exists, is alive, needs food, is nearby, and mom has food
           if (this.targetPlayer == null || !this.targetPlayer.isAlive() || !this.targetPlayer.getFoodData().needsFood()) {
                return false;
           }
           if (this.superMom.distanceToSqr(this.targetPlayer) > 64.0D) { // 8 blocks squared
                return false;
           }
           return this.superMom.findInInventory(ItemStack::isEdible) != -1;
       }

       @Override
       public boolean canContinueToUse() {
            // Stop if defending or targeting a monster
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) return false;
            if (this.superMom.getTarget() instanceof Monster) return false;
            // Stop if GoHomeGoal activates
            if (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive()) return false;

           // Continue if player still needs food, mom has food, navigation isn't finished, and player is reasonably close
           return this.targetPlayer != null
                   && this.targetPlayer.isAlive()
                   && this.targetPlayer.getFoodData().needsFood()
                   && this.superMom.findInInventory(ItemStack::isEdible) != -1
                   && !this.superMom.getNavigation().isDone() // Check if path is complete
                   && this.superMom.distanceToSqr(this.targetPlayer) <= 144.0D; // 12 blocks squared
       }

       @Override
       public void start() {
           LOGGER.debug("FeedPlayerGoal started for {}", this.targetPlayer.getName().getString());
           this.timeToRecalcPath = 0;
           this.superMom.getNavigation().moveTo(this.targetPlayer, 1.0D); // Standard speed
       }

       @Override
       public void stop() {
           LOGGER.debug("FeedPlayerGoal stopped for {}", this.targetPlayer != null ? this.targetPlayer.getName().getString() : "null target");
           this.targetPlayer = null;
           this.superMom.getNavigation().stop();
       }

       @Override
       public void tick() {
           if (this.targetPlayer == null) return;
            // Stop ticking if defending or targeting a monster or going home
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) return;
            if (this.superMom.getTarget() instanceof Monster) return;
            if (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive()) return;

           this.superMom.getLookControl().setLookAt(this.targetPlayer, 10.0F, (float) this.superMom.getMaxHeadXRot());

           // Recalculate path periodically if not close
           if (this.superMom.distanceToSqr(this.targetPlayer) >= REACH_DISTANCE_SQ) {
                if (--this.timeToRecalcPath <= 0) {
                    this.timeToRecalcPath = this.adjustedTickDelay(10);
                    this.superMom.getNavigation().moveTo(this.targetPlayer, 1.0D);
                }
           } else {
                // If close enough, stop moving and attempt to feed
                this.superMom.getNavigation().stop(); // Stop moving when close
                LOGGER.debug("FeedPlayerGoal: Reached player {}, attempting to feed.", this.targetPlayer.getName().getString());
                this.superMom.feedPlayer(this.targetPlayer);
                // Goal will stop via canContinueToUse if player no longer needs food or mom runs out
           }
       }
    } // Fin FeedPlayerGoal

    // --- Goal for Healing the Player --- (No changes needed here based on errors)
    class HealPlayerGoal extends Goal {
       private final SuperMomEntity superMom;
       private Player targetPlayer;
       private int timeToRecalcPath;
       private static final double REACH_DISTANCE_SQ = 4.0D; // 2 blocks squared
       private static final float HEAL_THRESHOLD_PERCENT = 0.8f; // Heal if health < 80%

       public HealPlayerGoal(SuperMomEntity mob) {
           this.superMom = mob;
           this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
       }

       @Override
       public boolean canUse() {
            // Don't activate if targeting a monster or defending
            if (this.superMom.getTarget() instanceof Monster) return false;
             if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) return false;
            // Don't run if GoHomeGoal is active
            if (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive()) return false;

           this.targetPlayer = this.superMom.getOwner();
           // Activate if owner exists, is alive, needs healing, is nearby, and mom has healing potions
           if (this.targetPlayer == null || !this.targetPlayer.isAlive() || this.targetPlayer.getHealth() >= this.targetPlayer.getMaxHealth() * HEAL_THRESHOLD_PERCENT) {
                return false;
           }
           if (this.superMom.distanceToSqr(this.targetPlayer) > 64.0D) { // 8 blocks squared
                return false;
           }
           return this.superMom.findInInventory(stack -> stack.is(Items.POTION) && (PotionUtils.getPotion(stack) == Potions.HEALING || PotionUtils.getPotion(stack) == Potions.STRONG_HEALING)) != -1;
       }

       @Override
       public boolean canContinueToUse() {
            // Stop if defending or targeting a monster
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) return false;
            if (this.superMom.getTarget() instanceof Monster) return false;
            // Stop if GoHomeGoal activates
            if (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive()) return false;

           // Continue if player still needs healing, mom has potions, navigation isn't finished, and player is reasonably close
           return this.targetPlayer != null
                   && this.targetPlayer.isAlive()
                   && this.targetPlayer.getHealth() < this.targetPlayer.getMaxHealth() * HEAL_THRESHOLD_PERCENT
                   && this.superMom.findInInventory(stack -> stack.is(Items.POTION) && (PotionUtils.getPotion(stack) == Potions.HEALING || PotionUtils.getPotion(stack) == Potions.STRONG_HEALING)) != -1
                   && !this.superMom.getNavigation().isDone() // Check if path is complete
                   && this.superMom.distanceToSqr(this.targetPlayer) <= 144.0D; // 12 blocks squared
       }

       @Override
       public void start() {
           LOGGER.debug("HealPlayerGoal started for {}", this.targetPlayer.getName().getString());
           this.timeToRecalcPath = 0;
           this.superMom.getNavigation().moveTo(this.targetPlayer, 1.0D); // Standard speed
       }

       @Override
       public void stop() {
           LOGGER.debug("HealPlayerGoal stopped for {}", this.targetPlayer != null ? this.targetPlayer.getName().getString() : "null target");
           this.targetPlayer = null;
           this.superMom.getNavigation().stop();
       }

       @Override
       public void tick() {
           if (this.targetPlayer == null) return;
            // Stop ticking if defending or targeting a monster or going home
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) return;
            if (this.superMom.getTarget() instanceof Monster) return;
            if (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive()) return;

           this.superMom.getLookControl().setLookAt(this.targetPlayer, 10.0F, (float) this.superMom.getMaxHeadXRot());

           // Recalculate path periodically if not close
           if (this.superMom.distanceToSqr(this.targetPlayer) >= REACH_DISTANCE_SQ) {
                if (--this.timeToRecalcPath <= 0) {
                    this.timeToRecalcPath = this.adjustedTickDelay(10);
                    this.superMom.getNavigation().moveTo(this.targetPlayer, 1.0D);
                }
           } else {
                // If close enough, stop moving and attempt to heal
                this.superMom.getNavigation().stop(); // Stop moving when close
                LOGGER.debug("HealPlayerGoal: Reached player {}, attempting to heal.", this.targetPlayer.getName().getString());
                this.superMom.healPlayer(this.targetPlayer);
                // Goal will stop via canContinueToUse if player no longer needs healing or mom runs out of potions
           }
       }
    } // Fin HealPlayerGoal

    // --- Goal for Picking Up Loose Edible Items (Non-Meat/Crop) --- (No changes needed here based on errors)
    class GatherFoodGoal extends Goal {
       private final SuperMomEntity superMom;
       private final double speedModifier;
       private final int searchRange;
       private ItemEntity targetFoodItem;
       private int timeToRecalcPath;

       public GatherFoodGoal(SuperMomEntity mob, double speed, int range) {
           this.superMom = mob;
           this.speedModifier = speed;
           this.searchRange = range;
           this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
       }

       @Override
       public boolean canUse() {
           // Basic conditions: no monster target, daytime, owner nearby and safe
           Player owner = this.superMom.getOwner();
           if (this.superMom.getTarget() instanceof Monster || !this.superMom.level().isDay() || owner == null || this.superMom.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ) {
                return false;
           }
           if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) {
                return false;
           }
           // Don't run if GoHomeGoal is active
           if (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive()) {
                return false;
           }
           // Don't run if the dedicated meat/crop pickup goal is running
           if (this.superMom.goalSelector.getRunningGoals().anyMatch(g -> g.getGoal() instanceof PickupNearbyItemsGoal)) {
                return false;
           }

           // Find suitable items using CheckSuppliesGoal's logic (or similar)
           this.targetFoodItem = findNearbyNonMeatCropEdible(); // Use the specific finder
           return this.targetFoodItem != null;
       }

       @Override
       public boolean canContinueToUse() {
           // Basic conditions check
            Player owner = this.superMom.getOwner();
           if (this.superMom.getTarget() instanceof Monster || !this.superMom.level().isDay() || owner == null || this.superMom.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ * 1.5) {
                return false;
           }
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) {
                return false;
           }
           // Stop if GoHomeGoal activates
           if (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive()) {
                return false;
           }
           // Stop if item is gone or navigation path is complete
           return this.targetFoodItem != null && this.targetFoodItem.isAlive() && !this.superMom.getNavigation().isDone();
       }

       @Override
       public void start() {
           this.timeToRecalcPath = 0;
           if (this.targetFoodItem != null) {
                SuperMomEntity.LOGGER.debug("GatherFoodGoal started. Target: {} at {}", this.targetFoodItem.getItem().getDescriptionId(), this.targetFoodItem.blockPosition());
                this.superMom.getNavigation().moveTo(this.targetFoodItem, this.speedModifier);
                this.superMom.resetAutonomousCooldown(); // Reset cooldown when starting to gather other food
           } else {
                SuperMomEntity.LOGGER.warn("GatherFoodGoal started but targetFoodItem is null!");
           }
       }

       @Override
       public void stop() {
           SuperMomEntity.LOGGER.debug("GatherFoodGoal stopped. Target Item: {}", this.targetFoodItem != null ? this.targetFoodItem.getItem().getDescriptionId() : "null");
           this.targetFoodItem = null;
           // Stop navigation only if goal is explicitly stopped
           if (this.superMom.goalSelector.getRunningGoals().anyMatch(g -> g.getGoal() == this)) {
                this.superMom.getNavigation().stop();
           }
       }

       @Override
       public void tick() {
           if (this.targetFoodItem != null && this.targetFoodItem.isAlive()) {
                this.superMom.getLookControl().setLookAt(this.targetFoodItem, 10.0F, (float) this.superMom.getMaxHeadXRot());
                // Recalculate path periodically if navigation isn't done
                if (!this.superMom.getNavigation().isDone()) {
                    if (--this.timeToRecalcPath <= 0) {
                        this.timeToRecalcPath = this.adjustedTickDelay(10);
                        this.superMom.getNavigation().moveTo(this.targetFoodItem, this.speedModifier);
                    }
                } else {
                    // If navigation is done, vanilla pickup should handle it.
                    // If it fails (e.g. inventory full), canContinueToUse will eventually fail.
                    LOGGER.trace("GatherFoodGoal: Navigation done for {}. Vanilla pickup should occur.", targetFoodItem.getItem().getDescriptionId());
                }
           } else {
                // Target lost or picked up
                this.targetFoodItem = null; // Goal will stop via canContinueToUse
           }
       }

       // Replicated finder logic from CheckSuppliesGoal for consistency
       @Nullable
       private ItemEntity findNearbyNonMeatCropEdible() {
           AABB searchBox = this.superMom.getBoundingBox().inflate(this.searchRange, this.searchRange / 2.0, this.searchRange);
           Predicate<ItemEntity> edibleItemPredicate = entity -> {
               // Basic checks: alive, not empty, edible, pickup delay ok
               if (!entity.isAlive() || entity.hasPickUpDelay() || entity.getItem().isEmpty() || !entity.getItem().isEdible()) return false;

               ItemStack stack = entity.getItem(); // Get ItemStack
               Item item = stack.getItem();

               // Exclude meat
               if (stack.getFoodProperties(this.superMom) != null && stack.getFoodProperties(this.superMom).isMeat()) return false;
               // Exclude crops/bread handled by PickupNearbyItemsGoal
               if (item == Items.WHEAT || item == Items.WHEAT_SEEDS ||
                   item == Items.CARROT ||
                   item == Items.POTATO ||
                   item == Items.BEETROOT || item == Items.BEETROOT_SEEDS ||
                   item == Items.BREAD) return false;

               // Check line of sight and inventory space
               return this.superMom.hasLineOfSight(entity) &&
                      this.superMom.inventory.canAddItem(stack);
           };
           // Find the closest matching item
           return this.superMom.level().getEntitiesOfClass(ItemEntity.class, searchBox, edibleItemPredicate)
                .stream()
                .min(Comparator.comparingDouble(this.superMom::distanceToSqr))
                .orElse(null);
       }
    } // Fin GatherFoodGoal


    // --- Goal for Hunting Passive Animals --- (No changes needed here based on errors)
    class HuntForMeatGoal extends Goal {
       private final SuperMomEntity superMom;
       private LivingEntity targetAnimal;
       private final TargetingConditions huntTargeting;
       private int timeToRecalcPath; // Added for path recalculation

       public HuntForMeatGoal(SuperMomEntity mob) {
           this.superMom = mob;
           // Need MOVE flag for navigation
           this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
           this.huntTargeting = TargetingConditions.forCombat()
                .range(16.0D) // Search range
                .ignoreLineOfSight() // Can target through grass etc.
                .selector((entity) ->
                    entity instanceof net.minecraft.world.entity.animal.Cow ||
                    entity instanceof net.minecraft.world.entity.animal.Pig ||
                    entity instanceof net.minecraft.world.entity.animal.Sheep ||
                    entity instanceof net.minecraft.world.entity.animal.Chicken);
       }

       @Override
       public boolean canUse() {
           Player owner = this.superMom.getOwner();
           // Basic conditions: daytime, no monster target, cooldown ok, owner nearby and safe, needs meat
           if (!this.superMom.level().isDay() ||
               this.superMom.getTarget() instanceof Monster ||
               this.superMom.autonomousActionCooldown > 0 ||
               owner == null ||
               this.superMom.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ ||
               (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) ||
               !this.superMom.needsMeat() ||
               (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive())) { // Don't hunt if going home
                return false;
           }

           // Find nearest valid animal also near owner
           this.targetAnimal = this.superMom.level().getNearestEntity(
                LivingEntity.class, // Class to search for
                this.huntTargeting, // Targeting conditions (includes type check)
                this.superMom, // The entity searching
                this.superMom.getX(), this.superMom.getY(), this.superMom.getZ(), // Search center
                this.superMom.getBoundingBox().inflate(16.0D) // Search area
           );

           if (this.targetAnimal != null && this.targetAnimal.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ) {
                this.targetAnimal = null; // Ignore if too far from owner
           }

           if (this.targetAnimal == null) {
                return false;
           }

           LOGGER.debug("HuntForMeatGoal.canUse: Found target animal {} near owner.", this.targetAnimal.getType().getDescription().getString());
           return true;
       }

       @Override
       public boolean canContinueToUse() {
           Player owner = this.superMom.getOwner();
           // Stop conditions: nighttime, owner too far/unsafe, different monster target, going home
           if (!this.superMom.level().isDay() ||
               owner == null ||
               this.superMom.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ * 1.5 ||
               (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) ||
               (this.superMom.getTarget() instanceof Monster && this.superMom.getTarget() != this.targetAnimal) ||
               (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive())) {
                return false;
           }
           // Continue as long as the target is alive and is the current target (or no target is set yet by this goal)
           return this.targetAnimal != null
                   && this.targetAnimal.isAlive()
                   && (this.superMom.getTarget() == this.targetAnimal || this.superMom.getTarget() == null); // Allow targeting if not yet set
       }

       @Override
       public void start() {
           if (this.targetAnimal != null) {
                LOGGER.debug("HuntForMeatGoal started. Targeting: {}", this.targetAnimal.getType().getDescription().getString());
                this.superMom.setTarget(this.targetAnimal); // Set target for attack logic and other goals
                this.superMom.resetAutonomousCooldown();
                this.timeToRecalcPath = 0; // Reset path timer
                this.superMom.getNavigation().moveTo(this.targetAnimal, 1.2D); // Start moving
           } else {
                LOGGER.error("HuntForMeatGoal started without a targetAnimal! This should not happen.");
           }
       }

       @Override
       public void stop() {
           LOGGER.debug("HuntForMeatGoal stopped. Target was: {}", this.targetAnimal != null ? this.targetAnimal.getType().getDescription().getString() : "null");
           // Clear target only if it was ours and no defense goal is active
           if (this.superMom.getTarget() == this.targetAnimal && (this.superMom.ownerHurtByTargetGoal == null || !this.superMom.ownerHurtByTargetGoal.isActive)) {
                // Clear the target if no other goal (like MeleeAttackGoal for monsters) is keeping it.
                if (!this.superMom.goalSelector.getRunningGoals().anyMatch(g -> g.getGoal() instanceof MeleeAttackGoal && g.getGoal().canContinueToUse())) {
                     this.superMom.setTarget(null);
                }
           }
           this.targetAnimal = null;
           this.superMom.getNavigation().stop(); // Stop moving
       }

       @Override
       public void tick() {
            LivingEntity currentTarget = this.superMom.getTarget(); // Get current target (might be changed by other goals)
            // If the target is no longer our animal (or null), stop.
            if (currentTarget != this.targetAnimal || this.targetAnimal == null || !this.targetAnimal.isAlive()) {
                return; // Let stop() handle cleanup
            }

           this.superMom.getLookControl().setLookAt(this.targetAnimal, 30.0F, 30.0F);

           // --- Manual Attack Logic ---
           // Recalculate path periodically
           if (--this.timeToRecalcPath <= 0) {
                this.timeToRecalcPath = this.adjustedTickDelay(10);
                this.superMom.getNavigation().moveTo(this.targetAnimal, 1.2D); // Attack speed
           }

           // Attack when in range and attack cooldown is ready
           double distSq = this.superMom.distanceToSqr(this.targetAnimal.getX(), this.targetAnimal.getY(), this.targetAnimal.getZ());
           double attackRangeSq = this.superMom.getMeleeAttackRangeSqr(this.targetAnimal);

           if (distSq <= attackRangeSq && this.superMom.getAttackAnim(0.0f) == 0.0f) { // Check attack animation progress
                this.superMom.swing(InteractionHand.MAIN_HAND);
                this.superMom.doHurtTarget(this.targetAnimal);
                LOGGER.trace("HuntForMeatGoal: Attacking target {}", this.targetAnimal.getName().getString());
           }
           // --- End Manual Attack Logic ---
       }
    } // Fin HuntForMeatGoal


    // --- Goal for Foraging Mature Crops --- (No changes needed here based on errors)
    class ForageCropGoal extends MoveToBlockGoal {
       private final SuperMomEntity superMom;
       private int harvestCooldown = 0;
       private static final int MAX_HARVEST_COOLDOWN = 40; // Ticks before trying again after failure/success

       public ForageCropGoal(SuperMomEntity mob, double speedModifier, int searchRange) {
           super(mob, speedModifier, searchRange, 6); // searchRange horizontal, 6 vertical
           this.superMom = mob;
           this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
       }

       @Override
       public boolean canUse() {
           Player owner = this.superMom.getOwner();
           // Basic conditions: daytime, no monster target, cooldown ok, owner nearby/safe, needs crops, not going home
           if (!this.superMom.level().isDay() ||
               this.superMom.getTarget() instanceof Monster ||
               this.superMom.autonomousActionCooldown > 0 ||
               this.harvestCooldown > 0 ||
               owner == null ||
               this.superMom.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ ||
               (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) ||
               !this.superMom.needsCrops() ||
               (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive())) {
                if (this.harvestCooldown > 0) this.harvestCooldown--; // Decrease cooldown even if not usable
                return false;
           }
           // Use MoveToBlockGoal's logic to find a valid crop block
           if (!super.canUse()) { // This calls findNearestBlock() which uses isValidTarget()
                return false;
           }
           LOGGER.debug("ForageCropGoal.canUse: Found potential crop at {}", this.blockPos);
           return true;
       }

       @Override
       public boolean canContinueToUse() {
            Player owner = this.superMom.getOwner();
           // Stop conditions: nighttime, owner too far/unsafe, different monster target, going home
           if (!this.superMom.level().isDay() ||
               owner == null ||
               this.superMom.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ * 1.5 ||
               (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) ||
                this.superMom.getTarget() instanceof Monster ||
               (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive())) {
                 return false;
            }
           // Continue if block target is still valid and not stuck
           return super.canContinueToUse() && this.isValidTarget(this.mob.level(), this.blockPos);
       }

       @Override
       public void start() {
           LOGGER.debug("ForageCropGoal started. Target: {}", this.blockPos);
           super.start(); // Handles moving to the block
           this.harvestCooldown = 0;
           this.superMom.resetAutonomousCooldown();
       }

       @Override
       public void stop() {
           LOGGER.debug("ForageCropGoal stopped. Target was: {}", this.blockPos);
           super.stop(); // Stop navigation
           // Set cooldown after stopping (success or failure)
           this.harvestCooldown = MAX_HARVEST_COOLDOWN / 2 + this.mob.getRandom().nextInt(MAX_HARVEST_COOLDOWN / 2);
       }

       @Override
       public void tick() {
           super.tick(); // Handle movement and tryTicks countdown

           if (this.blockPos != null) {
                this.mob.getLookControl().setLookAt(this.blockPos.getX() + 0.5, this.blockPos.getY() + 0.5, this.blockPos.getZ() + 0.5, 10.0F, (float)this.mob.getMaxHeadXRot());
           }

           if (this.isReachedTarget()) {
                LOGGER.debug("ForageCropGoal: Reached crop position {}. Attempting harvest.", this.blockPos);
                tryHarvest();
           } else if (this.blockPos != null && !this.isValidTarget(this.mob.level(), this.blockPos)) { // Check blockPos not null
                 LOGGER.debug("ForageCropGoal: Target {} became invalid during approach. Stopping.", this.blockPos);
                 // Goal stops via canContinueToUse
           }
           if (this.harvestCooldown > 0) {
                this.harvestCooldown--;
           }
       }

       private void tryHarvest() {
           Level level = this.superMom.level();
           BlockPos targetPos = this.blockPos; // blockPos is updated by MoveToBlockGoal

           if (targetPos == null || !(level instanceof ServerLevel serverLevel)) {
                LOGGER.warn("ForageCropGoal.tryHarvest: Target position is null or level is not ServerLevel.");
                this.stop(); // Stop the goal if something is wrong
                return;
           }

           BlockState blockState = level.getBlockState(targetPos);
           if (this.isValidTarget(level, targetPos)) { // Double check validity
                LOGGER.debug("ForageCropGoal: Harvesting mature crop {} at {}.", blockState.getBlock().getDescriptionId(), targetPos);

                this.superMom.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, targetPos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);

                // Get drops before breaking
                List<ItemStack> drops = Block.getDrops(blockState, serverLevel, targetPos, level.getBlockEntity(targetPos), this.superMom, ItemStack.EMPTY);

                // Break block
                level.destroyBlock(targetPos, false, this.superMom); // false = don't drop items automatically

                // Spawn calculated drops
                for(ItemStack drop : drops) {
                    if (!drop.isEmpty()) {
                        ItemEntity itementity = new ItemEntity(level, targetPos.getX() + 0.5D, targetPos.getY() + 0.2D, targetPos.getZ() + 0.5D, drop.copy());
                        itementity.setPickUpDelay(5); // Short delay
                        level.addFreshEntity(itementity);
                        LOGGER.trace("ForageCropGoal: Spawned drop {}", drop.getDescriptionId());
                    }
                }
                // MoveToBlockGoal should stop naturally as the target block is gone
           } else {
                LOGGER.debug("ForageCropGoal.tryHarvest: Crop at {} is no longer valid when trying to harvest.", targetPos);
           }
           // Set cooldown and stop after attempting harvest
           this.harvestCooldown = MAX_HARVEST_COOLDOWN;
           this.stop();
       }

       /** Checks if the block is a mature, supported crop. */
       @Override
       protected boolean isValidTarget(LevelReader pLevel, BlockPos pPos) {
           BlockState blockstate = pLevel.getBlockState(pPos);
           Block block = blockstate.getBlock();
           if (block instanceof CropBlock cropBlock) {
                return cropBlock.isMaxAge(blockstate);
           }
           return false;
       }
    } // Fin ForageCropGoal


    // --- Goal: PickupNearbyItemsGoal --- (No changes needed here based on errors)
    class PickupNearbyItemsGoal extends Goal {

        private final SuperMomEntity mom;
        private final double speed;
        private final int radius; // Horizontal radius
        private ItemEntity target;
        private int timeToRecalcPath; // Timer for path recalculation

        public PickupNearbyItemsGoal(SuperMomEntity mom, double speed, int radius) {
            this.mom = mom;
            this.speed = speed;
            this.radius = radius;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        /* === CONDITIONS ==================================================== */

        @Override
        public boolean canUse() {
            // Don't activate if fighting or defending or going home
            if (mom.getTarget() instanceof Monster) return false;
            if (mom.ownerHurtByTargetGoal != null && mom.ownerHurtByTargetGoal.isActive) return false;
            if (mom.goHomeGoal != null && mom.goHomeGoal.isGoalActive()) return false;

            // Try to find a suitable item
            target = findItem();
            return target != null;
        }

        @Override
        public boolean canContinueToUse() {
            // Stop if fighting or defending or going home
            if (mom.getTarget() instanceof Monster) return false;
            if (mom.ownerHurtByTargetGoal != null && mom.ownerHurtByTargetGoal.isActive) return false;
            if (mom.goHomeGoal != null && mom.goHomeGoal.isGoalActive()) return false;

            // Continue if target exists, is alive, navigation is not done (or we are very close), and target is within reasonable range
            return target != null && target.isAlive()
                   && (!mom.getNavigation().isDone() || mom.distanceToSqr(target) < 2.25) // Continue if pathing or very close (1.5 blocks)
                   && mom.distanceToSqr(target) < (radius + 2.0) * (radius + 2.0);
        }

        /* === LOGIC ======================================================= */

        @Override
        public void start() {
            if (target != null) {
                 LOGGER.debug("PickupNearbyItemsGoal started. Target: {} at {}", target.getItem().getDescriptionId(), target.blockPosition());
                 timeToRecalcPath = 0;
                 mom.getNavigation().moveTo(target, speed); // Start moving towards the item
            } else {
                 LOGGER.warn("PickupNearbyItemsGoal started with null target!");
            }
        }

        @Override
        public void stop() {
             LOGGER.debug("PickupNearbyItemsGoal stopped. Target was: {}", target != null ? target.getItem().getDescriptionId() : "null");
             target = null;
             // Stop navigation only if the goal is explicitly stopped
             if (this.mom.goalSelector.getRunningGoals().anyMatch(g -> g.getGoal() == this)) {
                  mom.getNavigation().stop();
             }
        }

        @Override
        public void tick() {
            // If target is lost or picked up (no longer alive)
            if (target == null || !target.isAlive()) {
                LOGGER.trace("PickupNearbyItemsGoal: Target lost/picked up. Searching for new target.");
                target = findItem(); // Try finding a new one
                if (target != null) {
                    LOGGER.debug("PickupNearbyItemsGoal: Found new target: {} at {}", target.getItem().getDescriptionId(), target.blockPosition());
                    timeToRecalcPath = 0; // Reset path timer for new target
                    mom.getNavigation().moveTo(target, speed); // Move to new target
                } else {
                    // No new target, goal will stop via canContinueToUse
                    LOGGER.trace("PickupNearbyItemsGoal: No new target found.");
                }
                return; // Exit tick
            }

            // Look at the target
            mom.getLookControl().setLookAt(target, 30f, 30f);

            // Recalculate path periodically if not close
            if (mom.distanceToSqr(target) >= 2.25) { // If further than 1.5 blocks
                 if (--timeToRecalcPath <= 0) {
                     timeToRecalcPath = this.adjustedTickDelay(10);
                     // Ensure navigation is still targeting the correct item position if it moved
                     mom.getNavigation().moveTo(target, speed);
                 }
            } else {
                // If close enough, stop moving and let vanilla pickup happen
                 mom.getNavigation().stop();
                 LOGGER.trace("PickupNearbyItemsGoal: Reached target {}. Vanilla pickup should occur.", target.getItem().getDescriptionId());
                 // Vanilla pickup logic happens automatically due to proximity and setCanPickUpLoot(true)
            }
        }

        /* === SEARCH ===================================================== */

        @Nullable
        private ItemEntity findItem() {
            // Define search area
            AABB box = mom.getBoundingBox().inflate(radius, radius / 2.0, radius);

            // Define predicate for desired items
            Predicate<ItemEntity> wanted = it -> {
                // Basic checks: alive, not empty, pickup delay ok
                if (!it.isAlive() || it.getItem().isEmpty() || it.hasPickUpDelay()) return false;

                ItemStack stack = it.getItem();
                Item item = stack.getItem();

                // 1) Check for meat
                boolean isMeat = stack.isEdible() && stack.getFoodProperties(this.mom) != null && stack.getFoodProperties(this.mom).isMeat();

                // 2) Check for specific crops/seeds/bread
                boolean isCropOrBread = item == Items.WHEAT || item == Items.WHEAT_SEEDS ||
                                         item == Items.CARROT ||
                                         item == Items.POTATO ||
                                         item == Items.BEETROOT || item == Items.BEETROOT_SEEDS ||
                                         item == Items.BREAD;

                // Determine if the resource type is needed (or if it's bread)
                boolean needsResource = (isMeat && mom.needsMeat()) ||
                                         (isCropOrBread && item != Items.BREAD && mom.needsCrops()); // Check needsCrops only for actual crops/seeds

                // Pickup if it's bread, OR if it's a needed resource, AND there's inventory space AND line of sight
                return (item == Items.BREAD || needsResource)
                       && mom.inventory.canAddItem(stack)
                       && mom.hasLineOfSight(it); // Add line of sight check
            };

            // Find the closest matching item
            return mom.level()
                    .getEntitiesOfClass(ItemEntity.class, box, wanted)
                    .stream()
                    .min(Comparator.comparingDouble(mom::distanceToSqr)) // Find closest
                    .orElse(null); // Return null if none found
        }
    } // Fin PickupNearbyItemsGoal


    // --- Autonomous Goal for Checking Supplies --- (No changes needed here based on errors)
    class CheckSuppliesGoal extends Goal {
       private final SuperMomEntity superMom;
       private static final int CHECK_INTERVAL = 100; // Check slightly less often
       private int timeUntilNextCheck = 0;
       // No longer need foundNearbyEdible flag, just check conditions in canUse

       public CheckSuppliesGoal(SuperMomEntity mob) {
           this.superMom = mob;
           this.setFlags(EnumSet.noneOf(Goal.Flag.class)); // No flags needed, it's an instant check
           this.timeUntilNextCheck = mob.random.nextInt(CHECK_INTERVAL / 2);
       }

       @Override
       public boolean canUse() {
           // Basic conditions: no monster target, cooldown ok, not defending, not going home
           if (this.superMom.getTarget() instanceof Monster ||
               this.superMom.autonomousActionCooldown > 0 ||
               (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) ||
               (this.superMom.goHomeGoal != null && this.superMom.goHomeGoal.isGoalActive())) {
                return false;
           }
           // Don't run if the dedicated meat/crop pickup goal is running
           if (this.superMom.goalSelector.getRunningGoals().anyMatch(g -> g.getGoal() instanceof PickupNearbyItemsGoal)) {
                return false;
           }
           // Don't run if GatherFoodGoal is already running
           if (this.superMom.goalSelector.getRunningGoals().anyMatch(g -> g.getGoal() instanceof GatherFoodGoal)) {
                return false;
           }
           // Check interval timer
           if (--this.timeUntilNextCheck > 0) {
                return false;
           }
           this.timeUntilNextCheck = CHECK_INTERVAL + this.superMom.random.nextInt(CHECK_INTERVAL / 2);

           // Check for nearby non-meat/crop edible items
           ItemEntity nearbyFood = findNearbyNonMeatCropEdible();
           if (nearbyFood != null) {
                LOGGER.trace("CheckSuppliesGoal.canUse: Found nearby non-meat/crop edible item: {}", nearbyFood.getItem().getDescriptionId());
                // This goal returning true allows GatherFoodGoal to potentially activate
                return true;
           }

           return false;
       }

       @Override
       public boolean canContinueToUse() {
           return false; // Runs only once per activation check
       }

       @Override
       public void start() {
            // This goal doesn't "run". Its canUse just enables GatherFoodGoal.
            LOGGER.debug("CheckSuppliesGoal activated: Allowing GatherFoodGoal to potentially run.");
       }

       @Override
       public void stop() {
           // Nothing to do on stop
       }

       // Helper method to find edible items NOT handled by PickupNearbyItemsGoal
       @Nullable
       private ItemEntity findNearbyNonMeatCropEdible() {
           int searchRange = 8;
           AABB searchBox = this.superMom.getBoundingBox().inflate(searchRange, searchRange / 2.0, searchRange);
           Predicate<ItemEntity> edibleItemPredicate = entity -> {
               // Basic checks: alive, not empty, edible, pickup delay ok
               if (!entity.isAlive() || entity.hasPickUpDelay() || entity.getItem().isEmpty() || !entity.getItem().isEdible()) return false;

               ItemStack stack = entity.getItem(); // Get ItemStack
               Item item = stack.getItem();

               // Exclude meat
               if (stack.getFoodProperties(this.superMom) != null && stack.getFoodProperties(this.superMom).isMeat()) return false;
               // Exclude crops/bread handled by PickupNearbyItemsGoal
               if (item == Items.WHEAT || item == Items.WHEAT_SEEDS ||
                   item == Items.CARROT ||
                   item == Items.POTATO ||
                   item == Items.BEETROOT || item == Items.BEETROOT_SEEDS ||
                   item == Items.BREAD) return false;

               // Check line of sight and inventory space
               return this.superMom.hasLineOfSight(entity) &&
                      this.superMom.inventory.canAddItem(stack);
           };

           // Find the closest matching item
           return this.superMom.level().getEntitiesOfClass(ItemEntity.class, searchBox, edibleItemPredicate)
                .stream()
                .min(Comparator.comparingDouble(this.superMom::distanceToSqr))
                .orElse(null);
       }

    } // Fin CheckSuppliesGoal


    // --- Goal Registration ---
    @Override
    protected void registerGoals() {
        LOGGER.debug("Registering goals for SuperMomEntity");
        // Prio 0: Float
        this.goalSelector.addGoal(0, new FloatGoal(this));

        // Prio 1: Attack Monsters
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.25D, true) {
            @Override public boolean canUse() { return super.canUse() && this.mob.getTarget() instanceof Monster; }
            @Override public boolean canContinueToUse() { return super.canContinueToUse() && this.mob.getTarget() instanceof Monster; }
            @Override public void start() {
                LOGGER.debug("MeleeAttackGoal (Monster) started for target: {}", this.mob.getTarget() != null ? this.mob.getTarget().getName().getString() : "null");
                // Stop GoHomeGoal if attacking monster
                if (SuperMomEntity.this.goHomeGoal != null && SuperMomEntity.this.goHomeGoal.isGoalActive()) {
                    LOGGER.debug("Stopping GoHomeGoal because MeleeAttackGoal (Monster) started.");
                    SuperMomEntity.this.stopGoingHome(); // Use helper to stop and remove goal
                }
                super.start();
            }
            @Override public void stop() {
                LOGGER.debug("MeleeAttackGoal (Monster) stopped.");
                super.stop();
            }
        });

        // Prio 2 & 3: Player Assist
        this.goalSelector.addGoal(2, new HealPlayerGoal(this));
        this.goalSelector.addGoal(3, new FeedPlayerGoal(this));

        // Prio 4 & 5: Resource Production
        this.goalSelector.addGoal(4, new HuntForMeatGoal(this));
        this.goalSelector.addGoal(5, new ForageCropGoal(this, 1.0D, 16));

        // Prio 6 & 7: Item Collection
        this.goalSelector.addGoal(6, new PickupNearbyItemsGoal(this, 1.1D, 10)); // Higher prio for main resources
        this.goalSelector.addGoal(7, new GatherFoodGoal(this, 1.0D, 8)); // Lower prio for other edibles

        // Prio 8: Go Home (Instance created, but goal added/removed dynamically)
        this.goHomeGoal = new GoHomeGoal(this, 1.0D, 16);

        // Prio 9+: Idle/Default Behaviors
        this.goalSelector.addGoal(9, new FollowPlayerGoal(this, 1.0D, 10.0F, 3.0F));
        this.goalSelector.addGoal(10, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(11, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(12, new RandomLookAroundGoal(this));
        // Add CheckSuppliesGoal to allow GatherFoodGoal to activate
        this.goalSelector.addGoal(13, new CheckSuppliesGoal(this)); // Low priority check

        // --- Target Selectors ---
        LOGGER.debug("Registering target goals for SuperMomEntity");
        this.ownerHurtByTargetGoal = new OwnerHurtByTargetGoal(this); // Initialize reference
        this.targetSelector.addGoal(0, this.ownerHurtByTargetGoal); // Prio 0: Defend owner
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers()); // Prio 1: Retaliate

        // Prio 2: Target nearby monsters near owner
        final double hostileNearOwnerRange = 20.0;
        final double hostileNearOwnerRangeSq = hostileNearOwnerRange * hostileNearOwnerRange;
        Predicate<LivingEntity> hostileNearOwnerPredicate = (targetMonster) -> {
            if (!(targetMonster instanceof Monster)) {
                return false;
            }
            Player owner = this.getOwner();
            if (owner == null || !owner.isAlive()) return true; // Target any nearby monster if no owner/owner dead
            return targetMonster.distanceToSqr(owner) <= hostileNearOwnerRangeSq;
        };
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                this, Monster.class, 5, // targetChance
                false, // mustSee
                false, // mustReach
                hostileNearOwnerPredicate));

        LOGGER.debug("Goals registered.");
    }


    // --- finalizeSpawn Method --- (Unchanged)
    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor pLevel, DifficultyInstance pDifficulty, MobSpawnType pReason, @Nullable SpawnGroupData pSpawnData, @Nullable CompoundTag pDataTag) {
        pSpawnData = super.finalizeSpawn(pLevel, pDifficulty, pReason, pSpawnData, pDataTag);
        LOGGER.info("Finalizing spawn for SuperMom - Equipping sword and adding potions.");
        ItemStack superSword = new ItemStack(Items.NETHERITE_SWORD);
        superSword.enchant(Enchantments.SHARPNESS, 5);
        superSword.enchant(Enchantments.UNBREAKING, 3);
        superSword.enchant(Enchantments.MENDING, 1);
        superSword.enchant(Enchantments.SWEEPING_EDGE, 3);
        this.setItemSlot(EquipmentSlot.MAINHAND, superSword);
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        LOGGER.debug("Equipped main hand with: {}", superSword);

        int potionsAdded = 0;
        for (int i = 0; i < 5; i++) {
            ItemStack healingPotion = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING);
            if(this.inventory.canAddItem(healingPotion)) { this.inventory.addItem(healingPotion); potionsAdded++; } else break;
        }
        for (int i = 0; i < 3; i++) {
             ItemStack strongHealingPotion = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRONG_HEALING);
            if(this.inventory.canAddItem(strongHealingPotion)) { this.inventory.addItem(strongHealingPotion); potionsAdded++; } else break;
        }
        for (int i = 0; i < 3; i++) {
             ItemStack regenPotion = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.REGENERATION);
            if(this.inventory.canAddItem(regenPotion)) { this.inventory.addItem(regenPotion); potionsAdded++; } else break;
        }
        LOGGER.debug("Added {} total potions to inventory.", potionsAdded);
        return pSpawnData;
    }

    // --- Lifecycle Methods --- (Unchanged)
    @Override
    public void aiStep() {
        super.aiStep();

        if (this.autonomousActionCooldown > 0) {
            this.autonomousActionCooldown--;
        }

        LivingEntity owner = this.getOwner();
        if (owner != null && !this.isLeashed() && !this.isPassenger() && !this.isInWaterOrBubble()) {
            if (this.distanceToSqr(owner) > TELEPORT_DISTANCE_SQ && this.level() instanceof ServerLevel) {
                this.teleportToOwner();
            }
        }

        if (this.autonomousActionCooldown <= 0 && !(this.getTarget() instanceof Monster) && !this.isUsingItem()) {
            float healthPercent = this.getHealth() / this.getMaxHealth();
            if (healthPercent < AUTONOMOUS_HEAL_THRESHOLD_PERCENT && !this.hasEffect(MobEffects.HEAL) && !this.hasEffect(MobEffects.REGENERATION)) {
                if (this.findInInventory(stack -> stack.is(Items.POTION) && (PotionUtils.getPotion(stack) == Potions.HEALING || PotionUtils.getPotion(stack) == Potions.STRONG_HEALING)) != -1) {
                    this.selfHeal();
                    this.resetAutonomousCooldown();
                }
            }
        }
    }

    // --- Teleportation Helper --- (Unchanged)
    private void teleportToOwner() {
       LivingEntity owner = this.getOwner();
       if (owner == null || !(this.level() instanceof ServerLevel serverLevel)) return;

       BlockPos ownerPos = owner.blockPosition();
       for(int i = 0; i < 10; ++i) {
           int dx = this.random.nextInt(5) - 2;
           int dy = this.random.nextInt(3) - 1;
           int dz = this.random.nextInt(5) - 2;
           if (dx == 0 && dz == 0 && dy == 0) continue;

           BlockPos targetPos = ownerPos.offset(dx, dy, dz);
           if (canTeleportTo(serverLevel, targetPos)) {
                if (this.randomTeleport(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D, true)) {
                    LOGGER.debug("Teleporting SuperMom from {} to {} near owner {}", this.position(), Vec3.atCenterOf(targetPos), owner.getName().getString());
                    this.getNavigation().stop();
                    return;
                }
           }
       }
       LOGGER.debug("Failed to find suitable teleport location near owner {}", owner.getName().getString());
    }

    // Helper for teleport check (Unchanged)
    private boolean canTeleportTo(ServerLevel level, BlockPos targetPos) {
        BlockPos posBelow = targetPos.below();
        BlockState stateBelow = level.getBlockState(posBelow);
        return stateBelow.entityCanStandOn(level, posBelow, this) &&
               level.isEmptyBlock(targetPos) &&
               level.isEmptyBlock(targetPos.above());
    }


    private void resetAutonomousCooldown() { this.autonomousActionCooldown = AUTONOMOUS_ACTION_COOLDOWN_TICKS; }

    // --- Inventory Handling --- (Unchanged)
    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        ListTag inventoryTag = new ListTag();
        for (int i = 0; i < this.inventory.getContainerSize(); ++i) {
            ItemStack itemstack = this.inventory.getItem(i);
            if (!itemstack.isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putByte("Slot", (byte) i);
                itemstack.save(itemTag);
                inventoryTag.add(itemTag);
            }
        }
        compound.put("SuperMomInventory", inventoryTag);
        if (this.homePosition != null) {
            compound.putInt("HomePosX", this.homePosition.getX());
            compound.putInt("HomePosY", this.homePosition.getY());
            compound.putInt("HomePosZ", this.homePosition.getZ());
        }
        if (this.ownerUUID != null) {
            compound.putUUID("OwnerUUID", this.ownerUUID);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        this.inventory.clearContent();
        if (compound.contains("SuperMomInventory", 9)) {
            ListTag inventoryTag = compound.getList("SuperMomInventory", 10);
            for (int i = 0; i < inventoryTag.size(); ++i) {
                CompoundTag itemTag = inventoryTag.getCompound(i);
                int slot = itemTag.getByte("Slot") & 255;
                if (slot >= 0 && slot < this.inventory.getContainerSize()) {
                    this.inventory.setItem(slot, ItemStack.of(itemTag));
                }
            }
        }
        if (compound.contains("HomePosX") && compound.contains("HomePosY") && compound.contains("HomePosZ")) {
            this.homePosition = new BlockPos(compound.getInt("HomePosX"), compound.getInt("HomePosY"), compound.getInt("HomePosZ"));
            LOGGER.debug("Loaded home position: {}", this.homePosition);
        } else {
            this.homePosition = null;
        }
        if (compound.hasUUID("OwnerUUID")) {
            this.ownerUUID = compound.getUUID("OwnerUUID");
             LOGGER.debug("Loaded owner UUID: {}", this.ownerUUID);
        } else {
            this.ownerUUID = null;
        }
    }

    // Method to find an item in the inventory based on a predicate (Unchanged)
    private int findInInventory(Predicate<ItemStack> predicate) {
        for (int i = 0; i < inventory.getContainerSize(); ++i) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return i; // Return the slot index
            }
        }
        return -1; // Not found
    }

    // Method to drop an item (Unchanged)
    private void dropItem(ItemStack stack, boolean offset) {
        if (!stack.isEmpty() && !this.level().isClientSide) {
            Vec3 dropPos = this.position();
            if (offset) {
                Vec3 lookAngle = this.getLookAngle();
                dropPos = dropPos.add(lookAngle.x * 0.5, 0.25, lookAngle.z * 0.5);
            }
            ItemEntity itementity = new ItemEntity(this.level(), dropPos.x(), dropPos.y(), dropPos.z(), stack.copy()); // Drop a copy
            itementity.setDefaultPickUpDelay(); // Standard pickup delay
            this.level().addFreshEntity(itementity);
            LOGGER.debug("Dropped item: {}", stack.getDescriptionId());
        }
    }

    // --- Action Methods Called by LlmHelper OR Goals ---
    // (feedPlayer, healPlayer, attackMob unchanged)
    public void feedPlayer(Player player) {
        LOGGER.info("Action: Attempting to feed player {}", player.getName().getString());
        int foodSlot = findInInventory(ItemStack::isEdible);
        if (foodSlot != -1) {
            ItemStack foodStack = inventory.removeItem(foodSlot, 1); // Remove one item from the slot
            LOGGER.debug("Found food {} in slot {}, attempting to give to player.", foodStack.getDescriptionId(), foodSlot);
            if (!player.getInventory().add(foodStack)) {
                Vec3 dropPos = player.position().add(0, 0.25, 0);
                ItemEntity itementity = new ItemEntity(this.level(), dropPos.x(), dropPos.y(), dropPos.z(), foodStack);
                itementity.setPickUpDelay(10); // Short delay before player can pick up
                this.level().addFreshEntity(itementity);
                LOGGER.info("Dropped {} for player {} (inventory full?)", foodStack.getDescriptionId(), player.getName().getString());
            } else {
                LOGGER.info("Gave {} to player {}", foodStack.getDescriptionId(), player.getName().getString());
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.2F, ((player.getRandom().nextFloat() - player.getRandom().nextFloat()) * 0.7F + 1.0F) * 2.0F);
            }
        } else {
            LOGGER.warn("Action 'feedPlayer': No food found in inventory.");
        }
    }

    public void healPlayer(Player player) {
        LOGGER.info("Action: Attempting to heal player {}", player.getName().getString());
        int potionSlot = findInInventory(stack -> stack.is(Items.POTION) && (PotionUtils.getPotion(stack) == Potions.HEALING || PotionUtils.getPotion(stack) == Potions.STRONG_HEALING));
        if (potionSlot != -1) {
            ItemStack potionStack = inventory.getItem(potionSlot);
            LOGGER.debug("Found healing potion {} in slot {}, attempting to use on player.", potionStack.getDescriptionId(), potionSlot);
            List<MobEffectInstance> effects = PotionUtils.getMobEffects(potionStack);
            if (!effects.isEmpty()) {
                for(MobEffectInstance effectinstance : effects) {
                    player.addEffect(new MobEffectInstance(effectinstance.getEffect(), 1, effectinstance.getAmplifier()));
                }
                inventory.setItem(potionSlot, new ItemStack(Items.GLASS_BOTTLE)); // Replace potion with bottle
                LOGGER.info("Used healing potion on player {}", player.getName().getString());
                level().playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 1.0F, level().random.nextFloat() * 0.1F + 0.9F);
            } else {
                LOGGER.warn("Potion {} had no effects?", potionStack.getDescriptionId());
                inventory.setItem(potionSlot, new ItemStack(Items.GLASS_BOTTLE)); // Still replace with bottle
            }
        } else {
            LOGGER.warn("Action 'healPlayer': No healing potion found in inventory.");
        }
    }

    public void attackMob(LivingEntity target) {
        stopGoingHome(); // Stop going home if ordered to attack
        LOGGER.info("Action: Setting attack target to {}", target.getName().getString());
        this.setTarget(target);
    }

    public void followPlayer(Player player) {
        LOGGER.info("Action: Ensuring following player {}", player.getName().getString());
        stopGoingHome(); // Stop going home if ordered to follow

        // Clear non-monster target if not defending, allowing FollowPlayerGoal to take over
        if ((this.ownerHurtByTargetGoal == null || !this.ownerHurtByTargetGoal.isActive) && !(this.getTarget() instanceof Monster)) {
            this.setTarget(null);
            LOGGER.debug("Cleared non-monster attack target, relying on FollowPlayerGoal.");
        } else {
            LOGGER.debug("Not clearing target, OwnerHurtByTargetGoal is active or target is a Monster.");
        }
    }

    // Helper method to stop and remove the GoHomeGoal
    private void stopGoingHome() {
        if (this.goHomeGoal != null && this.goHomeGoal.isGoalActive()) {
            LOGGER.debug("Stopping and removing GoHomeGoal.");
            this.goHomeGoal.stop(); // Stop the goal's execution
            this.goalSelector.removeGoal(this.goHomeGoal); // Remove it from the selector
        }
    }

    public void goHome() {
        LOGGER.info("Action: Attempting to go home");
        if (this.homePosition != null && this.goHomeGoal != null) {
            LOGGER.debug("Activating GoHomeGoal to navigate to: {}", this.homePosition);
            this.setTarget(null); // Clear attack target when going home

            // Check if the goal is already added and running
            boolean alreadyRunning = this.goHomeGoal.isGoalActive(); // Use helper

            if (!alreadyRunning) {
                 // Add the goal with priority 8. Use stream() for noneMatch check.
                 LOGGER.debug("Checking if GoHomeGoal needs to be added to selector...");
                 // FIX: Use stream() for noneMatch
                 if(this.goalSelector.getAvailableGoals().stream().noneMatch(g -> g.getGoal() == this.goHomeGoal)) {
                     LOGGER.debug("Adding GoHomeGoal to active goals with priority 8.");
                     this.goalSelector.addGoal(8, this.goHomeGoal); // Add with priority 8
                 } else {
                     LOGGER.debug("GoHomeGoal already present in selector.");
                 }
                 // Start the goal explicitly after ensuring it's added
                 this.goHomeGoal.start();
            } else {
                 // If already running but maybe path failed, try moving again
                 LOGGER.debug("GoHomeGoal already active, ensuring path calculation.");
                 this.goHomeGoal.moveMobToBlock();
            }
        } else {
            LOGGER.warn("Action 'goHome' called but homePosition or goHomeGoal is not set/initialized.");
        }
    }

    public void setHomePosition(BlockPos pos) {
        BlockPos oldPos = this.homePosition;
        this.homePosition = pos;
        LOGGER.info("SuperMom home position set to: {}", pos);

        // If GoHomeGoal is currently active and the position actually changed, stop and restart it
        if (this.goHomeGoal != null && this.goHomeGoal.isGoalActive() && !pos.equals(oldPos)) {
             LOGGER.debug("Restarting active GoHomeGoal due to home position change.");
             // FIX: Stop and restart the goal instead of accessing protected field
             this.stopGoingHome(); // Stop and remove the current instance
             this.goHome(); // Call goHome to add and start a new instance targeting the new position
        }
    }

    @Nullable public BlockPos getHomePosition() { return this.homePosition; }
    public void setOwnerUUID(@Nullable java.util.UUID ownerUUID) { this.ownerUUID = ownerUUID; LOGGER.debug("Set owner UUID to: {}", ownerUUID); }
    @Nullable public java.util.UUID getOwnerUUID() { return this.ownerUUID; }

    @Nullable public Player getOwner() {
        try {
            UUID uuid = this.getOwnerUUID();
            return uuid == null || this.level() == null ? null : this.level().getPlayerByUUID(uuid);
        } catch (IllegalArgumentException illegalargumentexception) {
            return null;
        }
    }

    // (selfHeal, gatherFood, huntForMeat, forageCrops, prepareSupplies unchanged)
     public void selfHeal() {
        LOGGER.info("Action: Attempting to self-heal");
        if (this.getHealth() < this.getMaxHealth()) {
            int potionSlot = findInInventory(stack -> stack.is(Items.POTION) && (PotionUtils.getPotion(stack) == Potions.HEALING || PotionUtils.getPotion(stack) == Potions.STRONG_HEALING));
            if (potionSlot != -1) {
                ItemStack potionStack = inventory.getItem(potionSlot);
                LOGGER.debug("Found healing potion {} in slot {}, attempting to use on self.", potionStack.getDescriptionId(), potionSlot);
                List<MobEffectInstance> effects = PotionUtils.getMobEffects(potionStack);
                if (!effects.isEmpty()) {
                    for(MobEffectInstance effectinstance : effects) {
                        this.addEffect(new MobEffectInstance(effectinstance.getEffect(), 1, effectinstance.getAmplifier()));
                    }
                    inventory.setItem(potionSlot, new ItemStack(Items.GLASS_BOTTLE)); // Replace potion with bottle
                    LOGGER.info("Used healing potion on self.");
                    level().playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 1.0F, level().random.nextFloat() * 0.1F + 0.9F);
                } else {
                    LOGGER.warn("Self-heal potion {} had no effects?", potionStack.getDescriptionId());
                    inventory.setItem(potionSlot, new ItemStack(Items.GLASS_BOTTLE)); // Still replace with bottle
                }
            } else {
                LOGGER.warn("Action 'selfHeal': No healing potion found in inventory.");
            }
        } else {
            LOGGER.info("Action 'selfHeal': Already at full health.");
        }
    }

    public void gatherFood() {
        LOGGER.info("Action: Attempting to gather nearby non-meat/crop edible food");
        stopGoingHome(); // Stop going home if ordered to gather

        if (!(this.getTarget() instanceof Monster) && (this.ownerHurtByTargetGoal == null || !this.ownerHurtByTargetGoal.isActive)) {
            this.setTarget(null);
        }
        LOGGER.debug("GatherFood action requested. Goal state managed by selector. Resetting autonomous cooldown.");
        this.resetAutonomousCooldown();
    }

    public void huntForMeat() { LOGGER.warn("huntForMeat() called directly, should be handled by HuntForMeatGoal"); }
    public void forageCrops() { LOGGER.warn("forageCrops() called directly, should be handled by ForageCropGoal"); }
    public void prepareSupplies() { LOGGER.info("Action: prepareSupplies (Not Implemented)"); LOGGER.warn("Action 'prepareSupplies' not fully implemented."); }

    // --- Vanilla Pickup Overrides ---
    @Override
    public boolean canPickUpLoot() {
        return true;
    }

    // FIX: Change access modifier to public
    @Override
    public boolean wantsToPickUp(ItemStack pStack) {
        Item item = pStack.getItem();

        // 1) Check for meat
        boolean isMeat = pStack.isEdible() && pStack.getFoodProperties(this) != null && pStack.getFoodProperties(this).isMeat();
        if (isMeat && this.needsMeat()) {
            return this.inventory.canAddItem(pStack);
        }

        // 2) Check for specific crops/seeds/bread
        boolean isCropOrBread = item == Items.WHEAT || item == Items.WHEAT_SEEDS ||
                                 item == Items.CARROT ||
                                 item == Items.POTATO ||
                                 item == Items.BEETROOT || item == Items.BEETROOT_SEEDS ||
                                 item == Items.BREAD;
        if (isCropOrBread) {
             if (item == Items.BREAD) return this.inventory.canAddItem(pStack); // Bread always if space
             if (this.needsCrops()) return this.inventory.canAddItem(pStack); // Crops/seeds if needed and space
        }

        // 3) Check for non-meat/crop edibles
        if (pStack.isEdible() && !isMeat && !isCropOrBread) {
             return this.inventory.canAddItem(pStack);
        }

        // Don't pick up other items by default
        return false;
    }

    @Override
    protected void pickUpItem(ItemEntity pItemEntity) {
        ItemStack itemstack = pItemEntity.getItem();
        if (this.wantsToPickUp(itemstack)) {
            ItemStack remainder = this.inventory.addItem(itemstack); // Add to custom inventory
            if (remainder.isEmpty()) {
                this.take(pItemEntity, itemstack.getCount()); // Entity "takes" the item
                pItemEntity.discard(); // Remove the item entity from the world
                LOGGER.trace("Picked up {} via pickUpItem", itemstack.getDescriptionId());
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.2F, ((this.random.nextFloat() - this.random.nextFloat()) * 0.7F + 1.0F) * 2.0F);
            } else {
                // Update the item entity stack if only partially picked up
                itemstack.setCount(remainder.getCount());
                LOGGER.trace("Partially picked up {} via pickUpItem, {} remaining", itemstack.getDescriptionId(), remainder.getCount());
                // Play sound even on partial pickup? Maybe.
                 this.level().playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.2F, ((this.random.nextFloat() - this.random.nextFloat()) * 0.7F + 1.0F) * 2.0F);

            }
        }
        // Do not call super.pickUpItem here
    }


} // Fin SuperMomEntity class