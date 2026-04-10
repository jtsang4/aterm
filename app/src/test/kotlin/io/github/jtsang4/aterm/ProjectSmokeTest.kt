package io.github.jtsang4.aterm

import io.github.jtsang4.aterm.core.domain.FeatureArea
import io.github.jtsang4.aterm.di.AppContainer
import io.github.jtsang4.aterm.navigation.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSmokeTest {
    @Test
    fun exposes_expected_feature_areas() {
        assertEquals(5, FeatureArea.entries.size)
    }

    @Test
    fun app_container_exposes_top_level_destinations_for_each_feature_area() {
        val container = AppContainer()

        assertEquals(AppDestination.topLevel.map { it.featureArea }, container.topLevelDestinations.map { it.featureArea })
    }
}
