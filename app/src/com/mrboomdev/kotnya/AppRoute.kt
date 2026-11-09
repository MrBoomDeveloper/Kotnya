package com.mrboomdev.kotnya

import androidx.navigation3.runtime.NavKey

/** The destinations of the IDE, used as Navigation 3 back-stack keys. */
sealed interface AppRoute : NavKey

data object ProjectsRoute : AppRoute

data object NewProjectRoute : AppRoute

data object SettingsRoute : AppRoute

data object SdkDashboardRoute : AppRoute

data class EditorRoute(val projectPath: String) : AppRoute
