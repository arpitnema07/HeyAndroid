package com.example.heyandroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heyandroid.llm.LlmSettingsStore
import com.example.heyandroid.tools.McpClient
import com.example.heyandroid.ui.components.AppCard
import com.example.heyandroid.ui.components.AppColors
import kotlinx.coroutines.launch

/**
 * Connects an MCP server - a Google Workspace one, for instance - so the assistant
 * can call its tools (read mail, list files, add events) from the chat. The URL and
 * token stay in this device's private settings.
 */
@Composable
fun McpConnectorSection(store: LlmSettingsStore, accent: Color) {
    val url by store.mcpUrl.collectAsState()
    val token by store.mcpToken.collectAsState()

    var urlDraft by remember(url) { mutableStateOf(url) }
    var tokenDraft by remember(token) { mutableStateOf(token) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    Text(
        text = "MCP connector",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.TextPrimary
    )
    Text(
        text = if (url.isBlank()) {
            "Off. Connect an MCP server (a Google Workspace one, for example) and the " +
                "assistant can use its tools - mail, calendar, Drive files - in the chat."
        } else {
            "Connected to $url. The assistant calls it through the mcp_call tool."
        },
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(top = 4.dp)
    )

    Spacer(modifier = Modifier.height(10.dp))

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column {
            McpField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                placeholder = "https://mcp.example.com/mcp",
                accent = accent
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                McpField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it },
                    placeholder = "Access token (optional)",
                    accent = accent,
                    secret = !reveal,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (reveal) "Hide" else "Show",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { reveal = !reveal }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }

    Row(
        modifier = Modifier.padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(AppColors.SurfaceRaised)
                .clickable(enabled = !busy) {
                    store.setMcpConnection(urlDraft, tokenDraft)
                    urlDraft = urlDraft.trim()
                    tokenDraft = tokenDraft.trim()
                    keyboard?.hide()
                    status = if (urlDraft.isBlank()) null else "Saved"
                }
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Save",
                style = MaterialTheme.typography.labelLarge,
                color = AppColors.TextPrimary
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = if (busy) "Connecting…" else "Connect",
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !busy && urlDraft.isNotBlank()) {
                    keyboard?.hide()
                    status = null
                    busy = true
                    scope.launch {
                        val result = McpClient().listTools(
                            urlDraft.trim(),
                            tokenDraft.trim()
                        )
                        status = result.fold(
                            onSuccess = { tools ->
                                if (tools.isEmpty()) {
                                    "Connected, but the server lists no tools."
                                } else {
                                    "Connected · ${tools.size} tools: " +
                                        tools.take(8).joinToString(", ") { it.name }
                                }
                            },
                            onFailure = { "Failed: ${it.message ?: "unknown error"}" }
                        )
                        busy = false
                    }
                }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )

        if (url.isNotBlank()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Turn off",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        store.clearMcpConnection()
                        urlDraft = ""
                        tokenDraft = ""
                        status = null
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }

    status?.let { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun McpField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    accent: Color,
    secret: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.SurfaceSunken)
            .border(1.dp, AppColors.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = AppColors.TextPrimary,
                letterSpacing = 0.2.sp
            ),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri
            ),
            visualTransformation = if (secret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextTertiary
                    )
                }
                inner()
            }
        )
    }
}
