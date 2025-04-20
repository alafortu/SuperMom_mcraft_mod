package com.alafortu.supermom.core;

import com.alafortu.supermom.SuperMomMod;
import com.alafortu.supermom.item.SuperMomSpawnEggItem; // Import the custom spawn egg class
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    // Create a DeferredRegister for Items
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, SuperMomMod.MODID);

    // Register the custom SuperMom Spawn Egg
    public static final RegistryObject<Item> SUPER_MOM_SPAWN_EGG = ITEMS.register("supermom_spawn_egg",
            () -> new SuperMomSpawnEggItem(
                    ModEntities.SUPER_MOM, // Supplier for the SuperMom entity type
                    0xADD8E6, // Light Blue background color (example)
                    0xFFC0CB, // Pink highlight color (example)
                    new Item.Properties() // Basic item properties
            ));

    // Method to register the DeferredRegister to the event bus
    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
        SuperMomMod.LOGGER.info("Registered ModItems DeferredRegister to MOD Event Bus.");
    }

    // Method to add items to creative tabs (optional but good practice)
    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(SUPER_MOM_SPAWN_EGG);
            SuperMomMod.LOGGER.info("Added SuperMom Spawn Egg to Spawn Eggs Creative Tab.");
        }
        // Add to other tabs if needed
        // if (event.getTabKey() == CreativeModeTabs.COMBAT) {
        //     event.accept(SOME_OTHER_ITEM);
        // }
    }
}