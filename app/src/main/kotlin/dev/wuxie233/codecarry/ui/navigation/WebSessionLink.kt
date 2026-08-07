package dev.wuxie233.codecarry.ui.navigation

import java.util.Base64

/**
 * Builds the server Web UI URL for a session, identical to what the in-app
 * WebView loads: serverUrl.trimEnd('/') + "/<base64url(directory)>/session/<id>".
 * Uses url-safe, unpadded base64 (matching the original NavGraph +/->-_ + strip
 * '=' scheme so directoryFromSessionPath can still decode it). No credentials
 * are embedded; Basic Auth stays in request headers.
 */
fun buildWebSessionLink(serverUrl: String, directory: String, sessionId: String): String {
    val encodedDir = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(directory.toByteArray(Charsets.UTF_8))
    return serverUrl.trimEnd('/') + "/$encodedDir/session/$sessionId"
}
