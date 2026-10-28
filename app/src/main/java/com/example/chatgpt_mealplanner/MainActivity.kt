package com.example.chatgpt_mealplanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import javax.inject.Inject
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.chatgpt_mealplanner.feature.chat.ChatViewModel
import com.example.chatgpt_mealplanner.feature.chat.ui.ChatScreen
import com.example.chatgpt_mealplanner.feature.inventory.InventoryViewModel
import com.example.chatgpt_mealplanner.feature.inventory.ui.InventoryScreen
import com.example.chatgpt_mealplanner.feature.mealplanner.MealPlannerViewModel
import com.example.chatgpt_mealplanner.feature.mealplanner.ui.MealPlannerScreen
import com.example.chatgpt_mealplanner.feature.receipt.ReceiptScannerScreen
import com.example.chatgpt_mealplanner.feature.scanner.ScannerState
import com.example.chatgpt_mealplanner.feature.scanner.ScannerViewModel
import com.example.chatgpt_mealplanner.feature.scanner.ui.ScannerScreen
import com.example.chatgpt_mealplanner.feature.settings.SettingsScreen
import com.example.chatgpt_mealplanner.feature.shopping.ShoppingViewModel
import com.example.chatgpt_mealplanner.feature.shopping.ui.ShoppingScreen
import com.example.chatgpt_mealplanner.feature.spend.ReceiptSpendScreen
import com.example.chatgpt_mealplanner.ui.theme.ChatGPT_MealPlannerTheme
import com.example.chatgpt_mealplanner.core.notification.AppNotificationManager
import com.example.chatgpt_mealplanner.feature.mealplanner.MealPlannerUiEvent
import dagger.hilt.android.AndroidEntryPoint

import androidx.compose.material.icons.filled.Devices
import com.example.chatgpt_mealplanner.feature.household.HouseholdScreen
import com.example.chatgpt_mealplanner.feature.household.HouseholdViewModel
import com.example.chatgpt_mealplanner.feature.household.JoinHouseholdScreen
import com.example.chatgpt_mealplanner.feature.household.JoinHouseholdViewModel
import com.example.chatgpt_mealplanner.feature.household.PairDeviceScreen
import com.example.chatgpt_mealplanner.feature.household.PairDeviceViewModel

enum class Screen(val label: String) {
    CHAT("Assistant"),
    INVENTORY("Inventory"),
    MEAL_PLANNER("Planner"),
    SHOPPING("Shopping"),
    SCANNER("Scanner"),
    SETTINGS("Settings"),
    RECEIPT_SPEND("Shopping Spend"),
    HOUSEHOLD("Household Sync"),
    PAIR_DEVICE("Pair New Device"),
    JOIN_HOUSEHOLD("Join Household")
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private data class NotificationRoute(val date: String?) {
        companion object {
            fun from(intent: android.content.Intent?): NotificationRoute? {
                val targetScreen = intent?.getStringExtra(AppNotificationManager.EXTRA_TARGET_SCREEN)
                val date = intent?.getStringExtra(AppNotificationManager.EXTRA_TARGET_DATE)
                return if (targetScreen == AppNotificationManager.TARGET_SCREEN_MEAL_PLANNER || date != null) NotificationRoute(date) else null
            }
        }
    }

    private val notificationRouteState = mutableStateOf<NotificationRoute?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationRouteState.value = NotificationRoute.from(intent)
    }

    @Inject
    lateinit var hostController: com.example.chatgpt_mealplanner.core.sync.server.PantrySyncHostController

    @Inject
    lateinit var appRoleResolver: com.example.chatgpt_mealplanner.core.sync.AppRoleResolver

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRouteState.value = NotificationRoute.from(intent)
        hostController.ensureHostStarted(this)
        enableEdgeToEdge()
        setContent {
            ChatGPT_MealPlannerTheme {
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { }
                )
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                var currentScreen by remember { mutableStateOf(if (notificationRouteState.value != null) Screen.MEAL_PLANNER else Screen.INVENTORY) }
                LaunchedEffect(notificationRouteState.value) {
                    if (notificationRouteState.value != null) currentScreen = Screen.MEAL_PLANNER
                }
                var lowStockItemToAdd by remember { mutableStateOf<com.example.chatgpt_mealplanner.core.model.InventoryItem?>(null) }
                var pendingRecipeDate by remember { mutableStateOf<String?>(null) }
                var mealChangeRequestForAi by remember { mutableStateOf<com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity?>(null) }
                val isFollower = remember { appRoleResolver.isFollower() }
                if (isFollower && currentScreen == Screen.CHAT) {
                    currentScreen = Screen.INVENTORY
                }
                val scannerViewModel: ScannerViewModel = hiltViewModel()
                val scannerState by scannerViewModel.state.collectAsState()
                val scannerUserStores by scannerViewModel.userStores.collectAsState()
                val isConfirming = scannerState is ScannerState.Confirming

                val topBarTitle = when {
                    currentScreen == Screen.SCANNER && isConfirming -> "Confirm Product"
                    currentScreen == Screen.SCANNER -> "Scan Barcode"
                    else -> currentScreen.label
                }

                val onBackClick: (() -> Unit)? = when {
                    currentScreen == Screen.SCANNER && isConfirming -> {
                        { scannerViewModel.onEvent(com.example.chatgpt_mealplanner.feature.scanner.ScannerUiEvent.ScanAnother) }
                    }
                    currentScreen == Screen.PAIR_DEVICE || currentScreen == Screen.JOIN_HOUSEHOLD -> {
                        { currentScreen = Screen.HOUSEHOLD }
                    }
                    currentScreen == Screen.SCANNER || currentScreen == Screen.SETTINGS || currentScreen == Screen.HOUSEHOLD -> {
                        { currentScreen = Screen.INVENTORY }
                    }
                    else -> null
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(topBarTitle) },
                            navigationIcon = {
                                if (onBackClick != null) {
                                    IconButton(onClick = onBackClick) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back"
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (currentScreen != Screen.HOUSEHOLD) {
                                    IconButton(onClick = { currentScreen = Screen.HOUSEHOLD }) {
                                        Icon(
                                            Icons.Default.Devices,
                                            contentDescription = "Household Sync"
                                        )
                                    }
                                }
                                if (currentScreen != Screen.SETTINGS) {
                                    IconButton(onClick = { currentScreen = Screen.SETTINGS }) {
                                        Icon(
                                            Icons.Default.Settings,
                                            contentDescription = "Settings"
                                        )
                                    }
                                }
                            }
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            if (!isFollower) {
                                NavigationBarItem(
                                    selected = currentScreen == Screen.CHAT,
                                    onClick = { currentScreen = Screen.CHAT },
                                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "Assistant") },
                                    label = { Text("Assistant") }
                                )
                            }
                            NavigationBarItem(
                                selected = currentScreen == Screen.INVENTORY,
                                onClick = { currentScreen = Screen.INVENTORY },
                                icon = { Icon(Icons.Default.Inventory, contentDescription = "Inventory") },
                                label = { Text("Inventory") }
                            )
                            NavigationBarItem(
                                selected = currentScreen == Screen.MEAL_PLANNER,
                                onClick = { currentScreen = Screen.MEAL_PLANNER },
                                icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "Planner") },
                                label = { Text("Planner") }
                            )
                            NavigationBarItem(
                                selected = currentScreen == Screen.SHOPPING,
                                onClick = { currentScreen = Screen.SHOPPING },
                                icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "Shopping") },
                                label = { Text("Shopping") }
                            )
                            NavigationBarItem(
                                selected = currentScreen == Screen.RECEIPT_SPEND,
                                onClick = { currentScreen = Screen.RECEIPT_SPEND },
                                icon = { Icon(Icons.Default.Receipt, contentDescription = "Spend") },
                                label = { Text("Spend") }
                            )
                        }
                    }
                ) { innerPadding ->
                    when (currentScreen) {
                        Screen.CHAT -> {
                            val chatViewModel: ChatViewModel = hiltViewModel()
                            LaunchedEffect(mealChangeRequestForAi, chatViewModel) {
                                mealChangeRequestForAi?.let { request ->
                                    chatViewModel.prepareMealChangeRequest(request)
                                    mealChangeRequestForAi = null
                                }
                            }
                            val state by chatViewModel.uiState.collectAsState()
                            ChatScreen(
                                uiState = state,
                                onEvent = chatViewModel::onEvent,
                                innerPadding = innerPadding
                            )
                        }

                        Screen.INVENTORY -> {
                            val inventoryViewModel: InventoryViewModel = hiltViewModel()
                            val state by inventoryViewModel.uiState.collectAsState()
                            InventoryScreen(
                                state = state,
                                onEvent = inventoryViewModel::onEvent,
                                onScanBarcodeClicked = { currentScreen = Screen.SCANNER },
                                onSearchProducts = inventoryViewModel::searchProductsByName,
                                modifier = Modifier.padding(innerPadding),
                                onAddLowStockToShopping = if (isFollower) null else { item ->
                                    lowStockItemToAdd = item
                                    currentScreen = Screen.SHOPPING
                                }
                            )
                        }

                        Screen.MEAL_PLANNER -> {
                            val mealPlannerViewModel: MealPlannerViewModel = hiltViewModel()
                            LaunchedEffect(notificationRouteState.value) {
                                notificationRouteState.value?.date?.let { date ->
                                    mealPlannerViewModel.onEvent(MealPlannerUiEvent.SelectDate(date))
                                }
                                notificationRouteState.value = null
                            }
                            LaunchedEffect(pendingRecipeDate) {
                                pendingRecipeDate?.let { date ->
                                    mealPlannerViewModel.onEvent(MealPlannerUiEvent.SelectDate(date))
                                }
                                pendingRecipeDate = null
                            }
                            val state by mealPlannerViewModel.uiState.collectAsState()
                            MealPlannerScreen(
                                uiState = state,
                                onEvent = mealPlannerViewModel::onEvent,
                                modifier = Modifier.padding(innerPadding),
                                onAdjustWithAiRequest = { request ->
                                    mealChangeRequestForAi = request
                                    currentScreen = Screen.CHAT
                                }
                            )
                        }

                        Screen.SHOPPING -> {
                            val shoppingViewModel: ShoppingViewModel = hiltViewModel()
                            LaunchedEffect(lowStockItemToAdd, shoppingViewModel) {
                                lowStockItemToAdd?.let { item ->
                                    shoppingViewModel.onEvent(com.example.chatgpt_mealplanner.feature.shopping.ShoppingUiEvent.RequestLowStockShortcut(item))
                                    lowStockItemToAdd = null
                                }
                            }
                            val state by shoppingViewModel.uiState.collectAsState()
                            ShoppingScreen(
                                uiState = state,
                                onEvent = shoppingViewModel::onEvent,
                                modifier = Modifier.padding(innerPadding),
                                onOpenMeal = { _, mealDate ->
                                    pendingRecipeDate = mealDate
                                    shoppingViewModel.onEvent(com.example.chatgpt_mealplanner.feature.shopping.ShoppingUiEvent.SelectItemDetail(null))
                                    currentScreen = Screen.MEAL_PLANNER
                                }
                            )
                        }

                        Screen.SCANNER -> {
                            ScannerScreen(
                                state = scannerState,
                                userStores = scannerUserStores,
                                onEvent = scannerViewModel::onEvent,
                                onNavigateBack = { currentScreen = Screen.INVENTORY },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }

                        Screen.SETTINGS -> {
                            SettingsScreen(
                                modifier = Modifier.padding(innerPadding)
                            )
                        }

                        Screen.RECEIPT_SPEND -> {
                            ReceiptSpendScreen(
                                modifier = Modifier.padding(innerPadding)
                            )
                        }

                        Screen.HOUSEHOLD -> {
                            val householdViewModel: HouseholdViewModel = hiltViewModel()
                            val state by householdViewModel.uiState.collectAsState()
                            HouseholdScreen(
                                uiState = state,
                                onPairNewDeviceClicked = { currentScreen = Screen.PAIR_DEVICE },
                                onJoinHouseholdClicked = { currentScreen = Screen.JOIN_HOUSEHOLD },
                                onRequestRevocation = householdViewModel::requestRevocationConfirmation,
                                onConfirmRevocation = householdViewModel::confirmRevocation,
                                onCancelRevocation = householdViewModel::cancelRevocation,
                                onSyncNowClicked = householdViewModel::triggerSyncNow,
                                onReauthorizeDevice = householdViewModel::createReauthorizationInvitation,
                                onDismissReauthorizationDialog = householdViewModel::dismissReauthorizationDialog,
                                onResolveConflictKeepMain = householdViewModel::resolveConflictKeepMain,
                                onResolveConflictApplyFollower = householdViewModel::resolveConflictApplyFollower,
                                onApprovePendingDiscard = householdViewModel::approvePendingDiscard,
                                onRejectPendingDiscard = householdViewModel::rejectPendingDiscard,
                                modifier = Modifier.padding(innerPadding)
                            )
                        }

                        Screen.PAIR_DEVICE -> {
                            val pairViewModel: PairDeviceViewModel = hiltViewModel()
                            val state by pairViewModel.uiState.collectAsState()
                            PairDeviceScreen(
                                uiState = state,
                                onGenerateNewCodeClicked = { pairViewModel.generateInvitation() },
                                onCancelClicked = { currentScreen = Screen.HOUSEHOLD },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }

                        Screen.JOIN_HOUSEHOLD -> {
                            val joinViewModel: JoinHouseholdViewModel = hiltViewModel()
                            val state by joinViewModel.uiState.collectAsState()
                            JoinHouseholdScreen(
                                uiState = state,
                                onQrScanned = { joinViewModel.onQrCodeScanned(it) },
                                onRetryClicked = { joinViewModel.retryScan() },
                                onDoneClicked = { currentScreen = Screen.HOUSEHOLD },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }
}
