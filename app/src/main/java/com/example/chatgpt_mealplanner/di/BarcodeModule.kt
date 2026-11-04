package com.example.chatgpt_mealplanner.di

import com.example.chatgpt_mealplanner.core.barcode.provider.OpenFoodFactsProductDataProvider
import com.example.chatgpt_mealplanner.core.barcode.provider.ProductDataProvider
import com.example.chatgpt_mealplanner.core.barcode.provider.openfoodfacts.OpenFoodFactsApi
import com.example.chatgpt_mealplanner.core.barcode.repository.ProductLookupRepository
import com.example.chatgpt_mealplanner.core.barcode.repository.ProductLookupRepositoryImpl
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .apply {
                if (com.example.chatgpt_mealplanner.BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    })
                }
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenFoodFactsApi(
        okHttpClient: OkHttpClient,
        json: Json
    ): OpenFoodFactsApi {
        val mediaType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl("https://world.openfoodfacts.org/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(mediaType))
            .build()
            .create(OpenFoodFactsApi::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BarcodeModule {

    @Binds
    @Singleton
    abstract fun bindProductDataProvider(
        impl: OpenFoodFactsProductDataProvider
    ): ProductDataProvider

    @Binds
    @Singleton
    abstract fun bindProductLookupRepository(
        impl: ProductLookupRepositoryImpl
    ): ProductLookupRepository
}
