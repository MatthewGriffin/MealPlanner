package com.example.chatgpt_mealplanner.core.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.chatgpt_mealplanner.core.repository.RetailRepository
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Installs a small dated Iceland estimate cache without requiring network access.
 * The following refresh work item is network-constrained and replaces this seed only
 * after a complete successful catalogue crawl.
 */
@HiltWorker
class IcelandStarterCatalogueSeedWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val retailRepository: RetailRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        val alreadyHasCatalogue = retailRepository.getCandidateProductsWithOffers().any {
            it.product.retailerId == RetailerId.ICELAND &&
                !it.product.retailerProductId.startsWith("RECEIPT_") &&
                !it.product.retailerProductId.startsWith("STARTER_")
        }
        val alreadyHasStarter = retailRepository.getCandidateProductsWithOffers().any {
            it.product.retailerId == RetailerId.ICELAND &&
                it.product.retailerProductId.startsWith("STARTER_")
        }
        if (!alreadyHasCatalogue && !alreadyHasStarter) {
            retailRepository.replaceCatalogueWithOffers(
                RetailerId.ICELAND,
                IcelandStarterCatalogue.products()
            )
        }
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.retry()
    }
}
