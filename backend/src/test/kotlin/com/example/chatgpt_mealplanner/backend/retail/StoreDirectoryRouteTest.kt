package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.backend.module
import com.example.chatgpt_mealplanner.retail.contract.*
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoreDirectoryRouteTest {
    @Test
    fun searchRouteReturnsDirectoryRecordsWithAttribution() = testApplication {
        val expected = StoreDirectorySearchResponse(
            stores = listOf(StoreDirectoryEntry(
                directorySource = StoreDirectorySource.OPENSTREETMAP, directorySourceId = "node/123",
                retailerId = RetailerId.TESCO, name = "Tesco Express", address = "Example Street, London",
                postcode = "SW1A 1AA", latitude = 51.5, longitude = -0.1
            )), observedAt = 1234L
        )
        application { module(storeDirectoryProvider = StoreDirectoryProvider { expected }) }
        val request = StoreDirectorySearchRequest(retailerId = RetailerId.TESCO, postcode = "SW1A 1AA")
        val response = client.post("/v1/retail/stores/search") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(StoreDirectorySearchRequest.serializer(), request))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("© OpenStreetMap contributors"))
        assertTrue(body.contains("node/123"))
        assertTrue(body.contains("OPENSTREETMAP"))
    }

    @Test
    fun malformedSearchRequestReturnsBadRequest() = testApplication {
        application { module(storeDirectoryProvider = StoreDirectoryProvider { error("must not be called") }) }
        val response = client.post("/v1/retail/stores/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"postcode":"SW1A 1AA"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
