package com.example.chatgpt_mealplanner.core.sync.follower.database.entity

import kotlinx.serialization.Serializable

@Serializable
data class FollowerBasketComparisonCachePayload(
    val fingerprint: String,
    val payloadJson: String
)
