pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "aterm"

include(
    ":app",
    ":core:designsystem",
    ":core:domain",
    ":core:data",
    ":core:security",
    ":core:ssh",
    ":core:terminal",
    ":feature:hosts",
    ":feature:identities",
    ":feature:session",
    ":feature:snippets",
    ":feature:settings",
)
