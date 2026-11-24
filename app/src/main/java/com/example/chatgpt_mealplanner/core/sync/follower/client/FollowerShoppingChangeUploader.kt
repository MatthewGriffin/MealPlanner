package com.example.chatgpt_mealplanner.core.sync.follower.client

import com.example.chatgpt_mealplanner.core.shopping.ShoppingChangeQueue
import com.example.chatgpt_mealplanner.core.sync.client.PantrySyncTestClient
import com.example.chatgpt_mealplanner.sync.contract.ShoppingChangesUploadDto
import javax.inject.Inject
import javax.inject.Singleton

/** Sends this follower's changes to hand-added shopping items to Main and forgets the ones Main confirms. */
@Singleton
open class FollowerShoppingChangeUploader @Inject constructor(
    private val queue: ShoppingChangeQueue,
    private val client: PantrySyncTestClient
) {
    /** Returns false only when Main could not be reached. An older Main without this route keeps the changes waiting. */
    open suspend fun upload(
        hostIp: String,
        port: Int,
        pinnedCertFingerprint: String?,
        sessionToken: String,
        refreshToken: suspend (Boolean) -> String
    ): Boolean {
        var token = sessionToken
        while (true) {
            val batch = queue.pending().take(BATCH)
            if (batch.isEmpty()) return true
            val body = ShoppingChangesUploadDto(batch)
            var result = client.postShoppingChanges(hostIp, port, pinnedCertFingerprint, token, body)
            if (result?.first == 401) {
                token = refreshToken(true)
                result = client.postShoppingChanges(hostIp, port, pinnedCertFingerprint, token, body)
            }
            when {
                result == null -> return false
                result.first == 200 -> {
                    val applied = result.second
                    if (applied.isEmpty()) return true
                    queue.acknowledge(applied)
                }
                // Main refused the batch as malformed: it will never be accepted, so drop it rather than block the queue.
                result.first == 400 -> queue.acknowledge(batch.map { it.changeId })
                else -> return true     // an older Main, or not ready: keep the changes for next time
            }
        }
    }

    private companion object { const val BATCH = 50 }
}
