package com.example.chatgpt_mealplanner.core.shopping

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Departments the user has fixed by hand for items the automatic guess got wrong, keyed by the item's
 * [StoreOrder.keyOf]. They win over [Department.classify]. Main's list is sent down to followers with the pantry snapshot.
 */
@Singleton
class DepartmentOverrides @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs get() = context.getSharedPreferences("department_overrides", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val overrides: StateFlow<Map<String, Department>> = state.asStateFlow()

    private fun load(): Map<String, Department> = runCatching {
        prefs.all.mapNotNull { (key, value) -> parse(value as? String)?.let { key to it } }.toMap()
    }.getOrDefault(emptyMap())

    private fun parse(name: String?): Department? = Department.entries.firstOrNull { it.name == name }

    /** Sets the department for an item, or clears the correction when [department] is null (back to the automatic guess). */
    fun set(itemName: String, department: Department?) {
        val key = StoreOrder.keyOf(itemName)
        if (key.isBlank()) return
        prefs.edit().apply { if (department == null) remove(key) else putString(key, department.name) }.apply()
        state.value = if (department == null) state.value - key else state.value + (key to department)
    }

    /** Main's corrections as plain strings, for the sync snapshot. */
    fun asStrings(): Map<String, String> = state.value.mapValues { it.value.name }

    /** Takes Main's corrections; this phone's own corrections for other items stay. */
    fun install(fromMain: Map<String, String>) {
        val parsed = fromMain.mapNotNull { (k, v) -> parse(v)?.let { k to it } }.toMap()
        if (parsed.isEmpty()) return
        prefs.edit().apply { parsed.forEach { (k, d) -> putString(k, d.name) } }.apply()
        state.value = state.value + parsed
    }
}
