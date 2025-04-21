package com.alafortu.supermom.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel; // Added for ForageCropGoal block breaking
import net.minecraft.sounds.SoundEvents; // Added for ForageCropGoal sound
import net.minecraft.sounds.SoundSource; // Added for ForageCropGoal sound
import net.minecraft.world.Container;
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
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item; // Added for ForageCropGoal drop check
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
import net.minecraft.world.level.block.Block; // Added for ForageCropGoal target check
import net.minecraft.world.level.block.CropBlock; // Added for ForageCropGoal target check
import net.minecraft.world.level.block.state.BlockState; // Added import for ForageCropGoal
import net.minecraft.world.level.storage.loot.LootParams; // Added for ForageCropGoal block drops
import net.minecraft.world.level.storage.loot.parameters.LootContextParams; // Added for ForageCropGoal block drops
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB; // Added import for HuntForMeatGoal drop collection
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
    private static final double TELEPORT_DISTANCE_SQ = 625.0D; // 25*25
    private static final double RESOURCE_GATHERING_RANGE_SQ = 625.0D; // 25*25 Rayon pour chercher ressources autour du joueur
    private static final int INVENTORY_SIZE = 18;
    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);

    @Nullable
    private BlockPos homePosition = null;
    private GoHomeGoal goHomeGoal;
    private GatherFoodGoal gatherFoodGoal;
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
        this.setCanPickUpLoot(true);
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
            if (!stack.isEmpty() && stack.getItem().isEdible() && stack.getItem().getFoodProperties() != null && stack.getItem().getFoodProperties().isMeat()) {
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
                    stack.is(Items.BREAD) // Compter le pain aussi?
                    // Ajouter Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS si on veut les compter ?
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
            // Ne pas rentrer à la maison si on défend le joueur
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) {
                return false;
            }
            // Ne pas rentrer si on a une cible hostile
            if (this.superMom.getTarget() != null && this.superMom.getTarget().getType().getCategory().isFriendly() == false) {
                 return false;
            }

            boolean isActive = this.superMom.goalSelector.getRunningGoals().anyMatch(goal -> goal.getGoal() == this);
            if (!isActive) {
                 // Si le goal n'est pas actif, on ne peut pas l'utiliser pour le moment (évite ajout multiple)
                 // On pourrait vouloir une logique différente ici, ex: l'ajouter s'il n'est pas là
                 // Pour l'instant, on suppose qu'il est ajouté par la méthode goHome()
                return false;
            }
            this.pathFound = this.findNearestBlock();
            return this.pathFound;
       }

       @Override
       public boolean canContinueToUse() {
            // Arrêter si on défend le joueur
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) {
                return false;
            }
            // Arrêter si on acquiert une cible hostile
            if (this.superMom.getTarget() != null && this.superMom.getTarget().getType().getCategory().isFriendly() == false) {
                 return false;
            }
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
       }

        @Override
        public void tick() {
            // Arrêter si on défend le joueur ou si cible hostile
            if ((this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) ||
                (this.superMom.getTarget() != null && !this.superMom.getTarget().getType().getCategory().isFriendly())) {
                return;
            }
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

        // Renommé pour éviter confusion avec le champ isActive de OwnerHurtByTargetGoal
        public boolean isGoalActive() {
            return this.superMom.goalSelector.getRunningGoals().anyMatch(goal -> goal.getGoal() == this);
        }
    } // Fin GoHomeGoal

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
           // Ne pas suivre si SuperMom a une cible d'attaque
           if (this.superMom.getTarget() != null) {
               return false;
           }
           // Ne pas suivre si OwnerHurtByTargetGoal est actif (priorité défense)
           if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) {
               return false;
           }
           this.targetPlayer = this.superMom.getOwner(); // Suivre le propriétaire
            return this.targetPlayer != null && !this.targetPlayer.isSpectator() && this.superMom.distanceToSqr(this.targetPlayer) > this.stopDistanceSq; // Activer si plus loin que stopDist
       }

       @Override
       public boolean canContinueToUse() {
            if (this.superMom.getTarget() != null || this.targetPlayer == null || this.targetPlayer.isSpectator()) {
                return false;
            }
            if (!this.targetPlayer.isAlive()) {
                return false;
            }
            // Arrêter si on est assez proche
            if (this.superMom.distanceToSqr(this.targetPlayer) <= this.stopDistanceSq) {
                return false;
            }
            // Ne pas continuer si OwnerHurtByTargetGoal s'active
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) {
               return false;
           }
            // Continuer si on est dans une portée raisonnable (évite de suivre à travers le monde entier si la téléportation échoue)
            return this.superMom.distanceToSqr(this.targetPlayer) <= (this.startDistanceSq * 4.0D); // Ex: max 2*startDist
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
           // Ne pas effacer targetPlayer ici, canUse le refera
           this.navigation.stop();
           LOGGER.debug("Stopping FollowPlayerGoal");
       }

       @Override
       public void tick() {
           if (this.targetPlayer != null) {
               this.superMom.getLookControl().setLookAt(this.targetPlayer, 10.0F, (float)this.superMom.getMaxHeadXRot());
               if (--this.timeToRecalcPath <= 0) {
                   this.timeToRecalcPath = this.adjustedTickDelay(10);
                   // Continuer à se déplacer vers le joueur s'il est plus loin que la distance d'arrêt + une petite marge
                   if (this.superMom.distanceToSqr(this.targetPlayer) > (this.stopDistanceSq + 1.0D)) {
                        this.navigation.moveTo(this.targetPlayer, this.speedModifier);
                   } else {
                        this.navigation.stop(); // Arrêter si on est assez proche
                   }
               }
           }
       }
    } // Fin FollowPlayerGoal

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
            // Ne pas activer si on a une cible d'attaque ou si on défend
            if (this.superMom.getTarget() != null) return false;
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) return false;


           this.targetPlayer = this.superMom.getOwner();
           if (this.targetPlayer == null || !this.targetPlayer.isAlive() || !this.targetPlayer.getFoodData().needsFood()) {
               return false;
           }
           // Vérifier si le joueur est proche
           if (this.superMom.distanceToSqr(this.targetPlayer) > 64.0D) { // Ne pas activer si trop loin (carré de 8 blocs)
               return false;
           }
           // Vérifier si SuperMom a de la nourriture
           return this.superMom.findInInventory(ItemStack::isEdible) != -1;
       }

       @Override
       public boolean canContinueToUse() {
            // Arrêter si on doit défendre
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) return false;

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
            // Arrêter si on doit défendre
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) return;


           this.superMom.getLookControl().setLookAt(this.targetPlayer, 10.0F, (float) this.superMom.getMaxHeadXRot());

           if (--this.timeToRecalcPath <= 0) {
               this.timeToRecalcPath = this.adjustedTickDelay(10);
               this.superMom.getNavigation().moveTo(this.targetPlayer, 1.0D);
           }

           // Si assez proche, essayer de nourrir
           if (this.superMom.distanceToSqr(this.targetPlayer) < REACH_DISTANCE_SQ) {
                LOGGER.debug("FeedPlayerGoal: Reached player {}, attempting to feed.", this.targetPlayer.getName().getString());
               this.superMom.feedPlayer(this.targetPlayer); // La méthode feedPlayer gère la logique de donner l'item
               // Le goal s'arrêtera via canContinueToUse si le joueur n'a plus faim ou si SuperMom n'a plus de nourriture
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
            // Ne pas activer si on a une cible d'attaque ou si on défend
            if (this.superMom.getTarget() != null) return false;
             if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) return false;


           this.targetPlayer = this.superMom.getOwner();
           if (this.targetPlayer == null || !this.targetPlayer.isAlive() || this.targetPlayer.getHealth() >= this.targetPlayer.getMaxHealth() * HEAL_THRESHOLD_PERCENT) {
               return false;
           }
            // Vérifier si le joueur est proche
           if (this.superMom.distanceToSqr(this.targetPlayer) > 64.0D) { // Ne pas activer si trop loin (carré de 8 blocs)
               return false;
           }
           // Vérifier si SuperMom a une potion de soin
           return this.superMom.findInInventory(stack -> stack.is(Items.POTION) && (PotionUtils.getPotion(stack) == Potions.HEALING || PotionUtils.getPotion(stack) == Potions.STRONG_HEALING)) != -1;
       }

       @Override
       public boolean canContinueToUse() {
            // Arrêter si on doit défendre
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) return false;

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
            // Arrêter si on doit défendre
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) return;


           this.superMom.getLookControl().setLookAt(this.targetPlayer, 10.0F, (float) this.superMom.getMaxHeadXRot());

           if (--this.timeToRecalcPath <= 0) {
               this.timeToRecalcPath = this.adjustedTickDelay(10);
               this.superMom.getNavigation().moveTo(this.targetPlayer, 1.0D);
           }

           // Si assez proche, essayer de soigner
           if (this.superMom.distanceToSqr(this.targetPlayer) < REACH_DISTANCE_SQ) {
                LOGGER.debug("HealPlayerGoal: Reached player {}, attempting to heal.", this.targetPlayer.getName().getString());
               this.superMom.healPlayer(this.targetPlayer); // La méthode healPlayer gère la logique d'utiliser la potion
               // Le goal s'arrêtera via canContinueToUse si le joueur n'a plus besoin de soin ou si SuperMom n'a plus de potion
           }
       }
    } // Fin HealPlayerGoal

    // --- Goal for Picking Up Loose Edible Items ---
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
           // Conditions de base: pas de cible hostile, jour, proche du joueur, joueur en sécurité
           Player owner = this.superMom.getOwner();
           if (this.superMom.getTarget() != null || !this.superMom.level().isDay() || owner == null || this.superMom.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ) {
               return false;
           }
           // CORRECTION: Utiliser .isActive
           if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) {
                return false; // Ne pas ramasser si le propriétaire est attaqué
           }

           this.targetFoodItem = findNearestEdibleItem();
           return this.targetFoodItem != null;
       }

       @Override
       public boolean canContinueToUse() {
           // Continuer si la cible existe, est vivante, pas trop loin, et conditions de base toujours valides
            Player owner = this.superMom.getOwner();
           if (this.superMom.getTarget() != null || !this.superMom.level().isDay() || owner == null || this.superMom.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ * 1.5) { // Marge un peu plus grande
               return false;
           }
            // CORRECTION: Utiliser .isActive
            if (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) {
                return false;
           }
           return this.targetFoodItem != null && this.targetFoodItem.isAlive() && !this.superMom.getNavigation().isDone();
       }

       @Override
       public void start() {
           this.timeToRecalcPath = 0;
           if (this.targetFoodItem != null) {
               SuperMomEntity.LOGGER.debug("GatherFoodGoal started. Target: {} at {}", this.targetFoodItem.getItem().getDescriptionId(), this.targetFoodItem.blockPosition());
               this.superMom.getNavigation().moveTo(this.targetFoodItem, this.speedModifier);
           } else {
               SuperMomEntity.LOGGER.warn("GatherFoodGoal started but targetFoodItem is null!");
           }
       }

       @Override
       public void stop() {
           SuperMomEntity.LOGGER.debug("GatherFoodGoal stopped. Target Item: {}", this.targetFoodItem != null ? this.targetFoodItem.getItem().getDescriptionId() : "null");
           this.targetFoodItem = null;
           this.superMom.getNavigation().stop();
           // Ne pas faire removeGoal ici, laisser le sélecteur gérer
       }

       @Override
       public void tick() {
           if (this.targetFoodItem != null && this.targetFoodItem.isAlive()) {
               this.superMom.getLookControl().setLookAt(this.targetFoodItem, 10.0F, (float) this.superMom.getMaxHeadXRot());
               if (--this.timeToRecalcPath <= 0) {
                   this.timeToRecalcPath = this.adjustedTickDelay(10);
                   // Si on est loin (>1.5 bloc), on continue de bouger
                   if (this.superMom.distanceToSqr(this.targetFoodItem) > 1.5D * 1.5D) {
                       this.superMom.getNavigation().moveTo(this.targetFoodItem, this.speedModifier);
                   } else {
                       // Si on est proche, on arrête de bouger et on attend que la logique de pickup vanilla fasse effet
                       this.superMom.getNavigation().stop();
                   }
               }
           } else {
               // La cible a disparu, on arrête (canContinueToUse devrait devenir faux)
               this.targetFoodItem = null; // Assurer que la cible est nulle
           }
       }

       @Nullable
       private ItemEntity findNearestEdibleItem() {
           // Utilise la searchRange définie dans le constructeur
           AABB searchBox = this.superMom.getBoundingBox().inflate(this.searchRange, this.searchRange / 2.0, this.searchRange);

           Predicate<ItemEntity> edibleItemPredicate = entity ->
               entity.isAlive() &&
               !entity.getItem().isEmpty() &&
               entity.getItem().isEdible() &&
               !entity.hasPickUpDelay() && // Important: Ne cibler que les items ramassables
               this.superMom.hasLineOfSight(entity) &&
               this.superMom.inventory.canAddItem(entity.getItem());

           java.util.List<ItemEntity> nearbyItems = this.superMom.level().getEntitiesOfClass(
               ItemEntity.class,
               searchBox,
               edibleItemPredicate // Appliquer le prédicat directement dans la recherche
           );

           ItemEntity closestItem = null;
           double closestDistSq = Double.MAX_VALUE;

           for (ItemEntity itemEntity : nearbyItems) {
               double distSq = this.superMom.distanceToSqr(itemEntity);
               if (distSq < closestDistSq) {
                   closestDistSq = distSq;
                   closestItem = itemEntity;
               }
           }
           return closestItem;
       }
    } // Fin GatherFoodGoal


    // --- Goal for Hunting Passive Animals --- CORRECTED ---
    class HuntForMeatGoal extends Goal {
       private final SuperMomEntity superMom;
       private LivingEntity targetAnimal;
       private final TargetingConditions huntTargeting; // Initialisé dans le constructeur

       private enum State { SEARCHING, ATTACKING, COLLECTING }
       private State currentState = State.SEARCHING;
       private int collectingTicks = 0;
       private static final int MAX_COLLECTING_TICKS = 80; // Temps max pour chercher les drops (4 sec)
       private BlockPos lastTargetPos = null; // Position où l'animal est mort

       public HuntForMeatGoal(SuperMomEntity mob) {
           this.superMom = mob;
           this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
           // Initialiser TargetingConditions ici
           this.huntTargeting = TargetingConditions.forCombat()
                .range(16.0D) // Portée de recherche initiale autour de SuperMom
                .ignoreLineOfSight() // Peut cibler même sans voir directement au début
                .selector((entity) -> {
                    // Cibler seulement les animaux passifs communs pour la viande
                    return entity instanceof net.minecraft.world.entity.animal.Cow ||
                           entity instanceof net.minecraft.world.entity.animal.Pig ||
                           entity instanceof net.minecraft.world.entity.animal.Sheep ||
                           entity instanceof net.minecraft.world.entity.animal.Chicken;
                });
       }

       @Override
       public boolean canUse() {
           // Conditions générales : jour, pas de cible hostile, cooldown, propriétaire existe et proche, propriétaire non attaqué, besoin de viande
           Player owner = this.superMom.getOwner();
           if (this.currentState != State.SEARCHING || // Seulement chercher si on ne fait rien d'autre dans ce goal
               !this.superMom.level().isDay() ||
               this.superMom.getTarget() != null || // Ne pas chasser si déjà une cible (pourrait être hostile)
               this.superMom.autonomousActionCooldown > 0 ||
               owner == null ||
               this.superMom.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ || // Vérifier distance au joueur (25 blocs)
               (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) || // Ne pas chasser si proprio attaqué
               !this.superMom.needsMeat()) // Vérifier le besoin en viande
           {
               return false;
           }

           // Trouver l'animal le plus proche respectant les conditions ET la proximité du joueur
           this.targetAnimal = this.superMom.level().getNearestEntity(
               // Chercher les entités dans la zone de chasse autour de SuperMom
               this.superMom.level().getEntitiesOfClass(LivingEntity.class, this.superMom.getBoundingBox().inflate(16.0D), e -> true),
               // Appliquer les conditions de base (type d'animal)
               this.huntTargeting,
               this.superMom, this.superMom.getX(), this.superMom.getY(), this.superMom.getZ()
           );

           // Vérification supplémentaire : l'animal trouvé est-il aussi proche du joueur ?
           if (this.targetAnimal != null && this.targetAnimal.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ) {
               this.targetAnimal = null; // Ignorer si l'animal est trop loin du joueur
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
            // Conditions générales d'arrêt : nuit, propriétaire trop loin ou attaqué, cible hostile prioritaire
           if (!this.superMom.level().isDay() ||
               owner == null ||
               this.superMom.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ * 1.5 || // Marge pour ne pas arrêter direct si le joueur bouge un peu
               (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) ||
               this.superMom.getTarget() != null && this.superMom.getTarget() != this.targetAnimal) // Si une autre cible (hostile) a pris le dessus
               {
                   return false;
               }

           // Si collecting, continuer tant qu'il y a des drops et du temps
           if (this.currentState == State.COLLECTING) {
               // Utilise la méthode renommée et corrigée
               return this.collectingTicks > 0 && findNearestDropFromKill(this.lastTargetPos) != null;
           }

           // Si attacking, continuer tant que la cible est valide
           if (this.currentState == State.ATTACKING) {
               return this.targetAnimal != null
                       && this.targetAnimal.isAlive()
                       && this.superMom.getTarget() == this.targetAnimal; // Vérifier que SuperMom cible toujours notre animal
           }

           return false; // Ne devrait pas arriver
       }

       @Override
       public void start() {
           if (this.targetAnimal != null) {
               LOGGER.debug("HuntForMeatGoal started. Targeting: {}", this.targetAnimal.getType().getDescription().getString());
               this.currentState = State.ATTACKING;
               this.superMom.setTarget(this.targetAnimal); // Important pour que MeleeAttackGoal (Prio 1) prenne le relais
               this.superMom.resetAutonomousCooldown(); // Cooldown pour démarrer l'action
           } else {
               LOGGER.error("HuntForMeatGoal started without a targetAnimal! This should not happen.");
           }
       }

       @Override
       public void stop() {
           LOGGER.debug("HuntForMeatGoal stopped. Final state: {}. Target was: {}", this.currentState, this.targetAnimal != null ? this.targetAnimal.getType().getDescription().getString() : (this.lastTargetPos != null ? "killed at "+this.lastTargetPos : "null"));

           // Nettoyer la cible de SuperMom SEULEMENT si c'était notre cible et qu'aucun goal de défense n'est actif
           if (this.superMom.getTarget() == this.targetAnimal && (this.superMom.ownerHurtByTargetGoal == null || !this.superMom.ownerHurtByTargetGoal.isActive)) {
               this.superMom.setTarget(null);
           }

           this.targetAnimal = null;
           this.currentState = State.SEARCHING;
           this.collectingTicks = 0;
           this.lastTargetPos = null;
            this.superMom.getNavigation().stop(); // Assurer l'arrêt de la navigation
       }

       @Override
       public void tick() {
           if (this.currentState == State.ATTACKING) {
               // Vérifier si la cible est morte ou invalide
               if (this.targetAnimal == null || !this.targetAnimal.isAlive()) {
                   LOGGER.debug("HuntForMeatGoal.tick: Target killed or lost. Switching to COLLECTING.");
                   this.lastTargetPos = this.targetAnimal != null ? this.targetAnimal.blockPosition() : this.superMom.blockPosition(); // Garder la dernière position connue
                   LivingEntity currentTarget = this.superMom.getTarget(); // Sauvegarder la cible actuelle
                   this.targetAnimal = null; // Oublier notre cible passive
                   // Ne pas effacer la cible de SuperMom ici, car MeleeAttackGoal pourrait encore être actif une fraction de seconde
                   // if (currentTarget == this.targetAnimal) { // Only clear if it was our target
                   //     this.superMom.setTarget(null);
                   // }
                   this.superMom.getNavigation().stop();
                   this.currentState = State.COLLECTING;
                   this.collectingTicks = MAX_COLLECTING_TICKS;
                   collectDrops(); // Essayer de collecter immédiatement
                   return;
               }

               // Vérifier si une cible hostile plus prioritaire est apparue (géré par canContinueToUse et TargetSelector)
               if (this.superMom.getTarget() != this.targetAnimal) {
                    LOGGER.warn("HuntForMeatGoal.tick: SuperMom target ({}) differs from goal target ({}). Stopping hunt goal.",
                                this.superMom.getTarget() != null ? this.superMom.getTarget().getName().getString() : "null",
                                this.targetAnimal != null ? this.targetAnimal.getName().getString() : "null");
                    // canContinueToUse devrait devenir false et arrêter le goal
                    return;
               }

               // **LOGIQUE D'ATTAQUE RETIRÉE**
               // MeleeAttackGoal (Prio 1) gère maintenant le déplacement et l'attaque.
               // On peut garder le look control.
               this.superMom.getLookControl().setLookAt(this.targetAnimal, 30.0F, 30.0F);


           } else if (this.currentState == State.COLLECTING) {
               this.collectingTicks--;
               if (this.collectingTicks <= 0) {
                   // Le temps est écoulé, canContinueToUse arrêtera le goal
                   return;
               }
               collectDrops(); // Continuer à collecter
           }
       }


       private void collectDrops() {
           if (this.lastTargetPos == null) {
               this.collectingTicks = 0; return;
           }
           // Utilise la méthode renommée et corrigée
           ItemEntity nearestDrop = findNearestDropFromKill(this.lastTargetPos);
           if (nearestDrop != null) {
               // Se rapprocher suffisamment pour que le pickup vanilla fonctionne
               if (this.superMom.distanceToSqr(nearestDrop) > 1.5D * 1.5D) { // Seuil de 1.5 blocs
                   this.superMom.getNavigation().moveTo(nearestDrop, 1.0D);
                   this.superMom.getLookControl().setLookAt(nearestDrop, 30.0F, 30.0F);
               } else {
                   this.superMom.getNavigation().stop(); // Assez proche, attendre pickup
               }
           } else {
               this.collectingTicks = 0; // Plus de drops trouvés
               this.superMom.getNavigation().stop();
           }
       }

       @Nullable
       // RENOMMÉ et CORRIGÉ
       private ItemEntity findNearestDropFromKill(BlockPos center) {
            if (center == null) return null;
            AABB searchBox = new AABB(center).inflate(5.0D); // Rayon de recherche autour du lieu de mort

            // PRÉDICAT CORRIGÉ: N'exclut plus les items non-viande
            Predicate<ItemEntity> dropFromKillPredicate = entity ->
                entity.isAlive() &&
                !entity.hasPickUpDelay() && // Est-ce ramassable maintenant ?
                !entity.getItem().isEmpty() && // L'item existe ?
                this.superMom.inventory.canAddItem(entity.getItem()); // Y a-t-il de la place ?

            java.util.List<ItemEntity> nearbyItems = this.superMom.level().getEntitiesOfClass(
                ItemEntity.class, searchBox, dropFromKillPredicate);

            // Trouve le plus proche parmi les items valides
            return nearbyItems.stream()
                .min((e1, e2) -> Double.compare(this.superMom.distanceToSqr(e1), this.superMom.distanceToSqr(e2)))
                .orElse(null);
       }

    } // Fin HuntForMeatGoal


    // --- Goal for Foraging Mature Crops --- MODIFIED ---
    class ForageCropGoal extends MoveToBlockGoal {
       private final SuperMomEntity superMom;
       private int harvestCooldown = 0;
       private static final int MAX_HARVEST_COOLDOWN = 40; // Cooldown between harvests

       private enum State { SEARCHING, MOVING_TO_CROP, HARVESTING, COLLECTING }
       private State currentState = State.SEARCHING;
       private int collectingTicks = 0;
       private static final int MAX_COLLECTING_TICKS = 80; // Time to search for drops (4 sec)
       private BlockPos lastHarvestPos = null;

       public ForageCropGoal(SuperMomEntity mob, double speedModifier, int searchRange) {
           super(mob, speedModifier, searchRange, 6); // searchVerticalRange = 6
           this.superMom = mob;
           this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
       }

       @Override
       public boolean canUse() {
           // Conditions générales : jour, pas de cible hostile, cooldown, propriétaire existe et proche, propriétaire non attaqué, besoin de récoltes
           Player owner = this.superMom.getOwner();
           if (this.currentState != State.SEARCHING ||
               !this.superMom.level().isDay() ||
               this.superMom.getTarget() != null ||
               this.superMom.autonomousActionCooldown > 0 ||
               this.harvestCooldown > 0 || // Cooldown spécifique à la récolte
               owner == null ||
               this.superMom.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ || // Vérifier distance au joueur (25 blocs)
               (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) || // Ne pas récolter si proprio attaqué
               !this.superMom.needsCrops()) // Vérifier le besoin en récoltes
           {
               // Décrémenter le cooldown même si on ne peut pas utiliser le goal
               if (this.harvestCooldown > 0) this.harvestCooldown--;
               return false;
           }

           // Utiliser la logique de MoveToBlockGoal pour trouver un bloc valide (qui appelle notre isValidTarget)
           if (!super.canUse()) { // super.canUse() appelle findNearestBlock()
               return false;
           }

           // Si super.canUse() est vrai, this.blockPos est une récolte mûre valide
           LOGGER.debug("ForageCropGoal.canUse: Found potential crop at {}", this.blockPos);
           return true;
       }

       @Override
       public boolean canContinueToUse() {
            Player owner = this.superMom.getOwner();
            // Conditions générales d'arrêt : nuit, propriétaire trop loin ou attaqué, cible hostile prioritaire
           if (!this.superMom.level().isDay() ||
               owner == null ||
               this.superMom.distanceToSqr(owner) > RESOURCE_GATHERING_RANGE_SQ * 1.5 ||
               (this.superMom.ownerHurtByTargetGoal != null && this.superMom.ownerHurtByTargetGoal.isActive) ||
                this.superMom.getTarget() != null)
               {
                   return false;
               }

           // Si collecting, continuer tant qu'il y a des drops et du temps
           if (this.currentState == State.COLLECTING) {
               return this.collectingTicks > 0 && findNearestCropDrop(this.lastHarvestPos) != null;
           }

           // Si moving ou harvesting, continuer si la cible est toujours valide
           if (this.currentState == State.MOVING_TO_CROP || this.currentState == State.HARVESTING) {
               return this.blockPos != null && this.isValidTarget(this.mob.level(), this.blockPos);
           }

           return false; // Ne devrait pas être en SEARCHING ici
       }

       @Override
       public void start() {
           LOGGER.debug("ForageCropGoal started. Target: {}", this.blockPos);
           this.currentState = State.MOVING_TO_CROP;
           this.moveMobToBlock(); // Démarrer la navigation
           this.harvestCooldown = 0; // Réinitialiser cooldown spécifique
           this.tryTicks = 0; // Réinitialiser timer interne de MoveToBlockGoal
           this.superMom.resetAutonomousCooldown(); // Cooldown général
       }

       @Override
       public void stop() {
           LOGGER.debug("ForageCropGoal stopped. Final state: {}. Target was: {}", this.currentState, this.blockPos != null ? this.blockPos : this.lastHarvestPos);
           super.stop(); // Arrêter la navigation de MoveToBlockGoal
           this.currentState = State.SEARCHING;
           this.collectingTicks = 0;
           this.lastHarvestPos = null;
           // Mettre un cooldown après avoir fini (ou échoué)
           this.harvestCooldown = MAX_HARVEST_COOLDOWN / 2 + this.mob.getRandom().nextInt(MAX_HARVEST_COOLDOWN / 2);
       }

       @Override
       public void tick() {
           if (this.currentState == State.MOVING_TO_CROP) {
               super.tick(); // Gérer le mouvement

               if (this.isReachedTarget()) {
                   LOGGER.debug("ForageCropGoal: Reached crop position {}. Switching to HARVESTING.", this.blockPos);
                   this.currentState = State.HARVESTING;
                   tryHarvest(); // Essayer de récolter
               } else if (!this.isValidTarget(this.mob.level(), this.blockPos)) {
                    LOGGER.debug("ForageCropGoal: Target {} became invalid during approach. Stopping.", this.blockPos);
                    // canContinueToUse deviendra faux et arrêtera le goal
               }
           } else if (this.currentState == State.HARVESTING) {
               // Normalement, tryHarvest change l'état. Si on est encore là, c'est un problème.
               LOGGER.warn("ForageCropGoal: Still in HARVESTING state during tick. Forcing stop.");
               this.currentState = State.SEARCHING; // Forcer l'arrêt pour éviter boucle infinie
           } else if (this.currentState == State.COLLECTING) {
               this.collectingTicks--;
               if (this.collectingTicks <= 0) {
                   // Arrêt géré par canContinueToUse
                   return;
               }
               collectCropDrops(); // Continuer la collecte
           }
       }

       private void tryHarvest() {
           Level level = this.superMom.level();
           BlockPos targetPos = this.blockPos;

           if (targetPos == null || !(level instanceof ServerLevel serverLevel)) {
               LOGGER.warn("ForageCropGoal.tryHarvest: Target position is null or level is not ServerLevel.");
               this.currentState = State.SEARCHING; return;
           }

           BlockState blockState = level.getBlockState(targetPos);
           if (this.isValidTarget(level, targetPos)) {
               LOGGER.debug("ForageCropGoal: Harvesting mature crop {} at {}.", blockState.getBlock().getDescriptionId(), targetPos);

               // Simuler la récolte
                this.superMom.swing(InteractionHand.MAIN_HAND); // Animation
               level.playSound(null, targetPos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);

               // Obtenir les drops SANS casser le bloc (pour que SuperMom puisse les ramasser)
               LootParams.Builder lootparams$builder = (new LootParams.Builder(serverLevel))
                   .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(targetPos))
                   .withParameter(LootContextParams.TOOL, ItemStack.EMPTY) // Outil vide
                   .withOptionalParameter(LootContextParams.THIS_ENTITY, this.superMom)
                   .withOptionalParameter(LootContextParams.BLOCK_ENTITY, level.getBlockEntity(targetPos));
               java.util.List<ItemStack> drops = blockState.getDrops(lootparams$builder);

               // Casser le bloc APRÈS avoir calculé les drops
               level.destroyBlock(targetPos, false, this.superMom); // false = ne pas faire dropper les items par le jeu

               // Faire apparaître les drops calculés
               boolean spawnedDrops = false;
               for(ItemStack drop : drops) {
                   if (!drop.isEmpty()) {
                       ItemEntity itementity = new ItemEntity(level, targetPos.getX() + 0.5D, targetPos.getY() + 0.2D, targetPos.getZ() + 0.5D, drop.copy());
                       itementity.setPickUpDelay(0); // Ramassable immédiatement
                       level.addFreshEntity(itementity);
                       spawnedDrops = true;
                   }
               }

               // TODO: Logique de replantation optionnelle ici
               // Block replantBlock = null;
               // if (blockState.is(Blocks.WHEAT)) replantBlock = Blocks.WHEAT;
               // ... etc ...
               // if (replantBlock != null && superMom.hasItemInInventory(replantBlock.asItem())) {
               //    level.setBlock(targetPos, replantBlock.defaultBlockState(), 3);
               //    superMom.removeItemFromInventory(replantBlock.asItem());
               // }

               this.lastHarvestPos = targetPos;
               this.currentState = State.COLLECTING;
               this.collectingTicks = spawnedDrops ? MAX_COLLECTING_TICKS : 0;
               this.blockPos = null; // La cible n'existe plus
               if (spawnedDrops) {
                   collectCropDrops(); // Essayer de collecter
               }

           } else {
               LOGGER.debug("ForageCropGoal.tryHarvest: Crop at {} is no longer valid.", targetPos);
               this.currentState = State.SEARCHING;
           }
       }


       private void collectCropDrops() {
            if (this.lastHarvestPos == null) {
                this.collectingTicks = 0; return;
            }
            ItemEntity nearestDrop = findNearestCropDrop(this.lastHarvestPos);
            if (nearestDrop != null) {
                if (this.superMom.distanceToSqr(nearestDrop) > 1.5D * 1.5D) {
                    this.superMom.getNavigation().moveTo(nearestDrop, 1.0D);
                    this.superMom.getLookControl().setLookAt(nearestDrop, 30.0F, 30.0F);
                } else {
                    this.superMom.getNavigation().stop(); // Assez proche
                }
            } else {
                this.collectingTicks = 0; // Plus de drops
                this.superMom.getNavigation().stop();
            }
       }

       @Nullable
       private ItemEntity findNearestCropDrop(BlockPos center) {
           if (center == null) return null;
           AABB searchBox = new AABB(center).inflate(5.0D);

           Predicate<ItemEntity> cropDropPredicate = entity -> {
               if (!entity.isAlive() || entity.hasPickUpDelay() || entity.getItem().isEmpty()) return false;
               Item item = entity.getItem().getItem();
               boolean isCropItem = item == Items.WHEAT || item == Items.WHEAT_SEEDS ||
                                    item == Items.CARROT || item == Items.POTATO ||
                                    item == Items.BEETROOT || item == Items.BEETROOT_SEEDS;
               return isCropItem && this.superMom.inventory.canAddItem(entity.getItem());
           };

           java.util.List<ItemEntity> nearbyItems = this.superMom.level().getEntitiesOfClass(
               ItemEntity.class, searchBox, cropDropPredicate);

            return nearbyItems.stream()
                .min((e1, e2) -> Double.compare(this.superMom.distanceToSqr(e1), this.superMom.distanceToSqr(e2)))
                .orElse(null);
       }


       /** Checks if the block is a mature, supported crop. */
       @Override
       protected boolean isValidTarget(LevelReader pLevel, BlockPos pPos) {
           BlockState blockstate = pLevel.getBlockState(pPos);
           Block block = blockstate.getBlock();
           if (block instanceof CropBlock cropBlock) {
               return cropBlock.isMaxAge(blockstate);
           }
           // Ajouter d'autres types si besoin (ex: Nether Wart)
           // else if (block instanceof NetherWartBlock wartBlock) {
           //     return blockstate.getValue(NetherWartBlock.AGE) >= 3;
           // }
           return false;
       }

       // Pas besoin d'override findNearestBlock, la version de base fait l'affaire car elle utilise notre isValidTarget

    } // Fin ForageCropGoal


    // --- Autonomous Goal for Checking Supplies --- MODIFIED ---
    class CheckSuppliesGoal extends Goal {
       private final SuperMomEntity superMom;
       private static final int CHECK_INTERVAL = 100; // Vérifier plus souvent pour les items au sol
       private int timeUntilNextCheck = 0;
       private boolean foundNearbyFood = false; // Flag pour item au sol trouvé

       public CheckSuppliesGoal(SuperMomEntity mob) {
           this.superMom = mob;
           this.setFlags(EnumSet.of(Goal.Flag.LOOK)); // Pas besoin de MOVE ici
           this.timeUntilNextCheck = mob.random.nextInt(CHECK_INTERVAL / 2);
       }

       @Override
       public boolean canUse() {
           // Conditions de base : pas de cible, cooldown passé
           if (this.superMom.getTarget() != null || this.superMom.autonomousActionCooldown > 0) {
               return false;
           }
           if (--this.timeUntilNextCheck > 0) {
               return false;
           }
           this.timeUntilNextCheck = CHECK_INTERVAL + this.superMom.random.nextInt(CHECK_INTERVAL / 2);
           this.foundNearbyFood = false; // Reset flag

           // Vérifier s'il y a de la nourriture au sol à proximité immédiate
           ItemEntity nearbyFood = findNearestEdibleItem();
           if (nearbyFood != null) {
               LOGGER.trace("CheckSuppliesGoal.canUse: Found nearby edible item: {}", nearbyFood.getItem().getDescriptionId());
               this.foundNearbyFood = true;
               return true; // Priorité au ramassage
           }

           // Ne plus vérifier les niveaux d'inventaire ici, les goals spécifiques le font
           return false; // Ne s'active que pour ramasser des items au sol
       }

       @Override
       public boolean canContinueToUse() {
           return false; // S'exécute une seule fois
       }

       @Override
       public void start() {
           if (this.foundNearbyFood) {
               LOGGER.debug("CheckSuppliesGoal started: Found nearby food. Triggering gatherFood.");
               this.superMom.gatherFood(); // Déclencher le goal de ramassage
               this.superMom.resetAutonomousCooldown(); // Mettre le cooldown après avoir agi
           } else {
               // Ne devrait pas arriver si canUse est correct
               LOGGER.warn("CheckSuppliesGoal started but foundNearbyFood was false?");
           }
       }

       @Override
       public void stop() {
           LOGGER.trace("CheckSuppliesGoal stopped.");
       }

       // Garder cette méthode car elle est utilisée par canUse()
       @Nullable
       private ItemEntity findNearestEdibleItem() {
           int searchRange = 8; // Petite portée pour les items au sol
           AABB searchBox = this.superMom.getBoundingBox().inflate(searchRange, searchRange / 2.0, searchRange);
           Predicate<ItemEntity> edibleItemPredicate = entity ->
               entity.isAlive() &&
               !entity.getItem().isEmpty() &&
               entity.getItem().isEdible() &&
               !entity.hasPickUpDelay() &&
               this.superMom.hasLineOfSight(entity) &&
               this.superMom.inventory.canAddItem(entity.getItem());

           java.util.List<ItemEntity> nearbyItems = this.superMom.level().getEntitiesOfClass(
               ItemEntity.class, searchBox, edibleItemPredicate);

            return nearbyItems.stream()
                .min((e1, e2) -> Double.compare(this.superMom.distanceToSqr(e1), this.superMom.distanceToSqr(e2)))
                .orElse(null);
       }

    } // Fin CheckSuppliesGoal


    // --- Goal Registration ---
    @Override
    protected void registerGoals() {
        LOGGER.debug("Registering goals for SuperMomEntity");
        // Priorités hautes : Actions essentielles / Réactions immédiates
        this.goalSelector.addGoal(0, new FloatGoal(this)); // Prio 0: Nager (essentiel)
        // Ajout d'un MeleeAttackGoal générique pour gérer l'attaque une fois la cible définie par TargetGoals
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.2D, false)); // Prio 1: Attaquer la cible (vitesse 1.2, ne s'arrête pas si la cible fuit)

        // Priorités moyennes : Assistance au joueur et Collecte de ressources autonome
        this.goalSelector.addGoal(3, new HealPlayerGoal(this));     // Prio 3: Soigner le joueur (important si besoin)
        this.goalSelector.addGoal(4, new FeedPlayerGoal(this));     // Prio 4: Nourrir le joueur (important si besoin)
        this.goalSelector.addGoal(5, new HuntForMeatGoal(this));    // Prio 5: Chasser pour viande (autonome)
        this.goalSelector.addGoal(6, new ForageCropGoal(this, 1.0D, 16)); // Prio 6: Récolter cultures (autonome)
        this.goalSelector.addGoal(7, new GatherFoodGoal(this, 1.0D, 8)); // Prio 7: Ramasser items au sol (activé par CheckSupplies)

        // Priorités basses : Comportements par défaut / Idle
        this.goalSelector.addGoal(8, new FollowPlayerGoal(this, 1.0D, 10.0F, 3.0F)); // Prio 8: Suivre joueur si rien d'autre (stopDist = 3)
        this.goalSelector.addGoal(9, new WaterAvoidingRandomStrollGoal(this, 1.0D)); // Prio 9: Se balader
        this.goalSelector.addGoal(10, new LookAtPlayerGoal(this, Player.class, 8.0F)); // Prio 10: Regarder joueur
        this.goalSelector.addGoal(11, new RandomLookAroundGoal(this)); // Prio 11: Regarder autour
        this.goalSelector.addGoal(12, new CheckSuppliesGoal(this)); // Prio 12: Vérifier items au sol (très basse prio)

        // Initialisation des goals spécifiques (ne sont pas ajoutés ici, mais activés par les actions)
        this.goHomeGoal = new GoHomeGoal(this, 1.0D, 16); // Sera ajouté dynamiquement si besoin
        // this.gatherFoodGoal = new GatherFoodGoal(this, 1.0D, 16); // Déjà ajouté avec priorité 7

        // --- Target Goals (Qui attaquer ?) ---
        LOGGER.debug("Registering target goals for SuperMomEntity");
        this.ownerHurtByTargetGoal = new OwnerHurtByTargetGoal(this); // Instancier pour référence
        this.targetSelector.addGoal(0, this.ownerHurtByTargetGoal);             // Prio 0: Défendre propriétaire
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers()); // Prio 1: Riposter si attaquée
        // Priorité 2: Attaquer hostiles proches du joueur
        final double hostileNearOwnerRange = 20.0; // Rayon de défense active autour du joueur
        final double hostileNearOwnerRangeSq = hostileNearOwnerRange * hostileNearOwnerRange;
        Predicate<LivingEntity> hostileNearOwnerPredicate = (targetMonster) -> {
            Player owner = this.getOwner();
            if (owner == null) return true; // Comportement par défaut si pas de propriétaire
            // Cibler seulement si le monstre est proche du propriétaire
            return targetMonster.distanceToSqr(owner) <= hostileNearOwnerRangeSq;
        };
        // CORRECTION APPLIQUÉE ICI : checkVisibility (4ème argument) est mis à false
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                this, Monster.class, 5, false, false, hostileNearOwnerPredicate)); // Prio 2

        LOGGER.debug("Goals registered.");
    }


    // --- finalizeSpawn Method --- (Ajout des potions initiales)
    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor pLevel, DifficultyInstance pDifficulty, MobSpawnType pReason, @Nullable SpawnGroupData pSpawnData, @Nullable CompoundTag pDataTag) {
        pSpawnData = super.finalizeSpawn(pLevel, pDifficulty, pReason, pSpawnData, pDataTag);
        LOGGER.info("Finalizing spawn for SuperMom - Equipping sword and adding potions.");
        // Épée
        ItemStack superSword = new ItemStack(Items.NETHERITE_SWORD);
        superSword.enchant(Enchantments.SHARPNESS, 5);
        superSword.enchant(Enchantments.UNBREAKING, 3);
        superSword.enchant(Enchantments.MENDING, 1);
        superSword.enchant(Enchantments.SWEEPING_EDGE, 3);
        this.setItemSlot(EquipmentSlot.MAINHAND, superSword);
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        LOGGER.debug("Equipped main hand with: {}", superSword);

        // Potions initiales
        int potionsAdded = 0;
        for (int i = 0; i < 5; i++) { // 5 Soin I
            if(this.inventory.canAddItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING))) {
                 this.inventory.addItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING));
                 potionsAdded++;
            }
        }
        for (int i = 0; i < 3; i++) { // 3 Soin II
             if(this.inventory.canAddItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRONG_HEALING))) {
                 this.inventory.addItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRONG_HEALING));
                 potionsAdded++;
             }
        }
        for (int i = 0; i < 3; i++) { // 3 Régénération I
             if(this.inventory.canAddItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.REGENERATION))) {
                 this.inventory.addItem(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.REGENERATION));
                 potionsAdded++;
             }
        }
        LOGGER.debug("Added {} total potions to inventory.", potionsAdded);

        return pSpawnData;
    }

    // --- Lifecycle Methods --- (aiStep, teleportation, etc.)
    @Override
    public void aiStep() {
        super.aiStep(); // Très important !

        // Gestion cooldown autonome
        if (this.autonomousActionCooldown > 0) {
            this.autonomousActionCooldown--;
        }

        // --- Téléportation si trop loin ---
        LivingEntity owner = this.getOwner();
        if (owner != null && !this.isLeashed() && !this.isPassenger() && !this.isInWaterOrBubble()) {
            if (this.distanceToSqr(owner) > TELEPORT_DISTANCE_SQ) {
                this.teleportToOwner();
            }
        }

        // --- Auto-soin autonome ---
        // S'exécute seulement si pas de cible et cooldown OK
        if (this.autonomousActionCooldown <= 0 && this.getTarget() == null) {
            float healthPercent = this.getHealth() / this.getMaxHealth();
            if (healthPercent < AUTONOMOUS_HEAL_THRESHOLD_PERCENT && !this.hasEffect(MobEffects.HEAL) && !this.hasEffect(MobEffects.REGENERATION)) {
                // LOGGER.debug("Autonomous Check: Low health detected ({}%). Attempting self-heal.", String.format("%.2f", healthPercent * 100));
                if (this.findInInventory(stack -> stack.is(Items.POTION) && (PotionUtils.getPotion(stack) == Potions.HEALING || PotionUtils.getPotion(stack) == Potions.STRONG_HEALING)) != -1) {
                    this.selfHeal();
                    this.resetAutonomousCooldown(); // Mettre le cooldown APRÈS avoir agi
                } else {
                    // LOGGER.debug("Autonomous Check: Low health but no healing potion found.");
                }
            }
        }
    }


    // --- Teleportation Helper ---
    private void teleportToOwner() {
       LivingEntity owner = this.getOwner();
       if (owner == null) return;

       BlockPos ownerPos = owner.blockPosition();
       for(int i = 0; i < 10; ++i) {
           int dx = this.random.nextInt(5) - 2; // -2 to +2
           int dy = this.random.nextInt(3) - 1; // -1 to +1
           int dz = this.random.nextInt(5) - 2; // -2 to +2

           if (Math.abs(dx) < 2 && Math.abs(dz) < 2 && Math.abs(dy) < 1) {
                if (dx == 0 && dz == 0 && dy <= 0) continue;
           }

           BlockPos targetPos = ownerPos.offset(dx, dy, dz);
           Level level = this.level();
           BlockPos posBelow = targetPos.below();
           BlockState stateBelow = level.getBlockState(posBelow);

           // Utiliser randomTeleport pour une téléportation plus sûre
           if (stateBelow.entityCanStandOn(level, posBelow, this) && level.noCollision(this, this.getBoundingBox().move(Vec3.atCenterOf(targetPos).subtract(this.position())))) {
               if (this.randomTeleport(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D, true)) {
                   LOGGER.debug("Teleporting SuperMom from {} to {} near owner {}", this.position(), Vec3.atCenterOf(targetPos), owner.getName().getString());
                   this.getNavigation().stop();
                   // level.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENDERMAN_TELEPORT, this.getSoundSource(), 1.0F, 1.0F);
                   return; // Succès
               }
           }
       }
       LOGGER.debug("Failed to find suitable teleport location near owner {}", owner.getName().getString());
    }

    private void resetAutonomousCooldown() {
        this.autonomousActionCooldown = AUTONOMOUS_ACTION_COOLDOWN_TICKS;
    }

    // --- Inventory Handling --- (addAdditionalSaveData, readAdditionalSaveData, findInInventory, dropItem)
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
            LOGGER.debug("Dropped item: {}", stack.getDescriptionId()); // Use getDescriptionId for better logging
        }
    }


    // --- Action Methods Called by LlmHelper OR Goals ---
    public void feedPlayer(Player player) {
       LOGGER.info("Action: Attempting to feed player {}", player.getName().getString());
       int foodSlot = findInInventory(ItemStack::isEdible);
       if (foodSlot != -1) {
           ItemStack foodStack = inventory.removeItem(foodSlot, 1);
           LOGGER.debug("Found food {} in slot {}, attempting to give to player.", foodStack.getDescriptionId(), foodSlot);
           // Donner directement au joueur si possible (plus fiable que jeter)
           if (!player.getInventory().add(foodStack)) {
                // Si l'inventaire du joueur est plein, jeter l'item à ses pieds
                Vec3 dropPos = player.position().add(0, 0.25, 0);
                ItemEntity itementity = new ItemEntity(this.level(), dropPos.x(), dropPos.y(), dropPos.z(), foodStack);
                itementity.setPickUpDelay(10); // Petit délai pour que le joueur puisse ramasser
                this.level().addFreshEntity(itementity);
                LOGGER.info("Dropped {} for player {} (inventory full?)", foodStack.getDescriptionId(), player.getName().getString());
           } else {
               LOGGER.info("Gave {} to player {}", foodStack.getDescriptionId(), player.getName().getString());
               // Jouer un son de succès ?
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

            // Simuler l'utilisation de la potion sur le joueur
            java.util.List<MobEffectInstance> effects = PotionUtils.getMobEffects(potionStack);
            if (!effects.isEmpty()) {
                 for(MobEffectInstance effectinstance : effects) {
                     // Appliquer l'effet directement au joueur
                     // Pour Instant Health, la durée est 1 tick
                     player.addEffect(new MobEffectInstance(effectinstance.getEffect(), 1, effectinstance.getAmplifier()));
                 }
                 // Remplacer la potion par une fiole vide
                 inventory.setItem(potionSlot, new ItemStack(Items.GLASS_BOTTLE));
                 LOGGER.info("Used healing potion on player {}", player.getName().getString());
                 // Jouer un son?
                 level().playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 1.0F, level().random.nextFloat() * 0.1F + 0.9F);
            } else {
                 LOGGER.warn("Potion {} had no effects?", potionStack.getDescriptionId());
                 inventory.setItem(potionSlot, new ItemStack(Items.GLASS_BOTTLE)); // Remplacer quand même
            }
        } else {
            LOGGER.warn("Action 'healPlayer': No healing potion found in inventory.");
        }
    }

    public void attackMob(LivingEntity target) {
       if (this.goHomeGoal != null && this.goHomeGoal.isGoalActive()) { // Use isGoalActive()
           LOGGER.debug("Deactivating GoHomeGoal due to attack command.");
           this.goHomeGoal.stop();
       }
       LOGGER.info("Action: Setting attack target to {}", target.getName().getString());
       this.setTarget(target);
    }

    public void followPlayer(Player player) {
        LOGGER.info("Action: Ensuring following player {}", player.getName().getString());
        if (this.goHomeGoal != null && this.goHomeGoal.isGoalActive()) { // Use isGoalActive()
            LOGGER.debug("Deactivating GoHomeGoal due to follow command.");
            this.goHomeGoal.stop();
        }
        // Ne pas effacer la cible ici si OwnerHurtByTarget est actif
        // CORRECTION: Utiliser .isActive
        if (this.ownerHurtByTargetGoal == null || !this.ownerHurtByTargetGoal.isActive) {
            this.setTarget(null);
            LOGGER.debug("Cleared attack target, relying on FollowPlayerGoal.");
        } else {
             LOGGER.debug("Not clearing target, OwnerHurtByTargetGoal is active.");
        }
    }

    public void goHome() {
        LOGGER.info("Action: Attempting to go home");
        if (this.homePosition != null && this.goHomeGoal != null) {
            LOGGER.debug("Activating GoHomeGoal to navigate to: {}", this.homePosition);
            this.setTarget(null); // Arrêter d'attaquer pour rentrer
            // Ajouter le goal s'il n'est pas déjà actif (ou le redémarrer)
            if (!this.goalSelector.getRunningGoals().anyMatch(goal -> goal.getGoal() == this.goHomeGoal)) {
                LOGGER.debug("Adding GoHomeGoal to active goals.");
                // Priorité 2 semble haute si on veut que l'assistance au joueur prime. Mettons plus bas.
                this.goalSelector.addGoal(8, this.goHomeGoal); // Priorité plus basse que FollowPlayer
            }
            // Forcer le démarrage si pas déjà en cours (parfois addGoal ne suffit pas immédiatement)
            if (!this.goHomeGoal.isGoalActive()) { // Use isGoalActive()
                this.goHomeGoal.start();
            } else {
                 // Si déjà actif mais bloqué, recalculer le chemin
                 this.goHomeGoal.moveMobToBlock();
            }
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
    public void setOwnerUUID(@Nullable java.util.UUID ownerUUID) {
       this.ownerUUID = ownerUUID;
       LOGGER.debug("Set owner UUID to: {}", ownerUUID);
    }

    @Nullable
    public java.util.UUID getOwnerUUID() {
       return this.ownerUUID;
    }

    @Nullable
    public Player getOwner() { // Déjà ajouté plus haut
        try {
            UUID uuid = this.getOwnerUUID();
            return uuid == null ? null : this.level().getPlayerByUUID(uuid);
        } catch (IllegalArgumentException illegalargumentexception) {
            return null;
        }
    }
    // --- End Owner UUID Methods ---

    public void selfHeal() {
        LOGGER.info("Action: Attempting to self-heal");
        if (this.getHealth() < this.getMaxHealth()) {
            int potionSlot = findInInventory(stack -> stack.is(Items.POTION) && (PotionUtils.getPotion(stack) == Potions.HEALING || PotionUtils.getPotion(stack) == Potions.STRONG_HEALING));
            if (potionSlot != -1) {
                ItemStack potionStack = inventory.getItem(potionSlot);
                LOGGER.debug("Found healing potion {} in slot {}, attempting to use on self.", potionStack.getDescriptionId(), potionSlot);

                java.util.List<MobEffectInstance> effects = PotionUtils.getMobEffects(potionStack);
                 if (!effects.isEmpty()) {
                     for(MobEffectInstance effectinstance : effects) {
                         this.addEffect(new MobEffectInstance(effectinstance.getEffect(), 1, effectinstance.getAmplifier()));
                     }
                     inventory.setItem(potionSlot, new ItemStack(Items.GLASS_BOTTLE));
                     LOGGER.info("Used healing potion on self.");
                     level().playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 1.0F, level().random.nextFloat() * 0.1F + 0.9F);
                 } else {
                      LOGGER.warn("Self-heal potion {} had no effects?", potionStack.getDescriptionId());
                      inventory.setItem(potionSlot, new ItemStack(Items.GLASS_BOTTLE));
                 }
            } else {
                LOGGER.warn("Action 'selfHeal': No healing potion found in inventory.");
            }
        } else {
             LOGGER.info("Action 'selfHeal': Already at full health.");
        }
    }

    // Méthode appelée par CheckSuppliesGoal pour ramasser items au sol
    public void gatherFood() {
        LOGGER.info("Action: Attempting to gather nearby loose food");
        this.setTarget(null); // Pas d'attaque pendant le ramassage
        if (this.goHomeGoal != null && this.goHomeGoal.isGoalActive()) { // Use isGoalActive()
            LOGGER.debug("Deactivating GoHomeGoal due to gatherFood command.");
            this.goHomeGoal.stop();
        }
        // Le goal GatherFoodGoal (priorité 7) devrait s'activer via son canUse() si les conditions sont bonnes
        LOGGER.debug("GatherFood action requested. Goal state managed by selector.");
        // On pourrait forcer son activation si besoin, mais laissons le sélecteur faire
        this.resetAutonomousCooldown(); // Mettre le cooldown après avoir décidé de ramasser
    }


    // Méthodes vides pour compatibilité avec CheckSuppliesGoal si appelées par erreur (devraient être retirées de CheckSuppliesGoal)
    public void huntForMeat() { LOGGER.warn("huntForMeat() called directly, should be handled by HuntForMeatGoal"); }
    public void forageCrops() { LOGGER.warn("forageCrops() called directly, should be handled by ForageCropGoal"); }


    public void prepareSupplies() {
        LOGGER.info("Action: prepareSupplies (Not Implemented)");
        LOGGER.warn("Action 'prepareSupplies' not fully implemented.");
    }

} // Fin SuperMomEntity class
