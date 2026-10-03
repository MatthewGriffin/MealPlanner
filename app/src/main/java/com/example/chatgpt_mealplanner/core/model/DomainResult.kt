package com.example.chatgpt_mealplanner.core.model

sealed interface DomainError {
    data class InsufficientQuantity(
        val available: Double,
        val requested: Double,
        val unit: QuantityUnit?
    ) : DomainError

    data class NeedsConfirmation(
        val itemId: String,
        val displayName: String,
        val requiredQuantity: Double,
        val unit: QuantityUnit?
    ) : DomainError

    data class ItemNotFound(val itemId: String) : DomainError
    data class InvalidQuantity(val quantity: Double) : DomainError
    data class InvalidUnitConversion(val from: QuantityUnit, val to: QuantityUnit) : DomainError
    data class ItemNotUsable(val itemId: String, val status: ItemStatus) : DomainError
    data class DuplicateOperation(val message: String) : DomainError
    data class TransactionFailed(val cause: String) : DomainError
}

sealed interface DomainResult<out T> {
    data class Success<out T>(val value: T) : DomainResult<T>
    data class Failure(val error: DomainError) : DomainResult<Nothing>

    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure
    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): DomainError? = (this as? Failure)?.error
}
