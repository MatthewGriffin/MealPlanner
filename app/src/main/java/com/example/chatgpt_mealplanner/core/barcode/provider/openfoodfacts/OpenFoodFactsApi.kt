package com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface OpenFoodFactsApi {

    @GET
    suspend fun getProductByUrl(
        @Url url: String
    ): OpenFoodFactsResponse

    @GET("api/v2/product/{barcode}.json")
    suspend fun getProductByBarcode(
        @Path("barcode") barcode: String
    ): OpenFoodFactsResponse

    @GET("cgi/search.pl?search_simple=1&action=process&json=1")
    suspend fun searchProductsByName(
        @Query("search_terms") terms: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): OpenFoodFactsSearchResponse
}
