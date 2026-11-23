package com.example.heyandroid.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.heyandroid.tools.CustomTool
import com.example.heyandroid.tools.CustomToolStore
import com.example.heyandroid.ui.components.AppColors
import com.example.heyandroid.ui.components.AppCard

/**
 * Editor for the user's own HTTP tools. The assistant gets a single
 * `call_custom_api` tool and calls these by name, so adding an endpoint never
 * changes the app's code.
 */
@Composable
fun CustomToolsSection(store: CustomToolStore, accent: Color) {
    val tools by store.tools.collectAsState()

    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var authHeader by remember { mutableStateOf("") }
    var authValue by remember { mutableStateOf("") }
    var post by remember { mutableStateOf(false) }
    var bodyTemplate by remember { mutableStateOf("") }

    Text(
        text = "Your API tools",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.TextPrimary,
        modifier = Modifier.padding(top = 26.dp)
    )
    Text(
        text = "Describe an endpoint once; the assistant can then call it by name. " +
            "Use {{placeholders}} in the URL or body for parameters it fills in.",
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(top = 4.dp)
    )

    Spacer(modifier = Modifier.height(12.dp))

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        ToolField("Name (e.g. weather)", name) { name = it }
        ToolField("Endpoint URL", url) { url = it }
        ToolField("What it does (optional)", description) { description = it }
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MethodChip("GET", !post, accent) { post = false }
            MethodChip("POST", post, accent) { post = true }
        }
        if (post) {
            ToolField("JSON body template (optional)", bodyTemplate) { bodyTemplate = it }
        }
        ToolField("Auth header (optional, e.g. Authorization)", authHeader) { authHeader = it }
        ToolField("Auth value (stored on this phone only)", authValue) { authValue = it }

        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accent)
                    .clickable {
                        store.add(
                            CustomTool(
                                name = name,
                                description = description,
                                url = url,
                                method = if (post) "POST" else "GET",
                                authHeader = authHeader,
                                authValue = authValue,
                                bodyTemplate = bodyTemplate
                            )
                        )
                        name = ""
                        url = ""
                        description = ""
                        authHeader = ""
                        authValue = ""
                        bodyTemplate = ""
                        post = false
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Add tool",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }

    if (tools.isNotEmpty()) {
        Spacer(modifier = Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tools.forEach { tool ->
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = AppColors.TextPrimary
                            )
                            Text(
                                text = "${tool.method} ${tool.url}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextTertiary
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Remove",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { store.remove(tool.name) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MethodChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) AppColors.AccentSoft else AppColors.SurfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) AppColors.TextPrimary else AppColors.TextSecondary
        )
    }
}

@Composable
private fun ToolField(
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        singleLine = true,
        textStyle = TextStyle(
            color = AppColors.TextPrimary,
            fontSize = MaterialTheme.typography.bodySmall.fontSize
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary
                )
            }
            inner()
        }
    )
}
