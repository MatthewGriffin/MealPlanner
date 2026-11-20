package com.example.chatgpt_mealplanner.core.sync.follower.client

import com.example.chatgpt_mealplanner.core.shopping.ShoppingSessionManager
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.sync.contract.ShopTickSyncDto
import com.example.chatgpt_mealplanner.sync.contract.ShopTripUploadDto
import javax.inject.Inject
import javax.inject.Singleton

/** Sends the shopping trips this follower has finished to Main, so the household learns each shop's order from them. */
@Singleton
open class FollowerShopTripUploader @Inject constructor(
    private val sessions: ShoppingSessionManager,
    private val client: PantrySyncTestClient
) {
    /**
     * Returns false only when Main could not be reached, so the pass can try again later. A trip Main refuses as invalid
     * is dropped from the queue (it will never be accepted); an old Main without this feature keeps the trips waiting.
     */
    open suspend fun upload(
        hostIp: String,
        port: Int,
        pinnedCertFingerprint: String?,
        sessionToken: String,
        refreshToken: suspend (Boolean) -> String
    ): Boolean {
        var token = sessionToken
        for (trip in sessions.tripsToUpload()) {
            val dto = ShopTripUploadDto(
                tripId = trip.session.id,
                storeId = trip.session.storeId,
                startedAt = trip.session.startedAt,
                endedAt = trip.session.endedAt ?: continue,
                ticks = trip.ticks.map { ShopTickSyncDto(it.displayName, it.tickedAt, it.x, it.y) }
            )
            var status = client.postShopTrip(hostIp, port, pinnedCertFingerprint, token, dto)
            if (status == 401) {
                token = refreshToken(true)
                status = client.postShopTrip(hostIp, port, pinnedCertFingerprint, token, dto)
            }
            when (status) {
                200 -> sessions.markUploaded(trip.session.id)
                400 -> sessions.markUploaded(trip.session.id)
                404, 503 -> Unit            // an older Main, or not ready: keep it for later
                null -> return false
                else -> Unit
            }
        }
        return true
    }
}
