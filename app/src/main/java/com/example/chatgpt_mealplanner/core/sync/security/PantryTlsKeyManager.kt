package com.example.chatgpt_mealplanner.core.sync.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class PantryTlsKeyManager @Inject constructor(
    @param:ApplicationContext private val context: Context?
) {
    private val keyAlias = "pantry_tls_key"
    private val fileName = "pantry_tls_keystore.p12"
    private val defaultFallbackPassword = "pantry_tls_password_12345".toCharArray()

    private fun getOrCreateEncryptedPassword(): CharArray {
        val ctx = context ?: return defaultFallbackPassword
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                ctx,
                "pantry_tls_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            var pwd = prefs.getString("KEY_TLS_P12_PASSWORD", null)
            if (pwd.isNullOrBlank()) {
                val pwdBytes = ByteArray(32)
                SecureRandom().nextBytes(pwdBytes)
                pwd = java.util.Base64.getEncoder().encodeToString(pwdBytes)
                prefs.edit().putString("KEY_TLS_P12_PASSWORD", pwd).apply()
            }
            pwd.toCharArray()
        } catch (e: Exception) {
            defaultFallbackPassword
        }
    }

    open fun getOrCreateMainTlsKeyStore(householdId: String, customFilesDir: File? = null): KeyStore {
        val targetDir = customFilesDir ?: context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val ksFile = File(targetDir, fileName)
        val password = getOrCreateEncryptedPassword()

        if (ksFile.exists()) {
            return try {
                val ks = KeyStore.getInstance("PKCS12")
                FileInputStream(ksFile).use { fis ->
                    ks.load(fis, password)
                }
                if (!ks.containsAlias(keyAlias)) {
                    throw SecurityException("Persistent TLS KeyStore exists but does not contain alias '$keyAlias'")
                }
                ks
            } catch (e: Exception) {
                // FAIL CLOSED: Never silently regenerate a replacement TLS certificate for an existing installation!
                throw SecurityException("Persistent Main TLS KeyStore failed to load/decrypt. Refusing to generate a new certificate.", e)
            }
        }

        // First initialization: Generate self-signed certificate & save to PKCS12 file
        val (keyPair, cert) = generateSelfSignedCertificate(householdId)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(keyAlias, keyPair.private, password, arrayOf(cert))

        FileOutputStream(ksFile).use { fos ->
            ks.store(fos, password)
        }

        return ks
    }

    open fun generateSelfSignedCertificate(householdId: String): Pair<KeyPair, X509Certificate> {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val keyPair = keyGen.generateKeyPair()

        val cert = createSelfSignedX509Certificate(householdId, keyPair)
        return Pair(keyPair, cert)
    }

    private fun createSelfSignedX509Certificate(householdId: String, keyPair: KeyPair): X509Certificate {
        val startDate = Date(System.currentTimeMillis() - 86400000L)
        val endDate = Date(System.currentTimeMillis() + 10L * 365 * 864000000L)
        val serialNumber = BigInteger(64, SecureRandom())
        val subjectDn = "CN=PantryMainHost_$householdId"

        val derCert = buildX509DerCertificate(subjectDn, keyPair, startDate, endDate, serialNumber)
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(derCert.inputStream()) as X509Certificate
    }

    private fun buildX509DerCertificate(
        subjectDn: String,
        keyPair: KeyPair,
        startDate: Date,
        endDate: Date,
        serialNumber: BigInteger
    ): ByteArray {
        val derTbs = buildTbsCertificate(subjectDn, keyPair.public, startDate, endDate, serialNumber)

        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(derTbs)
        val signatureBytes = signer.sign()

        val rsaOid = byteArrayOf(0x30.toByte(), 0x0D.toByte(), 0x06.toByte(), 0x09.toByte(), 0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(), 0xF7.toByte(), 0x0D.toByte(), 0x01.toByte(), 0x01.toByte(), 0x0B.toByte(), 0x05.toByte(), 0x00.toByte())

        val sigBitString = ByteArrayOutputStream()
        sigBitString.write(0x03)
        writeLength(sigBitString, signatureBytes.size + 1)
        sigBitString.write(0x00)
        sigBitString.write(signatureBytes)

        val certSeq = ByteArrayOutputStream()
        certSeq.write(derTbs)
        certSeq.write(rsaOid)
        certSeq.write(sigBitString.toByteArray())

        val finalDer = ByteArrayOutputStream()
        finalDer.write(0x30)
        writeLength(finalDer, certSeq.size())
        finalDer.write(certSeq.toByteArray())

        return finalDer.toByteArray()
    }

    private fun buildTbsCertificate(
        dn: String,
        pubKey: java.security.PublicKey,
        startDate: Date,
        endDate: Date,
        serial: BigInteger
    ): ByteArray {
        val out = ByteArrayOutputStream()

        val version = byteArrayOf(0xA0.toByte(), 0x03.toByte(), 0x02.toByte(), 0x01.toByte(), 0x02.toByte())
        out.write(version)

        val serialBytes = serial.toByteArray()
        out.write(0x02)
        writeLength(out, serialBytes.size)
        out.write(serialBytes)

        val rsaOid = byteArrayOf(0x30.toByte(), 0x0D.toByte(), 0x06.toByte(), 0x09.toByte(), 0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(), 0xF7.toByte(), 0x0D.toByte(), 0x01.toByte(), 0x01.toByte(), 0x0B.toByte(), 0x05.toByte(), 0x00.toByte())
        out.write(rsaOid)

        val dnBytes = buildRdnSequence(dn)
        out.write(dnBytes)

        val validityBytes = buildValidityPeriod(startDate, endDate)
        out.write(validityBytes)

        out.write(dnBytes)

        out.write(pubKey.encoded)

        val tbsContent = out.toByteArray()
        val tbsSeq = ByteArrayOutputStream()
        tbsSeq.write(0x30)
        writeLength(tbsSeq, tbsContent.size)
        tbsSeq.write(tbsContent)

        return tbsSeq.toByteArray()
    }

    private fun buildRdnSequence(dn: String): ByteArray {
        val cnValue = dn.removePrefix("CN=").toByteArray(Charsets.UTF_8)
        val cnAttribute = ByteArrayOutputStream()
        cnAttribute.write(byteArrayOf(0x06.toByte(), 0x03.toByte(), 0x55.toByte(), 0x04.toByte(), 0x03.toByte()))
        cnAttribute.write(0x0C)
        writeLength(cnAttribute, cnValue.size)
        cnAttribute.write(cnValue)

        val attrSeq = ByteArrayOutputStream()
        attrSeq.write(0x30)
        writeLength(attrSeq, cnAttribute.size())
        attrSeq.write(cnAttribute.toByteArray())

        val setSeq = ByteArrayOutputStream()
        setSeq.write(0x31)
        writeLength(setSeq, attrSeq.size())
        setSeq.write(attrSeq.toByteArray())

        val rdnSeq = ByteArrayOutputStream()
        rdnSeq.write(0x30)
        writeLength(rdnSeq, setSeq.size())
        rdnSeq.write(setSeq.toByteArray())

        return rdnSeq.toByteArray()
    }

    private fun buildValidityPeriod(start: Date, end: Date): ByteArray {
        val sdf = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")

        val startStr = sdf.format(start).toByteArray(Charsets.US_ASCII)
        val endStr = sdf.format(end).toByteArray(Charsets.US_ASCII)

        val startUtc = ByteArrayOutputStream()
        startUtc.write(0x17)
        writeLength(startUtc, startStr.size)
        startUtc.write(startStr)

        val endUtc = ByteArrayOutputStream()
        endUtc.write(0x17)
        writeLength(endUtc, endStr.size)
        endUtc.write(endStr)

        val validity = ByteArrayOutputStream()
        validity.write(0x30)
        writeLength(validity, startUtc.size() + endUtc.size())
        validity.write(startUtc.toByteArray())
        validity.write(endUtc.toByteArray())

        return validity.toByteArray()
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 128) {
            out.write(length)
        } else if (length < 256) {
            out.write(0x81)
            out.write(length)
        } else {
            out.write(0x82)
            out.write(length shr 8)
            out.write(length and 0xFF)
        }
    }

    open fun getCertificateFingerprint(keyStore: KeyStore): String {
        val cert = keyStore.getCertificate(keyAlias) as? X509Certificate
            ?: throw IllegalArgumentException("KeyStore does not contain X509Certificate for alias '$keyAlias'")
        return computeSha256Fingerprint(cert.encoded)
    }

    open fun computeSha256Fingerprint(certBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(certBytes)
        val sb = StringBuilder("SHA256:")
        for (i in digest.indices) {
            sb.append(String.format("%02X", digest[i]))
            if (i < digest.size - 1) sb.append(":")
        }
        return sb.toString()
    }

    open fun isFingerprintMatching(fp1: String?, fp2: String?): Boolean {
        if (fp1.isNullOrBlank() || fp2.isNullOrBlank()) return false
        val clean1 = fp1.uppercase().replace("SHA256:", "").replace(":", "").trim()
        val clean2 = fp2.uppercase().replace("SHA256:", "").replace(":", "").trim()
        return clean1 == clean2
    }

    fun getTlsPassword(): CharArray = getOrCreateEncryptedPassword()
}
