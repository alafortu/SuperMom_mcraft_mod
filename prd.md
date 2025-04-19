# SuperMom - Minecraft Mod Product Requirements Document (PRD)

## Overview
SuperMom is an intelligent AI companion for Minecraft, designed as a mod built using Forge. SuperMom follows the player, monitors their health, hunger, and surroundings, and communicates using a local LLM (via Ollama or OpenAI). She is autonomous, funny, and fiercely protective.

The mod uses the in-game chat for user interaction and provides real-time reactive behaviors based on context and player input.

---

## Goals
- Create a loyal and powerful AI companion that reacts naturally to chat commands or emotional expressions (in French or English).
- Leverage LLMs to process unstructured commands and game data.
- Integrate deeply with Minecraft's systems for combat, health monitoring, and environmental awareness.
- Make players laugh with SuperMom's witty and funny responses.

---

## Core Features

### 👩‍👦 SuperMom Companion
- Spawns at start of game or world load
- Follows player everywhere
- Can teleport to player if lost or lagging behind
- Has extremely high health and is hard to kill
- Wields a powerful sword and a machine gun with infinite ammo

### 💬 Chat Interaction
- Monitors Minecraft chat
- Responds to emotional, casual, or direct player commands (e.g., "J'ai faim!", "Aidez-moi!", "Go home!", "Help!")
- Replies in Minecraft chat using a humorous, witty, and protective tone designed to entertain and amuse players

### 🧠 AI Decision-Making (LLM)
- Player inputs and game state are sent to an LLM server (local Ollama or OpenAI)
- Triggers for sending data to the LLM:
  - When player sends a chat message
  - When player health is below 75%
  - When player hunger is below 75%
  - When any mob enters a configurable danger radius around the player or SuperMom
- LLM returns structured JSON response:
  ```json
  {
    "action": "feed_player",
    "chat": "Voici de la nourriture, mon chéri !"
  }
  ```
- Minecraft mod executes the action and displays chat message from SuperMom

### ⚔️ Combat & Danger Detection
- Monitors nearby mobs
- Tracks position of player and hostile entities
- Tracks SuperMom's position and mobs near her
- Attacks if:
  - Mob is targeting the player
  - Mob enters danger radius (user-configurable)
- Uses existing weapons with boosted stats

### 🍗 Support & Care
- Monitors health, hunger, potion effects, and general status in real-time
- Can:
  - Feed player
  - Heal player
  - Keep a stack of potions and use them
  - Fetch food or supplies in advance
- Also monitors her own health, hunger, and inventory
- Can self-heal, feed herself, and manage her own inventory proactively
- Acts independently when no direct LLM response is available, making decisions like gathering food, healing, or preparing supplies

### 🏠 Home Behavior
- Knows a home location (configurable or settable)
- Can go home if commanded

---

## Technical Architecture

### Minecraft Mod (Java, Forge)
- Version: Aligned with Forge + MrCrayfish Controllable Mod
- Monitors player events and world state
- Hooks:
  - `ClientChatReceivedEvent`
  - `PlayerTickEvent`
  - `EntityJoinWorldEvent`
- Sends JSON payload to AI bridge server only if:
  - Player sends chat
  - Player health < 75%
  - Player hunger < 75%
  - A mob enters the configurable danger radius around player or SuperMom
- Includes:
  - Player position
  - Hostile mob positions
  - Player health & hunger
  - SuperMom's health, hunger, position, and inventory state
  - Mobs near SuperMom

### AI Bridge Server (Python or Node.js)
- Receives POST requests from Minecraft
- Packages data into prompt for LLM
- Calls LLM (via Ollama HTTP API or OpenAI API)
- Parses response and returns structured JSON with action and reply

### Communication Protocol
**Request from Minecraft Mod:**
```json
{
  "player_chat": "J'ai faim!",
  "health": 12,
  "hunger": 6,
  "position": { "x": 100, "y": 64, "z": 200 },
  "mobs_nearby": [
    { "type": "zombie", "x": 102, "y": 64, "z": 198 },
    { "type": "skeleton", "x": 98, "y": 64, "z": 203 }
  ],
  "inventory": ["bread", "sword"],
  "supermom_status": {
    "health": 30,
    "hunger": 18,
    "position": { "x": 101, "y": 64, "z": 199 },
    "inventory": ["healing_potion", "bread"],
    "mobs_nearby": [
      { "type": "creeper", "x": 103, "y": 64, "z": 197 }
    ]
  },
  "language": "fr"
}
```

**Response from AI Bridge:**
```json
{
  "action": "feed_player",
  "chat": "Voici du pain, mon chéri !"
}
```

### Action Map
The mod will map LLM actions to in-game behaviors:
- `feed_player` → SuperMom gives food from her inventory to the player
- `heal_player` → SuperMom uses a potion on the player
- `attack_mob` → SuperMom targets and attacks a specified mob
- `follow_player` → SuperMom begins following the player again
- `go_home` → SuperMom returns to her home location
- `say_joke` → SuperMom posts a funny, comforting line in chat
- `self_heal` → SuperMom uses potion to restore her own health
- `gather_food` → SuperMom searches nearby area or prepares for food run
- `prepare_supplies` → SuperMom checks inventory and organizes items for emergencies

---

## MVP Scope
- Spawn and follow behavior
- Chat monitoring and LLM communication
- Conditional game state triggers for AI requests
- Basic LLM response parsing (chat + 1 action)
- Basic combat reaction logic
- Tracking and care for both player and SuperMom status
- Autonomous behavior fallback when LLM action is missing
- Configurable danger radius setting

---

## Future Features
- GUI interface for SuperMom (status, settings)
- Voice chat integration
- More complex inventory management
- Configurable personality types
- Emotional awareness based on tone/keywords

---

## Dev Tools
- **IDE**: VSCode
- **Minecraft Version**: Compatible with Forge + MrCrayfish Controllable Mod
- **LLM Runtime**: Ollama (preferred) or OpenAI API
- **Languages**: Java (mod), Python/Node (bridge)

---

## Notes
- SuperMom will act autonomously but always prioritize player safety.
- French and English language inputs should be supported natively.
- She should feel alive, warm, and a little bit sassy.
- Humor is essential—every line of dialog should have the potential to make players smile or laugh.

---

Let SuperMom protect and pamper you. Let’s go!

