package com.example.chatgpt_mealplanner.core.sync.server

import com.example.chatgpt_mealplanner.core.util.rethrowIfCancelled
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.MealChangeRequestDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.database.dao.StapleRuleDao
import com.example.chatgpt_mealplanner.core.database.entity.toSyncDto
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.engine.MainCanonicalSyncEngine
import com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto
import com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto
import com.example.chatgpt_mealplanner.sync.contract.SYNC_PROTOCOL_VERSION
import com.example.chatgpt_mealplanner.sync.contract.SyncContractValidation
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import com.example.chatgpt_mealplanner.sync.contract.ShoppingListSyncDto
import com.example.chatgpt_mealplanner.sync.contract.ShoppingItemSyncDto
import com.example.chatgpt_mealplanner.sync.contract.StapleRuleSyncDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readRemaining
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.netty.channel.nio.NioEventLoopGroup
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

import com.example.chatgpt_mealplanner.core.sync.enrollment.PantryEnrollmentInvitationManager
import com.example.chatgpt_mealplanner.sync.contract.EnrollmentRequestDto

class SnapshotTooLargeException(message: String) : RuntimeException(message)

enum class PantryServerState {
    STOPPED,
    STARTING,
    RUNNING,
    FAILED
}

@Singleton
open class PantrySyncServer @Inject constructor(
    private val identityManager: HouseholdIdentityManager,
    private val sessionManager: SyncSessionManager,
    private val syncEngine: MainCanonicalSyncEngine,
    private val canonicalSyncDao: CanonicalSyncDao,
    private val inventoryDao: InventoryDao,
    private val transactionRunner: DatabaseTransactionRunner? = null,
    private val invitationManager: PantryEnrollmentInvitationManager? = null,
    private val mealChangeRequestDao: MealChangeRequestDao? = null,
    private val mealDao: com.example.chatgpt_mealplanner.core.database.dao.MealDao? = null,
    private val shoppingDao: ShoppingDao? = null,
    private val stapleRuleDao: StapleRuleDao? = null
) {
    private var engine: io.ktor.server.engine.ApplicationEngine? = null
    private var serverState = PantryServerState.STOPPED

    open fun getServerState(): PantryServerState = serverState
    open fun isRunning(): Boolean = serverState == PantryServerState.RUNNING

    open fun markRunning() {
        if (serverState == PantryServerState.STARTING) {
            serverState = PantryServerState.RUNNING
        }
    }

    open fun markFailed() {
        try { engine?.stop(1000, 2000) } catch (_: Throwable) {}
        engine = null
        serverState = PantryServerState.FAILED
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var onBeforeSnapshotReadHook: (() -> Unit)? = null
    var onInsideSnapshotTransactionHook: (() -> Unit)? = null
    var onAfterSnapshotReadHook: (() -> Unit)? = null

    companion object {
        const val MAX_BOOTSTRAP_ITEMS = 5000
    }

    open suspend fun createBootstrapSnapshot(): BootstrapResponseDto {
        onBeforeSnapshotReadHook?.invoke()
        val householdId = identityManager.getOrCreateHouseholdIdentity().householdId

        val (latestSeq, invItems, mealData) = if (transactionRunner != null) {
            transactionRunner.runInTransaction {
                val seq = canonicalSyncDao.getLatestCanonicalSequence() ?: 0L
                onInsideSnapshotTransactionHook?.invoke()
                val items = inventoryDao.getAllItemsSync()
                Triple(seq, items, Pair(mealChangeRequestDao?.getAllRequestsSync().orEmpty().map { it.toSyncDto() },
                    mealDao?.getAllMealsWithIngredientsSync()?.map { row ->
                        val model = com.example.chatgpt_mealplanner.core.model.MealWithIngredients(
                            row.meal.toDomainModel(), row.ingredients.map { it.toDomainModel() })
                        json.decodeFromString<com.example.chatgpt_mealplanner.sync.contract.MealWithIngredientsSyncDto>(json.encodeToString(model))
                    }))
            }
        } else {
            val seq = canonicalSyncDao.getLatestCanonicalSequence() ?: 0L
            onInsideSnapshotTransactionHook?.invoke()
            val items = inventoryDao.getAllItemsSync()
            Triple(seq, items, Pair(mealChangeRequestDao?.getAllRequestsSync().orEmpty().map { it.toSyncDto() },
                    mealDao?.getAllMealsWithIngredientsSync()?.map { row ->
                        val model = com.example.chatgpt_mealplanner.core.model.MealWithIngredients(
                            row.meal.toDomainModel(), row.ingredients.map { it.toDomainModel() })
                        json.decodeFromString<com.example.chatgpt_mealplanner.sync.contract.MealWithIngredientsSyncDto>(json.encodeToString(model))
                    }))
        }

        val (mealRequests, mealPlan) = mealData
        val shoppingRows = shoppingDao?.getAllListsWithItemsAndSourcesSync()
        val shoppingLists = shoppingRows?.map { row ->
            val list = row.shoppingList
            ShoppingListSyncDto(list.id, list.name, list.startDate, list.endDate, list.status.name, list.createdAt, list.updatedAt)
        }
        val shoppingItems = shoppingRows?.flatMap { row -> row.items.map { wrapped ->
            val item = wrapped.item
            ShoppingItemSyncDto(
                id = item.id, shoppingListId = item.shoppingListId, canonicalIngredientId = item.canonicalIngredientId,
                displayName = item.displayName, requiredQuantity = item.requiredQuantity, requiredUnit = item.requiredUnit?.name,
                suggestedPurchaseQuantity = item.suggestedPurchaseQuantity, suggestedPurchaseUnit = item.suggestedPurchaseUnit?.name,
                packCount = item.packCount, reason = item.reason, priority = item.priority.name, status = item.status.name,
                optional = item.optional, sourceType = item.sourceType.name, hasMealRequirement = item.hasMealRequirement,
                hasStapleRestock = item.hasStapleRestock, mealShortfallQuantity = item.mealShortfallQuantity,
                stapleReserveTopUpQuantity = item.stapleReserveTopUpQuantity, expectedFinalInventory = item.expectedFinalInventory,
                expectedFinalInventoryUnit = item.expectedFinalInventoryUnit?.name, quantityUncertain = item.quantityUncertain,
                uncertaintyReason = item.uncertaintyReason, requirementConfidence = item.requirementConfidence.name,
                wasteRisk = item.wasteRisk.name, createdAt = item.createdAt, updatedAt = item.updatedAt
            )
        } }
        val stapleRules = stapleRuleDao?.getAllRulesSync()?.map { rule ->
            StapleRuleSyncDto(
                id = rule.id, canonicalIngredientId = rule.canonicalIngredientId, displayName = rule.displayName,
                enabled = rule.enabled, archivedByMerge = rule.archivedByMerge, mode = rule.mode.name,
                thresholdQuantity = rule.thresholdQuantity, thresholdUnit = rule.thresholdUnit?.name,
                targetQuantity = rule.targetQuantity, targetUnit = rule.targetUnit?.name,
                thresholdPackCount = rule.thresholdPackCount, targetPackCount = rule.targetPackCount,
                preferredStorageLocation = rule.preferredStorageLocation?.name, notes = rule.notes,
                createdAt = rule.createdAt, updatedAt = rule.updatedAt
            )
        }
        if ((mealPlan?.size ?: 0) > MAX_BOOTSTRAP_ITEMS) throw SnapshotTooLargeException("Meal plan too large")
        if (invItems.size > MAX_BOOTSTRAP_ITEMS || mealRequests.size > MAX_BOOTSTRAP_ITEMS ||
            (shoppingLists?.size ?: 0) > MAX_BOOTSTRAP_ITEMS || (shoppingItems?.size ?: 0) > MAX_BOOTSTRAP_ITEMS ||
            (stapleRules?.size ?: 0) > MAX_BOOTSTRAP_ITEMS) {
            throw SnapshotTooLargeException("Bootstrap snapshot item count ${invItems.size} exceeds maximum limit of $MAX_BOOTSTRAP_ITEMS")
        }

        val syncItems = invItems.map { invItem ->
            InventoryItemSyncDto(
                itemId = invItem.id,
                barcode = invItem.barcode,
                canonicalName = invItem.canonicalName,
                displayName = invItem.displayName,
                brand = invItem.brand,
                category = invItem.category.name,
                storageLocation = invItem.storageLocation.name,
                quantity = invItem.quantity,
                quantityUnit = invItem.quantityUnit?.name,
                notes = invItem.notes,
                status = invItem.status.name,
                revision = invItem.revision
            )
        }

        val dto = BootstrapResponseDto(
            protocolVersion = SYNC_PROTOCOL_VERSION,
            householdId = householdId,
            snapshotCanonicalSequence = latestSeq,
            inventoryItems = syncItems,
            mealRequests = mealRequests,
            mealPlan = mealPlan,
            shoppingLists = shoppingLists,
            shoppingItems = shoppingItems,
            stapleRules = stapleRules
        )
        onAfterSnapshotReadHook?.invoke()
        return dto
    }

    @Synchronized
    open fun start(keyStore: KeyStore, password: CharArray, port: Int = 8443) {
        if (serverState == PantryServerState.RUNNING || serverState == PantryServerState.STARTING) return

        if (engine != null) {
            try { engine?.stop(0, 0) } catch (_: Throwable) {}
            engine = null
        }

        serverState = PantryServerState.STARTING

        try {
            var env: io.ktor.server.engine.ApplicationEngineEnvironment? = null
            env = applicationEngineEnvironment {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = "pantry_tls_key",
                    keyStorePassword = { password },
                    privateKeyPassword = { password }
                ) {
                    this.port = port
                }

                    module {
                        install(ContentNegotiation) {
                            json(Json {
                                ignoreUnknownKeys = true
                                prettyPrint = true
                            })
                        }

                        routing {
                    get("/sync/v1/health") {
                        call.respond(buildJsonObject {
                            put("service", "pantry-household-sync")
                            put("protocolVersion", SYNC_PROTOCOL_VERSION)
                            put("status", "ok")
                        })
                    }

                    post("/sync/v1/enrollment/register") {
                        val bodyText = call.receiveBoundedText() ?: return@post
                        val reqDto = runCatching { json.decodeFromString<EnrollmentRequestDto>(bodyText) }.getOrNull()

                        if (reqDto == null) {
                            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                put("success", false)
                                put("errorCode", "INVALID_REQUEST_JSON")
                                put("errorMessage", "Malformed enrollment request JSON")
                            })
                            return@post
                        }

                        val invMgr = invitationManager
                        if (invMgr == null) {
                            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                put("success", false)
                                put("errorCode", "INVITATION_MANAGER_UNAVAILABLE")
                            })
                            return@post
                        }

                        val household = identityManager.getOrCreateHouseholdIdentity()
                        val response = invMgr.processEnrollment(
                            request = reqDto,
                            expectedHouseholdId = household.householdId,
                            expectedMainDeviceId = household.mainDeviceId
                        )

                        if (response.success) {
                            call.respond(response)
                        } else {
                            val status = when (response.errorCode) {
                                "INVITATION_NOT_FOUND", "INVITATION_EXPIRED", "INVITATION_ALREADY_CONSUMED" -> HttpStatusCode.Gone
                                "IDENTITY_MISMATCH", "INVITATION_IDENTITY_MISMATCH", "FOLLOWER_REVOKED" -> HttpStatusCode.Forbidden
                                else -> HttpStatusCode.BadRequest
                            }
                            call.respond(status, response)
                        }
                    }

                    post("/sync/v1/reauthorize") {
                        val bodyText = call.receiveBoundedText() ?: return@post
                        val req = runCatching { json.decodeFromString<com.example.chatgpt_mealplanner.sync.contract.ReauthorizationRequestDto>(bodyText) }.getOrNull()

                        if (req == null) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                com.example.chatgpt_mealplanner.sync.contract.ReauthorizationResponseDto(
                                    success = false,
                                    householdId = identityManager.getOrCreateHouseholdIdentity().householdId,
                                    followerDeviceId = "",
                                    errorCode = "MALFORMED_REQUEST",
                                    errorMessage = "Malformed re-authorization request JSON"
                                )
                            )
                            return@post
                        }

                        val invitationMgr = invitationManager
                        if (invitationMgr == null) {
                            call.respond(HttpStatusCode.ServiceUnavailable)
                            return@post
                        }

                        val household = identityManager.getOrCreateHouseholdIdentity()
                        val response = invitationMgr.processReauthorization(
                            request = req,
                            expectedHouseholdId = household.householdId,
                            expectedMainDeviceId = household.mainDeviceId
                        )

                        if (response.success) {
                            call.respond(response)
                        } else {
                            val status = when (response.errorCode) {
                                "INVITATION_NOT_FOUND", "INVITATION_EXPIRED", "INVITATION_ALREADY_CONSUMED" -> HttpStatusCode.Gone
                                "IDENTITY_MISMATCH", "TARGET_DEVICE_MISMATCH", "INVALID_SIGNATURE", "INVALID_INVITATION_PURPOSE" -> HttpStatusCode.Forbidden
                                else -> HttpStatusCode.BadRequest
                            }
                            call.respond(status, response)
                        }
                    }

                    post("/sync/v1/auth/challenge") {
                        val bodyText = call.receiveBoundedText() ?: return@post
                        val reqObj = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
                        val deviceId = reqObj?.get("deviceId")?.jsonPrimitive?.content

                        if (deviceId.isNullOrBlank()) {
                            call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "MISSING_DEVICE_ID") })
                            return@post
                        }

                        val household = identityManager.getOrCreateHouseholdIdentity()
                        val challenge = sessionManager.createChallenge(
                            householdId = household.householdId,
                            deviceId = deviceId
                        )
                        call.respond(challenge)
                    }

                    post("/sync/v1/auth/session") {
                        val bodyText = call.receiveBoundedText() ?: return@post
                        val reqObj = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
                        val challengeId = reqObj?.get("challengeId")?.jsonPrimitive?.content
                        val deviceId = reqObj?.get("deviceId")?.jsonPrimitive?.content
                        val signature = reqObj?.get("signature")?.jsonPrimitive?.content

                        if (challengeId.isNullOrBlank() || deviceId.isNullOrBlank() || signature.isNullOrBlank()) {
                            call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "INVALID_REQUEST_PAYLOAD") })
                            return@post
                        }

                        val token = sessionManager.verifyChallengeAndCreateSession(
                            challengeId = challengeId,
                            deviceId = deviceId,
                            signatureBase64 = signature
                        )

                        if (token == null) {
                            call.respond(HttpStatusCode.Unauthorized, buildJsonObject { put("error", "AUTHENTICATION_FAILED") })
                        } else {
                            call.respond(buildJsonObject {
                                put("sessionToken", token)
                                put("expiresIn", 3600)
                            })
                        }
                    }

                    get("/sync/v1/authenticated-ping") {
                        val token = call.request.headers["X-Pantry-Session-Token"]
                        val authenticatedDeviceId = sessionManager.validateSessionToken(token)

                        if (authenticatedDeviceId == null) {
                            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                                put("authenticated", false)
                                put("error", "UNAUTHORIZED")
                            })
                        } else {
                            call.respond(buildJsonObject {
                                put("authenticated", true)
                                put("deviceId", authenticatedDeviceId)
                                put("protocolVersion", SYNC_PROTOCOL_VERSION)
                            })
                        }
                    }

                    post("/sync/v1/operations") {
                        val token = call.request.headers["X-Pantry-Session-Token"]
                        val authenticatedDeviceId = sessionManager.validateSessionToken(token)

                        if (authenticatedDeviceId == null) {
                            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                                put("error", "UNAUTHORIZED")
                            })
                            return@post
                        }

                        val bodyText = call.receiveBoundedText() ?: return@post
                        val operationDto = runCatching { json.decodeFromString<SyncOperationDto>(bodyText) }.getOrNull()

                        if (operationDto == null) {
                            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                put("error", "MALFORMED_OPERATION_JSON")
                            })
                            return@post
                        }

                        val result = syncEngine.processOperation(authenticatedDeviceId, operationDto)
                        call.respond(result)
                    }

                    get("/sync/v1/feed") {
                        val reqStartNanos = System.nanoTime()
                        val token = call.request.headers["X-Pantry-Session-Token"]
                        val authenticatedDeviceId = sessionManager.validateSessionToken(token)

                        if (authenticatedDeviceId == null) {
                            val durMs = (System.nanoTime() - reqStartNanos) / 1_000_000
                            println("[SYNC_MAIN] feed request device=UNAUTHORIZED since=-1 limit=0")
                            println("[SYNC_MAIN] feed response status=401 entries=0 latestSequence=0 hasMore=false duration_ms=$durMs")
                            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                                put("error", "UNAUTHORIZED")
                            })
                            return@get
                        }

                        val sinceSeq = call.request.queryParameters["sinceSequence"]?.toLongOrNull() ?: 0L
                        val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                        val limit = rawLimit.coerceIn(1, 500)
                        val redactedDevice = authenticatedDeviceId.take(8)

                        println("[SYNC_MAIN] feed request device=$redactedDevice... since=$sinceSeq limit=$limit")

                        val fetchedOps = canonicalSyncDao.getFeedOperationsSince(sinceSeq, limit + 1)
                        val hasMore = fetchedOps.size > limit
                        val opsToReturn = fetchedOps.take(limit)

                        val latestSeq = canonicalSyncDao.getLatestCanonicalSequence() ?: 0L

                        val feedItems = opsToReturn.map { op ->
                            var itemSnapshot: InventoryItemSyncDto? = null
                            if (op.status == SyncOperationStatus.APPLIED.name && op.targetItemId != null) {
                                val invItem = inventoryDao.getItemById(op.targetItemId)
                                if (invItem != null) {
                                    itemSnapshot = InventoryItemSyncDto(
                                        itemId = invItem.id,
                                        barcode = invItem.barcode,
                                        canonicalName = invItem.canonicalName,
                                        displayName = invItem.displayName,
                                        brand = invItem.brand,
                                        category = invItem.category.name,
                                        storageLocation = invItem.storageLocation.name,
                                        quantity = invItem.quantity,
                                        quantityUnit = invItem.quantityUnit?.name,
                                        notes = invItem.notes,
                                        status = invItem.status.name,
                                        revision = invItem.revision
                                    )
                                }
                            }

                            val isResolutionResultCode = op.resultCode in listOf(
                                "RESOLVED_KEEP_MAIN",
                                "RESOLVED_APPLY_FOLLOWER",
                                "APPROVED_DISCARD",
                                "REJECTED_DISCARD"
                            )

                            val origOpId = if (isResolutionResultCode) {
                                runCatching {
                                    val obj = json.parseToJsonElement(op.payloadJson).jsonObject
                                    obj["originalOperationId"]?.jsonPrimitive?.content
                                }.getOrNull()
                            } else {
                                null
                            }

                            SyncFeedItemDto(
                                canonicalSequence = op.canonicalSequence,
                                operationId = op.operationId,
                                sourceDeviceId = op.deviceId,
                                operationType = SyncOperationType.valueOf(op.operationType),
                                targetItemId = op.targetItemId,
                                baseRevision = op.baseRevision,
                                payloadJson = op.payloadJson,
                                status = SyncOperationStatus.valueOf(op.status),
                                resultCode = op.resultCode,
                                resultingRevision = if (op.status == SyncOperationStatus.APPLIED.name) itemSnapshot?.revision else null,
                                canonicalItemSnapshot = itemSnapshot,
                                conflictId = if (op.status == SyncOperationStatus.CONFLICT.name) canonicalSyncDao.getConflictByOperationId(op.operationId)?.conflictId else null,
                                approvalId = if (op.status == SyncOperationStatus.PENDING_APPROVAL.name) canonicalSyncDao.getApprovalByOperationId(op.operationId)?.approvalId else null,
                                originalOperationId = origOpId,
                                occurredAt = op.createdAt
                            )
                        }

                        val responseDto = SyncFeedResponseDto(
                            householdId = identityManager.getOrCreateHouseholdIdentity().householdId,
                            latestCanonicalSequence = latestSeq,
                            items = feedItems,
                            hasMore = hasMore
                        )

                        val durMs = (System.nanoTime() - reqStartNanos) / 1_000_000
                        println("[SYNC_MAIN] feed response status=200 entries=${feedItems.size} latestSequence=$latestSeq hasMore=$hasMore duration_ms=$durMs")

                        call.respond(responseDto)
                    }

                    get("/sync/v1/bootstrap") {
                        val reqStartNanos = System.nanoTime()
                        val token = call.request.headers["X-Pantry-Session-Token"]
                        val authenticatedDeviceId = sessionManager.validateSessionToken(token)

                        if (authenticatedDeviceId == null) {
                            val durMs = (System.nanoTime() - reqStartNanos) / 1_000_000
                            println("[SYNC_MAIN] bootstrap request device=UNAUTHORIZED")
                            println("[SYNC_MAIN] bootstrap response status=401 items=0 watermark=0 duration_ms=$durMs")
                            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                                put("error", "UNAUTHORIZED")
                            })
                            return@get
                        }

                        val redactedDevice = authenticatedDeviceId.take(8)
                        println("[SYNC_MAIN] bootstrap request device=$redactedDevice...")

                        try {
                            val bootstrapDto = createBootstrapSnapshot()
                            val durMs = (System.nanoTime() - reqStartNanos) / 1_000_000
                            println("[SYNC_MAIN] bootstrap response status=200 items=${bootstrapDto.inventoryItems.size} watermark=${bootstrapDto.snapshotCanonicalSequence} duration_ms=$durMs")
                            call.respond(bootstrapDto)
                        } catch (e: SnapshotTooLargeException) {
                            val durMs = (System.nanoTime() - reqStartNanos) / 1_000_000
                            println("[SYNC_MAIN] bootstrap response status=413 items=0 watermark=0 duration_ms=$durMs")
                            call.respond(HttpStatusCode.PayloadTooLarge, buildJsonObject {
                                put("error", "SNAPSHOT_TOO_LARGE")
                                put("message", e.message ?: "Snapshot too large")
                            })
                        }
                    }
                }
            }
        }

        serverState = PantryServerState.STARTING
        var attempts = 0
        while (attempts < 5) {
            try {
                val newEngine = io.ktor.server.netty.NettyApplicationEngine(env!!) {}
                newEngine.start(wait = false)
                engine = newEngine
                return
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                attempts++
                if (attempts >= 5) {
                    serverState = PantryServerState.FAILED
                    engine = null
                    return
                }
                try { Thread.sleep(500L) } catch (_: Throwable) {}
            } catch (e: Throwable) {
                serverState = PantryServerState.FAILED
                engine = null
                throw e
            }
        }
    } catch (e: Throwable) {
        serverState = PantryServerState.FAILED
        engine = null
        throw e
    }
    }

    @Synchronized
    open fun stop() {
        val eng = engine ?: return
        try {
            eng.stop(300L, 1500L)
        } catch (_: Throwable) {}
        engine = null
        serverState = PantryServerState.STOPPED
    }

    private fun createPinnedOkHttpClient(pinnedCertFingerprint: String): okhttp3.OkHttpClient {
        if (!SyncContractValidation.isValidSha256Fingerprint(pinnedCertFingerprint)) {
            throw IllegalArgumentException("Missing, blank, or malformed TLS certificate fingerprint: $pinnedCertFingerprint")
        }

        val trustManager = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("Server certificate chain is empty")
                val serverCert = chain[0]
                val sha256Digest = java.security.MessageDigest.getInstance("SHA-256").digest(serverCert.encoded)
                val fingerprint = "SHA256:" + sha256Digest.joinToString(":") { "%02X".format(it) }

                val expected = pinnedCertFingerprint.trim().uppercase()
                if (fingerprint.uppercase() != expected) {
                    throw java.security.cert.CertificateException("TLS Certificate Fingerprint Mismatch. Expected: $expected, Actual: $fingerprint")
                }
            }
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }

        val hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
            try {
                val certs = session.peerCertificates
                if (certs.isNullOrEmpty()) return@HostnameVerifier false
                val serverCert = certs[0] as? java.security.cert.X509Certificate ?: return@HostnameVerifier false
                val sha256Digest = java.security.MessageDigest.getInstance("SHA-256").digest(serverCert.encoded)
                val fingerprint = "SHA256:" + sha256Digest.joinToString(":") { "%02X".format(it) }
                fingerprint.uppercase() == pinnedCertFingerprint.trim().uppercase()
            } catch (_: Exception) {
                false
            }
        }

        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())

        return okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .build()
    }

    open suspend fun verifyHealthProbe(
        hostIp: String,
        port: Int,
        expectedTlsFingerprint: String
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!SyncContractValidation.isValidSha256Fingerprint(expectedTlsFingerprint)) return@withContext false
        val client = try {
            createPinnedOkHttpClient(expectedTlsFingerprint)
        } catch (e: Exception) {
            rethrowIfCancelled()
            return@withContext false
        }

        val healthUrl = "https://$hostIp:$port/sync/v1/health"
        try {
            val request = okhttp3.Request.Builder().url(healthUrl).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val dto = runCatching { json.decodeFromString<HealthResponseDto>(body) }.getOrNull()
            val isOk = response.isSuccessful && dto != null && dto.status == "ok" && dto.service == "pantry-household-sync"
            if (isOk) {
                markRunning()
                true
            } else {
                markFailed()
                false
            }
        } catch (e: Exception) {
            markFailed()
            rethrowIfCancelled()
            false
        }
    }
}

internal const val MAX_SYNC_REQUEST_BODY_BYTES = 1_048_576L

/**
 * Reads a request body of at most [MAX_SYNC_REQUEST_BODY_BYTES], checking the bytes actually
 * received because Content-Length can be absent or wrong. Over the limit it responds 413 and
 * returns null, so the caller should stop handling the request.
 */
private suspend fun ApplicationCall.receiveBoundedText(): String? {
    val declaredLength = request.contentLength()
    val packet = if (declaredLength != null && declaredLength > MAX_SYNC_REQUEST_BODY_BYTES) {
        null
    } else {
        receiveChannel().readRemaining(MAX_SYNC_REQUEST_BODY_BYTES + 1)
    }
    if (packet == null || packet.remaining > MAX_SYNC_REQUEST_BODY_BYTES) {
        packet?.release()
        respond(HttpStatusCode.PayloadTooLarge, buildJsonObject { put("error", "REQUEST_TOO_LARGE") })
        return null
    }
    return packet.readText()
}
