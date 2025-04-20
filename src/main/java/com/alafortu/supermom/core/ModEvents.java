package com.alafortu.supermom.core;

// Imports existants (vérifiez qu'ils sont tous là)
import com.alafortu.supermom.SuperMomMod;
import com.alafortu.supermom.entity.SuperMomEntity;
import com.alafortu.supermom.llm.LlmHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist; // Gardé pour info, mais pas utilisé pour l'enregistrement
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.UUID; // Added for owner tracking

// PAS d'annotation @Mod.EventBusSubscriber ici

public class ModEvents {

    // Note: Les méthodes @SubscribeEvent statiques seront trouvées quand on enregistre
    // la classe via MinecraftForge.EVENT_BUS.register(ModEvents.class)

    /* <-- Start of commented out section
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) { // Utilise EntityJoinLevelEvent
        Level level = event.getLevel();
        // Agir seulement côté serveur et seulement pour les joueurs
        if (!level.isClientSide() && event.getEntity() instanceof ServerPlayer player) {
            UUID playerUUID = player.getUUID();
            ServerLevel serverLevel = (ServerLevel) level; // On sait que c'est un ServerLevel ici

            // Vérifier si une SuperMom appartenant à ce joueur existe DÉJÀ sur le serveur
            boolean alreadyHasSuperMom = false;
            // Itérer sur tous les niveaux chargés par le serveur (juste au cas où elle serait dans une autre dimension ?)
            // Ou juste le niveau actuel si vous préférez : serverLevel.getEntitiesOfType(...)
            if (serverLevel.getServer() != null) { // Sécurité si l'event est déclenché trop tôt ?
                 for (ServerLevel worldFromServer : serverLevel.getServer().getAllLevels()) {
                     // Chercher les SuperMom dans ce monde
                     for (net.minecraft.world.entity.Entity entity : worldFromServer.getAllEntities()) { // Iterate all entities
                         if (entity instanceof SuperMomEntity existingMom) { // Check type and cast
                         // Vérifier si elle a un propriétaire et si c'est ce joueur
                         if (playerUUID.equals(existingMom.getOwnerUUID())) {
                             alreadyHasSuperMom = true;
                             SuperMomMod.LOGGER.debug("Found existing SuperMom for player {} (UUID: {}). Skipping spawn.", player.getName().getString(), playerUUID);
                             break; // Sortir de la boucle interne
                         }
                         } // End if instanceof
                     } // End for entity
                     if (alreadyHasSuperMom) {
                         break; // Sortir de la boucle externe
                     }
                 }
            }


            // Si aucune SuperMom n'a été trouvée pour ce joueur, on en crée une nouvelle
            if (!alreadyHasSuperMom) {
                SuperMomMod.LOGGER.info("No existing SuperMom found for player {}. Spawning a new one.", player.getName().getString());
                SuperMomEntity superMom = new SuperMomEntity(ModEntities.SUPER_MOM.get(), serverLevel);
                // Lui assigner le propriétaire
                superMom.setOwnerUUID(playerUUID);
                // Positionner près du joueur
                Vec3 position = player.position();
                superMom.setPos(position.x + player.getRandom().nextDouble() * 2.0 - 1.0, position.y, position.z + player.getRandom().nextDouble() * 2.0 - 1.0);
                // Ajouter au monde
                serverLevel.addFreshEntity(superMom);
                SuperMomMod.LOGGER.info("Spawned new SuperMom for player {} (UUID: {})", player.getName().getString(), playerUUID);
                // Le code dans finalizeSpawn (pour l'épée) sera appelé automatiquement après addFreshEntity
            }
        }
    }
    */ // <-- End of commented out section

    // Sera appelé uniquement côté client car c'est un événement client
    @SubscribeEvent
    public static void onClientChatReceived(ClientChatReceivedEvent event) {
       Component messageComponent = event.getMessage();
        if (messageComponent != null) {
            String message = messageComponent.getString();

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return; // Sécurité

            String playerName = mc.player.getName().getString();

            // Utiliser une regex pour une détection plus souple du nom au début
            boolean isLikelyPlayerMessage = message.matches("^<" + playerName.replaceAll("([\\<\\(\\[\\{\\\\^\\-\\=\\$\\!\\|\\?\\*\\+\\.\\>])", "\\\\$1") + ">.*"); // Échapper les caractères spéciaux du nom

            // Identifier SuperMom (ajuster si son nom change)
            boolean isSuperMomMessage = message.startsWith("<SuperMom>");

            if (isLikelyPlayerMessage && !isSuperMomMessage) {
                String actualMessage = message.substring(message.indexOf('>') + 1).trim();
                SuperMomMod.LOGGER.debug("[SuperMomMod] Player Chat Detected: {}", actualMessage);

                new Thread(() -> {
                    try {
                        // Assurez-vous que LlmHelper et sa méthode existent
                        LlmHelper.processPlayerInteraction(actualMessage); // Appel LLM
                        SuperMomMod.LOGGER.debug("LLM processing initiated for: {}", actualMessage);
                    } catch (Exception e) {
                        SuperMomMod.LOGGER.error("Error during LLM processing thread", e);
                    }
                }, "SuperMom-LLM-Thread").start();

            }
        }
    }

    // PAS besoin de onAttributeCreate ici, car c'est dans ModEntities
}