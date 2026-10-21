package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreFormat
import com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenStreetMapStoreDirectoryTest {
    private val directory = OpenStreetMapStoreDirectory(HttpClient())

    @Test
    fun configuredApiKeyUsesOverpassChEndpointFirstWithPost() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("api.overpass.ch", request.url.host)
            assertEquals("/v1/test-key/interpreter", request.url.encodedPath)
            respond(
                content = """{"elements":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        })
        val keyedDirectory = OpenStreetMapStoreDirectory(client)
        keyedDirectory.configureApiKey("test-key")
        val response = keyedDirectory.search(
            com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                retailerId = RetailerId.TESCO,
                latitude = 51.5,
                longitude = -0.1
            )
        )
        assertTrue(response.stores.isEmpty())
        client.close()
    }

    @Test
    fun configuredKeyFailureDoesNotFallBackToPublicOverpassOrNominatim() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertEquals("api.overpass.ch", request.url.host)
            assertEquals("/v1/test-key/interpreter", request.url.encodedPath)
            respond(
                content = "Keyed endpoint unavailable at /v1/test-key/interpreter",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        })
        try {
            val keyedDirectory = OpenStreetMapStoreDirectory(client)
            keyedDirectory.configureApiKey("test-key")
            val error = runCatching {
                keyedDirectory.search(
                    com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                        retailerId = RetailerId.ASDA,
                        latitude = 53.27776,
                        longitude = -2.90437
                    )
                )
            }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("api.overpass.ch"))
            assertTrue(!error?.message.orEmpty().contains("test-key"))
        } finally {
            client.close()
        }
    }

    @Test
    fun keyedAsdaSearchExcludesExpressPetrolBranch() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertEquals("api.overpass.ch", request.url.host)
            respond(
                content = """{"elements":[{"type":"node","id":771,"lat":53.27776,"lon":-2.90437,"tags":{"name":"ASDA Ellesmere Port Express Petrol","brand":"ASDA","amenity":"fuel"}}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        })
        try {
            val keyedDirectory = OpenStreetMapStoreDirectory(client)
            keyedDirectory.configureApiKey("test-key")
            val response = keyedDirectory.search(
                com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                    retailerId = RetailerId.ASDA,
                    latitude = 53.27776,
                    longitude = -2.90437
                )
            )
            assertTrue(response.stores.none { it.name == "ASDA Ellesmere Port Express Petrol" })
        } finally {
            client.close()
        }
    }

    @Test
    fun fallsBackToActualOpenStreetMapNameAndPostcodeWhenOverpassFails() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "nominatim.openstreetmap.org") {
                assertEquals("/search", request.url.encodedPath)
                assertEquals("ASDA", request.url.parameters["q"])
                respond(
                    content = """[{"osm_type":"way","osm_id":123,"lat":"51.5","lon":"-0.1","category":"shop","type":"supermarket","name":"ASDA Superstore","display_name":"ASDA Superstore, Example Road, London, SW1A 1AA, United Kingdom","address":{"road":"Example Road","city":"London","postcode":"SW1A 1AA"},"extratags":{"brand":"ASDA"}}]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(
                    content = "Overpass unavailable",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(HttpHeaders.ContentType, "text/plain")
                )
            }
        })
        try {
            val fallbackDirectory = OpenStreetMapStoreDirectory(client)
            val response = fallbackDirectory.search(
                com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                    retailerId = RetailerId.ASDA,
                    latitude = 51.5,
                    longitude = -0.1
                )
            )
            assertEquals("ASDA Superstore", response.stores.single().name)
            assertEquals("SW1A 1AA", response.stores.single().postcode)
            assertEquals("way/123", response.stores.single().directorySourceId)
            assertTrue(response.warnings.single().contains("Overpass was unavailable"))
        } finally {
            client.close()
        }
    }

    @Test
    fun successfulOverpassSearchExcludesPetrolStationListingsWithoutShopTag() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "nominatim.openstreetmap.org") {
                assertEquals("ASDA", request.url.parameters["q"])
                respond(
                    content = """[{"osm_type":"way","osm_id":771,"lat":"53.27776","lon":"-2.90437","category":"amenity","type":"fuel","name":"ASDA Ellesmere Port Express Petrol","display_name":"ASDA Ellesmere Port Express Petrol, 118 Whitby Road, Ellesmere Port, CH65 6TF","address":{"house_number":"118","road":"Whitby Road","city":"Ellesmere Port","postcode":"CH65 6TF"}}]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(
                    content = """{"elements":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        })
        try {
            val asdaDirectory = OpenStreetMapStoreDirectory(client)
            val response = asdaDirectory.search(
                com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                    retailerId = RetailerId.ASDA,
                    latitude = 53.27776,
                    longitude = -2.90437
                )
            )
            assertTrue(response.stores.none { it.name.contains("Petrol", ignoreCase = true) })
        } finally {
            client.close()
        }
    }

    @Test
    fun icelandFallbackSearchAlsoFindsFoodWarehouseBranches() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "nominatim.openstreetmap.org") {
                when (request.url.parameters["q"]) {
                    "Iceland" -> respond(
                        content = """[]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                    "Food Warehouse" -> respond(
                        content = """[{"osm_type":"way","osm_id":456,"lat":"51.5","lon":"-0.1","category":"shop","type":"supermarket","name":"The Food Warehouse","display_name":"The Food Warehouse, Example Road, London, SW1A 1AA, United Kingdom","address":{"road":"Example Road","city":"London","postcode":"SW1A 1AA"}}]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                    else -> error("Unexpected Nominatim search term: ${request.url.parameters["q"]}")
                }
            } else {
                respond(
                    content = "Overpass unavailable",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(HttpHeaders.ContentType, "text/plain")
                )
            }
        })
        try {
            val fallbackDirectory = OpenStreetMapStoreDirectory(client)
            val response = fallbackDirectory.search(
                com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                    retailerId = RetailerId.ICELAND,
                    latitude = 51.5,
                    longitude = -0.1
                )
            )
            assertEquals(1, response.stores.size)
            assertEquals("Iceland Food Warehouse", response.stores.single().name)
            assertEquals(RetailerId.ICELAND, response.stores.single().retailerId)
            assertEquals("SW1A 1AA", response.stores.single().postcode)
        } finally {
            client.close()
        }
    }

    @Test
    fun sharedQuerySearchesAllSupportedRetailers() {
        val query = directory.buildMultiRetailerQuery(51.5, -0.1, 3000)
        assertTrue(query.contains("ASDA|Iceland|Food.*Warehouse|Tesco|Morrisons|Sainsbury"))
        assertTrue(query.contains("""nwr["shop"]["name"~"""))
        assertTrue(query.contains("""nwr["brand"~"""))
        assertTrue(query.contains("""nwr["operator"~"""))
        assertTrue(query.contains("around:3000,51.5,-0.1"))
        assertTrue(!query.contains("""["amenity"="fuel"]"""))
    }

    @Test
    fun allRetailerSearchReturnsMixedRetailersWithoutFiltering() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(
                content = """{"elements":[
                  {"type":"node","id":2001,"lat":51.5,"lon":-0.1,"tags":{"name":"ASDA Superstore","brand":"ASDA","shop":"supermarket","addr:postcode":"SW1A 1AA"}},
                  {"type":"node","id":2002,"lat":51.501,"lon":-0.101,"tags":{"name":"Tesco Express","brand":"Tesco","shop":"convenience","addr:postcode":"SW1A 1AB"}},
                  {"type":"node","id":2003,"lat":51.502,"lon":-0.102,"tags":{"name":"Iceland","brand":"Iceland","shop":"supermarket","addr:postcode":"SW1A 1AC"}}
                ]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        })
        try {
            val allStores = OpenStreetMapStoreDirectory(client).search(
                com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                    retailerId = RetailerId.ASDA,
                    latitude = 51.5,
                    longitude = -0.1,
                    radiusMeters = 3000,
                    searchAllRetailers = true
                )
            )
            assertEquals(3, allStores.stores.size)
            assertEquals(
                setOf(RetailerId.ASDA, RetailerId.TESCO, RetailerId.ICELAND),
                allStores.stores.map { it.retailerId }.toSet()
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun nearbyRetailerResultsAreSharedFromOneOverpassRequest() = runBlocking {
        var overpassRequests = 0
        val client = HttpClient(MockEngine { request ->
            assertEquals("overpass-api.de", request.url.host)
            overpassRequests++
            respond(
                content = """{"elements":[
                  {"type":"node","id":1001,"lat":51.5,"lon":-0.1,"tags":{"name":"ASDA Superstore","brand":"ASDA","shop":"supermarket","addr:postcode":"SW1A 1AA"}},
                  {"type":"node","id":1002,"lat":51.501,"lon":-0.101,"tags":{"name":"Tesco Express","brand":"Tesco","shop":"convenience","addr:postcode":"SW1A 1AB"}}
                ]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        })
        try {
            val sharedDirectory = OpenStreetMapStoreDirectory(client)
            val asda = sharedDirectory.search(
                com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                    retailerId = RetailerId.ASDA,
                    latitude = 51.5,
                    longitude = -0.1,
                    radiusMeters = 3000
                )
            )
            val tesco = sharedDirectory.search(
                com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                    retailerId = RetailerId.TESCO,
                    latitude = 51.5,
                    longitude = -0.1,
                    radiusMeters = 3000
                )
            )
            assertEquals("ASDA Superstore", asda.stores.single().name)
            assertEquals("Tesco Express", tesco.stores.single().name)
            assertTrue(tesco.fromCache)
            assertEquals(1, overpassRequests)
        } finally {
            client.close()
        }
    }

    @Test
    fun queryUsesBoundedRetailerFilteredShopScans() {
        val query = directory.buildQuery(
            com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                retailerId = RetailerId.TESCO,
                postcode = "SW1A 1AA"
            ),
            51.5,
            -0.1
        )
        assertTrue(query.contains("[out:json][timeout:60]"))
        assertTrue(query.contains("""nwr["shop"]["name"~"Tesco",i]"""))
        assertTrue(query.contains("""nwr["shop"]["brand"~"Tesco",i]"""))
        assertTrue(query.contains("""nwr["shop"]["operator"~"Tesco",i]"""))
        assertTrue(!query.contains("""["amenity"="fuel"]"""))
        assertTrue(query.contains("out center tags"))
    }

    @Test
    fun querySearchesRetailerIdentityEvenWhenShopTagIsMissing() {
        val query = directory.buildQuery(
            com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                retailerId = RetailerId.TESCO,
                postcode = "SW1A 1AA"
            ),
            51.5,
            -0.1
        )
        assertTrue(query.contains("""nwr["name"~"Tesco",i]"""))
        assertTrue(query.contains("""nwr["brand"~"Tesco",i]"""))
        assertTrue(query.contains("""nwr["operator"~"Tesco",i]"""))
    }

    @Test
    fun parserAcceptsCommonBranchesWithoutShopTags() {
        val raw = """{"elements":[
          {"type":"node","id":901,"lat":51.5,"lon":-0.1,"tags":{"name":"Tesco Express","brand":"Tesco"}},
          {"type":"node","id":902,"lat":51.501,"lon":-0.101,"tags":{"name":"Morrisons Daily","brand":"Morrisons"}},
          {"type":"node","id":903,"lat":51.502,"lon":-0.102,"tags":{"name":"Morrisons","brand":"Morrisons"}},
          {"type":"node","id":904,"lat":51.503,"lon":-0.103,"tags":{"name":"Sainsbury's Local","brand":"Sainsbury's"}}
        ]}"""
        val tesco = directory.parseOverpassResponse(raw, RetailerId.TESCO, 51.5, -0.1)
        val morrisons = directory.parseOverpassResponse(raw, RetailerId.MORRISONS, 51.5, -0.1)
        val sainsburys = directory.parseOverpassResponse(raw, RetailerId.SAINSBURYS, 51.5, -0.1)
        assertEquals("Tesco Express", tesco.single().name)
        assertEquals("Morrisons Daily", morrisons.first().name)
        assertEquals(2, morrisons.size)
        assertEquals("Sainsbury's Local", sainsburys.single().name)
    }

    @Test
    fun parserFiltersToRequestedRetailerAndRetainsDirectoryId() {
        val raw = """{"elements":[
          {"type":"node","id":100,"lat":51.5,"lon":-0.1,"tags":{"name":"Tesco Express","brand":"Tesco","shop":"convenience","addr:postcode":"SW1A 1AA","addr:street":"Example Road"}},
          {"type":"way","id":200,"center":{"lat":51.501,"lon":-0.101},"tags":{"name":"ASDA Superstore","brand":"ASDA","shop":"supermarket"}},
          {"type":"node","id":300,"lat":51.49,"lon":-0.11,"tags":{"name":"Tesco Express","brand":"Tesco","shop":"convenience","addr:postcode":"SW1A 1AA"}}
        ]}"""
        val stores = directory.parseOverpassResponse(raw, RetailerId.TESCO, 51.5, -0.1)
        assertEquals(2, stores.size)
        assertTrue(stores.all { it.retailerId == RetailerId.TESCO })
        assertEquals("node/100", stores.first().directorySourceId)
        assertEquals(StoreDirectorySource.OPENSTREETMAP, stores.first().directorySource)
        assertEquals("SW1A 1AA", stores.first().postcode)
        assertTrue(stores.first().distanceMeters!! < stores.last().distanceMeters!!)
    }

    @Test
    fun parserRejectsAsdaCafeAndUnnamedAddresslessBrandPoint() {
        val raw = """{"elements":[
          {"type":"node","id":701,"lat":51.5,"lon":-0.1,"tags":{"name":"ASDA Cafe","brand":"ASDA","amenity":"cafe"}},
          {"type":"node","id":702,"lat":51.501,"lon":-0.101,"tags":{"brand":"ASDA","shop":"supermarket"}},
          {"type":"node","id":703,"lat":51.502,"lon":-0.102,"tags":{"name":"ASDA Superstore","brand":"ASDA","shop":"supermarket","addr:street":"Example Road","addr:postcode":"SW1A 1AA"}}
        ]}"""
        val stores = directory.parseOverpassResponse(raw, RetailerId.ASDA, 51.5, -0.1)
        assertEquals(1, stores.size)
        assertEquals("ASDA Superstore", stores.single().name)
    }

    @Test
    fun parserExcludesAsdaExpressButKeepsRegularAsdaStore() {
        val raw = """{"elements":[
          {"type":"node","id":501,"lat":51.5,"lon":-0.1,"tags":{"name":"Asda Express","brand":"Asda Express","shop":"convenience","addr:postcode":"SW1A 1AA"}},
          {"type":"node","id":502,"lat":51.501,"lon":-0.101,"tags":{"name":"ASDA","brand":"ASDA","shop":"supermarket","addr:postcode":"SW1A 1AB"}}
        ]}"""
        val stores = directory.parseOverpassResponse(raw, RetailerId.ASDA, 51.5, -0.1)
        assertEquals(1, stores.size)
        assertTrue(stores.none { it.name.contains("Express", ignoreCase = true) })
        assertTrue(stores.any { it.name == "ASDA" })
    }

    @Test
    fun parserExcludesPetrolVariantsForOtherRetailersButKeepsRegularExpressShops() {
        val raw = """{"elements":[
          {"type":"node","id":701,"lat":51.5,"lon":-0.1,"tags":{"name":"Tesco Express Petrol","brand":"Tesco","shop":"convenience"}},
          {"type":"node","id":702,"lat":51.501,"lon":-0.101,"tags":{"name":"Tesco Express","brand":"Tesco","shop":"convenience"}},
          {"type":"node","id":703,"lat":51.502,"lon":-0.102,"tags":{"name":"Tesco","brand":"Tesco","shop":"convenience","amenity":"fuel"}}
        ]}"""
        val stores = directory.parseOverpassResponse(raw, RetailerId.TESCO, 51.5, -0.1)
        assertEquals(1, stores.size)
        assertEquals("Tesco Express", stores.single().name)
    }

    @Test
    fun queryUsesSelectedRetailerIdentityWithoutHardcodingBranchFormats() {
        val query = directory.buildQuery(
            com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                retailerId = RetailerId.TESCO,
                postcode = "SW1A 1AA"
            ),
            51.5,
            -0.1
        )
        assertTrue(query.contains("""nwr["shop"]["name"~"Tesco",i]"""))
        assertTrue(query.contains("""nwr["shop"]["brand"~"Tesco",i]"""))
        assertTrue(query.contains("""nwr["shop"]["operator"~"Tesco",i]"""))
        assertTrue(!query.contains("""["amenity"="fuel"]"""))
        assertTrue(!query.contains("Express"))
    }

    @Test
    fun parserAcceptsExplicitAsdaExpressWithoutShopTagButRejectsTaggedOptician() {
        val raw = """{"elements":[
          {"type":"node","id":611,"lat":51.5,"lon":-0.1,"tags":{"name":"ASDA Express","brand":"ASDA Express","amenity":"fuel"}},
          {"type":"node","id":612,"lat":51.501,"lon":-0.101,"tags":{"name":"ASDA Express Opticians","brand":"ASDA Express","shop":"optician"}}
        ]}"""
        val stores = directory.parseOverpassResponse(raw, RetailerId.ASDA, 51.5, -0.1)
        assertTrue(stores.isEmpty())
    }
    @Test
    fun parserExcludesRetailerBrandedOpticianButKeepsGroceryStores() {
        val raw = """{"elements":[
          {"type":"node","id":601,"lat":51.5,"lon":-0.1,"tags":{"name":"ASDA Opticians","brand":"ASDA","shop":"optician"}},
          {"type":"node","id":602,"lat":51.501,"lon":-0.101,"tags":{"name":"Asda Express","brand":"Asda Express","shop":"convenience"}},
          {"type":"node","id":603,"lat":51.502,"lon":-0.102,"tags":{"name":"ASDA Superstore","brand":"ASDA","shop":"supermarket"}}
        ]}"""
        val stores = directory.parseOverpassResponse(raw, RetailerId.ASDA, 51.5, -0.1)
        assertEquals(1, stores.size)
        assertTrue(stores.none { it.name.contains("Optician", ignoreCase = true) })
        assertTrue(stores.none { it.name.contains("Express", ignoreCase = true) })
        assertTrue(stores.any { it.name == "ASDA Superstore" })
    }
    @Test
    fun parserAcceptsUtf8BomBeforeJsonResponse() {
        val raw = "\uFEFF" + """{"elements":[{"type":"node","id":42,"lat":51.5,"lon":-0.1,"tags":{"name":"Tesco","brand":"Tesco","shop":"supermarket"}}]}"""
        val stores = directory.parseOverpassResponse(raw, RetailerId.TESCO, 51.5, -0.1)
        assertEquals("node/42", stores.single().directorySourceId)
    }

    @Test
    fun foodWarehouseQueryIncludesNarrowRecoveryForMissingShopTags() {
        val query = directory.buildQuery(
            com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                retailerId = RetailerId.FOOD_WAREHOUSE,
                postcode = "CH65 6TF"
            ),
            53.27776,
            -2.90437
        )
        assertTrue(query.contains("""nwr["shop"]["name"~"Food.*Warehouse",i]"""))
        assertTrue(query.contains("""nwr["name"~"Food.*Warehouse",i]"""))
        assertTrue(query.contains("""nwr["brand"~"Food.*Warehouse",i]"""))
        assertTrue(query.contains("""nwr["operator"~"Food.*Warehouse",i]"""))
    }

    @Test
    fun parserAcceptsFoodWarehouseListingWithoutShopTag() {
        val raw = """{"elements":[
          {"type":"node","id":801,"lat":53.27776,"lon":-2.90437,"tags":{"name":"The Food Warehouse","addr:postcode":"CH65 6TF"}}
        ]}"""
        val stores = directory.parseOverpassResponse(raw, RetailerId.FOOD_WAREHOUSE, 53.27776, -2.90437)
        assertEquals(1, stores.size)
        assertEquals("The Food Warehouse", stores.single().name)
        assertEquals(RetailStoreFormat.FOOD_WAREHOUSE, stores.single().format)
    }

    @Test
    fun icelandQueryIncludesFoodWarehouseBrandForIncompletelyTaggedBranch() {
        val query = directory.buildQuery(
            com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                retailerId = RetailerId.ICELAND,
                postcode = "CH65 0AP"
            ),
            53.2776,
            -2.9001
        )
        assertTrue(query.contains("""nwr["name"~"Food.*Warehouse",i]"""))
        assertTrue(query.contains("""nwr["shop"]["name"~"Iceland",i]"""))
    }

    @Test
    fun parserRecognizesFoodWarehouseAsIcelandBranch() {
        val raw = """{"elements":[
          {"type":"node","id":1,"lat":53.2776,"lon":-2.9001,"tags":{"name":"The Food Warehouse","brand":"The Food Warehouse","shop":"supermarket","addr:street":"Wellington Road","addr:postcode":"CH65 0AP"}},
          {"type":"node","id":2,"lat":51.5,"lon":-0.1,"tags":{"name":"Iceland","brand":"Iceland","shop":"supermarket"}}
        ]}"""
        val stores = directory.parseOverpassResponse(raw, RetailerId.ICELAND, 53.2776, -2.9001)
        assertEquals(2, stores.size)
        assertEquals("Iceland Food Warehouse", stores.first().name)
        assertEquals(RetailStoreFormat.ICELAND, stores.first().format)
        assertEquals("CH65 0AP", stores.first().postcode)
    }

    @Test
    fun parserSupportsWayCenterCoordinates() {
        val raw = """{"elements":[{"type":"way","id":42,"center":{"lat":51.52,"lon":-0.12},"tags":{"name":"Sainsbury's Local","brand":"Sainsbury's","shop":"convenience"}}]}"""
        val store = directory.parseOverpassResponse(raw, RetailerId.SAINSBURYS, 51.5, -0.1).single()
        assertEquals(51.52, store.latitude)
        assertEquals(-0.12, store.longitude)
        assertEquals("way/42", store.directorySourceId)
        assertTrue(store.distanceMeters!! > 0)
    }
}
