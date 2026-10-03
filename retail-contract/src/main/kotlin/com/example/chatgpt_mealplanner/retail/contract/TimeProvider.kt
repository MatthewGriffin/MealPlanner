package com.example.chatgpt_mealplanner.retail.contract

interface TimeProvider {
    fun currentTimeMillis(): Long
}

object SystemTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

data class FixedTimeProvider(var fixedTimeMs: Long) : TimeProvider {
    override fun currentTimeMillis(): Long = fixedTimeMs
}
