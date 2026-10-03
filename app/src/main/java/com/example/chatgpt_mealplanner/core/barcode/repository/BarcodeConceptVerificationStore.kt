package com.example.chatgpt_mealplanner.core.barcode.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class BarcodeConceptVerificationStore @Inject constructor(
    @param:ApplicationContext private val context: Context?
) {
    private val verifiedSet = mutableSetOf<String>()

    private val prefs by lazy {
        context?.let { runCatching { it.getSharedPreferences("barcode_concept_verification_v1", Context.MODE_PRIVATE) }.getOrNull() }
    }

    open fun isConceptVerified(barcode: String): Boolean {
        if (verifiedSet.contains(barcode)) return true
        val fromPrefs = runCatching { prefs?.getBoolean("concept_verified_$barcode", false) }.getOrNull() ?: false
        if (fromPrefs) verifiedSet.add(barcode)
        return fromPrefs
    }

    open fun markConceptVerified(barcode: String) {
        verifiedSet.add(barcode)
        runCatching { prefs?.edit()?.putBoolean("concept_verified_$barcode", true)?.apply() }
    }
}
