package com.example.chatgpt_mealplanner.core.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pairs of names the household has said are the same ingredient, picked from the pantry when a recipe or the shopping list
 * used a different wording ("Briosh burger buns" is "Briosh buns"). They feed [IngredientNameMatcher], so cooking, the shopping
 * list and stock checks all agree from then on. Main's pairs go to followers with the pantry snapshot.
 */
@Singleton
class IngredientAliases @Inject constructor(@ApplicationContext private val context: Context?) {
    private val prefs get() = context?.getSharedPreferences("ingredient_aliases", Context.MODE_PRIVATE)
    private val lock = Any()
    private var pairs: Map<String, Set<String>> = emptyMap()

    init { synchronized(lock) { pairs = load(); publish() } }

    private fun load(): Map<String, Set<String>> = runCatching {
        prefs?.all?.mapNotNull { (k, v) -> (v as? Set<*>)?.filterIsInstance<String>()?.toSet()?.let { k to it } }?.toMap()
    }.getOrNull().orEmpty()

    private fun publish() { IngredientNameMatcher.aliases = pairs }

    private fun save(key: String) {
        runCatching { prefs?.edit()?.putStringSet(key, pairs[key]?.toSet())?.apply() }
    }

    /** Says [name] and [pantryName] are the same ingredient. Returns false for names that reduce to nothing or to the same thing. */
    fun add(name: String, pantryName: String): Boolean = synchronized(lock) {
        val a = IngredientNameMatcher.core(name)
        val b = IngredientNameMatcher.core(pantryName)
        if (a.isEmpty() || b.isEmpty() || a == b) return false
        pairs = pairs + (a to ((pairs[a] ?: emptySet()) + b)) + (b to ((pairs[b] ?: emptySet()) + a))
        save(a); save(b); publish()
        true
    }

    /** The pairs as plain lists, for the sync snapshot. */
    fun snapshot(): Map<String, List<String>> = synchronized(lock) { pairs.mapValues { it.value.toList() } }

    /** Takes Main's pairs; this phone's own pairs stay. */
    fun install(fromMain: Map<String, List<String>>) = synchronized(lock) {
        if (fromMain.isEmpty()) return
        var merged = pairs
        fromMain.forEach { (k, v) -> merged = merged + (k to ((merged[k] ?: emptySet()) + v)) }
        pairs = merged
        fromMain.keys.forEach { save(it) }
        publish()
    }
}
