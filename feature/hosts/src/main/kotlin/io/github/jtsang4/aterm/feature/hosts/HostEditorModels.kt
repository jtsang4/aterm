package io.github.jtsang4.aterm.feature.hosts

import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.HostAuthKind
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind

internal enum class HostAuthMode {
    PASSWORD,
    KEY,
}

internal data class HostEditorDraft(
    val hostId: Long = 0,
    val label: String = "",
    val address: String = "",
    val portText: String = "22",
    val username: String = "",
    val authMode: HostAuthMode? = HostAuthMode.PASSWORD,
    val selectedIdentityId: Long? = null,
) {
    val isEditing: Boolean = hostId != 0L

    companion object {
        fun from(
            host: Host?,
            identities: List<Identity>,
        ): HostEditorDraft {
            val linkedIdentity = identities.firstOrNull { it.id == host?.identityId }
            val authenticationReadyIdentities = identities.filter(Identity::isAuthenticationReady)
            val defaultMode = when {
                host?.authKind == HostAuthKind.KEY -> HostAuthMode.KEY
                host?.authKind == HostAuthKind.PASSWORD -> HostAuthMode.PASSWORD
                host?.authKind == HostAuthKind.UNKNOWN -> null
                linkedIdentity?.kind == IdentityKind.PASSWORD -> HostAuthMode.PASSWORD
                linkedIdentity != null -> HostAuthMode.KEY
                authenticationReadyIdentities.none { it.kind == IdentityKind.PASSWORD } &&
                    authenticationReadyIdentities.any { it.kind != IdentityKind.PASSWORD } -> HostAuthMode.KEY
                else -> HostAuthMode.PASSWORD
            }
            val selectedIdentityId = if (host == null) {
                defaultMode?.let { authenticationReadyIdentities.firstCompatibleIdOrNull(it) }
            } else {
                host.identityId?.takeIf { existingId ->
                    defaultMode?.let { mode ->
                    authenticationReadyIdentities
                        .filter { it.isCompatibleWith(mode) }
                        .any { it.id == existingId }
                    } == true
                }
            }

            return HostEditorDraft(
                hostId = host?.id ?: 0,
                label = host?.label.orEmpty(),
                address = host?.address.orEmpty(),
                portText = (host?.port ?: 22).toString(),
                username = host?.username.orEmpty(),
                authMode = defaultMode,
                selectedIdentityId = selectedIdentityId,
            )
        }
    }
}

internal fun Identity.isCompatibleWith(mode: HostAuthMode): Boolean = when (mode) {
    HostAuthMode.PASSWORD -> kind == IdentityKind.PASSWORD
    HostAuthMode.KEY -> kind != IdentityKind.PASSWORD
}

internal fun List<Identity>.compatibleWith(mode: HostAuthMode): List<Identity> =
    filter { it.isCompatibleWith(mode) }

internal fun List<Identity>.firstCompatibleIdOrNull(mode: HostAuthMode): Long? =
    firstOrNull { it.isCompatibleWith(mode) }?.id

internal fun HostAuthMode.label(): String = when (this) {
    HostAuthMode.PASSWORD -> "Password"
    HostAuthMode.KEY -> "SSH key"
}

internal fun HostAuthMode.identityRequirementLabel(): String = when (this) {
    HostAuthMode.PASSWORD -> "password identity"
    HostAuthMode.KEY -> "key identity"
}
