package dev.wuxie233.codecarry.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.domain.model.ServerConfig
import dev.wuxie233.codecarry.domain.model.ServerType

/**
 * Parse and validate a server URL string.
 * Accepts formats like:
 *   http://192.168.0.10:4096
 *   https://192.168.0.10
 *   https://my-server.example.com:4848
 *   192.168.0.10:4096           -> defaults to http://
 *   192.168.0.10                -> defaults to http://
 *
 * Returns the normalized URL (with scheme) or null if invalid.
 */
internal fun validateAndNormalizeUrl(input: String, serverType: ServerType = ServerType.OPENCODE): String? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null

    val allowedSchemes = setOf("http", "https")
    val defaultScheme = "http"
    val suppliedScheme = trimmed.substringBefore("://", missingDelimiterValue = "")
    val withScheme = if (suppliedScheme !in allowedSchemes) {
        if ("://" in trimmed) return null
        "$defaultScheme://$trimmed"
    } else {
        trimmed
    }

    return try {
        val uri = java.net.URI(withScheme)
        if (uri.scheme !in allowedSchemes || uri.host.isNullOrBlank()) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        java.net.URI(
            uri.scheme,
            null,
            uri.host,
            uri.port,
            uri.path.takeUnless { it.isNullOrBlank() || it == "/" },
            null,
            null,
        ).toString().trimEnd('/')
    } catch (e: Exception) {
        null
    }
}

private fun deriveServerNameFromUrl(normalizedUrl: String): String {
    return try {
        val url = java.net.URI(normalizedUrl)
        val host = url.host
        val port = url.port
        if (port != -1) "$host:$port" else host
    } catch (_: Exception) {
        normalizedUrl
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("ws://")
            .removePrefix("wss://")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDialog(
    server: ServerConfig?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        url: String,
        type: ServerType,
        username: String,
        password: String?,
        token: String?,
        autoConnect: Boolean,
    ) -> Unit,
) {
    var name by remember { mutableStateOf(server?.name ?: "") }
    var serverType by remember { mutableStateOf(server?.type ?: ServerType.OPENCODE) }
    var url by remember { mutableStateOf(server?.url ?: "http://") }
    var username by remember { mutableStateOf(server?.username ?: "opencode") }
    var password by remember { mutableStateOf(server?.password ?: "") }
    var token by remember { mutableStateOf(server?.token ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var autoConnect by remember { mutableStateOf(server?.autoConnect ?: false) }

    var urlError by remember { mutableStateOf<String?>(null) }

    val nameLabel = stringResource(R.string.server_name)
    val urlLabel = stringResource(R.string.server_url)
    val usernameLabel = stringResource(R.string.server_username)
    val passwordLabel = stringResource(R.string.server_password)
    val passwordToggleDescription = stringResource(
        if (passwordVisible) R.string.server_password_hide else R.string.server_password_show
    )
    val saveLabel = stringResource(R.string.server_save)
    val addServerLabel = stringResource(R.string.server_add)
    val editServerLabel = stringResource(R.string.home_edit)
    val saveServerDescription = "$saveLabel ${if (server != null) editServerLabel else addServerLabel}"
    val urlRequiredText = urlLabel
    val urlInvalidText = stringResource(R.string.server_invalid_url)
    val dialogMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    val scrollState = rememberScrollState()

    val isAmoled = MaterialTheme.colorScheme.background == Color.Black && MaterialTheme.colorScheme.surface == Color.Black
    val switchColors = if (isAmoled) {
        SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedTrackColor = Color.Black,
            checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = Color.Black,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
        )
    } else {
        SwitchDefaults.colors()
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(if (serverType == ServerType.OPENCODE) 14.dp else 20.dp),
            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
            border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
            tonalElevation = if (isAmoled) 0.dp else 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = dialogMaxHeight)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (server != null) editServerLabel else addServerLabel,
                        style = MaterialTheme.typography.headlineSmall
                    )

                    val serverTypeOptions = listOf(ServerType.OPENCODE, ServerType.DSH)
                    Text(
                        text = stringResource(R.string.server_type),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        serverTypeOptions.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = serverType == option,
                                onClick = {
                                    serverType = option
                                    urlError = null
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = serverTypeOptions.size),
                                label = {
                                    Text(stringResource(when (option) {
                                        ServerType.OPENCODE -> R.string.server_type_opencode
                                        ServerType.DSH -> R.string.server_type_dsh
                                    }))
                                },
                            )
                        }
                    }

                    if (serverType == ServerType.OPENCODE) {
                        Text(
                            text = stringResource(R.string.server_connection_section),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(nameLabel) },
                        placeholder = { Text(stringResource(R.string.server_name_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = nameLabel }
                    )

                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            urlError = null
                        },
                        label = { Text(urlLabel) },
                        placeholder = {
                            Text(stringResource(
                                if (serverType == ServerType.DSH) R.string.server_dsh_url_hint else R.string.server_url_hint
                            ))
                        },
                        isError = urlError != null,
                        supportingText = if (urlError != null) {
                            { Text(urlError!!) }
                        } else {
                            {
                                Text(stringResource(
                                    if (serverType == ServerType.DSH) R.string.server_dsh_url_example else R.string.server_url_example
                                ))
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = urlLabel }
                    )

                    if (serverType == ServerType.OPENCODE || serverType == ServerType.DSH) {
                    Text(
                        text = stringResource(R.string.server_authentication_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (serverType == ServerType.OPENCODE) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(usernameLabel) },
                        placeholder = { Text(stringResource(R.string.server_username_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = usernameLabel }
                    )
                    }

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = {
                            Text(
                                if (serverType == ServerType.DSH) {
                                    stringResource(R.string.server_dsh_password)
                                } else {
                                    passwordLabel
                                }
                            )
                        },
                        placeholder = {
                            Text(
                                stringResource(
                                    if (serverType == ServerType.DSH) {
                                        R.string.server_dsh_password_hint
                                    } else {
                                        R.string.server_password_hint
                                    }
                                )
                            )
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = passwordToggleDescription
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = passwordLabel }
                    )

                    if (serverType == ServerType.DSH) {
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text(stringResource(R.string.server_dsh_token)) },
                            placeholder = { Text(stringResource(R.string.server_dsh_token_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    }

                    Surface(
                        shape = RoundedCornerShape(if (serverType == ServerType.OPENCODE) 8.dp else 12.dp),
                        color = if (serverType == ServerType.OPENCODE) {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
                        },
                        border = if (serverType == ServerType.OPENCODE) {
                            null
                        } else {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.server_auto_connect),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = stringResource(R.string.server_auto_connect_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = autoConnect,
                                onCheckedChange = { autoConnect = it },
                                colors = switchColors
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.server_cancel))
                    }
                    TextButton(
                        modifier = Modifier.semantics { contentDescription = saveServerDescription },
                        onClick = {
                            // A pasted launch URL carries ?token=; split it into the
                            // token field so the saved URL stays clean.
                            val pastedToken = runCatching {
                                java.net.URI(url.trim()).rawQuery
                            }.getOrNull()?.let { query ->
                                query.split('&')
                                    .firstOrNull { it.startsWith("token=") }
                                    ?.substringAfter('=')
                                    ?.takeIf { it.isNotBlank() }
                            }
                            if (pastedToken != null) {
                                if (token.isBlank()) token = java.net.URLDecoder.decode(pastedToken, "UTF-8")
                                val uri = java.net.URI(url.trim())
                                url = java.net.URI(
                                    uri.scheme,
                                    null,
                                    uri.host,
                                    uri.port,
                                    uri.path.takeUnless { it.isNullOrBlank() },
                                    null,
                                    null,
                                ).toString().trimEnd('/')
                            }
                            val normalizedUrl = validateAndNormalizeUrl(url, serverType)
                            urlError = when {
                                url.isBlank() -> urlRequiredText
                                normalizedUrl == null -> urlInvalidText
                                else -> null
                            }

                            if (urlError == null && normalizedUrl != null) {
                                val finalName = name.trim().ifBlank {
                                    deriveServerNameFromUrl(normalizedUrl)
                                }
                                onSave(
                                    finalName,
                                    normalizedUrl,
                                    serverType,
                                    if (serverType == ServerType.DSH) "" else username.ifBlank { "opencode" },
                                    password.takeIf { it.isNotBlank() },
                                    if (serverType == ServerType.DSH) token.trim().takeIf { it.isNotBlank() } else null,
                                    autoConnect
                                )
                            }
                        }
                    ) {
                        Text(saveLabel)
                    }
                }
            }
        }
    }
}
