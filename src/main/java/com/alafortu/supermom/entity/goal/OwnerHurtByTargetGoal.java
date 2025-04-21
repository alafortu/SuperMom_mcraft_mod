package com.alafortu.supermom.entity.goal; // Assurez-vous que ce package correspond à votre structure

import com.alafortu.supermom.entity.SuperMomEntity; // Importez votre classe SuperMomEntity
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import java.util.EnumSet;

public class OwnerHurtByTargetGoal extends TargetGoal {
    private final SuperMomEntity superMom;
    private LivingEntity attacker;
    private int timestamp;
    public boolean isActive = false; // Flag pour savoir si le goal est actif

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
        if (owner == null || owner.isSpectator()) {
            return false;
        } else {
            this.attacker = owner.getLastHurtByMob();
            int timeSinceHurt = owner.getLastHurtByMobTimestamp();

            // Utiliser des conditions moins strictes pour la vérification initiale canAttack
            TargetingConditions defenseCheckConditions = TargetingConditions.forCombat()
                .ignoreLineOfSight() // <-- Ignorer la ligne de vue pour cette vérification
                .selector(e -> e != null && e.isAlive() && !e.isAlliedTo(this.mob));

            return timeSinceHurt != this.timestamp
                && this.attacker != null
                && this.attacker != this.superMom
                && this.attacker != owner
                // CORRECTION: Utiliser les conditions modifiées
                && this.canAttack(this.attacker, defenseCheckConditions);
        }
    }

    /**
     * Execute a one shot task or start executing a continuous task
     */
    @Override
    public void start() {
        // Dire à SuperMom de cibler l'attaquant du propriétaire
        this.mob.setTarget(this.attacker); // 'this.mob' est hérité de TargetGoal et référence SuperMom
        Player owner = this.superMom.getOwner();
        if (owner != null) {
            // Mémoriser le timestamp pour éviter de réagir en boucle au même coup
            this.timestamp = owner.getLastHurtByMobTimestamp();
        }
        this.isActive = true; // <-- METTRE À JOUR LE FLAG
        SuperMomEntity.LOGGER.debug("OwnerHurtByTargetGoal started: SuperMom targeting {} who hurt {}", this.attacker != null ? this.attacker.getName().getString() : "null attacker", owner != null ? owner.getName().getString() : "Unknown Owner");

        // Important : Appeler super.start() pour la logique de base du TargetGoal
        super.start();
    }

     /**
     * Reset the task's internal state. Called when this task is interrupted by another one OR stops normally.
     */
    @Override
    public void stop() {
        this.isActive = false; // <-- METTRE À JOUR LE FLAG
        // Optionnel: Effacer la cible de SuperMom si c'était celle de ce goal.
        // Il est souvent préférable de laisser le système gérer la cible (ex: si HurtByTargetGoal prend le relais)
        // mais si on veut être sûr qu'elle arrête de cibler l'ancien attaquant :
        if (this.mob.getTarget() == this.attacker) {
             this.mob.setTarget(null);
        }
        this.attacker = null; // Oublier l'attaquant actuel
        super.stop(); // Important d'appeler super.stop()
        SuperMomEntity.LOGGER.debug("OwnerHurtByTargetGoal stopped.");
    }

     /**
      * Returns whether the goal should keep executing. (Continuation condition)
      * On utilise la logique de base de TargetGoal qui vérifie si la cible est toujours valide.
      * On ajoute une vérification pour s'assurer que le propriétaire est toujours blessé par CET attaquant.
      */
     @Override
     public boolean canContinueToUse() {
         Player owner = this.superMom.getOwner();
         // Arrêter si plus de propriétaire, si l'attaquant est mort/invalide, ou si le timestamp de blessure du propriétaire a changé (indiquant une autre source de dégât ou plus de dégât récent)
         if (owner == null || this.attacker == null || !this.attacker.isAlive() || owner.getLastHurtByMobTimestamp() != this.timestamp) {
              return false;
         }
         // Utiliser la vérification de base de TargetGoal (cible toujours valide, etc.)
         return super.canContinueToUse();
     }

     // Méthode publique pour vérifier l'état (utilisée par SuperMomEntity)
     public boolean isActive() {
          return this.isActive;
     }
}