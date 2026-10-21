package com.example.chatgpt_mealplanner.feature.mealplanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.MealWithIngredients
import com.example.chatgpt_mealplanner.feature.mealplanner.MealPlannerTab
import com.example.chatgpt_mealplanner.feature.mealplanner.MealPlannerUiEvent
import com.example.chatgpt_mealplanner.feature.mealplanner.MealPlannerUiState
import com.example.chatgpt_mealplanner.feature.mealplanner.MealPlannerViewModel
import com.example.chatgpt_mealplanner.feature.mealplanner.requestsForExistingMealIds
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun MealPlannerScreen(
    uiState: MealPlannerUiState,
    onEvent: (MealPlannerUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    onAdjustWithAiRequest: (com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity) -> Unit = {}
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            onEvent(MealPlannerUiEvent.ClearUserMessage)
        }
    }

    if (uiState.showAddDialog) {
        AddEditMealDialog(
            mealWithIngredients = uiState.mealToAddOrEdit,
            selectedDate = uiState.selectedDate,
            onDismiss = { onEvent(MealPlannerUiEvent.DismissAddEditDialog) },
            onSaveMeal = { meal, ingredients ->
                onEvent(MealPlannerUiEvent.SaveMeal(meal, ingredients))
            }
        )
    }

    val parentViewModel: MealPlannerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, parentViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> parentViewModel.setPlannerForeground(true)
                Lifecycle.Event.ON_STOP -> parentViewModel.setPlannerForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            parentViewModel.setPlannerForeground(true)
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            parentViewModel.setPlannerForeground(false)
        }
    }

    var currentDestination by remember { androidx.compose.runtime.mutableStateOf("planner_overview") }

    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { backStackEntry ->
            currentDestination = backStackEntry.destination.route ?: "planner_overview"
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (currentDestination == "planner_overview" && uiState.activeTab == MealPlannerTab.PLANNER) {
                FloatingActionButton(
                    onClick = { onEvent(MealPlannerUiEvent.RequestAddMeal) }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Planned Meal")
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "planner_overview",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("planner_overview") {
                Column(modifier = Modifier.fillMaxSize()) {
                    PrimaryTabRow(
                        selectedTabIndex = uiState.activeTab.ordinal
                    ) {
                        MealPlannerTab.values().forEach { tab ->
                            Tab(
                                selected = uiState.activeTab == tab,
                                onClick = { onEvent(MealPlannerUiEvent.SelectTab(tab)) },
                                text = { Text(tab.displayName) }
                            )
                        }
                    }

                    when (uiState.activeTab) {
                        MealPlannerTab.PLANNER -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                DateNavigationHeader(
                                    selectedDate = uiState.selectedDate,
                                    onDateSelected = { date -> onEvent(MealPlannerUiEvent.SelectDate(date)) }
                                )
                                val requestsForSelectedDate = requestsForExistingMealIds(
                                    requests = uiState.allChangeRequests,
                                    existingMealIds = uiState.mealsForSelectedDate.map { it.meal.id }.toSet()
                                )
                                MealPlanContent(
                                    meals = uiState.mealsForSelectedDate,
                                    isLoading = uiState.isMealPlanLoading,
                                    onMealClicked = { mealWithIngredients ->
                                        navController.navigate("recipe_details/${mealWithIngredients.meal.id}")
                                    },
                                    onDeleteClicked = { mealId -> onEvent(MealPlannerUiEvent.DeleteMeal(mealId)) },
                                    belowContent = {
                                        if (uiState.isFollower && requestsForSelectedDate.isNotEmpty()) {
                                            FollowerRequestStatusSection(requestsForSelectedDate, uiState.mealTitleMap)
                                        }
                                        if (!uiState.isFollower && requestsForSelectedDate.isNotEmpty()) {
                                            MainRequestReviewSection(
                                                requests = requestsForSelectedDate,
                                                mealTitleMap = uiState.mealTitleMap,
                                                onUpdateStatus = { reqId, status, resp ->
                                                    parentViewModel.updateRequestStatus(reqId, status, resp)
                                                },
                                                onAdjustWithAi = { request ->
                                                    parentViewModel.beginAiAdjustment(request.id) { onAdjustWithAiRequest(request) }
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        }
                        MealPlannerTab.LEFTOVERS -> {
                            LeftoversScreen(
                                leftovers = uiState.leftovers,
                                onConsumePortion = { id, qty -> onEvent(MealPlannerUiEvent.ConsumeLeftover(id, qty)) },
                                onDiscardLeftover = { id -> onEvent(MealPlannerUiEvent.DiscardLeftover(id)) }
                            )
                        }
                        MealPlannerTab.HISTORY -> {
                            MealHistoryScreen(
                                recentCookedMeals = uiState.recentCookedMeals,
                                onMealClicked = { mealId -> navController.navigate("recipe_details/$mealId") }
                            )
                        }
                    }
                }
            }
            composable("recipe_details/{plannedMealId}") { backStackEntry ->
                val plannedMealId = backStackEntry.arguments?.getString("plannedMealId") ?: ""
                RecipeDetailsScreen(
                    plannedMealId = plannedMealId,
                    viewModel = parentViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onCookNowClicked = { mealId ->
                        navController.navigate("cook_now/$mealId")
                    },
                    onAdjustWithAiRequest = { request ->
                        parentViewModel.beginAiAdjustment(request.id) { onAdjustWithAiRequest(request) }
                    }
                )
            }
            composable("cook_now/{plannedMealId}") { backStackEntry ->
                val plannedMealId = backStackEntry.arguments?.getString("plannedMealId") ?: ""
                CookNowScreen(
                    plannedMealId = plannedMealId,
                    viewModel = parentViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
fun DateNavigationHeader(
    selectedDate: String,
    onDateSelected: (String) -> Unit
) {
    val today = LocalDate.now()
    val currentDate = try {
        LocalDate.parse(selectedDate, DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (e: Exception) {
        today
    }

    val isToday = currentDate == today
    val isTomorrow = currentDate == today.plusDays(1)
    val isYesterday = currentDate == today.minusDays(1)

    val primaryTitle = when {
        isToday -> "Today"
        isTomorrow -> "Tomorrow"
        isYesterday -> "Yesterday"
        else -> currentDate.format(DateTimeFormatter.ofPattern("EEEE"))
    }

    val formattedSubtitle = currentDate.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                val prev = currentDate.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                onDateSelected(prev)
            }
        ) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Day")
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$primaryTitle, $formattedSubtitle",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (!isToday) {
                TextButton(
                    onClick = {
                        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
                        onDateSelected(todayStr)
                    }
                ) {
                    Text("Jump to Today", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    text = "Current Day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        IconButton(
            onClick = {
                val next = currentDate.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                onDateSelected(next)
            }
        ) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Next Day")
        }
    }
}

@Composable
fun MealPlanContent(
    meals: List<MealWithIngredients>,
    onMealClicked: (MealWithIngredients) -> Unit,
    onDeleteClicked: (String) -> Unit,
    isLoading: Boolean = false,
    belowContent: @Composable () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isLoading) {
            item(key = "loading_meal_plan") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Loading meal plan…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (meals.isEmpty()) {
            item(key = "empty_meal_plan") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "No meals planned for this date",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap + to plan a meal for today or select another date.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(meals, key = { it.meal.id }) { mealWithIngredients ->
                MealCard(
                    mealWithIngredients = mealWithIngredients,
                    onMealClicked = { onMealClicked(mealWithIngredients) },
                    onDeleteClicked = { onDeleteClicked(mealWithIngredients.meal.id) }
                )
            }
        }
        if (!isLoading) {
            item(key = "meal_request_section") {
                belowContent()
            }
        }
    }
}

@Composable
fun MealCard(
    mealWithIngredients: MealWithIngredients,
    onMealClicked: () -> Unit,
    onDeleteClicked: () -> Unit
) {
    val meal = mealWithIngredients.meal

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onMealClicked),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = meal.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = meal.status.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = when (meal.status) {
                        MealStatus.PLANNED -> MaterialTheme.colorScheme.primary
                        MealStatus.COOKED -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${meal.mealType.name} • ${meal.servings} servings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDeleteClicked) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete Meal",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun MainRequestReviewSection(
    requests: List<com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity>,
    onUpdateStatus: (String, String, String?) -> Unit,
    mealTitleMap: Map<String, String> = emptyMap(),
    onAdjustWithAi: (com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity) -> Unit = {}
) {
    if (requests.isEmpty()) return
    var showPast by remember { mutableStateOf(false) }
    val pastStatuses = setOf("COMPLETED", "DECLINED")
    val visibleRequests = requests.filter { (it.status in pastStatuses) == showPast }
    val pendingCount = requests.count { it.status == "PENDING" }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Incoming Meal Change Requests ($pendingCount Pending)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        TextButton(onClick = { showPast = !showPast }) {
            Text(if (showPast) "Show active requests" else "View past requests (${requests.count { it.status in pastStatuses }})")
        }
        if (visibleRequests.isEmpty()) {
            Text(if (showPast) "No past requests." else "No active requests.")
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(visibleRequests, key = { it.id }) { req ->
                val canDecide = req.status == "PENDING" || req.status == "DISCUSSED"
                var responseInput by remember(req.id, req.mainResponse) { mutableStateOf(req.mainResponse ?: "") }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Request: ${req.requestText}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(req.status.replace('_', ' '), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val mealTitle = mealTitleMap[req.mealId]
                        val mealLabel = if (!mealTitle.isNullOrBlank()) "Meal: $mealTitle" else "Meal ID: ${req.mealId}"
                        Text("$mealLabel • By: ${req.authorDeviceId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (canDecide) {
                            MealRequestTextField(value = responseInput, onValueChange = { if (it.length <= 2000) responseInput = it }, label = "Response / Discussion note...")
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onUpdateStatus(req.id, "ACCEPTED", responseInput.trim().ifBlank { null }) }, modifier = Modifier.weight(1f)) { Text("Accept") }
                                Button(onClick = { onUpdateStatus(req.id, "DECLINED", responseInput.trim().ifBlank { null }) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.weight(1f)) { Text("Decline") }
                                Button(onClick = { onUpdateStatus(req.id, "DISCUSSED", responseInput.trim().ifBlank { null }) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary), modifier = Modifier.weight(1f)) { Text("Discuss") }
                            }
                        } else {
                            req.mainResponse?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            if (req.status == "ACCEPTED") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = {
                                    onAdjustWithAi(req)
                                }) { Text("Adjust with AI") }
                            } else if (req.status == "IN_PROGRESS") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("AI adjustment in progress", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onAdjustWithAi(req) }) { Text("Continue with AI") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FollowerRequestStatusSection(
    requests: List<com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity>,
    mealTitleMap: Map<String, String> = emptyMap()
) {
    val requestsForExistingMeals = requestsForExistingMealIds(requests, mealTitleMap.keys)
    if (requestsForExistingMeals.isEmpty()) return

    var showPast by remember { mutableStateOf(false) }
    val pastStatuses = setOf("COMPLETED", "DECLINED")
    val visibleRequests = requestsForExistingMeals.filter { (it.status in pastStatuses) == showPast }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Meal change requests", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val completedCount = requestsForExistingMeals.count { it.status == "COMPLETED" }
        if (completedCount > 0) {
            Text(
                if (completedCount == 1) "Main completed your meal change request."
                else "Main completed $completedCount meal change requests.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        TextButton(onClick = { showPast = !showPast }) {
            Text(if (showPast) "Show active requests" else "View past requests (${requestsForExistingMeals.count { it.status in pastStatuses }})")
        }
        if (visibleRequests.isEmpty()) Text(if (showPast) "No past requests." else "No active requests.")
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleRequests, key = { it.id }) { request ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val mealTitle = mealTitleMap[request.mealId]
                        if (!mealTitle.isNullOrBlank()) Text("Meal: $mealTitle", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Text(request.requestText, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = when (request.status) {
                                "QUEUED" -> "Waiting to send"
                                "PENDING" -> "Awaiting Main's decision"
                                "ACCEPTED" -> "Accepted"
                                "IN_PROGRESS" -> "Main is adjusting the meal"
                                "COMPLETED" -> "Your request is complete"
                                "DECLINED" -> "Declined"
                                "DISCUSSED" -> "Discussed"
                                "SYNC_FAILED" -> "Could not send"
                                else -> request.status
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (request.status == "SYNC_FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        request.mainResponse?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
