# Plan: Implement Core Actions in SuperMomEntity

## Context

Based on the analysis of `prd.md`, `llm_integration_plan.md`, and `implementation_log.md`, the next critical step is to implement the actual behaviors corresponding to the actions determined by the LLM. The `implementation_log.md` indicates that while the LLM communication and action dispatching framework exists in `LlmHelper`, the core logic within `SuperMomEntity` is missing.

## Objective

Implement the core action behaviors within the `SuperMomEntity.java` class, translating action strings from the LLM (e.g., `"feed_player"`, `"attack_mob"`) into concrete actions performed by the SuperMom entity in the Minecraft world.

## Detailed Steps

1.  **Identify Target Class:** The primary focus will be `src/main/java/com/alafortu/supermom/entity/SuperMomEntity.java`.
2.  **Review Action Map:** Revisit the Action Map defined in `prd.md` and used in `LlmHelper.executeLlmAction`. The actions to implement are: `feed_player`, `heal_player`, `attack_mob`, `follow_player`, `go_home`, `self_heal`, `gather_food`, `prepare_supplies`.
3.  **Implement Action Methods/Goals:** For each action string:
    *   Determine the best implementation strategy:
        *   Create new public methods in `SuperMomEntity` (e.g., `public void feedPlayer(PlayerEntity player)`).
        *   Trigger specific AI Goals (e.g., `AttackMeleeGoal`, `FollowOwnerGoal`).
        *   A combination of both.
    *   Write the Java code to perform the action (e.g., inventory checks, applying effects, setting targets).
    *   Ensure correct interaction with the player and game world.
4.  **Integrate with `LlmHelper`:** Modify `LlmHelper.executeLlmAction` to call the new methods or trigger the AI goals on the `SuperMomEntity` instance.
5.  **Placeholder Logic:** Implement basic placeholders or logging for complex actions (`gather_food`, `prepare_supplies`) initially. Prioritize direct interaction actions.
6.  **Refine SuperMom Lookup (Recommended):** Consider addressing the TODO in `implementation_log.md` regarding optimizing the lookup of the `SuperMomEntity` instance.

## Visual Plan

```mermaid
graph TD
    A[LLM Response Received in LlmHelper] --> B{Parse Action String};
    B --> C{Call LlmHelper.executeLlmAction};
    C --> D{Switch on Action String};
    D -- "feed_player" --> E[Call SuperMomEntity.feedPlayer(player)];
    D -- "attack_mob" --> F[Call SuperMomEntity.setAttackTarget(mob)];
    D -- "heal_player" --> G[Call SuperMomEntity.healPlayer(player)];
    D -- "follow_player" --> H[Activate SuperMomEntity FollowGoal];
    D -- "go_home" --> I[Activate SuperMomEntity GoHomeGoal];
    D -- "..." --> J[Other Action Implementations];
    E --> K[SuperMom gives food];
    F --> L[SuperMom attacks mob];
    G --> M[SuperMom uses potion on player];
    H --> N[SuperMom follows player];
    I --> O[SuperMom moves to home location];
```

## Next Step

Proceed to implement this plan, likely by switching to Code mode.