package io.github.jtsang4.aterm.core.domain

import io.github.jtsang4.aterm.core.domain.fixtures.sampleHost
import io.github.jtsang4.aterm.core.domain.fixtures.sampleIdentity
import io.github.jtsang4.aterm.core.domain.fixtures.sampleKnownHostTrust
import io.github.jtsang4.aterm.core.domain.fixtures.sampleSessionMetadata
import io.github.jtsang4.aterm.core.domain.fixtures.sampleSnippet
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.SessionConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationDomainModelTest {
    @Test
    fun host_exposes_endpoint_for_repository_and_ssh_layers() {
        assertEquals("example.internal:22", sampleHost().endpoint)
    }

    @Test
    fun host_can_enter_repair_state_without_losing_record_identity() {
        val host = sampleHost().copy(identityId = null)

        assertFalse(host.hasLinkedIdentity)
        assertEquals(null, host.identityId)
    }

    @Test
    fun key_based_identity_reports_its_material_type() {
        assertTrue(sampleIdentity().usesKeyMaterial)
        assertFalse(sampleIdentity().copy(kind = IdentityKind.PASSWORD).usesKeyMaterial)
    }

    @Test(expected = IllegalArgumentException::class)
    fun password_identity_cannot_claim_a_passphrase() {
        Identity(
            id = 1,
            name = "Password identity",
            kind = IdentityKind.PASSWORD,
            hasSecret = true,
            hasPassphrase = true,
        )
    }

    @Test
    fun snippet_tracks_whether_it_has_an_explicit_target_host() {
        assertTrue(sampleSnippet().hasTargetHost)
        assertFalse(sampleSnippet(hostId = null).hasTargetHost)
    }

    @Test
    fun session_metadata_marks_only_connected_non_reconnect_states_as_live() {
        assertFalse(sampleSessionMetadata().isLive)
        assertTrue(
            sampleSessionMetadata()
                .copy(state = SessionConnectionState.CONNECTED, reconnectRequired = false)
                .isLive,
        )
    }

    @Test
    fun known_host_trust_is_keyed_by_endpoint() {
        assertEquals("example.internal:22", sampleKnownHostTrust().endpointKey)
    }
}
