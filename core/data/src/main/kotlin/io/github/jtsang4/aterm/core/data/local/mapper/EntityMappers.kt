package io.github.jtsang4.aterm.core.data.local.mapper

import io.github.jtsang4.aterm.core.data.local.entity.HostEntity
import io.github.jtsang4.aterm.core.data.local.entity.IdentityEntity
import io.github.jtsang4.aterm.core.data.local.entity.KnownHostTrustEntity
import io.github.jtsang4.aterm.core.data.local.entity.SessionMetadataEntity
import io.github.jtsang4.aterm.core.data.local.entity.SnippetEntity
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.KnownHostTrust
import io.github.jtsang4.aterm.core.domain.model.SessionConnectionState
import io.github.jtsang4.aterm.core.domain.model.SessionMetadata
import io.github.jtsang4.aterm.core.domain.model.Snippet
import java.time.Instant

internal fun HostEntity.toDomain(): Host = Host(
    id = id,
    label = label,
    address = address,
    port = port,
    username = username,
    identityId = identityId,
    isFavorite = isFavorite,
    lastUsedAt = lastUsedAtEpochMillis?.toInstant(),
    createdAt = createdAtEpochMillis.toInstant(),
    updatedAt = updatedAtEpochMillis.toInstant(),
)

internal fun Host.toEntity(): HostEntity = HostEntity(
    id = id,
    label = label,
    address = address,
    port = port,
    username = username,
    identityId = identityId,
    isFavorite = isFavorite,
    lastUsedAtEpochMillis = lastUsedAt?.toEpochMilli(),
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
)

internal fun IdentityEntity.toDomain(): Identity = Identity(
    id = id,
    name = name,
    kind = IdentityKind.valueOf(kind),
    username = username,
    publicKey = publicKey,
    hasSecret = hasSecret,
    hasPassphrase = hasPassphrase,
    createdAt = createdAtEpochMillis.toInstant(),
    updatedAt = updatedAtEpochMillis.toInstant(),
)

internal fun Identity.toEntity(
    primaryCipherText: ByteArray? = null,
    primaryIv: ByteArray? = null,
    passphraseCipherText: ByteArray? = null,
    passphraseIv: ByteArray? = null,
): IdentityEntity = IdentityEntity(
    id = id,
    name = name,
    kind = kind.name,
    username = username,
    publicKey = publicKey,
    hasSecret = hasSecret,
    hasPassphrase = hasPassphrase,
    primaryCipherText = primaryCipherText,
    primaryIv = primaryIv,
    passphraseCipherText = passphraseCipherText,
    passphraseIv = passphraseIv,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
)

internal fun SnippetEntity.toDomain(): Snippet = Snippet(
    id = id,
    title = title,
    description = description,
    tags = tagsSerialized.toTagList(),
    hostId = hostId,
    createdAt = createdAtEpochMillis.toInstant(),
    updatedAt = updatedAtEpochMillis.toInstant(),
    lastRunAt = lastRunAtEpochMillis?.toInstant(),
)

internal fun Snippet.toEntity(
    bodyCipherText: ByteArray? = null,
    bodyIv: ByteArray? = null,
): SnippetEntity = SnippetEntity(
    id = id,
    title = title,
    description = description,
    tagsSerialized = tags.serializeTags(),
    hostId = hostId,
    bodyCipherText = bodyCipherText,
    bodyIv = bodyIv,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
    lastRunAtEpochMillis = lastRunAt?.toEpochMilli(),
)

internal fun SessionMetadataEntity.toDomain(): SessionMetadata = SessionMetadata(
    id = id,
    hostId = hostId,
    state = SessionConnectionState.valueOf(state),
    title = title,
    connectedAt = connectedAtEpochMillis?.toInstant(),
    disconnectedAt = disconnectedAtEpochMillis?.toInstant(),
    reconnectRequired = reconnectRequired,
    lastError = lastError,
)

internal fun SessionMetadata.toEntity(): SessionMetadataEntity = SessionMetadataEntity(
    id = id,
    hostId = hostId,
    state = state.name,
    title = title,
    connectedAtEpochMillis = connectedAt?.toEpochMilli(),
    disconnectedAtEpochMillis = disconnectedAt?.toEpochMilli(),
    reconnectRequired = reconnectRequired,
    lastError = lastError,
)

internal fun KnownHostTrustEntity.toDomain(): KnownHostTrust = KnownHostTrust(
    id,
    host,
    port,
    algorithm,
    fingerprint,
    hostKeyBase64,
    createdAtEpochMillis.toInstant(),
)

internal fun KnownHostTrust.toEntity(): KnownHostTrustEntity = KnownHostTrustEntity(
    id,
    host,
    port,
    algorithm,
    fingerprint,
    hostKeyBase64,
    createdAt.toEpochMilli(),
)

private fun Long.toInstant(): Instant = Instant.ofEpochMilli(this)

private fun List<String>.serializeTags(): String = joinToString(separator = "|") { it.trim() }

private fun String.toTagList(): List<String> =
    takeIf { it.isNotBlank() }
        ?.split('|')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()
