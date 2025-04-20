package com.alafortu.supermom.client; // Ou com.alafortu.supermom.client.event

import com.alafortu.supermom.SuperMomMod; // Vérifiez l'import
import com.alafortu.supermom.client.renderer.entity.SuperMomRenderer; // Vérifiez l'import
import com.alafortu.supermom.core.ModEntities; // Vérifiez l'import
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Enregistre cette classe pour écouter les événements du bus MOD, MAIS SEULEMENT COTE CLIENT
@Mod.EventBusSubscriber(modid = SuperMomMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    // Méthode appelée lors de l'événement d'enregistrement des renderers
    @SubscribeEvent
    public static void registerRenderers(final EntityRenderersEvent.RegisterRenderers event) {
        SuperMomMod.LOGGER.info("Registering Entity Renderers for SuperMomMod.");
        // On dit à Forge d'utiliser notre SuperMomRenderer pour notre entité SUPER_MOM
        event.registerEntityRenderer(ModEntities.SUPER_MOM.get(), SuperMomRenderer::new);
        SuperMomMod.LOGGER.info("SuperMom Entity Renderer Registered.");
    }

    // Ajoutez d'autres événements client ici si nécessaire (ex: RegisterKeyMappingsEvent)
}