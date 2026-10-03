package com.example.chatgpt_mealplanner.core.ai.resolver

import com.example.chatgpt_mealplanner.core.model.MealType
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class MealSlotResolutionResult(
    val date: String,
    val mealType: MealType,
    val resolutionSource: String
)

object MealSlotResolver {

    fun resolveSlot(
        requestedDateStr: String?,
        requestedMealTypeStr: String?,
        proposalDateStr: String? = null,
        proposalMealTypeStr: String? = null,
        currentDate: LocalDate = LocalDate.now(),
        currentTime: LocalTime = LocalTime.now()
    ): MealSlotResolutionResult {
        val todayIso = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // 1. Resolve MealType (PROHIBIT FOOD-NAME INFERENCE)
        val explicitMealType = parseMealType(requestedMealTypeStr)
        val proposalMealType = parseMealType(proposalMealTypeStr)

        val (mealType, mealTypeSource) = when {
            explicitMealType != null -> Pair(explicitMealType, "EXPLICIT_USER")
            proposalMealType != null -> Pair(proposalMealType, "REFERENCED_PROPOSAL")
            else -> {
                val inferredType = when (currentTime.hour) {
                    in 5..10 -> MealType.BREAKFAST
                    in 11..15 -> MealType.LUNCH
                    in 16..21 -> MealType.DINNER
                    else -> MealType.DINNER
                }
                Pair(inferredType, "CURRENT_TIME")
            }
        }

        // 2. Resolve Date (NO ROLLOVER FOR UNSPECIFIED DATES - DEFAULT IS ALWAYS TODAY)
        val explicitDateIso = normalizeDateString(requestedDateStr, currentDate)
        val proposalDateIso = normalizeDateString(proposalDateStr, currentDate)

        val (dateIso, dateSource) = when {
            !requestedDateStr.isNullOrBlank() -> Pair(explicitDateIso, "EXPLICIT_USER")
            !proposalDateStr.isNullOrBlank() -> Pair(proposalDateIso, "REFERENCED_PROPOSAL")
            else -> Pair(todayIso, "CURRENT_DATE_DEFAULT")
        }

        val resolutionSource = "${dateSource}_$mealTypeSource"
        logDiagnostic("MEAL_SLOT_RESOLUTION", "explicitDate=\"$requestedDateStr\" explicitMealType=\"$requestedMealTypeStr\" proposalDate=\"$proposalDateStr\" proposalMealType=\"$proposalMealTypeStr\" localDate=\"$todayIso\" localTime=\"$currentTime\" resolvedDate=\"$dateIso\" resolvedMealType=\"${mealType.name}\" resolutionSource=\"$resolutionSource\"")

        return MealSlotResolutionResult(
            date = dateIso,
            mealType = mealType,
            resolutionSource = resolutionSource
        )
    }

    private fun parseMealType(rawType: String?): MealType? {
        if (rawType.isNullOrBlank()) return null
        val trimmed = rawType.trim().uppercase()
        return try {
            MealType.valueOf(trimmed)
        } catch (_: Exception) {
            when {
                trimmed.contains("BREAKFAST") || trimmed.contains("MORNING") -> MealType.BREAKFAST
                trimmed.contains("LUNCH") || trimmed.contains("MIDDAY") || trimmed.contains("NOON") -> MealType.LUNCH
                trimmed.contains("DINNER") || trimmed.contains("SUPPER") || trimmed.contains("EVENING") -> MealType.DINNER
                trimmed.contains("SNACK") -> MealType.SNACK
                else -> null
            }
        }
    }

    fun normalizeDateString(rawDate: String?, currentDate: LocalDate = LocalDate.now()): String {
        if (rawDate.isNullOrBlank()) return currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val trimmed = rawDate.trim()
        return try {
            when {
                trimmed.equals("today", ignoreCase = true) -> currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                trimmed.equals("tomorrow", ignoreCase = true) -> currentDate.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                trimmed.equals("yesterday", ignoreCase = true) -> currentDate.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                trimmed.contains("/") -> {
                    val parts = trimmed.split("/")
                    if (parts.size == 3) {
                        val p1 = parts[0].toInt()
                        val p2 = parts[1].toInt()
                        val p3 = parts[2].toInt()
                        if (p1 > 1000) {
                            LocalDate.of(p1, p2, p3).format(DateTimeFormatter.ISO_LOCAL_DATE)
                        } else {
                            LocalDate.of(p3, p2, p1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                        }
                    } else trimmed
                }
                else -> {
                    runCatching { LocalDate.parse(trimmed) }.getOrNull()?.format(DateTimeFormatter.ISO_LOCAL_DATE)
                        ?: trimmed
                }
            }
        } catch (_: Exception) {
            currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        }
    }

    private fun logDiagnostic(tag: String, message: String) {
        println("[$tag] $message")
        try {
            android.util.Log.d(tag, message)
        } catch (_: Throwable) {
            // Unstubbed in JVM unit tests
        }
    }
}
