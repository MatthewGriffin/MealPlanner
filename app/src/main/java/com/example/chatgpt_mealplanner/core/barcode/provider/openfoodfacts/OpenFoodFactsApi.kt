package com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts

import retrofit2.http.GET
import retrofit2.http.Path
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
}
