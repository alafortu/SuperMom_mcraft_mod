package com.alafortu.supermom.llm;

import com.alafortu.supermom.Config;
// Removed import com.aiboostediq.SuperMomMod.SuperMomMod; // No longer needed
import com.alafortu.supermom.entity.SuperMomEntity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity; // Needed for attack target
import net.minecraft.world.entity.monster.Monster; // For identifying hostile mobs
import net.minecraft.world.entity.player.Player; // Import Player
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component; // For chat messages
import net.minecraft.world.entity.ai.targeting.TargetingConditions; // For finding targets
import org.apache.logging.log4j.LogManager; // Import LogManager
import org.apache.logging.log4j.Logger; // Import Logger

import javax.annotation.Nullable; // For nullable parameters/fields
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture; // For async operations
import java.util.stream.Collectors;

public class LlmHelper {

    private static final Logger LOGGER = LogManager.getLogger("SuperMomMod/LlmHelper"); // Local logger
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final String OLLAMA_MODEL = "llama3"; // Or make configurable

    // --- Data Structures for JSON ---

    public static class LlmRequestPayload {
        @SerializedName("model")
        String model = OLLAMA_MODEL; // Specify the model for Ollama

        @SerializedName("prompt")
        String prompt; // We will construct the prompt based on game data

        @SerializedName("stream")
        boolean stream = false; // We want the full response, not streamed chunks

        // Keep the raw data structure separate for clarity if needed,
        // or embed it directly in the prompt construction.
        // For now, let's build the prompt string directly.
        // GameContext gameContext; // Could encapsulate all game data

        // Constructor to build the prompt
        public LlmRequestPayload(GameContext context) {
            this.prompt = buildPrompt(context);
        }

        private String buildPrompt(GameContext context) {
            // Construct a detailed prompt for the LLM based on PRD requirements
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("You are SuperMom, a fiercely protective, witty, and slightly sassy AI companion in Minecraft. ");
            promptBuilder.append("You speak in ").append(context.language).append(". ");
            promptBuilder.append("Your goal is to help the player, keep them safe, and make them laugh.\n\n");
            promptBuilder.append("Current Situation:\n");
            promptBuilder.append("- Player Health: ").append(context.health).append("/20\n");
            promptBuilder.append("- Player Hunger: ").append(context.hunger).append("/20\n");
            promptBuilder.append("- Player Position: ").append(context.position).append("\n");
            if (context.playerChat != null && !context.playerChat.isEmpty()) {
                promptBuilder.append("- Player Just Said: \"").append(context.playerChat).append("\"\n");
            }
            promptBuilder.append("- Mobs Near Player: ").append(context.mobsNearby.isEmpty() ? "None" : context.mobsNearby).append("\n");
            promptBuilder.append("- Player Inventory: ").append(context.inventory.isEmpty() ? "Empty" : context.inventory).append("\n");
            promptBuilder.append("- SuperMom Status:\n");
            promptBuilder.append("  - Health: ").append(context.superMomStatus.health).append("/").append(context.superMomStatus.maxHealth).append("\n"); // Assuming max health is needed
            promptBuilder.append("  - Hunger: ").append(context.superMomStatus.hunger).append("/20\n"); // Assuming max hunger is 20
            promptBuilder.append("  - Position: ").append(context.superMomStatus.position).append("\n");
            promptBuilder.append("  - Inventory: ").append(context.superMomStatus.inventory.isEmpty() ? "Empty" : context.superMomStatus.inventory).append("\n");
            promptBuilder.append("  - Mobs Near SuperMom: ").append(context.superMomStatus.mobsNearby.isEmpty() ? "None" : context.superMomStatus.mobsNearby).append("\n\n");

            promptBuilder.append("Based on this situation, decide the best single action and what to say. ");
            promptBuilder.append("Available actions: feed_player, heal_player, attack_mob, follow_player, go_home, say_joke, self_heal, gather_food, prepare_supplies, do_nothing. ");
            promptBuilder.append("Respond ONLY with a JSON object containing 'action' and 'chat'. Example: {\"action\": \"feed_player\", \"chat\": \"Here's some food, honey!\"}\n\n");
            promptBuilder.append("JSON Response:");

            return promptBuilder.toString();
        }
    }

     // Structure to hold game context for prompt generation
    public static class GameContext {
        String playerChat; // Can be null if triggered by game state
        float health;
        int hunger;
        Position position;
        List<MobInfo> mobsNearby;
        List<String> inventory;
        SuperMomStatus superMomStatus;
        String language = "en"; // Default to English, potentially detect later
        @Nullable SuperMomEntity superMomEntity; // Added to store the entity reference
    }

    public static class Position {
        double x, y, z;
        public Position(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        @Override public String toString() { return String.format("x=%.1f, y=%.1f, z=%.1f", x, y, z); }
    }

    public static class MobInfo {
        String type;
        Position position;
        public MobInfo(String type, Position position) { this.type = type; this.position = position; }
         @Override public String toString() { return String.format("%s at %s", type, position); }
    }

    public static class SuperMomStatus {
        float health;
        float maxHealth; // Added for context
        int hunger;
        Position position;
        List<String> inventory;
        List<MobInfo> mobsNearby;
    }

    // Ollama's actual response structure is nested
    public static class OllamaResponse {
        String model;
        @SerializedName("created_at")
        String createdAt;
        String response; // This contains the JSON string we want
        boolean done;
        // Other fields like total_duration, load_duration, etc. exist but we mainly need 'response'
    }

    // The structure we expect inside OllamaResponse.response
    public static class LlmResponsePayload {
        public String action;
        public String chat;
    }

    // --- Helper Methods ---

    /**
     * Gathers current game state relevant to the LLM.
     * @param playerChatMessage The chat message from the player (can be null if trigger is game state).
     * @return GameContext object populated with current data, including the SuperMomEntity instance if found.
     */
    public static GameContext gatherGameContext(String playerChatMessage) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = player.level(); // Use player.level() for consistency

        if (player == null || level == null) {
            LOGGER.error("Cannot gather context: Player or Level is null."); // Use local logger
            return null; // Or throw an exception
        }

        GameContext context = new GameContext();
        context.playerChat = playerChatMessage;
        context.health = player.getHealth();
        context.hunger = player.getFoodData().getFoodLevel();
        context.position = new Position(player.getX(), player.getY(), player.getZ());

        // Player Inventory (simplified to item names)
        context.inventory = player.getInventory().items.stream()
                .filter(itemStack -> !itemStack.isEmpty())
                .map(itemStack -> itemStack.getItem().getDescriptionId()) // or .getDisplayName().getString()
                .collect(Collectors.toList());

        // Find SuperMom instance near the player
        // Store the found entity directly in the context
        context.superMomEntity = level.getEntitiesOfClass(SuperMomEntity.class, player.getBoundingBox().inflate(50))
                                      .stream().findFirst().orElse(null);

        // Mobs near player (use config value)
        // TODO: Add mobDetectionRadius to Config.java
        double detectionRadius = 15.0; // Default value, replace with Config.mobDetectionRadius once added
        context.mobsNearby = findNearbyHostileMobs(level, player.position(), detectionRadius);

        // SuperMom Status
        if (context.superMomEntity != null) {
            SuperMomEntity superMom = context.superMomEntity; // Use the stored entity
            context.superMomStatus = new SuperMomStatus();
            context.superMomStatus.health = superMom.getHealth();
            context.superMomStatus.maxHealth = superMom.getMaxHealth();
            context.superMomStatus.hunger = 20; // TODO: Implement hunger for SuperMom if needed
            context.superMomStatus.position = new Position(superMom.getX(), superMom.getY(), superMom.getZ());
            context.superMomStatus.inventory = getSuperMomInventory(superMom); // Placeholder
            // Use a smaller radius for mobs near SuperMom herself
            context.superMomStatus.mobsNearby = findNearbyHostileMobs(level, superMom.position(), detectionRadius / 2.0); // Use corrected radius variable
        } else {
            // Handle case where SuperMom isn't found (maybe she's far away or hasn't spawned)
            context.superMomStatus = new SuperMomStatus(); // Provide default empty status
            context.superMomStatus.health = 0;
            context.superMomStatus.maxHealth = 0;
            context.superMomStatus.hunger = 0;
            context.superMomStatus.position = new Position(0,0,0);
            context.superMomStatus.inventory = new ArrayList<>();
            context.superMomStatus.mobsNearby = new ArrayList<>();
            // No need to log here every time, might be spammy if she's just far away
            // LOGGER.debug("SuperMom entity not found nearby during context gathering.");
        }

        // TODO: Language detection or configuration
        context.language = "en"; // Hardcoded for now

        return context;
    }

    private static List<MobInfo> findNearbyHostileMobs(Level level, Vec3 center, double radius) {
        List<MobInfo> mobs = new ArrayList<>();
        // Correct AABB creation
        AABB searchBox = new AABB(center.x - radius, center.y - radius, center.z - radius,
                                  center.x + radius, center.y + radius, center.z + radius);
        // Ensure we only get LivingEntities that are Monsters and are alive
        List<Monster> nearbyEntities = level.getEntitiesOfClass(Monster.class, searchBox, entity -> entity.isAlive() && !entity.isSpectator());

        for (Monster entity : nearbyEntities) {
            mobs.add(new MobInfo(
                entity.getType().getDescriptionId(), // e.g., "entity.minecraft.zombie"
                new Position(entity.getX(), entity.getY(), entity.getZ())
            ));
        }
        return mobs;
    }

     private static List<String> getSuperMomInventory(SuperMomEntity superMom) {
         // TODO: Implement actual inventory access for SuperMomEntity
         // This likely requires adding inventory capabilities to the entity itself.
         List<String> mockInventory = new ArrayList<>();
         mockInventory.add("item.minecraft.bread");
         mockInventory.add("item.minecraft.iron_sword");
         return mockInventory;
     }


    /**
     * Sends the prepared payload to the Ollama server.
     * @param payload The LlmRequestPayload object.
     * @return Optional containing the LlmResponsePayload if successful, empty otherwise.
     */
    public static Optional<LlmResponsePayload> sendToOllama(LlmRequestPayload payload) {
        // Use the loaded config value
        String ollamaUrl = Config.ollamaUrl; // Access static field directly
        String jsonRequest = GSON.toJson(payload);

        LOGGER.debug("Sending to Ollama ({}): {}", ollamaUrl, jsonRequest); // Use local logger

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                    .build();

            // Consider making this asynchronous in the future
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                LOGGER.debug("Received from Ollama: {}", response.body()); // Log raw response
                // Parse the outer Ollama response structure
                OllamaResponse ollamaResponse = GSON.fromJson(response.body(), OllamaResponse.class);
                if (ollamaResponse != null && ollamaResponse.response != null) {
                    // Now parse the inner JSON string containing action and chat
                    try {
                         // Attempt to parse the nested JSON string
                        LlmResponsePayload llmResponse = GSON.fromJson(ollamaResponse.response, LlmResponsePayload.class);
                        if (llmResponse == null || llmResponse.action == null || llmResponse.chat == null) {
                            LOGGER.error("Parsed LLM response is incomplete (action or chat is null): {}", ollamaResponse.response);
                            return Optional.empty();
                        }
                        return Optional.of(llmResponse);
                    } catch (com.google.gson.JsonSyntaxException jsonEx) {
                        LOGGER.error("Error parsing nested JSON from Ollama response: {}", ollamaResponse.response, jsonEx);
                        // Attempt to manually extract if possible (simple cases) - Less reliable
                        if (ollamaResponse.response.contains("\"action\"") && ollamaResponse.response.contains("\"chat\"")) {
                             LOGGER.warn("Attempting manual extraction as fallback...");
                             try {
                                 // Very basic extraction, prone to errors with complex chat content
                                 String action = extractJsonValue(ollamaResponse.response, "action");
                                 String chat = extractJsonValue(ollamaResponse.response, "chat");
                                 if (action != null && chat != null) {
                                     LlmResponsePayload fallbackResponse = new LlmResponsePayload();
                                     fallbackResponse.action = action;
                                     fallbackResponse.chat = chat;
                                     LOGGER.warn("Manual extraction successful (action={})", action); // Use local logger
                                     return Optional.of(fallbackResponse);
                                 } else {
                                     LOGGER.error("Manual extraction failed to find action or chat."); // Use local logger
                                     return Optional.empty();
                                 }
                             } catch (Exception manualEx) {
                                 LOGGER.error("Manual extraction failed", manualEx); // Use local logger
                                 return Optional.empty();
                             }
                        }
                        return Optional.empty();
                    }
                } else {
                     LOGGER.error("Error parsing Ollama response structure or response field is null: {}", response.body()); // Use local logger
                     return Optional.empty();
                }
            } else {
                LOGGER.error("Ollama request failed: {} - {}", response.statusCode(), response.body()); // Use local logger
                return Optional.empty();
            }
        } catch (Exception e) {
            LOGGER.error("Error sending request to Ollama: {}", e.getMessage(), e); // Use local logger
            // e.printStackTrace(); // Avoid printStackTrace in production code, use logger
            return Optional.empty();
        }
    }

    // Helper for manual JSON extraction (use with caution)
    private static String extractJsonValue(String jsonString, String key) {
        String keyPattern = "\"" + key + "\":\\s*\"";
        int startIndex = jsonString.indexOf(keyPattern);
        if (startIndex == -1) return null;
        startIndex += keyPattern.length();
        int endIndex = jsonString.indexOf("\"", startIndex);
        if (endIndex == -1) return null;
        // Handle basic escapes like \" and \\
        return jsonString.substring(startIndex, endIndex).replace("\\\"", "\"").replace("\\\\", "\\");
    }


    /**
     * Processes player interaction, gathers context, and communicates with LLM.
     * Called from event handlers. Runs network operations asynchronously.
     * @param playerChatMessage The message from the player chat (can be null).
     */
    public static void processPlayerInteraction(String playerChatMessage) {
        GameContext context = gatherGameContext(playerChatMessage);
        if (context == null) {
            return; // Error gathering context, already logged
        }

        LlmRequestPayload payload = new LlmRequestPayload(context);

        // Run network request off the main thread to avoid blocking
        CompletableFuture.supplyAsync(() -> sendToOllama(payload))
            .thenAcceptAsync(responseOpt -> { // Execute the response handling on the main thread
                responseOpt.ifPresent(response -> {
                    LOGGER.info("LLM Action: {}", response.action); // Use local logger
                    LOGGER.info("LLM Chat: {}", response.chat); // Use local logger

                    // Use the SuperMom instance already found in the context
                    SuperMomEntity superMom = context.superMomEntity;

                    // --- Step 4: Action Execution ---
                    executeLlmAction(response, superMom, context); // Pass context for potential target info

                    // --- Display Chat Message ---
                    displayChat(response.chat);
                });
            }, Minecraft.getInstance()); // Ensure the .thenAcceptAsync runs on the main Minecraft thread
    }

    /**
     * Executes the action specified by the LLM response.
     * This method MUST be called on the main Minecraft thread.
     * @param response The parsed response from the LLM.
     * @param superMom The SuperMomEntity instance (can be null).
     * @param context The game context used for the LLM request.
     */
    private static void executeLlmAction(LlmResponsePayload response, @Nullable SuperMomEntity superMom, GameContext context) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player; // Get player from Minecraft instance
        Level level = player.level(); // Use player.level()

        if (player == null || level == null) {
             LOGGER.error("Cannot execute action: Player or Level is null."); // Use local logger
             return;
        }

        // Ensure actions that require SuperMom are only attempted if she exists
        boolean requiresSuperMom = !List.of("say_joke", "do_nothing").contains(response.action);

        LOGGER.debug("Attempting to execute action: {}", response.action); // Use local logger

        if (requiresSuperMom && superMom == null) {
             LOGGER.warn("Cannot execute action '{}': SuperMom entity not found nearby.", response.action); // Use local logger
             // Optionally, display a message indicating SuperMom is missing or can't act
             displayChat("I can't do that right now, honey, I seem to be lost!"); // Keep chat display
             return;
        }


        // Use a switch statement to handle different actions based on PRD Action Map
        switch (response.action) {
            case "feed_player":
                LOGGER.debug("Executing action: Feed Player"); // Use local logger
                superMom.feedPlayer(player); // Pass the player entity
                break;
            case "heal_player":
                 LOGGER.debug("Executing action: Heal Player"); // Use local logger
                 superMom.healPlayer(player); // Pass the player entity
                break;
            case "attack_mob":
                 LOGGER.debug("Executing action: Attack Mob"); // Use local logger
                 // Find the nearest hostile mob to the player as a default target
                 // TODO: Enhance LLM prompt/response to specify target mob type/ID if needed
                 // TODO: Add mobDetectionRadius to Config.java
                 double attackRadius = 15.0; // Default value, replace with Config.mobDetectionRadius once added
                 LivingEntity target = findNearestHostileMobToPlayer(player, level, attackRadius); // Pass player directly
                 if (target != null) {
                     superMom.attackMob(target);
                 } else {
                     LOGGER.warn("Action 'attack_mob' requested, but no hostile mobs found nearby player."); // Use local logger
                     // Maybe add a chat message like "There's nothing to attack, dear."
                     displayChat("There's nothing to attack around you right now, sweetie.");
                 }
                break;
            case "follow_player":
                 LOGGER.debug("Executing action: Follow Player"); // Use local logger
                 superMom.followPlayer(player); // Pass the player entity
                break;
            case "go_home":
                 LOGGER.debug("Executing action: Go Home"); // Use local logger
                 superMom.goHome();
                break;
            case "say_joke":
                 LOGGER.debug("Executing action: Say Joke (handled by chat display)"); // Use local logger
                 // Action is just displaying the chat message.
                break;
            case "self_heal":
                 LOGGER.debug("Executing action: Self Heal"); // Use local logger
                 superMom.selfHeal();
                break;
            case "gather_food":
                 LOGGER.debug("Executing action: Gather Food"); // Use local logger
                 superMom.gatherFood();
                break;
            case "prepare_supplies":
                 LOGGER.debug("Executing action: Prepare Supplies"); // Use local logger
                 superMom.prepareSupplies();
                break;
             case "do_nothing":
                 LOGGER.debug("Executing action: Do Nothing"); // Use local logger
                 // No game action needed.
                 break;
            default:
                LOGGER.error("Unknown action received from LLM: {}", response.action); // Use local logger
                // Optionally display a confused message
                 displayChat("Hmm, I'm not sure what you mean by '" + response.action + "', dear.");
                break;
        }
    }

    // Helper to find the nearest hostile mob specifically to the player
    @Nullable
    private static LivingEntity findNearestHostileMobToPlayer(Player player, Level level, double radius) { // Use imported Player type
        // Use a TargetingCondition that checks for attackable, living monsters within the radius
        TargetingConditions targetConditions = TargetingConditions.forCombat().range(radius).selector(entity -> entity instanceof Monster);
        return level.getNearestEntity(
            level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(radius), e -> true), // Get nearby living entities first
            targetConditions, // Apply conditions
            player, // Relative to the player
            player.getX(), player.getY(), player.getZ() // Search origin
        );
    }


     private static void displayChat(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && message != null && !message.trim().isEmpty()) {
            // Format the message nicely, perhaps prefixing with "[SuperMom]"
            // Using Component.literal for simplicity. Could use TextComponent etc. for formatting.
            mc.player.sendSystemMessage(Component.literal("<SuperMom> " + message)); // Use sendSystemMessage for clarity
             // mc.gui.getChat().addMessage(Component.literal("<SuperMom> " + message)); // Alternative
             LOGGER.debug("Displayed chat: {}", message); // Use local logger
        }
     }
}