package com.alafortu.supermom.entity.goal; // Ajustez le package si nécessaire

import com.alafortu.supermom.entity.SuperMomEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import java.util.EnumSet;
import java.util.UUID; // Although not directly used, good practice if dealing with UUIDs often

public class OwnerHurtByTargetGoal extends TargetGoal {
    private final SuperMomEntity superMom;
    private LivingEntity attacker;
    private int timestamp;

    public OwnerHurtByTargetGoal(SuperMomEntity pSuperMom) {
        // false = ne vérifie pas la ligne de vue au début (elle ira le chercher)
        super(pSuperMom, false);
        this.superMom = pSuperMom;
        this.setFlags(EnumSet.of(Flag.TARGET)); // Nécessaire pour les TargetGoal
    }

    /**
     * Returns whether execution should begin. You can also read and cache variables here.
     */
    @Override
    public boolean canUse() {
        Player owner = this.superMom.getOwner();
        if (owner == null || owner.isSpectator()) { // Added spectator check
            return false; // Pas de propriétaire ou spectateur, pas de défense
        } else {
            // Récupérer qui a blessé le propriétaire
            this.attacker = owner.getLastHurtByMob();
            // Vérifier quand le propriétaire a été blessé
            int timeSinceHurt = owner.getLastHurtByMobTimestamp();
            // Condition : L'attaquant existe, le timestamp est différent du dernier enregistré (pour ne pas réagir sans cesse au même coup),
            // et l'attaquant est une cible valide pour SuperMom
            // Also check if the attacker is not the SuperMom itself or the owner
            return timeSinceHurt != this.timestamp
                   && this.attacker != null // Ensure attacker is not null
                   && this.attacker != this.superMom // Don't target self
                   && this.attacker != owner // Don't target owner
                   && this.canAttack(this.attacker, TargetingConditions.DEFAULT.selector(e -> e != null && e.isAlive() && !e.isAlliedTo(this.mob))); // Added more robust check
        }
    }

    /**
     * Execute a one shot task or start executing a continuous task
     */
    @Override
    public void start() {
        // Dire à SuperMom de cibler l'attaquant du propriétaire
        this.mob.setTarget(this.attacker);
        Player owner = this.superMom.getOwner();
        if (owner != null) {
            // Mémoriser le timestamp pour éviter de réagir en boucle au même coup
            this.timestamp = owner.getLastHurtByMobTimestamp();
        }
        // Use SuperMom's logger if available, otherwise use the mob's logger (less ideal)
        SuperMomEntity.LOGGER.debug("OwnerHurtByTargetGoal started: SuperMom targeting {} who hurt {}",
            this.attacker != null ? this.attacker.getName().getString() : "Unknown Attacker",
            owner != null ? owner.getName().getString() : "Unknown Owner");

        // Important : Appeler super.start() pour la logique de base du TargetGoal
        super.start();
    }

     /**
     * Reset the task's internal state. Called when this task is interrupted by another one
     */
    @Override
    public void stop() {
        // Clear the target when the goal stops
        this.mob.setTarget(null);
        this.attacker = null; // Clear the attacker reference
        SuperMomEntity.LOGGER.debug("OwnerHurtByTargetGoal stopped.");
        super.stop();
    }

    /**
     * Returns whether an in-progress EntityAIBase should continue executing
     */
    @Override
    public boolean canContinueToUse() {
        // Continue if the mob still has a target and the target is the attacker we initially identified
        // Also check if the owner is still being attacked by the same mob (or recently was)
        Player owner = this.superMom.getOwner();
        if (owner == null || this.attacker == null || !this.attacker.isAlive()) {
            return false;
        }
        // If the owner's last attacker changed, stop targeting the old one
        if (owner.getLastHurtByMob() != this.attacker) {
             // Allow a short grace period in case the timestamp hasn't updated yet
             if (owner.getLastHurtByMobTimestamp() == this.timestamp) {
                 // Keep targeting if the timestamp is the same, maybe the event hasn't propagated
             } else {
                 return false; // Owner is hurt by someone else now, or not hurt recently
             }
        }

        return super.canContinueToUse() && this.mob.getTarget() == this.attacker;
    }
}