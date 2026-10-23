package com.example.chatgpt_mealplanner.core.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.chatgpt_mealplanner.backend.retail.IcelandProductParser
import com.example.chatgpt_mealplanner.backend.retail.IcelandSourceClient
import com.example.chatgpt_mealplanner.core.repository.RetailRepository
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * Imports Iceland's public online catalogue in bounded pages.
 *
 * Products are published to Room only after every page in the crawl succeeds, so an
 * interrupted crawl cannot replace the usable cache with a partial catalogue. The source
 * must expose a usable empty-query index and stable pagination metadata for this to work.
 */
@HiltWorker
class IcelandCatalogueRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val retailRepository: RetailRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val lastSuccess = preferences.getLong(KEY_LAST_SUCCESS, 0L)
        if (!inputData.getBoolean(KEY_FORCE_REFRESH, false) &&
            lastSuccess > 0L && System.currentTimeMillis() - lastSuccess < TimeUnit.DAYS.toMillis(7)) {
            IcelandCatalogueRefreshScheduler.scheduleWeekly(applicationContext)
            return Result.success()
        }

        val httpClient = HttpClient(OkHttp)
        return try {
            val source = IcelandSourceClient(httpClient)
            val parser = IcelandProductParser(retailerId = RetailerId.ICELAND)
            val json = Json { ignoreUnknownKeys = true }
            val allProducts = LinkedHashMap<String, com.example.chatgpt_mealplanner.retail.contract.RetailProductWithOffer>()
            var expectedPages: Int? = null
            var page = 0

            while (page < MAX_PAGES) {
                if (isStopped) return Result.retry()
                val raw = source.fetchAlgoliaCataloguePageJson(page, PAGE_SIZE)
                    ?: return Result.retry()
                val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?: return Result.retry()
                val totalPages = root["nbPages"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: return Result.retry()
                if (totalPages <= 0 || totalPages > MAX_PAGES || page >= totalPages) return Result.retry()
                if (expectedPages != null && expectedPages != totalPages) return Result.retry()
                expectedPages = totalPages

                val parsed = parser.parseAlgoliaResponse(raw).map { it.productWithOffer }
                // A page with hits but no parseable product records indicates source drift.
                val hitCount = root["hits"]?.let { runCatching { it as kotlinx.serialization.json.JsonArray }.getOrNull()?.size } ?: 0
                if (hitCount > 0 && parsed.isEmpty()) return Result.retry()
                parsed.forEach { product ->
                    allProducts[product.product.retailerProductId] = product
                }
                page++
                if (page >= totalPages) break
            }

            // Empty/near-empty results are more likely a changed source than a real catalogue.
            if (expectedPages == null || page != expectedPages || allProducts.size < MINIMUM_PRODUCTS) {
                return Result.retry()
            }

            retailRepository.replaceCatalogueWithOffers(RetailerId.ICELAND, allProducts.values.toList())
            Log.i(
                TAG,
                "Published complete Iceland catalogue: pages=$page, uniqueProducts=${allProducts.size}"
            )
            preferences.edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).apply()
            IcelandCatalogueRefreshScheduler.scheduleWeekly(applicationContext)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        } finally {
            httpClient.close()
        }
    }

    companion object {
        const val LAUNCH_WORK_NAME = "iceland_catalogue_refresh_on_launch"
        const val PERIODIC_WORK_NAME = "iceland_catalogue_refresh_weekly"
        const val KEY_FORCE_REFRESH = "force_refresh"
        private const val PREFERENCES = "retail_catalogue_refresh"
        private const val KEY_LAST_SUCCESS = "iceland_last_success_at"
        private const val TAG = "IcelandCatalogueRefresh"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 500
        private const val MINIMUM_PRODUCTS = 100
    }
}
