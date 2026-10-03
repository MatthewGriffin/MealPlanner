package com.example.chatgpt_mealplanner.core.sync.server

import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.sync.contract.SYNC_PROTOCOL_VERSION
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
import io.ktor.server.response.respondText
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
    private val sessionManager: SyncSessionManager
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
