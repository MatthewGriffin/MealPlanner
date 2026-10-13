package com.example.chatgpt_mealplanner.core.backup

import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(JUnit4::class)
class PantryPhase6Step3ContainerTest {

    private lateinit var encoderDecoder: MainBackupContainerEncoderDecoder
    private val jsonFormatter = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Before
    fun setUp() {
        encoderDecoder = MainBackupContainerEncoderDecoder()
    }

    private fun createSampleSnapshot(): MainHouseholdBackupDto = MainHouseholdBackupDto(
        exportedAt = 1791890000000L,
        householdId = "house-cont-100",
        householdName = "Family Pantry Container Test",
        mainDeviceId = "main-dev-1",
        nextCanonicalSequence = 42L,
        inventory = listOf(
            InventoryItemExportDto(
                id = "item-cont-1",
                barcode = "123456789",
                canonicalIngredientId = "ING_OIL",
                canonicalName = "olive oil",
                displayName = "Extra Virgin Olive Oil",
                brand = "Asda",
                category = "CUPBOARD",
                storageLocation = "CUPBOARD",
                quantity = 3.5,
                quantityUnit = "LITRE",
                packCount = 1,
                packSize = 1.0,
                packSizeUnit = "LITRE",
                quantityConfidence = "EXACT",
                status = "AVAILABLE",
                opened = true,
                frozen = false,
                staple = true,
                purchaseDate = "2026-10-01",
                openedDate = "2026-10-02",
                useByDate = "2027-10-01",
                bestBeforeDate = null,
                notes = "Container test",
                createdAt = 1000L,
                updatedAt = 2000L,
                revision = 5L
            )
        ),
        canonicalOperations = listOf(
            CanonicalSyncOperationExportDto(
                operationId = "op-cont-10",
                deviceId = "follower-dev-1",
                deviceSequence = 1L,
                protocolVersion = 1,
                operationType = "SET_QUANTITY",
                targetItemId = "item-cont-1",
                baseRevision = 4L,
                payloadJson = "{}",
                createdAt = 1500L,
                canonicalSequence = 10L,
                receivedAt = 1600L,
                status = "APPLIED",
                resultCode = "OK",
                resultPayloadJson = null
            )
        )
    )

    private fun encodeWithCustomManifest(
        snapshot: MainHouseholdBackupDto,
        manifestCustomizer: (MainBackupManifestDto) -> MainBackupManifestDto
    ): ByteArray {
        val payloadJson = jsonFormatter.encodeToString(snapshot)
        val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)

        val baseManifest = MainBackupManifestDto(
            backupFormatVersion = "pantry-household-backup-v1",
            role = "MAIN",
            roomSchemaVersion = 16,
            createdAt = 2000000L,
            householdId = snapshot.householdId,
            mainDeviceId = snapshot.mainDeviceId,
            appVersionName = snapshot.appVersionName,
            payloadSizeBytes = payloadBytes.size.toLong()
        )

        val customizedManifest = manifestCustomizer(baseManifest)
        val containerDto = MainHouseholdBackupContainerDto(manifest = customizedManifest, payload = snapshot)

        val bodyJson = jsonFormatter.encodeToString(containerDto)
        val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)
        val bodyLength = bodyBytes.size

        val framedBuffer = ByteBuffer.allocate(CONTAINER_HEADER_SIZE + bodyLength).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(CONTAINER_MAGIC)
            putInt(CONTAINER_WIRE_VERSION)
            putInt(bodyLength)
            put(bodyBytes)
        }
        val framedBytes = framedBuffer.array()
        val rawSha256 = MessageDigest.getInstance("SHA-256").digest(framedBytes)

        val outputBuffer = ByteBuffer.allocate(framedBytes.size + CONTAINER_DIGEST_SIZE).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(framedBytes)
            put(rawSha256)
        }
        return outputBuffer.array()
    }

    @Test
    fun encodeAndDecode_validSnapshot_roundTripsLosslessly() = runTest {
        val originalSnapshot = createSampleSnapshot()

        val containerBytes = encoderDecoder.encodeContainer(originalSnapshot, currentTimeMs = 2000000L)
        assertTrue("Container bytes MUST be non-empty", containerBytes.isNotEmpty())

        val result = encoderDecoder.decodeAndValidateContainer(containerBytes)
        assertTrue("Decoding valid container MUST succeed", result is DomainResult.Success)

        val decodedSnapshot = (result as DomainResult.Success).value
        assertEquals("pantry-household-backup-v1", decodedSnapshot.backupFormatVersion)
        assertEquals(16, decodedSnapshot.roomSchemaVersion)
        assertEquals("MAIN", decodedSnapshot.role)
        assertEquals("house-cont-100", decodedSnapshot.householdId)
        assertEquals(42L, decodedSnapshot.nextCanonicalSequence)

        assertEquals(1, decodedSnapshot.inventory.size)
        val inv = decodedSnapshot.inventory.first()
        assertEquals("item-cont-1", inv.id)
        assertEquals("Extra Virgin Olive Oil", inv.displayName)
        assertEquals(3.5, inv.quantity, 0.001)
        assertEquals(5L, inv.revision)

        assertEquals(1, decodedSnapshot.canonicalOperations.size)
        assertEquals("op-cont-10", decodedSnapshot.canonicalOperations.first().operationId)
    }

    @Test
    fun encodeContainer_sameInput_isDeterministic() = runTest {
        val snapshot = createSampleSnapshot()
        val bytes1 = encoderDecoder.encodeContainer(snapshot, currentTimeMs = 2000000L)
        val bytes2 = encoderDecoder.encodeContainer(snapshot, currentTimeMs = 2000000L)

        assertEquals(bytes1.size, bytes2.size)
        assertTrue(bytes1.contentEquals(bytes2))
    }

    @Test
    fun decodeAndValidate_singleByteMutationInMagic_rejectsMagicMismatch() = runTest {
        val snapshot = createSampleSnapshot()
        val bytes = encoderDecoder.encodeContainer(snapshot, currentTimeMs = 2000000L)

        val mutatedBytes = bytes.clone()
        mutatedBytes[0] = 'X'.code.toByte()

        val result = encoderDecoder.decodeAndValidateContainer(mutatedBytes)
        assertTrue("Mutated magic byte MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention magic identifier", errorMsg.contains("magic"))
    }

    @Test
    fun decodeAndValidate_singleByteMutationInWireVersion_rejectsWireVersionMismatch() = runTest {
        val snapshot = createSampleSnapshot()
        val bytes = encoderDecoder.encodeContainer(snapshot, currentTimeMs = 2000000L)

        val mutatedBytes = bytes.clone()
        mutatedBytes[11] = 99.toByte()

        val result = encoderDecoder.decodeAndValidateContainer(mutatedBytes)
        assertTrue("Mutated wire version MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention wire version", errorMsg.contains("wire version"))
    }

    @Test
    fun decodeAndValidate_singleByteMutationInBodyJson_rejectsExactByteSha256Mismatch() = runTest {
        val snapshot = createSampleSnapshot()
        val bytes = encoderDecoder.encodeContainer(snapshot, currentTimeMs = 2000000L)

        val mutatedBytes = bytes.clone()
        mutatedBytes[20] = if (mutatedBytes[20] == 'a'.code.toByte()) 'b'.code.toByte() else 'a'.code.toByte()

        val result = encoderDecoder.decodeAndValidateContainer(mutatedBytes)
        assertTrue("Mutated body byte MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention exact-byte SHA-256 digest mismatch", errorMsg.contains("SHA-256"))
    }

    @Test
    fun decodeAndValidate_appendedTrailingBytes_rejectsLengthMismatch() = runTest {
        val snapshot = createSampleSnapshot()
        val bytes = encoderDecoder.encodeContainer(snapshot, currentTimeMs = 2000000L)

        val trailingBytes = bytes + byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)

        val result = encoderDecoder.decodeAndValidateContainer(trailingBytes)
        assertTrue("Appended trailing bytes MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention length mismatch", errorMsg.contains("length mismatch"))
    }

    @Test
    fun decodeAndValidate_validFramingWithWrongRole_rejectsRoleMismatch() = runTest {
        val snapshot = createSampleSnapshot()
        val bytes = encodeWithCustomManifest(snapshot) { it.copy(role = "FOLLOWER") }

        val result = encoderDecoder.decodeAndValidateContainer(bytes)
        assertTrue("Valid framing with wrong role MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention role mismatch", errorMsg.contains("role"))
    }

    @Test
    fun decodeAndValidate_validFramingWithWrongSchemaVersion_rejectsSchemaMismatch() = runTest {
        val snapshot = createSampleSnapshot()
        val bytes = encodeWithCustomManifest(snapshot) { it.copy(roomSchemaVersion = 15) }

        val result = encoderDecoder.decodeAndValidateContainer(bytes)
        assertTrue("Valid framing with wrong schema version MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention Room schema version", errorMsg.contains("Room schema version"))
    }

    @Test
    fun decodeAndValidate_mismatchedHouseholdId_rejectsMetadataMismatch() = runTest {
        val snapshot = createSampleSnapshot()
        val bytes = encodeWithCustomManifest(snapshot) { it.copy(householdId = "house-DIFFERENT-999") }

        val result = encoderDecoder.decodeAndValidateContainer(bytes)
        assertTrue("Mismatched householdId MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention householdId mismatch", errorMsg.contains("householdId"))
    }

    @Test
    fun decodeAndValidate_mismatchedMainDeviceId_rejectsMetadataMismatch() = runTest {
        val snapshot = createSampleSnapshot()
        val bytes = encodeWithCustomManifest(snapshot) { it.copy(mainDeviceId = "main-DIFFERENT-999") }

        val result = encoderDecoder.decodeAndValidateContainer(bytes)
        assertTrue("Mismatched mainDeviceId MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention mainDeviceId mismatch", errorMsg.contains("mainDeviceId"))
    }

    @Test
    fun decodeAndValidate_missingRequiredIncludedEntities_rejectsCoverageError() = runTest {
        val snapshot = createSampleSnapshot()
        val bytes = encodeWithCustomManifest(snapshot) { it.copy(includedEntities = listOf("inventory_items")) }

        val result = encoderDecoder.decodeAndValidateContainer(bytes)
        assertTrue("Missing required included entities MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention required Main entity coverage declarations", errorMsg.contains("required Main entity coverage"))
    }

    @Test
    fun decodeAndValidate_declaringFollowerOutboxInMainBackup_rejectsCoverageError() = runTest {
        val snapshot = createSampleSnapshot()
        val bytes = encodeWithCustomManifest(snapshot) { it.copy(includedEntities = it.includedEntities + "follower_sync_outbox") }

        val result = encoderDecoder.decodeAndValidateContainer(bytes)
        assertTrue("Declaring Follower outbox in Main backup MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention Follower-local state cannot be declared in Main backup", errorMsg.contains("Follower-local state"))
    }

    @Test
    fun decodeAndValidate_emptyContainer_rejectsEmptyFile() = runTest {
        val result = encoderDecoder.decodeAndValidateContainer(ByteArray(0))
        assertTrue("Empty container MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention empty file", errorMsg.contains("empty"))
    }

    @Test
    fun decodeAndValidate_truncatedHeader_rejectsTruncatedHeader() = runTest {
        val truncatedHeader = ByteArray(10) // 10 bytes < 48 overhead bytes

        val result = encoderDecoder.decodeAndValidateContainer(truncatedHeader)
        assertTrue("Truncated header MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention truncated header", errorMsg.contains("Truncated"))
    }

    @Test
    fun decodeAndValidate_negativeDeclaredBodyLength_rejectsNegativeLength() = runTest {
        val bytes = ByteArray(48)
        ByteBuffer.wrap(bytes).apply {
            order(ByteOrder.BIG_ENDIAN)
            put("PANTRYBK".toByteArray(Charsets.US_ASCII))
            putInt(1) // wire version
            putInt(-100) // negative length
        }

        val result = encoderDecoder.decodeAndValidateContainer(bytes)
        assertTrue("Negative body length MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention negative body length", errorMsg.contains("negative"))
    }

    @Test
    fun decodeAndValidate_truncatedDigest_rejectsTruncatedDigest() = runTest {
        val snapshot = createSampleSnapshot()
        val fullBytes = encoderDecoder.encodeContainer(snapshot, currentTimeMs = 2000000L)

        // Truncate the trailing 32-byte digest by 16 bytes
        val truncatedBytes = fullBytes.copyOf(fullBytes.size - 16)

        val result = encoderDecoder.decodeAndValidateContainer(truncatedBytes)
        assertTrue("Truncated digest MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention length mismatch", errorMsg.contains("length mismatch"))
    }

    @Test
    fun decodeAndValidate_invalidUtf8InBody_rejectsMalformedUtf8() = runTest {
        val invalidUtf8Body = byteArrayOf(0x7B, 0x22, 0x80.toByte(), 0x81.toByte(), 0xFF.toByte(), 0x7D) // Invalid UTF-8 bytes
        val bodyLength = invalidUtf8Body.size

        val framedBuffer = ByteBuffer.allocate(CONTAINER_HEADER_SIZE + bodyLength).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(CONTAINER_MAGIC)
            putInt(CONTAINER_WIRE_VERSION)
            putInt(bodyLength)
            put(invalidUtf8Body)
        }
        val framedBytes = framedBuffer.array()
        val rawSha256 = MessageDigest.getInstance("SHA-256").digest(framedBytes)

        val outputBuffer = ByteBuffer.allocate(framedBytes.size + CONTAINER_DIGEST_SIZE).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(framedBytes)
            put(rawSha256)
        }

        val result = encoderDecoder.decodeAndValidateContainer(outputBuffer.array())
        assertTrue("Invalid UTF-8 body MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention UTF-8 encoding", errorMsg.contains("UTF-8"))
    }

    @Test
    fun decodeAndValidate_oversizedContainer_rejectsSizeLimit() = runTest {
        val oversizedBytes = ByteArray((51 * 1024 * 1024)) // 51 MB > 50 MB limit

        val result = encoderDecoder.decodeAndValidateContainer(oversizedBytes)
        assertTrue("Oversized container MUST fail validation", result is DomainResult.Failure)

        val errorMsg = ((result as DomainResult.Failure).error as DomainError.TransactionFailed).cause
        assertTrue("Error MUST mention size limit", errorMsg.contains("size limit"))
    }
}
