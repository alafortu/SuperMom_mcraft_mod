package com.alafortu.supermom.client.renderer.entity;

import com.alafortu.supermom.SuperMomMod; // Vérifiez l'import
import com.alafortu.supermom.entity.SuperMomEntity; // Vérifiez l'import
import net.minecraft.client.model.HumanoidModel; // Utilise le modèle humanoïde standard
import net.minecraft.client.model.geom.ModelLayers; // Pour obtenir le layer du modèle standard
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer; // Renderer basé sur le modèle humanoïde
import net.minecraft.resources.ResourceLocation;

public class SuperMomRenderer extends HumanoidMobRenderer<SuperMomEntity, HumanoidModel<SuperMomEntity>> {

    // Définir l'emplacement de la texture (même si elle n'existe pas encore)
    private static final ResourceLocation TEXTURE_LOCATION = new ResourceLocation(SuperMomMod.MODID, "textures/entity/supermom.png");

    public SuperMomRenderer(EntityRendererProvider.Context pContext) {
        // Le constructeur utilise le modèle Humanoid standard (couche PLAYER) et une ombre de taille 0.5f
        super(pContext, new HumanoidModel<>(pContext.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        // Si vous voulez le modèle slim (Alex) : ModelLayers.PLAYER_SLIM
    }

    @Override
    public ResourceLocation getTextureLocation(SuperMomEntity pEntity) {
        // Retourne toujours la même texture pour l'instant
        return TEXTURE_LOCATION;
    }
}