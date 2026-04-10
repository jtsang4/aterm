package io.github.jtsang4.aterm

import io.github.jtsang4.aterm.core.domain.FeatureArea
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSmokeTest {
    @Test
    fun exposes_expected_feature_areas() {
        assertEquals(5, FeatureArea.entries.size)
    }
}
