package com.example.heyandroid.widget

import android.util.Log
import com.example.heyandroid.data.memory.Memory
import com.example.heyandroid.data.memory.MemoryKind
import com.example.heyandroid.llm.LlmClient
import com.example.heyandroid.llm.LlmClientFactory
import com.example.heyandroid.llm.LlmMessage
import com.example.heyandroid.llm.LlmResult
import com.example.heyandroid.llm.LlmSettingsStore
import com.example.heyandroid.memory.MemoryFormatter
import com.example.heyandroid.memory.MemoryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The brief agent: the user's memories and the widget's real canvas size go in,
 * one HTML page comes out, which [HtmlImageRenderer] turns into the widget image.
 *
 * The model authors the whole page - markup **and** stylesheet - for exactly the
 * width and height the widget reports, so the design is built for its actual
 * aspect ratio instead of a guess. A random direction and a nonce push each run
 * somewhere new; the renderer's measurement of a clipped page is fed back
 * through [feedback] so the model can correct its own layout.
 *
 * The page is never empty: without a key, or when the model cannot be reached,
 * [localPage] composes the same content as a sticky-note wall at the same size.
 */
@Singleton
class BriefGenerator @Inject constructor(
    private val repository: MemoryRepository,
    private val settings: LlmSettingsStore
) {

    /**
     * @param width canvas width in pixels, measured from the widget
     * @param height canvas height in pixels, measured from the widget
     * @param feedback what the renderer measured about the previous attempt
     */
    suspend fun html(
        widthCss: Int,
        heightCss: Int,
        deviceWidthCss: Int,
        deviceHeightCss: Int,
        feedback: String? = null
    ): String {
        val width = widthCss
        val height = heightCss
        val everything = repository.recent(60)
        val picks = pick(everything, capacity(width, height))
        if (picks.isEmpty()) return page(emptyPage(width, height), width, height)

        val config = settings.config.value
        if (!config.isConfigured) {
            Log.d(TAG, "no API key: local sticky-note page")
            return page(
                localPage(
                    picks,
                    everything.size,
                    "Offline design · add an API key in Settings for an AI-drawn brief.",
                    width,
                    height
                ),
                width,
                height
            )
        }

        val client = LlmClientFactory.create(
            config.provider, config.apiKey, config.model, config.baseUrl
        )
        val input = buildString {
            append(briefInput(picks, everything.size, width, height))
            append("\nPhone portrait canvas: ").append(deviceWidthCss).append('x')
                .append(deviceHeightCss)
                .append(" CSS px. The app page scrolls, so height is flexible; the square ")
                .append("above is the part the home-screen widget shows.\n")
            if (feedback != null) {
                append("\n\nCorrection from the renderer: ").append(feedback)
            }
        }
        val messages = listOf(LlmMessage.system(promptFor(width, height)), LlmMessage.user(input))

        val first = ask(client, messages)
        if (first == null) {
            Log.w(TAG, "model returned nothing")
            return page(
                localPage(
                    picks,
                    everything.size,
                    "Offline design · the model did not answer, tap refresh to try again.",
                    width,
                    height
                ),
                width,
                height
            )
        }
        Log.d(TAG, "AI page from ${config.model} (${first.length} chars)")

        // A brief that silently drops half the data is not a brief.
        val missing = picks.filterNot { first.contains(it.title.take(20).trim(), ignoreCase = true) }
        if (missing.size * 2 > picks.size) {
            Log.d(TAG, "first page missed ${missing.size}/${picks.size}; asking again")
            val retry = ask(
                client,
                messages + LlmMessage("assistant", first) + LlmMessage.user(
                    "That page dropped ${missing.size} of the ${picks.size} items: " +
                        missing.joinToString("; ") { it.title } +
                        ". Redesign the whole page so every supplied item appears, still " +
                        "${width}x$height, 20px inner margins, long text wraps, nothing clipped."
                )
            )
            if (retry != null) return page(retry, width, height)
        }

        return page(first, width, height)
    }

    private suspend fun ask(client: LlmClient, messages: List<LlmMessage>): String? {
        val result = runCatching {
            client.complete(messages = messages, maxTokens = 2200)
        }.onFailure { Log.w(TAG, "model call failed", it) }.getOrNull()

        val text = (result as? LlmResult.Success)?.text ?: return null
        val body = stripFences(text)
        return body.takeIf { it.length > 120 && it.contains("<") }
    }

    /**
     * How many memories are worth asking for at this canvas size: roughly the
     * rows that fit at a legible size, across one or two columns.
     */
    private fun capacity(width: Int, height: Int): Int {
        // ~40px rows once the headline and totals line are accounted for, in two
        // columns as soon as the canvas is wide enough for them.
        val rows = ((height - 56) / 40).coerceIn(1, 6)
        val columns = if (width >= 300) 2 else 1
        return (rows * columns).coerceIn(4, 10)
    }

    /**
     * Up to [target] memories, taken round-robin across kinds so a brief is never
     * ten variations of the same list. Newest first inside each kind.
     */
    private fun pick(memories: List<Memory>, target: Int): List<Memory> {
        if (memories.isEmpty()) return emptyList()
        val byKind = LinkedHashMap<MemoryKind, MutableList<Memory>>()
        memories.sortedByDescending { it.updatedAt }.forEach { memory ->
            byKind.getOrPut(memory.kind) { mutableListOf() } += memory
        }

        val picked = mutableListOf<Memory>()
        while (picked.size < target) {
            var addedThisRound = false
            for (kind in MemoryKind.entries) {
                val queue = byKind[kind] ?: continue
                if (queue.isEmpty()) continue
                picked += queue.removeAt(0)
                addedThisRound = true
                if (picked.size >= target) break
            }
            if (!addedThisRound) break
        }
        return picked
    }

    private fun briefInput(picks: List<Memory>, total: Int, width: Int, height: Int): String {
        val lines = picks.mapIndexed { index, memory ->
            buildString {
                append(index + 1).append(". [")
                    .append(MemoryFormatter.kindLabel(memory).uppercase(Locale.ROOT))
                if (memory.collection.isNotBlank()) append(" · ").append(memory.collection)
                if (memory.status.name != "ACTIVE") append(" · ").append(memory.status.name.lowercase())
                append("] ").append(memory.title)
                if (memory.progress.isNotBlank()) append(" — at ").append(memory.progress)
                if (memory.content.isNotBlank()) {
                    append(" — ").append(memory.content.replace('\n', ' ').take(220))
                }
                append(" · ").append(age(memory.updatedAt))
            }
        }.joinToString("\n")

        return buildString {
            append("Today: ").append(MemoryFormatter.now()).append('\n')
            append("Saved in total: ").append(total).append('\n')
            append("Widget canvas: ").append(width).append('x').append(height)
                .append(" CSS pixels (exact; design for this aspect ratio)\n")
            append("Design direction for this run: ").append(DIRECTIONS.random()).append('\n')
            append("Nonce (make this run look unlike the last): ").append(System.nanoTime()).append('\n')
            append("Items (every one of the ").append(picks.size)
                .append(" must appear; mix the kinds, do not group by kind):\n").append(lines)
        }
    }

    /**
     * Deterministic fallback: a wall of sticky notes built from the same picks,
     * sized to the same canvas. Inline SVG icons, tape strips, folded corners.
     */
    private fun localPage(
        picks: List<Memory>,
        total: Int,
        footer: String,
        width: Int,
        height: Int
    ): String {
        val columns = (width / 170).coerceIn(2, 5)
        val notes = picks.take(columns * 2).mapIndexed { index, memory ->
            val tint = tint(memory)
            val angle = if (index % 2 == 0) -1.2 else 1.1
            buildString {
                append(
                    """<div class="note" style="transform:rotate(${angle}deg);border-color:${tint}55">"""
                )
                append("""<div class="tape" style="background:${tint}44"></div>""")
                append("""<div class="head"><span class="ic" style="color:$tint">""")
                append(icon(memory.kind))
                append("</span><span class=\"kind\" style=\"color:$tint\">")
                append(escape(MemoryFormatter.kindLabel(memory).uppercase(Locale.ROOT)))
                append("</span><span class=\"when\">")
                append(escape(age(memory.updatedAt)))
                append("</span></div>")
                append("""<div class="title">""").append(escape(memory.title)).append("</div>")
                val body = buildString {
                    if (memory.progress.isNotBlank()) append(memory.progress).append(" · ")
                    if (memory.content.isNotBlank()) {
                        append(memory.content.replace('\n', ' ').replace(Regex("^[-*•]\\s*"), ""))
                    }
                }.trim().take(140)
                if (body.isNotEmpty()) {
                    append("""<div class="body">""").append(escape(body)).append("</div>")
                }
                MemoryFormatter.due(memory)?.let {
                    append("""<div class="due" style="color:$tint">""")
                        .append(escape(it)).append("</div>")
                }
                append("</div>")
            }
        }.joinToString("")

        return buildString {
            append(STYLE(width, height, columns))
            append("""<div class="wall">""")
            append(notes)
            append("""<div class="foot">""")
            append(escape("$total saved · ${picks.size} in this brief")).append("<span>")
            append(escape(footer)).append("</span></div>")
            append("</div>")
        }
    }

    private fun emptyPage(width: Int, height: Int): String {
        return buildString {
            append(STYLE(width, height, 1))
            append(
                """
                <div class="wall empty">
                  <div class="note" style="transform:rotate(-1.4deg)">
                    <div class="tape" style="background:#8FA8FF44"></div>
                    <div class="head"><span class="ic" style="color:#8FA8FF">${icon(MemoryKind.FACT)}</span>
                      <span class="kind" style="color:#8FA8FF">HEYANDROID</span></div>
                    <div class="title">Nothing saved yet</div>
                    <div class="body">Summon the assistant and say what to remember - your brief builds itself from that.</div>
                  </div>
                </div>
                """.trimIndent()
            )
        }
    }

    private fun icon(kind: MemoryKind): String = when (kind) {
        MemoryKind.TASK ->
            """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="16" height="16" rx="3"/><path d="M8 12.4l2.6 2.7L16 9.6"/></svg>"""
        MemoryKind.PROGRESS ->
            """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.4"/><path d="M10.2 8.6l5.2 3.4-5.2 3.4z"/></svg>"""
        MemoryKind.ITEM ->
            """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M11.4 3.6h5.6a3.4 3.4 0 0 1 3.4 3.4v5.6l-8.4 8.4-9-9z"/><path d="M16.2 8.4h.01"/></svg>"""
        MemoryKind.NOTE ->
            """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20l1.2-4.2 11-11a2.1 2.1 0 0 1 3 3l-11 11z"/><path d="M14.4 6.6l3 3M5.2 15.8l3 3"/></svg>"""
        MemoryKind.TRANSACTION ->
            """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3.6" y="7.2" width="16.8" height="9.6" rx="1.4"/><circle cx="12" cy="12" r="2.6"/><path d="M6.2 12h.01M17.8 12h.01"/></svg>"""
        MemoryKind.FACT ->
            """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.2l1.9 5.1 5.1 1.9-5.1 1.9-1.9 5.1-1.9-5.1-5.1-1.9 5.1-1.9z"/><path d="M18.4 15.6l.7 1.9 1.9.7-1.9.7-.7 1.9-.7-1.9-1.9-.7 1.9-.7z"/></svg>"""
    }

    private fun tint(memory: Memory): String = when (memory.kind) {
        MemoryKind.PROGRESS -> "#7DE2C3"
        MemoryKind.TASK -> "#8FA8FF"
        MemoryKind.ITEM -> "#7FC7FF"
        MemoryKind.TRANSACTION -> "#9BE38A"
        MemoryKind.NOTE -> "#C3A6FF"
        MemoryKind.FACT -> "#FF9AA5"
    }

    private fun age(timestamp: Long): String {
        val delta = System.currentTimeMillis() - timestamp
        val minutes = delta / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))
        }
    }

    /** Fixed shell: the canvas is pinned to the widget's size. */
    private fun page(inner: String, width: Int, height: Int): String = """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=$width, initial-scale=1">
        <style>
          * { box-sizing: border-box; margin: 0; padding: 0; }
          html, body {
            width: ${width}px; height: ${height}px; overflow: hidden;
            font-family: -apple-system, "Roboto", "Segoe UI", sans-serif;
            background: #0B0B12; color: #F5F4F9;
            -webkit-font-smoothing: antialiased;
          }
          svg { display: block; }
        </style>
        </head>
        <body>$inner</body></html>
    """.trimIndent()

    private fun stripFences(raw: String): String = raw.trim()
        .removePrefix("```html").removePrefix("```HTML").removePrefix("```")
        .removeSuffix("```")
        .trim()

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private companion object {
        const val TAG = "BriefGen"

        /** A wall of paper notes; the fallback design and the empty state share it. */
        fun STYLE(width: Int, height: Int, columns: Int) = """
            <style>
              .wall {
                width: ${width}px; height: ${height}px; padding: 12px 14px 28px;
                display: grid; grid-template-columns: repeat($columns, 1fr); gap: 8px;
                align-content: start;
                background:
                  radial-gradient(420px 240px at 8% -10%, rgba(143,168,255,.18), transparent 62%),
                  radial-gradient(380px 220px at 96% 108%, rgba(125,226,195,.14), transparent 60%);
              }
              .wall.empty { grid-template-columns: 1fr; padding: 40px 60px; }
              .note {
                position: relative; overflow: hidden;
                background: #151922; border: 1px solid #2A3040; border-radius: 12px;
                padding: 11px 10px 10px; display: flex; flex-direction: column; gap: 4px;
              }
              .note::after {
                content: ""; position: absolute; right: 0; bottom: 0;
                border-left: 12px solid transparent; border-bottom: 12px solid rgba(0,0,0,.45);
              }
              .tape {
                position: absolute; top: 0; left: 12px; width: 34px; height: 6px;
                border-bottom-left-radius: 3px; border-bottom-right-radius: 3px;
              }
              .head { display: flex; align-items: center; gap: 5px; }
              .ic svg { width: 11px; height: 11px; }
              .kind { font-size: 8px; letter-spacing: 1px; font-weight: 700; }
              .when { margin-left: auto; font-size: 8px; color: #7E8797; }
              .title {
                font-size: 11px; line-height: 1.25; font-weight: 650; color: #EDF0F6;
                display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
                overflow: hidden;
              }
              .body {
                font-size: 9px; line-height: 1.3; color: #A9B1BE;
                display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
                overflow: hidden;
              }
              .due { font-size: 8px; font-weight: 600; margin-top: auto; }
              .foot {
                position: absolute; left: 14px; right: 14px; bottom: 9px;
                display: flex; gap: 8px; font-size: 8.5px; color: #7E8797;
              }
              .foot span { color: #5F6878; }
            </style>
        """.trimIndent()

        val DIRECTIONS = listOf(
            "a wall of slightly rotated paper notes with tape and folded corners",
            "an editorial page: oversized headline, hairline rules, one strong column",
            "a compact dashboard: big numerals, small captions, thin progress bars",
            "a vertical timeline of today, with the newest item pulled out large",
            "a poster: one hero item at large type, the rest as a dense index strip",
            "a notebook spread: ruled lines, margin scribbles, arrows between entries"
        )

        fun promptFor(width: Int, height: Int): String = """
            You design the home-screen brief for a private memory app: ONE page,
            exactly ${width}x$height CSS pixels. You get the user's saved memories,
            the date, that canvas size, and a design direction hint.

            Output the page body as HTML: your own markup plus ONE <style> block.
            Do not output <html>, <head> or <body> tags, and no markdown fences.

            Hard rules:
            - The canvas is ${width}x$height CSS pixels. No scrolling, nothing
              clipped or overflowing, no fixed 800px assumptions.
            - Make the layout responsive to that canvas: use %, fr and flex/grid,
              not fixed pixel widths. A short canvas gets fewer, denser rows; a
              wider one gets columns. Fonts may be sized in px, but the layout must
              adapt to the space you are given.
            - No <script>, no external images, fonts, or links. System fonts only.
              Inline SVG is allowed and encouraged for icons and decoration.
            - Never make a network request; CSS gradients and inline SVG only.
            - Text must be readable: body text >= 11px, contrast >= 4.5:1 against
              whatever sits behind it. No text over busy gradients.
            - Dark canvas: the page background must be between #0B0B12 and #1A1F29.
              The widget sits on a dark launcher, so never use a light page.
            - Every supplied item must appear, each with its useful detail
              (position, amount, due time, or first line of content). If the canvas
              is short, shorten the details rather than dropping items.
            - Show totals: how many are active, what is due, what changed recently.
            - Use the real data. No invented memories, no placeholder rows, and no
              generic heading like "My Memories" - the headline must name the
              actual day or the most important item.
            - Keep 14px inner margins and let long text wrap: nothing may run off
              the right or bottom edge.
            - Never put the items in a fixed-height box with overflow:hidden, never
              position items absolutely, never use a list with hidden overflow. If
              a row does not fit, shorten its text - do not clip it.

            Make it new every time:
            - The layout must be derived from the content: if the data is mostly
              positions in shows, design for progress; if it is money, design for
              amounts; if it is tasks, design for what is left.
            - Choose a distinct composition and palette for this run. Vary type
              scale, density, alignment, borders, corner radius, and background
              treatment. Do not reuse a fixed template or the same accents twice.
            - The direction hint is a starting point, not a rule; follow the data
              when it suggests something better.
            - Be a creative display, not a plain list of cards: draw doodles,
              hand-drawn arrows, underlines, stars, tape strips, stickers and
              small illustrations as inline SVG. Give every item its own icon.
              A hand-made, sketched feel beats another dashboard.
            - Icons must be line drawings: <svg> with fill="none" and a stroke of
              1.6-2, 16-22px. Never draw icons as filled colour blocks or plain
              rectangles.
            - Keep 16px clear margins and never draw a frame or border around the
              whole canvas. Spread the items over the whole square: no large
              empty corner, no heavy top band.
            - It must look designed but alive: clear hierarchy, generous spacing,
              no clutter, no emoji spam.
        """.trimIndent()
    }
}
