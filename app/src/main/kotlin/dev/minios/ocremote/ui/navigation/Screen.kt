package dev.minios.ocremote.ui.navigation

import java.net.URLEncoder

/**
 * Navigation routes for the app
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    
    data object WebView : Screen("webview") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            initialPath: String = ""
        ): String {
            val encodedUrl = URLEncoder.encode(serverUrl, "UTF-8")
            val encodedUsername = URLEncoder.encode(username, "UTF-8")
            val encodedPassword = URLEncoder.encode(password, "UTF-8")
            val encodedName = URLEncoder.encode(serverName, "UTF-8")
            val encodedPath = URLEncoder.encode(initialPath, "UTF-8")
            return "webview?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&initialPath=$encodedPath"
        }
    }
    
    data object SessionList : Screen("sessions") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            serverId: String
        ): String {
            val encodedUrl = URLEncoder.encode(serverUrl, "UTF-8")
            val encodedUsername = URLEncoder.encode(username, "UTF-8")
            val encodedPassword = URLEncoder.encode(password, "UTF-8")
            val encodedName = URLEncoder.encode(serverName, "UTF-8")
            val encodedServerId = URLEncoder.encode(serverId, "UTF-8")
            return "sessions?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&serverId=$encodedServerId"
        }
    }

    data object RoundtableCenter : Screen("roundtable_center") {
        fun createRoute(serverId: String): String {
            val encodedServerId = URLEncoder.encode(serverId, "UTF-8")
            return "roundtable_center?serverId=$encodedServerId"
        }
    }


    data object RoundtableCasting : Screen("roundtable_casting") {
        fun createRoute(serverId: String): String {
            val encodedServerId = URLEncoder.encode(serverId, "UTF-8")
            return "roundtable_casting?serverId=$encodedServerId"
        }
    }

    data object RoundtableSummary : Screen("roundtable_summary") {
        fun createRoute(
            serverId: String,
            roundtableId: String,
        ): String {
            val encodedServerId = URLEncoder.encode(serverId, "UTF-8")
            val encodedRoundtableId = URLEncoder.encode(roundtableId, "UTF-8")
            return "roundtable_summary?serverId=$encodedServerId&roundtableId=$encodedRoundtableId"
        }
    }

    data object PersonaLibrary : Screen("persona_library") {
        fun createRoute(serverId: String): String {
            val encodedServerId = URLEncoder.encode(serverId, "UTF-8")
            return "persona_library?serverId=$encodedServerId"
        }
    }
    data object Chat : Screen("chat") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            serverId: String,
            sessionId: String,
            openTerminal: Boolean = false,
            directory: String = "",
            serverType: String = "OPENCODE",
        ): String {
            val encodedUrl = URLEncoder.encode(serverUrl, "UTF-8")
            val encodedUsername = URLEncoder.encode(username, "UTF-8")
            val encodedPassword = URLEncoder.encode(password, "UTF-8")
            val encodedName = URLEncoder.encode(serverName, "UTF-8")
            val encodedServerId = URLEncoder.encode(serverId, "UTF-8")
            val encodedSessionId = URLEncoder.encode(sessionId, "UTF-8")
            val encodedDirectory = URLEncoder.encode(directory, "UTF-8")
            val encodedServerType = URLEncoder.encode(serverType, "UTF-8")
            return "chat?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&serverId=$encodedServerId&sessionId=$encodedSessionId&openTerminal=$openTerminal&directory=$encodedDirectory&serverType=$encodedServerType"
        }
    }

    data object ServerSettings : Screen("server_settings") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            serverId: String
        ): String {
            val encodedUrl = URLEncoder.encode(serverUrl, "UTF-8")
            val encodedUsername = URLEncoder.encode(username, "UTF-8")
            val encodedPassword = URLEncoder.encode(password, "UTF-8")
            val encodedName = URLEncoder.encode(serverName, "UTF-8")
            val encodedServerId = URLEncoder.encode(serverId, "UTF-8")
            return "server_settings?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&serverId=$encodedServerId"
        }
    }

    data object ServerProviders : Screen("server_providers") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            serverId: String
        ): String {
            val encodedUrl = URLEncoder.encode(serverUrl, "UTF-8")
            val encodedUsername = URLEncoder.encode(username, "UTF-8")
            val encodedPassword = URLEncoder.encode(password, "UTF-8")
            val encodedName = URLEncoder.encode(serverName, "UTF-8")
            val encodedServerId = URLEncoder.encode(serverId, "UTF-8")
            return "server_providers?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&serverId=$encodedServerId"
        }
    }

    data object ServerModelFilter : Screen("server_model_filter") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            serverId: String
        ): String {
            val encodedUrl = URLEncoder.encode(serverUrl, "UTF-8")
            val encodedUsername = URLEncoder.encode(username, "UTF-8")
            val encodedPassword = URLEncoder.encode(password, "UTF-8")
            val encodedName = URLEncoder.encode(serverName, "UTF-8")
            val encodedServerId = URLEncoder.encode(serverId, "UTF-8")
            return "server_model_filter?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&serverId=$encodedServerId"
        }
    }
    
    data object Settings : Screen("settings")
    data object Diagnostics : Screen("diagnostics")
    data object About : Screen("about")
}
