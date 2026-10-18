package com.example.chatgpt_mealplanner.sync.contract

object MealRequestSyncValidation {
    fun isValid(request: MealChangeRequestSyncDto): Boolean =
        request.id.startsWith("req_") &&
            SyncContractValidation.isValidUuid(request.id.removePrefix("req_")) &&
            request.mealId.isNotBlank() && request.mealId.length <= 128 &&
            request.authorDeviceId.isNotBlank() && request.authorDeviceId.length <= 128 &&
            request.requestText.isNotBlank() && request.requestText.length <= 2000 &&
            request.status in setOf("PENDING", "ACCEPTED", "DECLINED", "DISCUSSED") &&
            (request.mainResponse?.length ?: 0) <= 2000 &&
            request.createdAt >= 0 && request.updatedAt >= 0
}
