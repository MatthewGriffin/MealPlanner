package com.example.chatgpt_mealplanner.backend.retail

import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySource
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenStreetMapStoreDirectoryTest {
    private val directory = OpenStreetMapStoreDirectory(HttpClient())

    @Test
    fun queryUsesOneBoundedNearbyShopScan() {
        val query = directory.buildQuery(
            com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest(
                retailerId = RetailerId.TESCO,
                postcode = "SW1A 1AA"
            ),
            51.5,
            -0.1
        )
        assertTrue(query.contains("[out:json][timeout:15]"))
        assertTrue(query.contains("""nwr["shop"~"^(supermarket|convenience)$"](around:5000,51.5,-0.1)"""))
        assertTrue(query.contains("out center tags"))
        assertTrue(!query.contains("""["name"~"""))
        assertTrue(!query.contains("""["brand"~"""))
        assertTrue(!query.contains("""["operator"~"""))
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
    fun parserAcceptsUtf8BomBeforeJsonResponse() {
        val raw = "\uFEFF" + """{"elements":[{"type":"node","id":42,"lat":51.5,"lon":-0.1,"tags":{"name":"Tesco","brand":"Tesco","shop":"supermarket"}}]}"""
        val stores = directory.parseOverpassResponse(raw, RetailerId.TESCO, 51.5, -0.1)
        assertEquals("node/42", stores.single().directorySourceId)
    }

    @Test
    fun parserDoesNotTreatFoodWarehouseAsIceland() {
        val raw = """{"elements":[
          {"type":"node","id":1,"lat":51.5,"lon":-0.1,"tags":{"name":"The Food Warehouse","brand":"The Food Warehouse","shop":"supermarket"}},
          {"type":"node","id":2,"lat":51.5,"lon":-0.1,"tags":{"name":"Iceland","brand":"Iceland","shop":"supermarket"}}
        ]}"""
        val stores = directory.parseOverpassResponse(raw, RetailerId.ICELAND, 51.5, -0.1)
        assertEquals(1, stores.size)
        assertEquals("node/2", stores.single().directorySourceId)
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
