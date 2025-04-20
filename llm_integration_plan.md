# Plan: Chat Monitoring and LLM Communication (Local Ollama)

This plan outlines the steps to implement chat monitoring and communication with a local Ollama server for the SuperMom Minecraft mod.

## 1. Chat Monitoring

*   **Goal:** Capture player chat messages within the Minecraft mod.
*   **Implementation:**
    *   Hook into the `ClientChatReceivedEvent`.
    *   Create an event handler method to listen for this event.
    *   Extract the chat message from the event.
*   **File(s) to Modify:** `SuperMomMod.java` (or potentially a new class for event handling).

## 2. Data Preparation

*   **Goal:** Gather relevant game state data to send to the LLM.
*   **Implementation:**
    *   Collect the following data:
        *   Player chat message.
        *   Player health.
        *   Player hunger.
        *   Player position.
        *   Nearby mob information (type, position).
        *   SuperMom's health, hunger, position, and inventory state.
        *   Mobs near SuperMom.
        *   Language (French or English).
    *   Structure this data into a JSON payload as defined in the PRD.
*   **File(s) to Modify:** Potentially a new helper class for data gathering and formatting.

## 3. LLM Communication (Ollama)

*   **Goal:** Send the JSON payload to the local Ollama server and receive a response.
*   **Implementation:**
    *   Establish an HTTP connection to the Ollama server (assuming it's running locally).
    *   Send the JSON payload as a POST request to the Ollama API endpoint.
    *   Handle the response from the Ollama server.
*   **File(s) to Modify:** Potentially a new class for handling LLM communication.

## 4. Response Parsing and Action Execution

*   **Goal:** Parse the JSON response from the LLM and execute the corresponding action in the game.
*   **Implementation:**
    *   Parse the JSON response to extract the `action` and `chat` fields.
    *   Implement a mapping between the `action` values and in-game behaviors (as defined in the PRD's Action Map).
    *   Execute the corresponding in-game behavior based on the `action`.
    *   Display the `chat` message in the Minecraft chat.
*   **File(s) to Modify:** Potentially a new class for handling LLM response parsing and action execution.

## 5. Error Handling

*   **Goal:** Implement robust error handling to gracefully handle potential issues.
*   **Implementation:**
    *   Handle potential network errors when communicating with the Ollama server.
    *   Handle potential JSON parsing errors.
    *   Handle cases where the LLM returns an unknown action.
*   **File(s) to Modify:** All files involved in LLM communication and response parsing.

## 6. Configuration

*   **Goal:** Allow users to configure the Ollama server endpoint.
*   **Implementation:**
    *   Add a configuration option for the Ollama server URL.
    *   Read this configuration option in the mod.
*   **File(s) to Modify:** `Config.java` and any files that use the Ollama server URL.

## 7. Testing

*   **Goal:** Thoroughly test the chat monitoring and LLM communication functionality.
*   **Implementation:**
    *   Test various chat commands and scenarios.
    *   Test error handling.
    *   Test the configuration option.

## Sequence Diagram

```mermaid
sequenceDiagram
    participant Player
    participant Minecraft Mod
    participant Ollama Server
    Player->>Minecraft Mod: Sends Chat Message
    Minecraft Mod->>Minecraft Mod: Captures Chat Message
    Minecraft Mod->>Minecraft Mod: Gathers Game State Data
    Minecraft Mod->>Minecraft Mod: Formats Data into JSON Payload
    Minecraft Mod->>Ollama Server: Sends JSON Payload (POST Request)
    Ollama Server->>Minecraft Mod: Returns JSON Response
    Minecraft Mod->>Minecraft Mod: Parses JSON Response
    Minecraft Mod->>Minecraft Mod: Executes Action
    Minecraft Mod->>Player: Displays Chat Message