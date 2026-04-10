package io.github.jtsang4.aterm.core.domain.fixtures

import io.github.jtsang4.aterm.core.domain.FeatureArea
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.KnownHostTrust
import io.github.jtsang4.aterm.core.domain.model.SessionConnectionState
import io.github.jtsang4.aterm.core.domain.model.SessionMetadata
import io.github.jtsang4.aterm.core.domain.model.Snippet
import io.github.jtsang4.aterm.core.domain.model.ThemePreference
import io.github.jtsang4.aterm.core.domain.model.UserPreferences
import java.time.Instant

private val sampleInstant: Instant = Instant.parse("2026-04-10T00:00:00Z")

fun sampleHost(
    id: Long = 1,
    identityId: Long = 1,
): Host = Host(
    id = id,
    label = "Primary host",
    address = "example.internal",
    port = 22,
    username = "factory",
    identityId = identityId,
    isFavorite = true,
    lastUsedAt = sampleInstant,
    createdAt = sampleInstant,
    updatedAt = sampleInstant,
)

fun sampleIdentity(id: Long = 1): Identity = Identity(
    id = id,
    name = "Primary identity",
    kind = IdentityKind.IMPORTED_KEY,
    username = "factory",
    publicKey = "TEST_PUBLIC_KEY_PLACEHOLDER_NOT_REAL",
    hasSecret = true,
    hasPassphrase = true,
    createdAt = sampleInstant,
    updatedAt = sampleInstant,
)

fun sampleSnippet(id: Long = 1, hostId: Long? = 1): Snippet = Snippet(
    id = id,
    title = "Restart service",
    description = "Restarts the local service and prints status.",
    tags = listOf("ops", "safe"),
    hostId = hostId,
    createdAt = sampleInstant,
    updatedAt = sampleInstant,
    lastRunAt = sampleInstant,
)

fun sampleSessionMetadata(id: Long = 1, hostId: Long = 1): SessionMetadata = SessionMetadata(
    id = id,
    hostId = hostId,
    state = SessionConnectionState.RECONNECT_REQUIRED,
    title = "example.internal",
    connectedAt = sampleInstant,
    disconnectedAt = sampleInstant,
    reconnectRequired = true,
    lastError = "Socket closed",
)

fun sampleKnownHostTrust(id: Long = 1): KnownHostTrust = KnownHostTrust(
    id = id,
    host = "example.internal",
    port = 22,
    algorithm = "ssh-ed25519",
    fingerprint = "SHA256:examplefingerprint",
    hostKeyBase64 = "TEST_HOST_KEY_PLACEHOLDER_NOT_REAL",
    createdAt = sampleInstant,
)

fun sampleUserPreferences(): UserPreferences = UserPreferences(
    themePreference = ThemePreference.DARK,
    terminalFontScale = 1.15f,
    lastViewedArea = FeatureArea.Snippets,
)
