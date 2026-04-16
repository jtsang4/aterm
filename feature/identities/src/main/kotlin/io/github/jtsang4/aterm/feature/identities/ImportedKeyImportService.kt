package io.github.jtsang4.aterm.feature.identities

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyFactory
import java.security.Security
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.util.security.SecurityUtils

open class ImportedKeyImportService {
    init {
        ensureBouncyCastleRegistered()
    }

    open fun parse(
        privateKeyMaterial: String,
        passphrase: String?,
    ): ImportedKeyParseResult {
        val normalizedMaterial = privateKeyMaterial.trim()
        if (normalizedMaterial.isBlank()) {
            return ImportedKeyParseResult.InvalidKeyMaterial
        }

        return try {
            val keyPairs = loadKeyPairs(normalizedMaterial, passphrase)
            val importedPair = keyPairs.firstOrNull() ?: return ImportedKeyParseResult.InvalidKeyMaterial
            ImportedKeyParseResult.Success(
                publicKey = PublicKeyEntry.toString(importedPair.public, null),
                hasPassphrase = requiresPassphrase(normalizedMaterial),
            )
        } catch (throwable: Throwable) {
            classifyFailure(privateKeyMaterial = normalizedMaterial, passphrase = passphrase, throwable = throwable)
        }
    }

    private fun loadKeyPairs(
        privateKeyMaterial: String,
        passphrase: String?,
    ): List<KeyPair> {
        if (looksLikePemPrivateKey(privateKeyMaterial)) {
            return listOf(loadPemKeyPair(privateKeyMaterial, passphrase))
        }
        return ByteArrayInputStream(privateKeyMaterial.encodeToByteArray()).use { input ->
            SecurityUtils.loadKeyPairIdentities(
                null,
                IMPORT_RESOURCE,
                input,
                passphrase?.takeIf(String::isNotBlank)?.let(FilePasswordProvider::of) ?: FilePasswordProvider.EMPTY,
            )
        }?.map(::normalizeKeyPair)?.toList().orEmpty()
    }

    private fun loadPemKeyPair(
        privateKeyMaterial: String,
        passphrase: String?,
    ): KeyPair {
        val converter = JcaPEMKeyConverter().setProvider(BOUNCY_CASTLE_PROVIDER)
        return PEMParser(StringReader(privateKeyMaterial)).use { parser ->
            when (val parsed = parser.readObject() ?: error("Private key could not be loaded.")) {
                is PEMEncryptedKeyPair -> {
                    val password = passphrase?.takeIf(String::isNotBlank)?.toCharArray()
                        ?: error("Private key could not be loaded.")
                    normalizeKeyPair(
                        converter.getKeyPair(
                            parsed.decryptKeyPair(
                                JcePEMDecryptorProviderBuilder()
                                    .setProvider(BOUNCY_CASTLE_PROVIDER)
                                    .build(password),
                            ),
                        ),
                    )
                }

                is PEMKeyPair -> normalizeKeyPair(converter.getKeyPair(parsed))

                is PKCS8EncryptedPrivateKeyInfo -> {
                    val password = passphrase?.takeIf(String::isNotBlank)?.toCharArray()
                        ?: error("Private key could not be loaded.")
                    keyPairFromPrivateKeyInfo(
                        converter,
                        parsed.decryptPrivateKeyInfo(
                            JceOpenSSLPKCS8DecryptorProviderBuilder()
                                .setProvider(BOUNCY_CASTLE_PROVIDER)
                                .build(password),
                        ),
                    )
                }

                is PrivateKeyInfo -> keyPairFromPrivateKeyInfo(converter, parsed)

                else -> error("Private key could not be loaded.")
            }
        }
    }

    private fun keyPairFromPrivateKeyInfo(
        converter: JcaPEMKeyConverter,
        privateKeyInfo: PrivateKeyInfo,
    ): KeyPair {
        val privateKey = converter.getPrivateKey(privateKeyInfo)
        val keyFactory = KeyFactory.getInstance("RSA")
        val publicKey = when (privateKey) {
            is RSAPrivateCrtKey -> keyFactory.generatePublic(
                RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent),
            )

            else -> error("Private key could not be loaded.")
        }
        val normalizedPrivateKey = when (privateKey) {
            is RSAPrivateCrtKey -> keyFactory.generatePrivate(
                RSAPrivateCrtKeySpec(
                    privateKey.modulus,
                    privateKey.publicExponent,
                    privateKey.privateExponent,
                    privateKey.primeP,
                    privateKey.primeQ,
                    privateKey.primeExponentP,
                    privateKey.primeExponentQ,
                    privateKey.crtCoefficient,
                ),
            )

            else -> error("Private key could not be loaded.")
        }
        return KeyPair(publicKey, normalizedPrivateKey)
    }

    private fun normalizeKeyPair(keyPair: KeyPair): KeyPair {
        val privateKey = keyPair.private
        return when (privateKey) {
            is RSAPrivateCrtKey -> {
                val keyFactory = KeyFactory.getInstance("RSA")
                KeyPair(
                    keyFactory.generatePublic(
                        RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent),
                    ),
                    keyFactory.generatePrivate(
                        RSAPrivateCrtKeySpec(
                            privateKey.modulus,
                            privateKey.publicExponent,
                            privateKey.privateExponent,
                            privateKey.primeP,
                            privateKey.primeQ,
                            privateKey.primeExponentP,
                            privateKey.primeExponentQ,
                            privateKey.crtCoefficient,
                        ),
                    ),
                )
            }

            else -> keyPair
        }
    }

    private fun requiresPassphrase(privateKeyMaterial: String): Boolean = try {
        loadKeyPairs(privateKeyMaterial, passphrase = null)
        false
    } catch (throwable: Throwable) {
        classifyFailure(
            privateKeyMaterial = privateKeyMaterial,
            passphrase = null,
            throwable = throwable,
        ) == ImportedKeyParseResult.PassphraseRequired
    }

    private fun classifyFailure(
        privateKeyMaterial: String,
        passphrase: String?,
        throwable: Throwable,
    ): ImportedKeyParseResult {
        val normalizedMessages = throwable.messageChain().map { it.lowercase() }
        if (normalizedMessages.any { "encrypted resource" in it || "password data" in it }) {
            return ImportedKeyParseResult.PassphraseRequired
        }
        if (
            passphrase.isNullOrBlank() &&
            looksLikePrivateKey(privateKeyMaterial) &&
            looksLikeEncryptedPrivateKey(privateKeyMaterial) &&
            normalizedMessages.none { message -> INVALID_KEY_MATERIAL_MARKERS.any(message::contains) }
        ) {
            return ImportedKeyParseResult.PassphraseRequired
        }
        if (
            !passphrase.isNullOrBlank() &&
            looksLikePrivateKey(privateKeyMaterial) &&
            normalizedMessages.any { message ->
                INCORRECT_PASSPHRASE_MARKERS.any(message::contains)
            }
        ) {
            return ImportedKeyParseResult.IncorrectPassphrase
        }
        if (
            !passphrase.isNullOrBlank() &&
            looksLikePrivateKey(privateKeyMaterial) &&
            looksLikeEncryptedPrivateKey(privateKeyMaterial) &&
            normalizedMessages.none { message -> INVALID_KEY_MATERIAL_MARKERS.any(message::contains) }
        ) {
            return ImportedKeyParseResult.IncorrectPassphrase
        }
        if (
            !passphrase.isNullOrBlank() &&
            looksLikePrivateKey(privateKeyMaterial) &&
            normalizedMessages.none { message -> INVALID_KEY_MATERIAL_MARKERS.any(message::contains) } &&
            throwable is GeneralSecurityException
        ) {
            return ImportedKeyParseResult.IncorrectPassphrase
        }
        return ImportedKeyParseResult.InvalidKeyMaterial
    }

    private fun Throwable.messageChain(): List<String> {
        val messages = mutableListOf<String>()
        var current: Throwable? = this
        while (current != null) {
            current.message?.takeIf(String::isNotBlank)?.let(messages::add)
            current = current.cause
        }
        return messages
    }

    private fun looksLikePrivateKey(privateKeyMaterial: String): Boolean =
        PRIVATE_KEY_HEADER_REGEX.containsMatchIn(privateKeyMaterial)

    private fun looksLikePemPrivateKey(privateKeyMaterial: String): Boolean =
        privateKeyMaterial.contains("BEGIN RSA PRIVATE KEY") ||
            privateKeyMaterial.contains("BEGIN EC PRIVATE KEY") ||
            privateKeyMaterial.contains("BEGIN DSA PRIVATE KEY") ||
            privateKeyMaterial.contains("BEGIN ENCRYPTED PRIVATE KEY")

    private fun looksLikeEncryptedPrivateKey(privateKeyMaterial: String): Boolean =
        ENCRYPTED_PRIVATE_KEY_MARKERS.any(privateKeyMaterial::contains)

    private fun Iterable<KeyPair>.toList(): List<KeyPair> = iterator().asSequence().toList()

    private companion object {
        fun ensureBouncyCastleRegistered() {
            val currentProvider = Security.getProvider(BOUNCY_CASTLE_PROVIDER)
            if (currentProvider !is BouncyCastleProvider) {
                Security.removeProvider(BOUNCY_CASTLE_PROVIDER)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }

        const val BOUNCY_CASTLE_PROVIDER = "BC"
        val IMPORT_RESOURCE = NamedResource { "imported-private-key" }
        val PRIVATE_KEY_HEADER_REGEX = Regex("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----")
        val ENCRYPTED_PRIVATE_KEY_MARKERS = listOf(
            "Proc-Type: 4,ENCRYPTED",
            "DEK-Info:",
            "BEGIN OPENSSH PRIVATE KEY",
        )
        val INCORRECT_PASSPHRASE_MARKERS = listOf(
            "decrypt",
            "password",
            "checksum",
            "mac",
            "padding",
            "integrity",
            "bad decrypt",
            "passphrase",
            "mismatch",
        )
        val INVALID_KEY_MATERIAL_MARKERS = listOf(
            "unsupported",
            "invalid key",
            "bad key",
            "cannot retrieve decoder",
            "format",
            "malformed",
            "no begin marker",
            "not a private key",
        )
    }
}

sealed interface ImportedKeyParseResult {
    data class Success(
        val publicKey: String,
        val hasPassphrase: Boolean,
    ) : ImportedKeyParseResult

    data object PassphraseRequired : ImportedKeyParseResult

    data object IncorrectPassphrase : ImportedKeyParseResult

    data object InvalidKeyMaterial : ImportedKeyParseResult
}
