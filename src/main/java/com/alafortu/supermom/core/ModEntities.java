package com.alafortu.supermom.core;

import com.alafortu.supermom.SuperMomMod;
import com.alafortu.supermom.entity.SuperMomEntity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, SuperMomMod.MODID);

    public static final RegistryObject<EntityType<SuperMomEntity>> SUPER_MOM =
            ENTITIES.register("supermom", () -> EntityType.Builder.of(SuperMomEntity::new, MobCategory.MISC) // Changed from CREATURE to MISC
                    .sized(0.75f, 0.75f)
                    .build("supermom"));

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
    }

    public static void registerAttributes(net.minecraftforge.event.entity.EntityAttributeCreationEvent event) {
        event.put(SUPER_MOM.get(), SuperMomEntity.createAttributes().build());
    }
}
