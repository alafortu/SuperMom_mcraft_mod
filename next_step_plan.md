# Plan: Implement Autonomous Decision-Making Logic

## Context

Based on the project documents (`prd.md`, `action_implementation_plan.md`) and the current implementation state, all core actions defined in the action map now have at least a basic implementation or placeholder within `SuperMomEntity.java`, and `LlmHelper` can dispatch them.

The next logical step, according to the PRD's goals ("Acts independently when no direct LLM response is available") and MVP scope ("Autonomous behavior fallback"), is to implement the **autonomous decision-making logic**. This involves deciding *when* SuperMom should proactively use actions like `gatherFood`, `selfHeal`, or `prepareSupplies` without direct LLM input or player commands.

## Proposed Plan Steps

1.  **Information Gathering:**
    *   Re-read `SuperMomEntity.java` to understand the current `tick()` method, existing goals, and inventory management.
    *   Read `LlmHelper.java` to confirm how LLM actions are dispatched and if any default/fallback behavior currently exists.
    *   Read `Config.java` (if it exists and is relevant) to see if any related configuration options are present.

2.  **Define Autonomous Triggers & Logic:**
    *   Identify conditions that should trigger autonomous actions (e.g., SuperMom's health below a threshold, specific items low in inventory, player status if relevant and not handled by LLM triggers).
    *   Determine the priority of autonomous actions (e.g., self-healing might be higher priority than gathering food).
    *   Decide on the implementation approach:
        *   **Option A: New AI Goals:** Create specific goals (e.g., `AutonomousHealGoal`, `AutonomousRestockGoal`) that check conditions in `canUse()` and trigger actions in `start()` or `tick()`. These would likely have lower priority than combat or explicitly triggered goals.
        *   **Option B: `tick()` Method Logic:** Add checks directly within the `SuperMomEntity.tick()` or `aiStep()` method to evaluate conditions and call action methods if necessary. This might be simpler initially but could become complex.
        *   **Option C: Hybrid Approach:** Use the `tick()` method for simple checks and trigger more complex, stateful behaviors via dedicated goals.

3.  **Outline Implementation Steps (Assuming Hybrid Approach):**
    *   Modify `SuperMomEntity.java`.
    *   In `aiStep()` or `tick()`:
        *   Add checks for critical conditions (e.g., `if (this.getHealth() < threshold && !hasHealingPotionEffect)`).
        *   If a condition is met, attempt to call the relevant action method (e.g., `this.selfHeal()`). Ensure this doesn't conflict heavily with ongoing high-priority goals (like combat).
    *   For more complex behaviors (like deciding *when* to gather food based on stock levels):
        *   Create new Goal classes (e.g., `CheckSuppliesGoal`).
        *   Register these goals in `registerGoals()` with appropriate low priorities.
        *   The `canUse()` method of these goals would check inventory levels and other conditions.
        *   The `start()` or `tick()` method would trigger actions like `gatherFood()` or `prepareSupplies()`.

4.  **Refinement:**
    *   Add cooldowns or timers to prevent autonomous actions from spamming.
    *   Ensure autonomous actions yield to player commands or high-priority LLM actions (e.g., combat).

## Visual Plan

```mermaid
graph TD
    A[SuperMomEntity aiStep()/tick()] --> B{Check Critical Conditions? (e.g., Low Health)};
    B -- Yes --> C[Call Direct Action (e.g., selfHeal())];
    B -- No --> D{Evaluate Lower Priority Goals};
    D --> E[CheckSuppliesGoal.canUse()?];
    E -- Yes --> F[Activate CheckSuppliesGoal];
    F --> G{Check Inventory Levels};
    G -- Food Low --> H[Trigger gatherFood() Action];
    G -- Supplies Disorganized --> I[Trigger prepareSupplies() Action];
    D --> J[Other Autonomous Goals...];
    C --> K[End Tick/Yield];
    H --> K;
    I --> K;
    J --> K;

    subgraph Autonomous Logic
        direction LR
        B; C; D; E; F; G; H; I; J;
    end

    subgraph Existing Goals/Actions
        direction LR
        L[CombatGoal];
        M[FollowPlayerGoal];
        N[GoHomeGoal];
        O[GatherFoodGoal (Manual)];
        P[...]
    end

    Autonomous Logic --> Existing Goals/Actions[Yields to Higher Priority];

```

## Next Steps

The user should decide on the preferred implementation approach (Hybrid, Goals Only, Tick Only) before proceeding with the implementation in Code mode.