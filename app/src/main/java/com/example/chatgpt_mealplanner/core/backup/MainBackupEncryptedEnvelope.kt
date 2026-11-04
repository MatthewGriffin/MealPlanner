package com.example.chatgpt_mealplanner.core.backup

val ENCRYPTED_CONTAINER_MAGIC = "PANTRYENC".toByteArray(Charsets.US_ASCII) // 9 bytes
const val ENCRYPTED_WIRE_VERSION = 1 // 4 bytes Big-Endian Int32
const val FULL_BACKUP_WIRE_VERSION = 2 // Whole-database archive, restorable by this app
const val KDF_ID_PBKDF2_HMAC_SHA256: Short = 1 // 2 bytes
const val AEAD_ID_AES_256_GCM: Short = 1 // 2 bytes
const val KDF_SALT_SIZE_BYTES = 32 // 32 bytes
const val AEAD_NONCE_SIZE_BYTES = 12 // 12 bytes
const val AEAD_GCM_TAG_SIZE_BITS = 128 // 16 bytes tag
const val AEAD_GCM_TAG_SIZE_BYTES = 16

const val MIN_KDF_ITERATIONS = 10_000
const val MAX_KDF_ITERATIONS = 1_000_000
const val DEFAULT_KDF_ITERATIONS = 250_000

// 9 (magic) + 4 (version) + 2 (kdfId) + 2 (aeadId) + 32 (salt) + 12 (nonce) + 4 (iterations) + 4 (ciphertextLength) = 69 bytes
const val ENCRYPTED_HEADER_SIZE_BYTES = 69
const val MIN_ENCRYPTED_CONTAINER_SIZE_BYTES = ENCRYPTED_HEADER_SIZE_BYTES + AEAD_GCM_TAG_SIZE_BYTES // 85 bytes

// Max encrypted container size = Max plaintext body (50 MB) + PANTRYBK framing overhead (48 B) + PANTRYENC header (69 B) + GCM tag (16 B) = 52,428,933 bytes
const val MAX_ENCRYPTED_CONTAINER_SIZE_BYTES = MAX_CONTAINER_BODY_SIZE_BYTES + CONTAINER_OVERHEAD_SIZE + ENCRYPTED_HEADER_SIZE_BYTES + AEAD_GCM_TAG_SIZE_BYTES

data class EncryptedBackupHeader(
    val magic: ByteArray,
    val wireVersion: Int,
    val kdfAlgorithmId: Short,
    val aeadAlgorithmId: Short,
    val salt: ByteArray,
    val nonce: ByteArray,
    val iterations: Int,
    val ciphertextLength: Int
)
