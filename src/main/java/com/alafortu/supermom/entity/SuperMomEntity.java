package com.alafortu.supermom.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantments; // Import pour finalizeSpawn
import net.minecraft.world.item.enchantment.EnchantmentHelper; // Import pour finalizeSpawn
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.DifficultyInstance; // Import pour finalizeSpawn
import net.minecraft.world.level.ServerLevelAccessor; // CORRECT
import net.minecraft.world.level.block.state.BlockState; // Added import for ForageCropGoal
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.alafortu.supermom.SuperMomMod;
import com.alafortu.supermom.entity.goal.OwnerHurtByTargetGoal; // Added import

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.function.Predicate; // Added import
import java.util.UUID; // Added for owner tracking

public class SuperMomEntity extends PathfinderMob {

    public static final Logger LOGGER = LogManager.getLogger(SuperMomMod.MODID + ".SuperMomEntity"); // Made public for access from Goals
    private static final double TELEPORT_DISTANCE_SQ = 625.0D; // 
    private static final int INVENTORY_SIZE = 18;
    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);

    @Nullable
    private BlockPos homePosition = null;
    private GoHomeGoal goHomeGoal;
    private GatherFoodGoal gatherFoodGoal;

    @Nullable // Peut être null si spawnée autrement (ex: commande)
    private java.util.UUID ownerUUID = null; // Added import in next step

    // --- Autonomous Action Fields ---
    private static final float AUTONOMOUS_HEAL_THRESHOLD_PERCENT = 0.6f;
    private static final int AUTONOMOUS_ACTION_COOLDOWN_TICKS = 100;
    private int autonomousActionCooldown = 0;

    public SuperMomEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        LOGGER.debug("SuperMomEntity created in level: {}", level.dimension().location());
        this.setCanPickUpLoot(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1000.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.35D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.ATTACK_DAMAGE, 10.0D) // Dégâts de base, l'épée ajoutera
                .add(Attributes.ARMOR, 10.0D);
    }

    // --- Goal Definitions (Inner Classes) ---
    // (Vos classes internes GatherFoodGoal, FollowPlayerGoal, GoHomeGoal, CheckSuppliesGoal restent ici)
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
             boolean isActive = this.superMom.goalSelector.getRunningGoals().anyMatch(goal -> goal.getGoal() == this);
             if (!isActive) {
                 return false;
             }
             this.targetFoodItem = findNearestEdibleItem();
             return this.targetFoodItem != null;
         }

         @Override
         public boolean canContinueToUse() {
            boolean isActive = this.superMom.goalSelector.getRunningGoals().anyMatch(goal -> goal.getGoal() == this);
            return isActive && this.targetFoodItem != null && this.targetFoodItem.isAlive() && !this.superMom.getNavigation().isDone();
         }

         @Override
         public void start() {
            this.timeToRecalcPath = 0;
            if (this.targetFoodItem != null) {
                SuperMomEntity.LOGGER.debug("GatherFoodGoal started. Target: {} at {}", this.targetFoodItem.getItem().getDisplayName().getString(), this.targetFoodItem.blockPosition());
                this.superMom.getNavigation().moveTo(this.targetFoodItem, this.speedModifier);
            } else {
                 SuperMomEntity.LOGGER.warn("GatherFoodGoal started but targetFoodItem is null!");
            }
         }

         @Override
         public void stop() {
            SuperMomEntity.LOGGER.debug("GatherFoodGoal stopped. Target Item: {}", this.targetFoodItem != null ? this.targetFoodItem.getItem().getDisplayName().getString() : "null");
            this.targetFoodItem = null;
            this.superMom.getNavigation().stop();
            this.superMom.goalSelector.removeGoal(this);
            SuperMomEntity.LOGGER.debug("Removed GatherFoodGoal from active goals.");
         }

         @Override
         public void tick() {
            if (this.targetFoodItem != null && this.targetFoodItem.isAlive()) {
                this.superMom.getLookControl().setLookAt(this.targetFoodItem, 10.0F, (float) this.superMom.getMaxHeadXRot());
                if (--this.timeToRecalcPath <= 0) {
                    this.timeToRecalcPath = this.adjustedTickDelay(10);
                    if (this.superMom.distanceToSqr(this.targetFoodItem) > 1.5D) {
                         this.superMom.getNavigation().moveTo(this.targetFoodItem, this.speedModifier);
                    } else {
                         this.superMom.getNavigation().stop();
                    }
                }
            } else {
                this.targetFoodItem = findNearestEdibleItem();
                if (this.targetFoodItem != null) {
                    SuperMomEntity.LOGGER.debug("GatherFoodGoal found new target: {}", this.targetFoodItem.getItem().getDisplayName().getString());
                    this.start();
                } else {
                    SuperMomEntity.LOGGER.debug("GatherFoodGoal: No more food items found nearby.");
                }
            }
         }

         @Nullable
         private ItemEntity findNearestEdibleItem() {
            Predicate<ItemEntity> edibleItemPredicate = entity ->
                entity.isAlive() &&
                !entity.getItem().isEmpty() &&
                entity.getItem().isEdible() &&
                this.superMom.hasLineOfSight(entity) &&
                this.superMom.inventory.canAddItem(entity.getItem());

            java.util.List<ItemEntity> nearbyItems = this.superMom.level().getEntitiesOfClass(
                ItemEntity.class,
                this.superMom.getBoundingBox().inflate(this.searchRange, this.searchRange / 2.0, this.searchRange),
                e -> true
            );

            ItemEntity closestItem = null;
            double closestDistSq = Double.MAX_VALUE;

            for (ItemEntity itemEntity : nearbyItems) {
                if (edibleItemPredicate.test(itemEntity)) {
                    double distSq = this.superMom.distanceToSqr(itemEntity);
                    if (distSq < closestDistSq) {
                        closestDistSq = distSq;
                        closestItem = itemEntity;
                    }
                }
            }
            return closestItem;
         }
     } // Fin GatherFoodGoal

      // --- Helper Class for FollowPlayerGoal ---
     static class FollowPlayerGoal extends Goal {
         private final SuperMomEntity superMom;
         private final double speedModifier;
         private final float stopDistanceSq;
         private final float startDistanceSq;
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
             if (this.superMom.getTarget() != null) {
                 return false;
             }
             // Utiliser Math.sqrt() ici car getNearestPlayer attend une distance, pas une distance au carré
             this.targetPlayer = this.superMom.level().getNearestPlayer(this.superMom, Math.sqrt(this.startDistanceSq));
              return this.targetPlayer != null && !this.targetPlayer.isSpectator();
         }

         @Override
         public boolean canContinueToUse() {
              if (this.superMom.getTarget() != null || this.targetPlayer == null || this.targetPlayer.isSpectator()) {
                  return false;
              }
              if (!this.targetPlayer.isAlive()) {
                  return false;
              }
              double distSq = this.superMom.distanceToSqr(this.targetPlayer);
              return !(distSq < this.stopDistanceSq) && distSq <= (this.startDistanceSq * 4.0D);
         }

         @Override
         public void start() {
             this.timeToRecalcPath = 0;
             if (targetPlayer != null) {
                  LOGGER.debug("Starting FollowPlayerGoal for {}", targetPlayer.getName().getString());
             }
         }

         @Override
         public void stop() {
             this.targetPlayer = null;
             this.navigation.stop();
             LOGGER.debug("Stopping FollowPlayerGoal");
         }

         @Override
         public void tick() {
             if (this.targetPlayer != null) {
                 this.superMom.getLookControl().setLookAt(this.targetPlayer, 10.0F, (float)this.superMom.getMaxHeadXRot());
                 if (--this.timeToRecalcPath <= 0) {
                     this.timeToRecalcPath = this.adjustedTickDelay(10);
                     if (this.superMom.distanceToSqr(this.targetPlayer) > (this.stopDistanceSq + 1.0D)) {
                          this.navigation.moveTo(this.targetPlayer, this.speedModifier);
                     } else {
                          this.navigation.stop();
                     }
                 }
             }
         }
     } // Fin FollowPlayerGoal

      // --- Goal for navigating to the home position ---
     class GoHomeGoal extends MoveToBlockGoal {
         private final SuperMomEntity superMom;
         private boolean pathFound = false;
         private static final double MIN_DISTANCE_SQ = 4.0D;

         public GoHomeGoal(SuperMomEntity mob, double speedModifier, int searchRange) {
              super(mob, speedModifier, searchRange, 1);
              this.superMom = mob;
              this.setFlags(EnumSet.of(Goal.Flag.MOVE));
         }

         @Override
         public boolean canUse() {
              if (this.superMom.getHomePosition() == null || !this.isFarFromHome()) {
                  return false;
              }
              boolean isActive = this.superMom.goalSelector.getRunningGoals().anyMatch(goal -> goal.getGoal() == this);
              if (!isActive) {
                  return false;
              }
              this.pathFound = this.findNearestBlock();
              return this.pathFound;
         }

         @Override
         public boolean canContinueToUse() {
              boolean isActive = this.superMom.goalSelector.getRunningGoals().anyMatch(goal -> goal.getGoal() == this);
              return isActive && this.superMom.getHomePosition() != null && this.blockPos != null && !this.isReachedTarget() && this.isValidTarget(this.superMom.level(), this.blockPos);
         }

         @Override
         public void start() {
              BlockPos home = this.superMom.getHomePosition();
              if (home != null) {
                  this.blockPos = home;
                  SuperMomEntity.LOGGER.debug("GoHomeGoal started. Target: {}", this.blockPos);
                  this.pathFound = false;
                  this.moveMobToBlock();
                  super.start();
              } else {
                   SuperMomEntity.LOGGER.warn("GoHomeGoal started but homePosition is null!");
              }
         }

         @Override
         public void stop() {
              SuperMomEntity.LOGGER.debug("GoHomeGoal stopped. Reached Target: {}", this.isReachedTarget());
              super.stop();
              this.pathFound = false;
              // Utilisation de try-catch au cas où removeGoal causerait une ConcurrentModificationException
              try {
                  this.superMom.goalSelector.removeGoal(this);
                  SuperMomEntity.LOGGER.debug("Removed GoHomeGoal from active goals.");
              } catch (Exception e) {
                  SuperMomEntity.LOGGER.error("Error removing GoHomeGoal", e);
              }
         }

          @Override
          public void tick() {
              BlockPos currentHome = this.superMom.getHomePosition();
              if (currentHome != null && this.blockPos != null && !this.blockPos.equals(currentHome)) {
                  SuperMomEntity.LOGGER.debug("GoHomeGoal detected home position change. Updating target.");
                  this.blockPos = currentHome;
                  this.moveMobToBlock();
              }
              super.tick();
          }

         private boolean isFarFromHome() {
             BlockPos home = this.superMom.getHomePosition();
             if (home == null) return false;
             return this.superMom.position().distanceToSqr(Vec3.atCenterOf(home)) > MIN_DISTANCE_SQ;
         }

         @Override
         protected boolean isValidTarget(LevelReader level, BlockPos pos) {
              BlockPos home = this.superMom.getHomePosition();
              return home != null && pos.equals(home);
         }

         @Override
         protected void moveMobToBlock() {
              BlockPos home = this.superMom.getHomePosition();
              if (home != null) {
                  this.blockPos = home;
                  Path path = this.mob.getNavigation().createPath(this.blockPos, 0);
                  if (path != null && path.canReach()) {
                      this.mob.getNavigation().moveTo(path, this.speedModifier);
                      this.pathFound = true;
                  } else {
                      this.pathFound = false;
                      SuperMomEntity.LOGGER.warn("GoHomeGoal.moveMobToBlock: Cannot create path to {}", this.blockPos);
                  }
              }
         }

          @Override
          public boolean isReachedTarget() {
               return super.isReachedTarget();
          }

          public boolean isActive() {
               return this.superMom.goalSelector.getRunningGoals().anyMatch(goal -> goal.getGoal() == this);
          }
     } // Fin GoHomeGoal

     // --- Autonomous Goal for Checking Supplies ---
      class CheckSuppliesGoal extends Goal {
         private final SuperMomEntity superMom;
         private static final int CHECK_INTERVAL = 200;
         private int timeUntilNextCheck = 0;
         private static final int MIN_MEAT_THRESHOLD = 4; // Ex: Minimum morceaux de viande crue/cuite
         private static final int MIN_CROP_THRESHOLD = 8; // Ex: Minimum carottes, patates, blé etc.
         // MIN_FOOD_THRESHOLD peut rester comme fallback pour ramasser n'importe quoi si tout est bas

         private boolean needsMeat = false;
         private boolean needsCrops = false;

         public CheckSuppliesGoal(SuperMomEntity mob) {
             this.superMom = mob;
             this.setFlags(EnumSet.of(Goal.Flag.LOOK));
             this.timeUntilNextCheck = mob.random.nextInt(CHECK_INTERVAL / 2);
         }

         @Override
         public boolean canUse() {
             // Ne pas vérifier les provisions si SuperMom a une cible, est en cooldown, ou s'il fait nuit
             if (this.superMom.getTarget() != null || this.superMom.autonomousActionCooldown > 0 || !this.superMom.level().isDay()) {
                 return false;
             }
             if (--this.timeUntilNextCheck > 0) {
                  return false;
             }
             this.timeUntilNextCheck = CHECK_INTERVAL + this.superMom.random.nextInt(CHECK_INTERVAL / 2);

             int currentMeat = countMeatItems();
             int currentCrops = countCropItems();

             this.needsMeat = currentMeat < MIN_MEAT_THRESHOLD;
             this.needsCrops = currentCrops < MIN_CROP_THRESHOLD;

             if (this.needsMeat) {
                 LOGGER.trace("CheckSuppliesGoal.canUse: Meat count = {}, Threshold = {}. Needs Meat.", currentMeat, MIN_MEAT_THRESHOLD);
                 return true; // Priorité à la viande si besoin
             }
             if (this.needsCrops) {
                 LOGGER.trace("CheckSuppliesGoal.canUse: Crop count = {}, Threshold = {}. Needs Crops.", currentCrops, MIN_CROP_THRESHOLD);
                 return true;
             }

             // Fallback: si on manque de nourriture générale (peut-être utile pour ramasser du pain, etc.)
             // int currentFoodCount = countEdibleItems();
             // boolean foodLow = currentFoodCount < MIN_FOOD_THRESHOLD;
             // if (foodLow) {
             //     LOGGER.trace("CheckSuppliesGoal.canUse: General food count low.");
             //     return true;
             // }

             LOGGER.trace("CheckSuppliesGoal.canUse: Supply levels sufficient (Meat: {}, Crops: {}).", currentMeat, currentCrops);
             return false; // Pas besoin de chercher activement si les stocks spécifiques sont bons
         }

         @Override
         public boolean canContinueToUse() {
             return false; // Run once
         }

         @Override
         public void start() {
             LOGGER.debug("CheckSuppliesGoal started: Checking which supplies are needed.");

             if (this.needsMeat) {
                 LOGGER.info("CheckSuppliesGoal: Meat level is low. Triggering huntForMeat.");
                 this.superMom.huntForMeat(); // Nouvelle méthode à ajouter à SuperMomEntity
                 // resetAutonomousCooldown sera appelé dans huntForMeat() ou forageCrops()
             } else if (this.needsCrops) {
                 LOGGER.info("CheckSuppliesGoal: Crop level is low. Triggering forageCrops.");
                 this.superMom.forageCrops(); // Nouvelle méthode à ajouter à SuperMomEntity
             // } else {
                 // Fallback si on utilisait MIN_FOOD_THRESHOLD
                 // LOGGER.info("CheckSuppliesGoal: General food level low. Triggering pickupLooseFood.");
                 // this.superMom.pickupLooseFood(); // Renommer gatherFood() éventuellement
             } else {
                 // Ne devrait pas arriver ici si canUse a retourné true, mais sécurité
                 LOGGER.warn("CheckSuppliesGoal started but needsMeat and needsCrops are false?");
             }
         }

         @Override
         public void stop() {
             LOGGER.trace("CheckSuppliesGoal stopped.");
         }

         // Compte tous les items comestibles
         private int countEdibleItems() {
             int count = 0;
             Container inv = this.superMom.inventory;
            for (int i = 0; i < inv.getContainerSize(); ++i) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && stack.isEdible()) {
                    count += stack.getCount();
                }
            }
             return count;
         }

         // Compte spécifiquement les viandes crues ou cuites
         private int countMeatItems() {
             int count = 0;
             Container inv = this.superMom.inventory;
             for (int i = 0; i < inv.getContainerSize(); ++i) {
                 ItemStack stack = inv.getItem(i);
                 if (!stack.isEmpty() && stack.getItem().isEdible() && stack.getItem().getFoodProperties() != null && stack.getItem().getFoodProperties().isMeat()) {
                      // Vérifie si c'est de la viande (cru ou cuit)
                      // Ex: Items.BEEF, Items.COOKED_BEEF, Items.PORKCHOP, Items.COOKED_PORKCHOP, etc.
                     count += stack.getCount();
                 }
             }
             return count;
         }

         // Compte spécifiquement les récoltes (légumes, blé)
         private int countCropItems() {
             int count = 0;
             Container inv = this.superMom.inventory;
             for (int i = 0; i < inv.getContainerSize(); ++i) {
                 ItemStack stack = inv.getItem(i);
                 // Ajouter ici les items spécifiques aux récoltes qu'on veut compter
                 if (!stack.isEmpty() && (
                     stack.is(Items.WHEAT) ||
                     stack.is(Items.CARROT) ||
                     stack.is(Items.POTATO) ||
                     stack.is(Items.BEETROOT)
                     // Ajouter Items.BREAD? Ou seulement les ingrédients bruts?
                 )) {
                     count += stack.getCount();
                 }
             }
             return count;
         }
     } // Fin CheckSuppliesGoal

     // --- Goal for Feeding the Player ---
     class FeedPlayerGoal extends Goal {
         private final SuperMomEntity superMom;
         private Player targetPlayer;
         private int timeToRecalcPath;
         private static final double REACH_DISTANCE_SQ = 4.0D; // Carré de 2 blocs

         public FeedPlayerGoal(SuperMomEntity mob) {
             this.superMom = mob;
             this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
         }

         @Override
         public boolean canUse() {
             this.targetPlayer = this.superMom.getOwner();
             if (this.targetPlayer == null || !this.targetPlayer.isAlive() || !this.targetPlayer.getFoodData().needsFood()) {
                 return false;
             }
             if (this.superMom.distanceToSqr(this.targetPlayer) > 64.0D) { // Ne pas activer si trop loin (carré de 8 blocs)
                 return false;
             }
             // Vérifier si SuperMom a de la nourriture
             return this.superMom.findInInventory(ItemStack::isEdible) != -1;
         }

         @Override
         public boolean canContinueToUse() {
             return this.targetPlayer != null
                    && this.targetPlayer.isAlive()
                    && this.targetPlayer.getFoodData().needsFood() // Continuer tant que le joueur a faim
                    && this.superMom.findInInventory(ItemStack::isEdible) != -1 // Et que SuperMom a de la nourriture
                    && !this.superMom.getNavigation().isDone() // Et qu'on n'est pas arrivé
                    && this.superMom.distanceToSqr(this.targetPlayer) <= 144.0D; // Ne pas continuer si trop loin (carré de 12 blocs)
         }

         @Override
         public void start() {
             LOGGER.debug("FeedPlayerGoal started for {}", this.targetPlayer.getName().getString());
             this.timeToRecalcPath = 0;
             this.superMom.getNavigation().moveTo(this.targetPlayer, 1.0D);
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

             this.superMom.getLookControl().setLookAt(this.targetPlayer, 10.0F, (float) this.superMom.getMaxHeadXRot());

             if (--this.timeToRecalcPath <= 0) {
                 this.timeToRecalcPath = this.adjustedTickDelay(10);
                 this.superMom.getNavigation().moveTo(this.targetPlayer, 1.0D);
             }

             // Si assez proche, essayer de nourrir
             if (this.superMom.distanceToSqr(this.targetPlayer) < REACH_DISTANCE_SQ) {
                 LOGGER.debug("FeedPlayerGoal: Reached player {}, attempting to feed.", this.targetPlayer.getName().getString());
                 this.superMom.feedPlayer(this.targetPlayer);
                 // Le goal s'arrêtera car canContinueToUse deviendra faux si le joueur n'a plus faim ou si SuperMom n'a plus de nourriture
             }
         }
     } // Fin FeedPlayerGoal

     // --- Goal for Healing the Player ---
      class HealPlayerGoal extends Goal {
         private final SuperMomEntity superMom;
         private Player targetPlayer;
         private int timeToRecalcPath;
         private static final double REACH_DISTANCE_SQ = 4.0D; // Carré de 2 blocs
         private static final float HEAL_THRESHOLD_PERCENT = 0.8f; // Soigner si vie < 80%

         public HealPlayerGoal(SuperMomEntity mob) {
             this.superMom = mob;
             this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
         }

         @Override
         public boolean canUse() {
             this.targetPlayer = this.superMom.getOwner();
             if (this.targetPlayer == null || !this.targetPlayer.isAlive() || this.targetPlayer.getHealth() >= this.targetPlayer.getMaxHealth() * HEAL_THRESHOLD_PERCENT) {
                 return false;
             }
             if (this.superMom.distanceToSqr(this.targetPlayer) > 64.0D) { // Ne pas activer si trop loin (carré de 8 blocs)
                 return false;
             }
             // Vérifier si SuperMom a une potion de soin
             return this.superMom.findInInventory(stack -> stack.is(Items.POTION) && (PotionUtils.getPotion(stack) == Potions.HEALING || PotionUtils.getPotion(stack) == Potions.STRONG_HEALING)) != -1;
         }

         @Override
         public boolean canContinueToUse() {
             return this.targetPlayer != null
                    && this.targetPlayer.isAlive()
                    && this.targetPlayer.getHealth() < this.targetPlayer.getMaxHealth() * HEAL_THRESHOLD_PERCENT // Continuer tant que le joueur a besoin de soin
                    && this.superMom.findInInventory(stack -> stack.is(Items.POTION) && (PotionUtils.getPotion(stack) == Potions.HEALING || PotionUtils.getPotion(stack) == Potions.STRONG_HEALING)) != -1 // Et que SuperMom a une potion
                    && !this.superMom.getNavigation().isDone() // Et qu'on n'est pas arrivé
                    && this.superMom.distanceToSqr(this.targetPlayer) <= 144.0D; // Ne pas continuer si trop loin (carré de 12 blocs)
         }

         @Override
         public void start() {
             LOGGER.debug("HealPlayerGoal started for {}", this.targetPlayer.getName().getString());
             this.timeToRecalcPath = 0;
             this.superMom.getNavigation().moveTo(this.targetPlayer, 1.0D);
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

             this.superMom.getLookControl().setLookAt(this.targetPlayer, 10.0F, (float) this.superMom.getMaxHeadXRot());

             if (--this.timeToRecalcPath <= 0) {
                 this.timeToRecalcPath = this.adjustedTickDelay(10);
                 this.superMom.getNavigation().moveTo(this.targetPlayer, 1.0D);
             }

             // Si assez proche, essayer de soigner
             if (this.superMom.distanceToSqr(this.targetPlayer) < REACH_DISTANCE_SQ) {
                  LOGGER.debug("HealPlayerGoal: Reached player {}, attempting to heal.", this.targetPlayer.getName().getString());
                 this.superMom.healPlayer(this.targetPlayer);
                 // Le goal s'arrêtera car canContinueToUse deviendra faux si le joueur n'a plus besoin de soin ou si SuperMom n'a plus de potion
             }
         }
     } // Fin HealPlayerGoal

     // --- Goal for Hunting Passive Animals ---
     class HuntForMeatGoal extends Goal {
         private final SuperMomEntity superMom;
         private LivingEntity targetAnimal;
         private final TargetingConditions huntTargeting = TargetingConditions.forCombat().range(16.0D).ignoreLineOfSight().selector((entity) -> {
             // Cibler seulement les animaux passifs communs pour la viande
             return entity instanceof net.minecraft.world.entity.animal.Cow ||
                    entity instanceof net.minecraft.world.entity.animal.Pig ||
                    entity instanceof net.minecraft.world.entity.animal.Sheep ||
                    entity instanceof net.minecraft.world.entity.animal.Chicken;
             // TODO: Ajouter d'autres animaux si désiré (lapins, etc.)
         });

         public HuntForMeatGoal(SuperMomEntity mob) {
             this.superMom = mob;
             this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK)); // MOVE est nécessaire pour trouver la cible
         }

         @Override
         public boolean canUse() {
             // Activer seulement le jour, si pas de cible hostile, et si le cooldown autonome est passé
             if (!this.superMom.level().isDay() || this.superMom.getTarget() != null || this.superMom.autonomousActionCooldown > 0) {
                 return false;
             }
             // TODO: Ajouter une vérification de l'inventaire pour voir si on a besoin de viande

             // Trouver l'animal le plus proche
             this.targetAnimal = this.superMom.level().getNearestEntity(
                 this.superMom.level().getEntitiesOfClass(LivingEntity.class, this.superMom.getBoundingBox().inflate(16.0D), e -> true), // Cherche tous les LivingEntity autour
                 this.huntTargeting, // Applique notre filtre pour animaux passifs
                 this.superMom, // Le mob qui cherche
                 this.superMom.getX(), this.superMom.getY(), this.superMom.getZ() // Position de recherche
             );

             if (this.targetAnimal == null) {
                 // LOGGER.trace("HuntForMeatGoal: No suitable animal found nearby.");
                 return false;
             }

             LOGGER.debug("HuntForMeatGoal: Found target animal: {}", this.targetAnimal.getType().getDescription().getString());
             return true;
         }

         @Override
         public boolean canContinueToUse() {
             // Continuer si la cible est valide et vivante, et si SuperMom n'a pas de cible hostile prioritaire
             return this.targetAnimal != null
                    && this.targetAnimal.isAlive()
                    && this.superMom.getTarget() == null // Arrêter si une cible hostile apparaît
                    && !this.superMom.getNavigation().isDone(); // Arrêter si on ne peut pas atteindre? (Peut-être pas nécessaire si MeleeAttackGoal gère)
         }

         @Override
         public void start() {
             LOGGER.debug("HuntForMeatGoal started. Targeting: {}", this.targetAnimal.getType().getDescription().getString());
             this.superMom.setTarget(this.targetAnimal); // Définir comme cible pour MeleeAttackGoal
             this.superMom.getNavigation().moveTo(this.targetAnimal, 1.2D); // Commencer à bouger vers la cible (MeleeAttackGoal prendra le relais)
             this.superMom.resetAutonomousCooldown(); // Mettre un cooldown après avoir initié une chasse
         }

         @Override
         public void stop() {
             LOGGER.debug("HuntForMeatGoal stopped. Target was: {}", this.targetAnimal != null ? this.targetAnimal.getType().getDescription().getString() : "null");
             // Si la cible de SuperMom est toujours l'animal qu'on chassait, l'effacer.
             // Ne pas effacer si SuperMom a changé de cible pour une menace.
             if (this.superMom.getTarget() == this.targetAnimal) {
                 this.superMom.setTarget(null);
             }
             this.targetAnimal = null;
             // Pas besoin d'arrêter la navigation ici, MeleeAttackGoal ou d'autres goals géreront.
         }

         @Override
         public void tick() {
             // Le travail principal (attaque) est géré par MeleeAttackGoal car nous avons défini la cible.
             // On pourrait ajouter une logique ici si MeleeAttackGoal ne suffit pas (ex: si l'animal s'enfuit trop loin)
             if (this.targetAnimal != null && this.targetAnimal.isAlive()) {
                 this.superMom.getLookControl().setLookAt(this.targetAnimal, 30.0F, 30.0F);
                 // Si la cible actuelle de SuperMom n'est plus notre animal (ex: une menace est apparue), arrêter ce goal.
                 if (this.superMom.getTarget() != this.targetAnimal) {
                     // Ce cas est normalement géré par canContinueToUse, mais double sécurité.
                     // this.stop(); // canContinueToUse devrait gérer ça
                 } else if (this.superMom.distanceToSqr(this.targetAnimal) > 256.0D) { // Si l'animal est trop loin (16*16)
                     LOGGER.debug("HuntForMeatGoal: Target animal too far away, stopping hunt.");
                     // this.stop(); // canContinueToUse devrait gérer ça via !isDone? Non, il faut l'arrêter explicitement.
                     // On ne peut pas appeler stop() directement dans tick(). On retourne false dans canContinueToUse implicitement? Non.
                     // La meilleure façon est de laisser MeleeAttackGoal gérer la poursuite. Si elle échoue, elle s'arrêtera.
                 }
             }
         }
     } // Fin HuntForMeatGoal

     // --- Goal for Foraging Mature Crops ---
     class ForageCropGoal extends MoveToBlockGoal {
         private final SuperMomEntity superMom;
         private boolean wantsToHarvest;
         private int harvestCooldown = 0;
         private static final int MAX_HARVEST_COOLDOWN = 40; // Cooldown between harvests

         public ForageCropGoal(SuperMomEntity mob, double speedModifier, int searchRange) {
             super(mob, speedModifier, searchRange, 6); // searchVerticalRange = 6
             this.superMom = mob;
             this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
         }

         @Override
         public boolean canUse() {
             // Activer seulement le jour, si pas de cible hostile, et si le cooldown autonome est passé
             if (!this.superMom.level().isDay() || this.superMom.getTarget() != null || this.superMom.autonomousActionCooldown > 0) {
                 return false;
             }
             if (this.harvestCooldown > 0) {
                 this.harvestCooldown--;
                 return false;
             }
             // TODO: Ajouter une vérification de l'inventaire pour voir si on a besoin de récoltes

             // Utiliser la logique de MoveToBlockGoal pour trouver un bloc cible potentiel
             if (!super.canUse()) {
                 // LOGGER.trace("ForageCropGoal: super.canUse() returned false.");
                 return false;
             }

             // Vérifier si le bloc trouvé est bien une culture mûre
             if (this.isValidTarget(this.mob.level(), this.blockPos)) {
                 LOGGER.debug("ForageCropGoal: Found potential crop at {}", this.blockPos);
                 this.wantsToHarvest = true;
                 return true;
             } else {
                 // LOGGER.trace("ForageCropGoal: Found block {} is not a valid mature crop.", this.blockPos);
                 return false;
             }
         }

         @Override
         public boolean canContinueToUse() {
             // Continuer si on veut toujours récolter, si le bloc est toujours valide, et si pas de cible hostile
             return this.wantsToHarvest
                    && this.blockPos != null
                    && this.isValidTarget(this.mob.level(), this.blockPos)
                    && this.superMom.getTarget() == null
                    && super.canContinueToUse(); // Utilise la logique de distance de MoveToBlockGoal
         }

         @Override
         public void start() {
             LOGGER.debug("ForageCropGoal started. Target: {}", this.blockPos);
             super.start(); // Commence la navigation via MoveToBlockGoal
             this.harvestCooldown = 0; // Reset cooldown au début
         }

         @Override
         public void stop() {
             LOGGER.debug("ForageCropGoal stopped. Target was: {}", this.blockPos);
             super.stop();
             this.wantsToHarvest = false;
             // Mettre un cooldown seulement si on a effectivement récolté? Ou toujours? Mettons toujours pour l'instant.
             this.harvestCooldown = MAX_HARVEST_COOLDOWN / 2 + this.mob.getRandom().nextInt(MAX_HARVEST_COOLDOWN / 2);
             this.superMom.resetAutonomousCooldown(); // Mettre aussi le cooldown général autonome
         }

         @Override
         public void tick() {
             super.tick(); // Gère le mouvement et le regard vers this.blockPos

             // Si on est arrivé à destination (très proche du bloc)
             if (this.isReachedTarget()) {
                 Level level = this.mob.level();
                 BlockPos targetPos = this.blockPos.above(); // La position où on se tient pour récolter

                 if (level.getBlockState(this.blockPos).is(this.mob.level().getBlockState(this.blockPos).getBlock())) { // Vérifie si le bloc est toujours là
                     LOGGER.debug("ForageCropGoal: Reached crop at {}. Attempting harvest.", this.blockPos);
                     // Casser le bloc de culture
                     level.destroyBlock(this.blockPos, true, this.superMom); // true = drop items, this.superMom = l'entité qui casse (pour les events)
                     // Les items droppés devraient être ramassés par GatherFoodGoal si activé

                     this.wantsToHarvest = false; // Arrêter ce goal après la récolte
                     this.harvestCooldown = MAX_HARVEST_COOLDOWN; // Mettre le cooldown spécifique à la récolte
                     // stop() sera appelé automatiquement car canContinueToUse deviendra faux
                 } else {
                      LOGGER.debug("ForageCropGoal: Crop at {} disappeared before harvest.", this.blockPos);
                      this.wantsToHarvest = false; // Arrêter si le bloc a disparu
                 }
             }
         }

         /**
          * Vérifie si le bloc à la position donnée est une culture mûre récoltable.
          */
         @Override
         protected boolean isValidTarget(LevelReader pLevel, BlockPos pPos) {
             BlockState blockstate = pLevel.getBlockState(pPos);
             // Vérifier Blé (Wheat)
             if (blockstate.is(net.minecraft.world.level.block.Blocks.WHEAT)) {
                 return blockstate.getValue(net.minecraft.world.level.block.CropBlock.AGE) >= net.minecraft.world.level.block.CropBlock.MAX_AGE;
             }
             // Vérifier Carottes (Carrots)
             if (blockstate.is(net.minecraft.world.level.block.Blocks.CARROTS)) {
                 return blockstate.getValue(net.minecraft.world.level.block.CropBlock.AGE) >= net.minecraft.world.level.block.CropBlock.MAX_AGE;
             }
             // Vérifier Pommes de terre (Potatoes)
             if (blockstate.is(net.minecraft.world.level.block.Blocks.POTATOES)) {
                 return blockstate.getValue(net.minecraft.world.level.block.CropBlock.AGE) >= net.minecraft.world.level.block.CropBlock.MAX_AGE;
             }
             // Vérifier Betteraves (Beetroots)
             if (blockstate.is(net.minecraft.world.level.block.Blocks.BEETROOTS)) {
                  return blockstate.getValue(net.minecraft.world.level.block.BeetrootBlock.AGE) >= net.minecraft.world.level.block.BeetrootBlock.MAX_AGE;
             }
             // TODO: Ajouter d'autres cultures si désiré (Nether Wart, Cacao, etc.)

             return false; // Pas une culture mûre supportée
         }
 
         // NOTE: getDesiredDistanceToTarget() n'est pas une méthode de MoveToBlockGoal à surcharger.
         // La distance est gérée par la logique interne de MoveToBlockGoal.
 
     } // Fin ForageCropGoal


    // --- Goal Registration ---
    @Override
    protected void registerGoals() {
        LOGGER.debug("Registering goals for SuperMomEntity");
        this.goalSelector.addGoal(0, new FloatGoal(this)); // Prio 0: Nager
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.0D, true)); // Prio 1: Attaquer la cible actuelle
        // Prio 2: Goals activés par des actions spécifiques (GoHome, GatherFood) - ajoutés dynamiquement, pas ici

        // Prio 3 & 4: Assistance au joueur
        this.goalSelector.addGoal(3, new HealPlayerGoal(this)); // Prio 3: Soigner le joueur si besoin
        this.goalSelector.addGoal(4, new FeedPlayerGoal(this)); // Prio 4: Nourrir le joueur si besoin

        // Prio 5 & 6: Collecte active de ressources (activée par CheckSuppliesGoal via huntForMeat/forageCrops)
        this.goalSelector.addGoal(5, new HuntForMeatGoal(this)); // Prio 5: Chasser pour la viande
        this.goalSelector.addGoal(6, new ForageCropGoal(this, 1.0D, 16)); // Prio 6: Récolter des cultures

        // Initialisation des goals spécifiques (ne sont pas ajoutés ici, mais activés par les actions)
        this.goHomeGoal = new GoHomeGoal(this, 1.0D, 16);
        this.gatherFoodGoal = new GatherFoodGoal(this, 1.0D, 16);

        // Mouvement & Regard (priorités plus basses)
        this.goalSelector.addGoal(7, new FollowPlayerGoal(this, 1.0D, 10.0F, 2.0F)); // Prio 7: Suivre joueur si rien d'autre à faire
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 1.0D)); // Prio 8: Se balader
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0F)); // Prio 9: Regarder joueur
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this)); // Prio 10: Regarder autour
        this.goalSelector.addGoal(11, new CheckSuppliesGoal(this)); // Prio 11: Vérifier inventaire (basse prio, activé par timer)


        // --- Target Goals (Qui attaquer ?) ---
        LOGGER.debug("Registering target goals for SuperMomEntity");

        // **NOUVEAU**: Défendre le propriétaire (Priorité la plus haute!)
        this.targetSelector.addGoal(0, new OwnerHurtByTargetGoal(this)); // Prio 0

        // Riposter si SuperMom est attaquée (Priorité juste après la défense du joueur)
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers()); // Prio 1

        // **MODIFIÉ**: Chasser les monstres, MAIS seulement s'ils sont près du joueur
        // Définir la distance maximale entre le monstre et le joueur pour la chasse
        final double huntNearOwnerRange = 16.0; // 16 blocs autour du joueur (ajustable)
        final double huntNearOwnerRangeSq = huntNearOwnerRange * huntNearOwnerRange; // Utiliser distance au carré pour performance

        // Prédicat pour vérifier la distance au propriétaire
        Predicate<LivingEntity> nearOwnerPredicate = (targetMonster) -> {
            Player owner = this.getOwner();
            // SI PAS DE PROPRIETAIRE: Toujours autoriser la cible (comportement par défaut)
            if (owner == null) {
                return true;
            }
            // SI PROPRIETAIRE EXISTE: Vérifier la distance au propriétaire
            // Ne cibler que si le monstre est assez proche du propriétaire
            return targetMonster.distanceToSqr(owner) <= huntNearOwnerRangeSq;
        };

        // Ajout du NearestAttackableTargetGoal AVEC le prédicat de proximité
        // Priorité plus basse que la défense et la riposte
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                this,                               // Le mob qui utilise le goal
                Monster.class,                      // La classe de cible (tous les monstres)
                5,                                 // Chance de revérifier la cible (1 toutes les 5 ticks) - ajustable
                true,                              // Doit voir la cible ? (true = oui)
                false,                             // Doit pouvoir atteindre sans obstacle spécial ? (false = non, tente même si chemin complexe)
                nearOwnerPredicate                  // NOTRE PRÉDICAT: Ne cible que si près du joueur !
        )); // Prio 2

        LOGGER.debug("Goals registered.");
    }

    // --- finalizeSpawn Method ---
    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor pLevel, DifficultyInstance pDifficulty, MobSpawnType pReason, @Nullable SpawnGroupData pSpawnData, @Nullable CompoundTag pDataTag) {
        // Appel à la méthode parente (important)
        pSpawnData = super.finalizeSpawn(pLevel, pDifficulty, pReason, pSpawnData, pDataTag);

        LOGGER.info("Finalizing spawn for SuperMom - Equipping sword.");

        // Créer l'item stack pour l'épée
        ItemStack superSword = new ItemStack(Items.NETHERITE_SWORD);

        // Ajouter des enchantements puissants
        superSword.enchant(Enchantments.SHARPNESS, 5); // Tranchant V
        superSword.enchant(Enchantments.UNBREAKING, 3); // Solidité III
        superSword.enchant(Enchantments.MENDING, 1); // Raccommodage I
        superSword.enchant(Enchantments.SWEEPING_EDGE, 3); // Balayage III

        // Équiper l'épée dans la main principale
        this.setItemSlot(EquipmentSlot.MAINHAND, superSword);
        LOGGER.debug("Equipped main hand with: {}", superSword);

        // Mettre les chances de drop à 0 pour qu'elle ne la perde pas en mourant
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);

        // Ajouter une variété de potions initiales à l'inventaire
        LOGGER.info("Adding initial variety of potions to inventory.");
        int potionsAdded = 0;

        // 5 Potions de Soin Standard
        for (int i = 0; i < 5; i++) {
            this.inventory.addItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING));
            potionsAdded++;
        }

        // 3 Potions de Soin Puissant
        for (int i = 0; i < 3; i++) {
            this.inventory.addItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRONG_HEALING));
            potionsAdded++;
        }

        // 3 Potions de Régénération
        for (int i = 0; i < 3; i++) {
            this.inventory.addItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.REGENERATION));
            potionsAdded++;
        }

        LOGGER.debug("Added {} total potions to inventory.", potionsAdded);

        return pSpawnData;
    }

    // --- Lifecycle Methods ---
    @Override
    public void aiStep() {
        super.aiStep();

        if (this.autonomousActionCooldown > 0) {
            this.autonomousActionCooldown--;
        }

        // --- Teleportation Logic ---
        LivingEntity owner = this.getOwner();
        // Check if owner exists, is player, not leashed, not riding, and SuperMom is not in water/bubble
        if (owner != null && owner instanceof Player && !this.isLeashed() && !this.isPassenger() && !this.isInWaterOrBubble()) {
            if (this.distanceToSqr(owner) > TELEPORT_DISTANCE_SQ) {
                 // Attempt teleport only if far enough and conditions met
                 this.teleportToOwner();
            }
        }
        // --- End Teleportation Logic ---

        if (this.autonomousActionCooldown <= 0 && this.getTarget() == null) {
            boolean actionTaken = false;
            float healthPercent = this.getHealth() / this.getMaxHealth();
            if (healthPercent < AUTONOMOUS_HEAL_THRESHOLD_PERCENT && !this.hasEffect(MobEffects.HEAL) && !this.hasEffect(MobEffects.REGENERATION)) {
                LOGGER.debug("Autonomous Check: Low health detected ({}%). Attempting self-heal.", String.format("%.2f", healthPercent * 100));
                if (this.findInInventory(stack -> stack.is(Items.POTION) && (PotionUtils.getPotion(stack) == Potions.HEALING || PotionUtils.getPotion(stack) == Potions.STRONG_HEALING)) != -1) {
                    this.selfHeal();
                    this.resetAutonomousCooldown();
                    actionTaken = true;
                } else {
                     LOGGER.debug("Autonomous Check: Low health but no healing potion found.");
                }
            }
        }
    }

// --- Teleportation Helper ---
private void teleportToOwner() {
    LivingEntity owner = this.getOwner();
    if (owner == null) {
        return; // Safety check
    }

    BlockPos ownerPos = owner.blockPosition();
    for(int i = 0; i < 10; ++i) { // Try 10 times to find a spot
        int dx = this.random.nextInt(5) - 2; // -2 to +2 blocks horizontally
        int dy = this.random.nextInt(3) - 1; // -1 to +1 blocks vertically
        int dz = this.random.nextInt(5) - 2; // -2 to +2 blocks horizontally

        // Ensure we don't try to teleport exactly onto the owner or into the ground directly below
        if (Math.abs(dx) < 2 && Math.abs(dz) < 2 && Math.abs(dy) < 1) {
             if (dx == 0 && dz == 0 && dy <= 0) continue; // Avoid same spot or directly below
        }


        BlockPos targetPos = ownerPos.offset(dx, dy, dz);
        Level level = this.level();
        BlockPos posBelow = targetPos.below();
        BlockState stateBelow = level.getBlockState(posBelow);

        // Check for safe landing: solid ground below, empty space at target and head level
        if (stateBelow.entityCanStandOn(level, posBelow, this) && level.noCollision(this, this.getBoundingBox().move(targetPos.getX() + 0.5D - this.getX(), targetPos.getY() - this.getY(), targetPos.getZ() + 0.5D - this.getZ()))) {
             // Use randomTeleport for better safety checks (prevents teleporting into blocks)
             if (this.randomTeleport(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D, true)) {
                 LOGGER.debug("Teleporting SuperMom from {} to {} near owner {}", this.position(), Vec3.atCenterOf(targetPos), owner.getName().getString());
                 this.getNavigation().stop(); // Stop any current pathfinding after teleport
                 // Optional: Add a sound effect or particle effect here
                 // level.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENDERMAN_TELEPORT, this.getSoundSource(), 1.0F, 1.0F);
                 return; // Success
             }
        }
    }
    LOGGER.debug("Failed to find suitable teleport location near owner {}", owner.getName().getString());
}

    private void resetAutonomousCooldown() {
        this.autonomousActionCooldown = AUTONOMOUS_ACTION_COOLDOWN_TICKS;
    }

    // --- Inventory Handling ---
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

        // Sauvegarder l'UUID du propriétaire
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

        // Charger l'UUID du propriétaire
        if (compound.hasUUID("OwnerUUID")) {
            this.ownerUUID = compound.getUUID("OwnerUUID");
        } else {
            this.ownerUUID = null; // S'assurer qu'il est null si non trouvé
        }
    }

    // Helper pour trouver item dans inventaire
    private int findInInventory(Predicate<ItemStack> predicate) {
        for (int i = 0; i < inventory.getContainerSize(); ++i) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    // Helper pour jeter item
    private void dropItem(ItemStack stack, boolean offset) {
        if (!stack.isEmpty() && !this.level().isClientSide) {
            Vec3 dropPos = this.position();
            if (offset) {
                Vec3 lookAngle = this.getLookAngle();
                dropPos = dropPos.add(lookAngle.x * 0.5, 0.25, lookAngle.z * 0.5);
            }
            ItemEntity itementity = new ItemEntity(this.level(), dropPos.x(), dropPos.y(), dropPos.z(), stack.copy());
            itementity.setDefaultPickUpDelay();
            this.level().addFreshEntity(itementity);
            LOGGER.debug("Dropped item: {}", stack);
        }
    }

    // --- Action Methods Called by LlmHelper ---
    public void feedPlayer(Player player) {
        LOGGER.info("Action: Attempting to feed player {}", player.getName().getString());
        int foodSlot = findInInventory(ItemStack::isEdible);
        if (foodSlot != -1) {
            ItemStack foodStack = inventory.removeItem(foodSlot, 1);
            LOGGER.debug("Found food {} in slot {}, attempting to give to player.", foodStack.getItem(), foodSlot);
            Vec3 dropPos = player.position().add(0, 0.25, 0);
             ItemEntity itementity = new ItemEntity(this.level(), dropPos.x(), dropPos.y(), dropPos.z(), foodStack);
             itementity.setPickUpDelay(10);
             this.level().addFreshEntity(itementity);
             LOGGER.info("Dropped {} for player {}", foodStack.getDisplayName().getString(), player.getName().getString());
        } else {
            LOGGER.warn("Action 'feedPlayer': No food found in inventory.");
        }
    }

    public void healPlayer(Player player) {
         LOGGER.info("Action: Attempting to heal player {}", player.getName().getString());
         // Correction parenthèses pour la condition OR
         int potionSlot = findInInventory(stack -> stack.is(Items.POTION) && (PotionUtils.getPotion(stack) == Potions.HEALING || PotionUtils.getPotion(stack) == Potions.STRONG_HEALING));
         if (potionSlot != -1) {
             ItemStack potionStack = inventory.getItem(potionSlot);
             LOGGER.debug("Found healing potion {} in slot {}, attempting to use on player.", potionStack.getDisplayName().getString(), potionSlot);
             int amplifier = (PotionUtils.getPotion(potionStack) == Potions.STRONG_HEALING) ? 1 : 0;
             player.addEffect(new MobEffectInstance(MobEffects.HEAL, 1, amplifier));
             inventory.setItem(potionSlot, new ItemStack(Items.GLASS_BOTTLE));
             LOGGER.info("Used healing potion on player {}", player.getName().getString());
         } else {
             LOGGER.warn("Action 'healPlayer': No healing potion found in inventory.");
         }
    }

    public void attackMob(LivingEntity target) {
        if (this.goHomeGoal != null && this.goHomeGoal.isActive()) { // Ajouter vérification nullité
             LOGGER.debug("Deactivating GoHomeGoal due to attack command.");
             this.goHomeGoal.stop();
        }
        LOGGER.info("Action: Setting attack target to {}", target.getName().getString());
        this.setTarget(target);
    }

    public void followPlayer(Player player) {
         LOGGER.info("Action: Ensuring following player {}", player.getName().getString());
         if (this.goHomeGoal != null && this.goHomeGoal.isActive()) { // Ajouter vérification nullité
              LOGGER.debug("Deactivating GoHomeGoal due to follow command.");
              this.goHomeGoal.stop();
         }
         this.setTarget(null);
         LOGGER.debug("Cleared attack target, relying on FollowPlayerGoal.");
    }

    public void goHome() {
         LOGGER.info("Action: Attempting to go home");
         if (this.homePosition != null && this.goHomeGoal != null) { // Ajouter vérification nullité
             LOGGER.debug("Activating GoHomeGoal to navigate to: {}", this.homePosition);
             this.setTarget(null);
             if (this.goalSelector.getRunningGoals().noneMatch(goal -> goal.getGoal() == this.goHomeGoal)) { // Correction lambda
                  LOGGER.debug("Adding GoHomeGoal to active goals.");
                  this.goalSelector.addGoal(2, this.goHomeGoal);
             }
             if (!this.goHomeGoal.isActive()) {
                 this.goHomeGoal.start();
             }
              this.navigation.moveTo(this.homePosition.getX() + 0.5D, this.homePosition.getY(), this.homePosition.getZ() + 0.5D, 1.0D);
         } else {
             LOGGER.warn("Action 'goHome' called but homePosition or goHomeGoal is not set/initialized.");
         }
    }

    public void setHomePosition(BlockPos pos) {
        this.homePosition = pos;
        LOGGER.info("SuperMom home position set to: {}", pos);
    }

    @Nullable
    public BlockPos getHomePosition() {
        return this.homePosition;
    }

    // --- Owner UUID Methods ---
    public void setOwnerUUID(@Nullable java.util.UUID ownerUUID) { // Added import in next step
        this.ownerUUID = ownerUUID;
        LOGGER.debug("Set owner UUID to: {}", ownerUUID);
    }

    @Nullable
    public java.util.UUID getOwnerUUID() { // Added import in next step
        return this.ownerUUID;
    }

    @Nullable
    public Player getOwner() {
        try {
            UUID ownerUUID = this.getOwnerUUID(); // Utilise la méthode que vous avez déjà
            return ownerUUID == null ? null : this.level().getPlayerByUUID(ownerUUID);
        } catch (IllegalArgumentException illegalargumentexception) {
            return null;
        }
    }
    // --- End Owner UUID Methods ---

    public void selfHeal() {
         LOGGER.info("Action: Attempting to self-heal");
         if (this.getHealth() < this.getMaxHealth()) {
             // Correction parenthèses pour la condition OR
             int potionSlot = findInInventory(stack -> stack.is(Items.POTION) && (PotionUtils.getPotion(stack) == Potions.HEALING || PotionUtils.getPotion(stack) == Potions.STRONG_HEALING));
             if (potionSlot != -1) {
                 ItemStack potionStack = inventory.getItem(potionSlot);
                 LOGGER.debug("Found healing potion {} in slot {}, attempting to use on self.", potionStack.getDisplayName().getString(), potionSlot);
                 int amplifier = (PotionUtils.getPotion(potionStack) == Potions.STRONG_HEALING) ? 1 : 0;
                 this.addEffect(new MobEffectInstance(MobEffects.HEAL, 1, amplifier));
                 inventory.setItem(potionSlot, new ItemStack(Items.GLASS_BOTTLE));
                 LOGGER.info("Used healing potion on self.");
             } else {
                 LOGGER.warn("Action 'selfHeal': No healing potion found in inventory.");
             }
         } else {
              LOGGER.info("Action 'selfHeal': Already at full health.");
         }
    }

    public void gatherFood() {
         LOGGER.info("Action: Attempting to gather food");
         this.setTarget(null);
         if (this.goHomeGoal != null && this.goHomeGoal.isActive()) { // Ajouter vérification nullité
              LOGGER.debug("Deactivating GoHomeGoal due to gatherFood command.");
              this.goHomeGoal.stop();
         }

         if (this.gatherFoodGoal != null && this.goalSelector.getRunningGoals().noneMatch(goal -> goal.getGoal() == this.gatherFoodGoal)) { // Ajouter vérification nullité
              LOGGER.debug("Adding GatherFoodGoal to active goals. Selector will manage execution.");
              this.goalSelector.addGoal(2, this.gatherFoodGoal);
         } else if (this.gatherFoodGoal == null) {
             LOGGER.warn("gatherFood called but gatherFoodGoal is null!");
         }
          else {
              LOGGER.debug("GatherFoodGoal is already added or running.");
         }
         LOGGER.debug("GatherFood action requested. Goal state managed by selector.");
     }
 
     // Nouvelle méthode pour déclencher la chasse
     public void huntForMeat() {
         LOGGER.info("Action: Attempting to hunt for meat");
         this.setTarget(null); // Assurer qu'on n'attaque pas autre chose
         if (this.goHomeGoal != null && this.goHomeGoal.isActive()) {
              LOGGER.debug("Deactivating GoHomeGoal due to huntForMeat command.");
              this.goHomeGoal.stop();
         }
         // Activer HuntForMeatGoal - Le goal lui-même trouvera une cible
         // On suppose que HuntForMeatGoal est déjà enregistré dans registerGoals
         // Il suffit de s'assurer qu'il peut s'exécuter (pas de cooldown, jour, etc.)
         // Le CheckSuppliesGoal a déjà vérifié ces conditions avant d'appeler cette méthode.
         // On pourrait forcer son activation si nécessaire, mais laissons le sélecteur gérer pour l'instant.
         LOGGER.debug("HuntForMeat action requested. Goal state managed by selector.");
         // Potentiellement, on pourrait ajouter un flag ou une logique pour prioriser ce goal si besoin.
         this.resetAutonomousCooldown(); // Mettre le cooldown après avoir décidé de chasser
     }
 
     // Nouvelle méthode pour déclencher la récolte
     public void forageCrops() {
         LOGGER.info("Action: Attempting to forage crops");
         this.setTarget(null); // Assurer qu'on n'attaque pas
         if (this.goHomeGoal != null && this.goHomeGoal.isActive()) {
              LOGGER.debug("Deactivating GoHomeGoal due to forageCrops command.");
              this.goHomeGoal.stop();
         }
         // Activer ForageCropGoal - Le goal lui-même trouvera une cible
         // On suppose que ForageCropGoal est déjà enregistré dans registerGoals
         LOGGER.debug("ForageCrops action requested. Goal state managed by selector.");
         this.resetAutonomousCooldown(); // Mettre le cooldown après avoir décidé de récolter
     }
 
 
     public void prepareSupplies() {
         LOGGER.info("Action: prepareSupplies (Not Implemented)");
         LOGGER.warn("Action 'prepareSupplies' not fully implemented.");
    }

} // Fin SuperMomEntity class