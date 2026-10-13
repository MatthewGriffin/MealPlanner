package com.example.chatgpt_mealplanner.core.backup

import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

val CONTAINER_MAGIC = "PANTRYBK".toByteArray(Charsets.US_ASCII) // 8 bytes
const val CONTAINER_WIRE_VERSION = 1 // 4 bytes Big-Endian Int
const val CONTAINER_HEADER_SIZE = 16 // 8 (magic) + 4 (version) + 4 (bodyLength)
const val CONTAINER_DIGEST_SIZE = 32 // 32 bytes raw SHA-256 binary
const val CONTAINER_OVERHEAD_SIZE = CONTAINER_HEADER_SIZE + CONTAINER_DIGEST_SIZE // 48 bytes
const val MAX_CONTAINER_BODY_SIZE_BYTES = 50 * 1024 * 1024 // 50 MB max payload limit

@Singleton
open class MainBackupContainerEncoderDecoder @Inject constructor() {

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    open fun encodeContainer(
        snapshot: MainHouseholdBackupDto,
        currentTimeMs: Long = System.currentTimeMillis()
    ): ByteArray {
        val payloadJson = jsonFormatter.encodeToString(snapshot)
        val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)

        val manifest = MainBackupManifestDto(
            backupFormatVersion = "pantry-household-backup-v1",
            role = "MAIN",
            roomSchemaVersion = 16,
            createdAt = currentTimeMs,
            householdId = snapshot.householdId,
            mainDeviceId = snapshot.mainDeviceId,
            appVersionName = snapshot.appVersionName,
            payloadSizeBytes = payloadBytes.size.toLong()
        )

        val containerDto = MainHouseholdBackupContainerDto(
            manifest = manifest,
            payload = snapshot
        )

        val bodyJson = jsonFormatter.encodeToString(containerDto)
        val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)
        val bodyLength = bodyBytes.size

        // Build framed buffer: [MAGIC (8B)][WIRE_VER (4B)][BODY_LEN (4B)][BODY (LB)]
        val framedBuffer = ByteBuffer.allocate(CONTAINER_HEADER_SIZE + bodyLength).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(CONTAINER_MAGIC)
            putInt(CONTAINER_WIRE_VERSION)
            putInt(bodyLength)
            put(bodyBytes)
        }

        val framedBytes = framedBuffer.array()
        val rawSha256Digest = MessageDigest.getInstance("SHA-256").digest(framedBytes)

        // Final container output: [FRAMED_BYTES][SHA-256_DIGEST (32B)]
        val outputBuffer = ByteBuffer.allocate(framedBytes.size + CONTAINER_DIGEST_SIZE).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(framedBytes)
            put(rawSha256Digest)
        }

        return outputBuffer.array()
    }

    open fun decodeAndValidateContainer(
        containerBytes: ByteArray
    ): DomainResult<MainHouseholdBackupDto> {
        if (containerBytes.isEmpty()) {
            return DomainResult.Failure(DomainError.TransactionFailed("Backup container file is empty."))
        }

        if (containerBytes.size < CONTAINER_OVERHEAD_SIZE) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Truncated container header: size ${containerBytes.size} bytes is less than minimum framing header ${CONTAINER_OVERHEAD_SIZE} bytes.")
            )
        }

        val totalSize = containerBytes.size
        if (totalSize > MAX_CONTAINER_BODY_SIZE_BYTES + CONTAINER_OVERHEAD_SIZE) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Backup container exceeds maximum size limit of ${MAX_CONTAINER_BODY_SIZE_BYTES / (1024 * 1024)} MB.")
            )
        }

        val buffer = ByteBuffer.wrap(containerBytes).apply {
            order(ByteOrder.BIG_ENDIAN)
        }

        // 1. Verify Magic
        val magicBytes = ByteArray(8)
        buffer.get(magicBytes)
        if (!magicBytes.contentEquals(CONTAINER_MAGIC)) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Invalid container magic identifier. Expected PANTRYBK.")
            )
        }

        // 2. Verify Wire Version
        val wireVersion = buffer.int
        if (wireVersion != CONTAINER_WIRE_VERSION) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Unsupported container wire version: $wireVersion. Expected $CONTAINER_WIRE_VERSION.")
            )
        }

        // 3. Verify Body Length
        val declaredBodyLength = buffer.int
        if (declaredBodyLength < 0) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Invalid negative container body length: $declaredBodyLength.")
            )
        }

        val expectedTotalSize = CONTAINER_HEADER_SIZE + declaredBodyLength + CONTAINER_DIGEST_SIZE
        if (totalSize != expectedTotalSize) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Container length mismatch: expected $expectedTotalSize bytes based on header length, but file size is $totalSize bytes.")
            )
        }

        // 4. Extract Framed Bytes and Stored Digest
        val framedLength = CONTAINER_HEADER_SIZE + declaredBodyLength
        val framedBytes = ByteArray(framedLength)
        System.arraycopy(containerBytes, 0, framedBytes, 0, framedLength)

        val storedDigest = ByteArray(CONTAINER_DIGEST_SIZE)
        System.arraycopy(containerBytes, framedLength, storedDigest, 0, CONTAINER_DIGEST_SIZE)

        // 5. Raw Exact-Byte SHA-256 Verification
        val computedDigest = MessageDigest.getInstance("SHA-256").digest(framedBytes)
        if (!storedDigest.contentEquals(computedDigest)) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Backup container integrity failure: Exact-byte SHA-256 digest mismatch.")
            )
        }

        // 6. Decode Body JSON using Strict UTF-8
        val bodyBytes = ByteArray(declaredBodyLength)
        System.arraycopy(containerBytes, CONTAINER_HEADER_SIZE, bodyBytes, 0, declaredBodyLength)

        val bodyJson = runCatching {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bodyBytes)).toString()
        }.getOrElse {
            return DomainResult.Failure(DomainError.TransactionFailed("Invalid UTF-8 encoding in backup container body: ${it.message}"))
        }

        val container = runCatching {
            jsonFormatter.decodeFromString<MainHouseholdBackupContainerDto>(bodyJson)
        }.getOrElse {
            return DomainResult.Failure(DomainError.TransactionFailed("Malformed backup container JSON structure: ${it.message}"))
        }

        val manifest = container.manifest

        // 7. Format Version Check
        if (manifest.backupFormatVersion != "pantry-household-backup-v1") {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Unsupported backup format version: ${manifest.backupFormatVersion}")
            )
        }

        // 8. Role Check
        if (manifest.role != "MAIN") {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Invalid backup container role: ${manifest.role}. Expected MAIN.")
            )
        }

        // 9. Room Schema Version Check
        if (manifest.roomSchemaVersion != 16) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Unsupported Room schema version: ${manifest.roomSchemaVersion}. Expected version 16.")
            )
        }

        // 10. Manifest vs Payload Metadata Consistency Check
        if (manifest.householdId != container.payload.householdId) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Backup container metadata mismatch: householdId in manifest does not match payload.")
            )
        }

        if (manifest.mainDeviceId != container.payload.mainDeviceId) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Backup container metadata mismatch: mainDeviceId in manifest does not match payload.")
            )
        }

        // 11. Manifest Entity Coverage Declaration Validation
        val requiredEntities = setOf(
            "inventory_items",
            "inventory_transactions",
            "canonical_sync_operations",
            "canonical_sync_sequence",
            "household_identity"
        )
        if (!manifest.includedEntities.containsAll(requiredEntities)) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Backup container manifest is missing required Main entity coverage declarations.")
            )
        }

        if (manifest.includedEntities.contains("follower_sync_outbox")) {
            return DomainResult.Failure(
                DomainError.TransactionFailed("Backup container manifest invalid: Follower-local state cannot be declared as included in Main backup.")
            )
        }

        return DomainResult.Success(container.payload)
    }
}
