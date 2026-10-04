package com.example.chatgpt_mealplanner.core.sync.server

import com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.engine.MainCanonicalSyncEngine
import com.example.chatgpt_mealplanner.sync.contract.InventoryItemSyncDto
import com.example.chatgpt_mealplanner.sync.contract.SYNC_PROTOCOL_VERSION
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedItemDto
import com.example.chatgpt_mealplanner.sync.contract.SyncFeedResponseDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationDto
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationStatus
import com.example.chatgpt_mealplanner.sync.contract.SyncOperationType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
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

@Singleton
open class PantrySyncServer @Inject constructor(
    private val identityManager: HouseholdIdentityManager,
    private val sessionManager: SyncSessionManager,
    private val syncEngine: MainCanonicalSyncEngine,
    private val canonicalSyncDao: CanonicalSyncDao,
    private val inventoryDao: InventoryDao
) {
    private var engine: CIOApplicationEngine? = null
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    open fun start(keyStore: KeyStore, password: CharArray, port: Int = 8443) {
        if (engine != null) return

        val env = applicationEngineEnvironment {
            connector {
                this.port = port
            }
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

                    post("/sync/v1/auth/challenge") {
                        val bodyText = call.receiveText()
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
                        val bodyText = call.receiveText()
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

                        val bodyText = call.receiveText()
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
                        val token = call.request.headers["X-Pantry-Session-Token"]
                        val authenticatedDeviceId = sessionManager.validateSessionToken(token)

                        if (authenticatedDeviceId == null) {
                            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                                put("error", "UNAUTHORIZED")
                            })
                            return@get
                        }

                        val sinceSeq = call.request.queryParameters["sinceSequence"]?.toLongOrNull() ?: 0L
                        val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                        val limit = rawLimit.coerceIn(1, 500)

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
                                occurredAt = op.createdAt
                            )
                        }

                        val responseDto = SyncFeedResponseDto(
                            householdId = identityManager.getOrCreateHouseholdIdentity().householdId,
                            latestCanonicalSequence = latestSeq,
                            items = feedItems,
                            hasMore = hasMore
                        )

                        call.respond(responseDto)
                    }

                    get("/sync/v1/bootstrap") {
                        val token = call.request.headers["X-Pantry-Session-Token"]
                        val authenticatedDeviceId = sessionManager.validateSessionToken(token)

                        if (authenticatedDeviceId == null) {
                            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                                put("error", "UNAUTHORIZED")
                            })
                            return@get
                        }

                        val householdId = identityManager.getOrCreateHouseholdIdentity().householdId
                        val latestSeq = canonicalSyncDao.getLatestCanonicalSequence() ?: 0L
                        val invItems = inventoryDao.getAllItemsSync()

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

                        val bootstrapDto = com.example.chatgpt_mealplanner.sync.contract.BootstrapResponseDto(
                            protocolVersion = SYNC_PROTOCOL_VERSION,
                            householdId = householdId,
                            snapshotCanonicalSequence = latestSeq,
                            inventoryItems = syncItems
                        )

                        call.respond(bootstrapDto)
                    }
                }
            }
        }

        engine = embeddedServer(CIO, env)
        engine?.start(wait = false)
    }

    @Synchronized
    open fun stop() {
        engine?.stop(1000, 2000)
        engine = null
    }

    open fun isRunning(): Boolean = engine != null
}
