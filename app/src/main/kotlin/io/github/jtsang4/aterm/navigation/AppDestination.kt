package io.github.jtsang4.aterm.navigation

import io.github.jtsang4.aterm.core.domain.FeatureArea
import io.github.jtsang4.aterm.feature.hosts.HostsEntryPoint
import io.github.jtsang4.aterm.feature.identities.IdentitiesEntryPoint
import io.github.jtsang4.aterm.feature.session.SessionEntryPoint
import io.github.jtsang4.aterm.feature.settings.SettingsEntryPoint
import io.github.jtsang4.aterm.feature.snippets.SnippetsEntryPoint

enum class AppDestination(
    val featureArea: FeatureArea,
    val route: String,
    val label: String,
) {
    Hosts(
        featureArea = FeatureArea.Hosts,
        route = HostsEntryPoint.route,
        label = "Hosts",
    ),
    Identities(
        featureArea = FeatureArea.Identities,
        route = IdentitiesEntryPoint.route,
        label = "Identities",
    ),
    Session(
        featureArea = FeatureArea.Session,
        route = SessionEntryPoint.route,
        label = "Sessions",
    ),
    Snippets(
        featureArea = FeatureArea.Snippets,
        route = SnippetsEntryPoint.route,
        label = "Snippets",
    ),
    Settings(
        featureArea = FeatureArea.Settings,
        route = SettingsEntryPoint.route,
        label = "Settings",
    ),
    ;

    companion object {
        val topLevel = entries

        fun fromRoute(route: String?): AppDestination = topLevel.firstOrNull { it.route == route } ?: Hosts
    }
}
