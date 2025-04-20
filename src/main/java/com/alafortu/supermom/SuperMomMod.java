package com.alafortu.supermom; // Package principal

// Imports nécessaires (ajuster si ModEntities/ModEvents sont dans des sous-packages)
import com.alafortu.supermom.core.ModEntities;
import com.alafortu.supermom.core.ModEvents;
import com.alafortu.supermom.core.ModItems; // Added import for ModItems
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent; // Added import for creative tabs
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent; // Gardé pour exemple
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

// Annotation du Mod
@Mod(SuperMomMod.MODID)
public class SuperMomMod {
    public static final String MODID = "supermom";
    public static final Logger LOGGER = LogUtils.getLogger();

    // Constructeur standard
    public SuperMomMod() {
        LOGGER.info("SuperMom Mod Initializing!");

        // Récupérer le bus d'événements MOD
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // === Enregistrements sur le Bus d'Événements MOD ===

        // 1. Enregistrer les types d'entités (via la méthode statique de ModEntities)
        ModEntities.register(modEventBus);
        LOGGER.info("Registered ModEntities DeferredRegister to MOD Event Bus.");

        // 2. Enregistrer le listener pour la création des attributs d'entités
        //    (via la méthode statique registerAttributes de ModEntities)
        modEventBus.addListener(ModEntities::registerAttributes);
        LOGGER.info("Registered Entity Attribute Creation Listener to MOD Event Bus.");

        // 3. Enregistrer les Items (via la méthode statique de ModItems)
        ModItems.register(modEventBus); // Added call to register items

        // 4. Enregistrer le listener pour ajouter les items aux Creative Tabs
        modEventBus.addListener(ModItems::addCreative); // Added listener for creative tabs

        // 5. (Optionnel) Enregistrer l'événement FMLCommonSetupEvent
        // modEventBus.addListener(this::commonSetup);


        // === Enregistrements sur le Bus d'Événements FORGE ===

        // 6. Enregistrer la classe ModEvents pour ses listeners statiques (@SubscribeEvent)
        //    Ceci enregistre onEntityJoinLevel (commenté) et onClientChatReceived
        MinecraftForge.EVENT_BUS.register(ModEvents.class);
        LOGGER.info("Registered ModEvents class handlers to FORGE Event Bus.");

    }

    // Méthode optionnelle pour commonSetup
    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("SuperMom common setup running.");
        // Mettre ici du code de setup si nécessaire
    }

}