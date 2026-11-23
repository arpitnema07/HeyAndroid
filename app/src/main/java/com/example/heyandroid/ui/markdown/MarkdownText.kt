package com.example.heyandroid.ui.markdown

import com.example.heyandroid.ui.components.AppColors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A small Markdown renderer for model replies: bold, italic, inline code,
 * headings, bullet and numbered lists, quotes and fenced code. It renders text
 * only (no HTML, no images, no click handlers), which is all the assistant emits.
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
    accent: Color = MaterialTheme.colorScheme.primary
) {
    val blocks = rememberParsed(text)
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(block.text, color, accent),
                    style = style.copy(
                        fontSize = if (block.level == 1) 19.sp else 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 24.sp,
                        color = color
                    ),
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )

                is MdBlock.Bullet -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                ) {
                    Text(text = "•", style = style, color = accent)
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = inline(block.text, color, accent),
                        style = style.copy(color = color)
                    )
                }

                is MdBlock.Numbered -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                ) {
                    Text(text = "${block.number}.", style = style, color = accent)
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = inline(block.text, color, accent),
                        style = style.copy(color = color)
                    )
                }

                is MdBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .width(2.dp)
                            .background(accent.copy(alpha = 0.6f))
                            .padding(vertical = 2.dp)
                    ) { Spacer(modifier = Modifier.width(2.dp)) }
                    Spacer(modifier = Modifier.width(9.dp))
                    Text(
                        text = inline(block.text, color, accent),
                        style = style.copy(fontStyle = FontStyle.Italic, color = color)
                    )
                }

                is MdBlock.Code -> Text(
                    text = block.text,
                    style = style.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    ),
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.SurfaceSunken)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )

                is MdBlock.Paragraph -> Text(
                    text = inline(block.text, color, accent),
                    style = style.copy(color = color),
                    modifier = Modifier.padding(vertical = 1.dp)
                )

                MdBlock.Spacer -> Spacer(modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Numbered(val number: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Spacer : MdBlock
}

@Composable
private fun rememberParsed(text: String): List<MdBlock> =
    androidx.compose.runtime.remember(text) { parse(text) }

private fun parse(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = text.replace("\r\n", "\n").lines()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            val code = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                code += lines[index]
                index++
            }
            out += MdBlock.Code(code.joinToString("\n"))
            index++
            continue
        }

        when {
            trimmed.isEmpty() -> out += MdBlock.Spacer
            trimmed.startsWith("### ") -> out += MdBlock.Heading(3, trimmed.removePrefix("### "))
            trimmed.startsWith("## ") -> out += MdBlock.Heading(2, trimmed.removePrefix("## "))
            trimmed.startsWith("# ") -> out += MdBlock.Heading(1, trimmed.removePrefix("# "))
            trimmed.startsWith("> ") -> out += MdBlock.Quote(trimmed.removePrefix("> ").trim())
            trimmed == ">" -> out += MdBlock.Spacer
            BULLET.matches(trimmed) -> out += MdBlock.Bullet(
                trimmed.replaceFirst(BULLET, "").trim()
            )
            NUMBERED.matches(trimmed) -> {
                val match = NUMBERED.find(trimmed)!!
                out += MdBlock.Numbered(
                    number = match.groupValues[1],
                    text = trimmed.removeRange(match.range).trim()
                )
            }
            else -> out += MdBlock.Paragraph(trimmed)
        }
        index++
    }
    return out
}

/** Inline spans: **bold**, *italic*, `code`, [label](url) and bare links. */
private fun inline(text: String, color: Color, accent: Color): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        INLINE.findAll(text).forEach { match ->
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") -> withStyle(
                    SpanStyle(fontWeight = FontWeight.SemiBold, color = color)
                ) { append(token.substring(2, token.length - 2)) }

                token.startsWith("`") && token.endsWith("`") -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = AppColors.Chip,
                        color = color
                    )
                ) { append(token.substring(1, token.length - 1)) }

                token.startsWith("[") -> {
                    val close = token.indexOf(']')
                    val label = token.substring(1, close)
                    val url = token.substringAfter("](", "").removeSuffix(")")
                    link(label, url, accent)
                }

                token.startsWith("http") -> link(token, token, accent)

                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(token.trim('*', '_'))
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }

/** A web link the user can tap; anything else stays plain, styled text. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.link(
    label: String,
    url: String,
    accent: Color
) {
    val style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
    if (url.startsWith("http://") || url.startsWith("https://")) {
        withLink(LinkAnnotation.Url(url, TextLinkStyles(style = style))) { append(label) }
    } else {
        withStyle(style) { append(label) }
    }
}

private val BULLET = Regex("^[-*•]\\s+")
private val NUMBERED = Regex("^(\\d+)[.)]\\s+")
private val INLINE = Regex(
    "\\*\\*[^*\\n]+\\*\\*|`[^`\\n]+`|\\[[^\\]]+\\]\\([^)]+\\)|https?://[^\\s)]+|\\*[^*\\n]+\\*|_[^_\\n]+_"
)
