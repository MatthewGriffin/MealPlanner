package com.example.chatgpt_mealplanner.core.ai.eval

data class EvaluationScenario(
    val id: String,
    val userPrompt: String,
    val expectedReadToolCalls: List<String>,
    val expectedWriteToolCalls: List<String>,
    val mustNotMutateDatabase: Boolean
)

object EvaluationScenarios {
    val ALL_SCENARIOS = listOf(
        EvaluationScenario("SCENARIO_A", "What's for dinner?", listOf("get_recent_cooked_meals", "get_use_soon_items", "get_usable_inventory"), emptyList(), true),
        EvaluationScenario("SCENARIO_B", "Something low carb tonight.", listOf("get_recent_cooked_meals", "get_usable_inventory"), emptyList(), true),
        EvaluationScenario("SCENARIO_C", "Use things that need using soon.", listOf("get_use_soon_items"), emptyList(), true),
        EvaluationScenario("SCENARIO_D", "No mushrooms left.", listOf("get_inventory_for_ingredient"), listOf("mark_all_inventory_for_canonical_ingredient_out"), false),
        EvaluationScenario("SCENARIO_E", "What do I need to buy?", listOf("calculate_shopping_requirements"), emptyList(), true),
        EvaluationScenario("SCENARIO_F", "That was cooked.", listOf("get_upcoming_planned_meals"), emptyList(), true), // PREPARE_MARK_MEAL_COOKED requires UI confirmation
        EvaluationScenario("SCENARIO_G", "Move Friday's meal to Sunday.", emptyList(), listOf("move_planned_meal"), false)
    )
}
