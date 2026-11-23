package com.example.heyandroid.brief

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.heyandroid.R
import com.example.heyandroid.ui.components.AppBackground
import com.example.heyandroid.ui.components.AppColors
import com.example.heyandroid.widget.BriefWorker
import java.io.File
import kotlinx.coroutines.delay

/**
 * Shows the brief page the assistant designed: the cached HTML is loaded into a
 * WebView, exactly as the widget rendered it. If nothing has been built yet the
 * page says so and starts a build, then swaps the WebView in when it lands.
 */
@Composable
fun BriefPage(onClose: () -> Unit = {}, embedded: Boolean = false) {
    val context = LocalContext.current
    val htmlFile = remember(context) { File(context.filesDir, "brief.html") }

    var html by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0L) }
    var building by remember { mutableStateOf(false) }

    // The worker rewrites brief.html when it finishes, so poll its modification
    // time instead of opening a channel between the worker and this screen.
    LaunchedEffect(Unit) {
        while (true) {
            val file = htmlFile
            if (file.exists()) {
                val modified = file.lastModified()
                if (modified != revision) {
                    val text = runCatching { file.readText() }.getOrNull()
                    if (!text.isNullOrBlank()) {
                        html = text
                        revision = modified
                        building = false
                    }
                }
            }
            delay(900)
        }
    }

    fun rebuild() {
        building = true
        BriefWorker.enqueue(context)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // As a tab the page sits inside the home screen, which already owns the
        // background and the system-bar insets.
        if (!embedded) AppBackground(accent = AppColors.Accent)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (embedded) {
                        Modifier
                    } else {
                        Modifier
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!embedded) {
                    CircleButton(
                        icon = R.drawable.ic_arrow_back,
                        label = "Back",
                        onClick = onClose
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (embedded) 6.dp else 10.dp)
                ) {
                    Text(
                        text = "Brief",
                        style = MaterialTheme.typography.headlineSmall,
                        color = AppColors.TextPrimary
                    )
                    Text(
                        text = "The page your widget shows",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                }
                CircleButton(
                    icon = R.drawable.ic_refresh,
                    label = "Design a new brief",
                    onClick = { rebuild() }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                val page = html
                if (page == null || building && page.isBlank()) {
                    DesigningBrief()
                } else {
                    HtmlPage(html = page, revision = revision)

                    Row(
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_kind_fact),
                            contentDescription = null,
                            tint = AppColors.Accent,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Designed for your widget from your latest memories.",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextTertiary
                        )
                    }

                    if (building) {
                        Row(
                            modifier = Modifier.padding(bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.5.dp,
                                color = AppColors.Accent
                            )
                            Text(
                                text = "Designing a new page…",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CircleButton(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(AppColors.Surface)
            .border(1.dp, AppColors.Border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(19.dp)
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPage(html: String, revision: Long) {
    // The page is authored for the widget's CSS width; scale it to the screen so
    // a narrow phone never clips the right-hand column.
    val pageWidth = remember(html) {
        Regex("width=(\\d+)").find(html)?.groupValues?.getOrNull(1)?.toFloatOrNull()
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, AppColors.Border, RoundedCornerShape(22.dp)),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.parseColor("#0B0B12"))
                settings.apply {
                    javaScriptEnabled = false
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    blockNetworkLoads = true
                    allowFileAccess = false
                    allowContentAccess = false
                    // Scale is computed from the page's own canvas width once it
                    // has loaded, so the whole design fits the screen width.
                    useWideViewPort = true
                    loadWithOverviewMode = false
                    setSupportZoom(false)
                    builtInZoomControls = false
                }
                isVerticalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        val target = pageWidth ?: return
                        val viewWidthCss = view.width / view.resources.displayMetrics.density
                        if (viewWidthCss > 0f && target > 0f) {
                            view.setInitialScale(((viewWidthCss / target) * 100).toInt())
                        }
                    }
                }
            }
        },
        update = { web ->
            if (web.tag != revision) {
                web.tag = revision
                web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }
    )
}

@Composable
private fun DesigningBrief() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AppColors.Surface)
            .border(1.dp, AppColors.Border, RoundedCornerShape(22.dp))
            .padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 2.dp,
            color = AppColors.Accent
        )
        Text(
            text = "Designing your brief",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = "The model is composing a page from your latest memories. It lands on your widget at the same time.",
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
