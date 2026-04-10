package io.github.jtsang4.aterm.core.domain

import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureAreaTest {
    @Test
    fun labels_are_non_blank() {
        assertTrue(FeatureArea.entries.all { it.label.isNotBlank() })
    }
}
