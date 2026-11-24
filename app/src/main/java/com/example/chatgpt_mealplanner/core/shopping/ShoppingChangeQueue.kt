package com.example.chatgpt_mealplanner.core.shopping

import android.content.Context
import com.example.chatgpt_mealplanner.sync.contract.ShoppingChangeDto
import com.example.chatgpt_mealplanner.sync.contract.ShoppingChangeKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The changes a follower has made to hand-added shopping items that Main has not confirmed yet. They are kept until Main
 * says it has them, and put back on the phone's copy of the list whenever Main's list replaces it.
 */
@Singleton
class ShoppingChangeQueue @Inject constructor(@ApplicationContext private val context: Context?) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(ShoppingChangeDto.serializer())
    private val lock = Any()
    private var memory: List<ShoppingChangeDto>? = null

    private fun prefs() = context?.getSharedPreferences("shopping_change_queue", Context.MODE_PRIVATE)

    private fun load(): List<ShoppingChangeDto> = memory ?: run {
        val loaded = runCatching { json.decodeFromString(serializer, prefs()?.getString("queue", null) ?: "[]") }.getOrDefault(emptyList())
        memory = loaded
        loaded
    }

    private fun save(list: List<ShoppingChangeDto>) {
        memory = list
        runCatching { prefs()?.edit()?.putString("queue", json.encodeToString(serializer, list))?.apply() }
    }

    fun pending(): List<ShoppingChangeDto> = synchronized(lock) { load() }

    /** Adds a change, folding it into what is already waiting for the same item so the queue stays small and in order. */
    fun record(change: ShoppingChangeDto) = synchronized(lock) { save(fold(load(), change)) }

    fun acknowledge(changeIds: Collection<String>) = synchronized(lock) {
        if (changeIds.isNotEmpty()) save(load().filterNot { it.changeId in changeIds })
    }

    fun clear() = synchronized(lock) { save(emptyList()) }

    companion object {
        fun newChange(
            kind: ShoppingChangeKind, itemId: String, displayName: String? = null, quantity: Double? = null,
            unit: String? = null, reason: String? = null, status: String? = null, requestedBy: String? = null,
            now: Long = System.currentTimeMillis()
        ) = ShoppingChangeDto(UUID.randomUUID().toString(), kind, itemId, displayName, quantity, unit, reason, status, requestedBy, now)

        /** Pure so it can be tested: how a new change combines with those already waiting. */
        fun fold(queue: List<ShoppingChangeDto>, change: ShoppingChangeDto): List<ShoppingChangeDto> {
            val forItem = queue.filter { it.itemId == change.itemId }
            val rest = queue.filter { it.itemId != change.itemId }
            val add = forItem.firstOrNull { it.kind == ShoppingChangeKind.ADD }
            return when (change.kind) {
                // Added and removed before Main ever heard of it: nothing to send.
                ShoppingChangeKind.REMOVE -> if (add != null) rest else rest + change
                // Still waiting to be added: the new details just become part of the add.
                ShoppingChangeKind.EDIT -> if (add != null) rest + add.copy(
                    displayName = change.displayName ?: add.displayName,
                    quantity = change.quantity ?: add.quantity,
                    unit = change.unit ?: add.unit,
                    reason = change.reason ?: add.reason
                ) else rest + forItem.filterNot { it.kind == ShoppingChangeKind.EDIT } + change
                ShoppingChangeKind.SET_STATUS -> rest + forItem.filterNot { it.kind == ShoppingChangeKind.SET_STATUS } + change
                ShoppingChangeKind.ADD, ShoppingChangeKind.ALIAS -> rest + forItem + change
            }
        }
    }
}
