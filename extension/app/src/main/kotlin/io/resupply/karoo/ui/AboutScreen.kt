package io.resupply.karoo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.launch

/** The GitHub "new issue" form — scanning the report QR lands the rider straight here. */
private const val REPORT_ISSUE_URL = "https://github.com/patricebender/resupply-karoo/issues/new"

/**
 * The "About" screen, reached from Settings and built like [RegionsScreen]: a full page with a
 * back-arrow + title top bar, not a dialog. A dialog couldn't fit a whole page (QR + prose) on the
 * Karoo's short screen — the QR fell below the fold. As a full screen each page owns the whole
 * viewport, so everything (including the QR) is visible at a glance.
 *
 * The body is a three-page [HorizontalPager], one story per page, with navigation dots pinned at
 * the bottom (tappable to jump):
 *  1. **Open & offline** — offline-first, no tracking, open source, + a "Get in touch" button
 *     that opens a QR to the GitHub issue form.
 *  2. **Location** — used to find places, but it never leaves the device (Agreement §4f).
 *  3. **OpenStreetMap** — the data source, refreshed regularly, with a light accuracy note.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val pageCount = 3
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    // The report QR lives behind a button, shown big in a dialog on tap — cramming it onto the
    // page left it tiny and the caption clipped. On tap it gets the room to be readable/scannable.
    var showReportQr by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("About", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()

        // The pager takes all the room between the bar and the dots, so each page is centered in
        // the full viewport and nothing needs to scroll.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (page) {
                    0 -> AboutPageOpenOffline(onReport = { showReportQr = true })
                    1 -> AboutPageLocation()
                    else -> AboutPageData()
                }
            }
        }

        PagerDots(
            count = pageCount,
            current = pagerState.currentPage,
            onDot = { i -> scope.launch { pagerState.animateScrollToPage(i) } },
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }

    if (showReportQr) {
        ReportQrDialog(onDismiss = { showReportQr = false })
    }
}

/** Page 1: the pitch + a button that opens the "get in touch" QR. */
@Composable
private fun AboutPageOpenOffline(onReport: () -> Unit) {
    AboutPageHeader(Icons.Filled.Code, "Open & offline")
    AboutBody(
        "100% offline by default and tracks nothing about you. It's open source and actively " +
            "maintained. Hit a bug or have an idea for a new feature? Your voice will be heard.",
    )
    Spacer(Modifier.height(20.dp))
    // Accent-filled pill, same vocabulary as the update CTA. Opens the QR big in a dialog.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onReport)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.BugReport,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "Get in touch",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
        )
    }
}

/**
 * The "get in touch" QR, shown big in a dialog so it's comfortably readable and scannable. Uses
 * the same QR styling as the rest of the app ([PoiDetailScreen]'s Open-on-phone card): a runtime
 * [rememberQrCodePainter] on a white rounded card, with a short instruction and Close.
 */
@Composable
private fun ReportQrDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                "Get in touch",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Scan with your phone to open the issue form on GitHub.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(10.dp),
                ) {
                    Image(
                        painter = rememberQrCodePainter(REPORT_ISSUE_URL),
                        contentDescription = "QR code linking to the issue report page",
                        modifier = Modifier.size(180.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

/** Page 2: the location disclosure — used, but never leaves the device (Agreement §4f). */
@Composable
private fun AboutPageLocation() {
    AboutPageHeader(Icons.Filled.LocationOn, "Your location stays with you")
    AboutBody(
        "The app uses your location to find water, food and shelter along your route and nearby. " +
            "That location never leaves your Karoo. Nothing is uploaded, shared or tracked.",
    )
}

/** Page 3: the offline OSM database + a light accuracy note. */
@Composable
private fun AboutPageData() {
    AboutPageHeader(Icons.Filled.Storage, "Powered by OpenStreetMap")
    AboutBody(
        "The on-device places database is built from OpenStreetMap data and refreshed regularly. " +
            "OSM is community-made, so a detail can sometimes be wrong or out of date. The community " +
            "keeps improving it, and you can help too.",
    )
}

/** The shared per-page header: an accent icon over a bold centered title. */
@Composable
private fun AboutPageHeader(icon: ImageVector, title: String) {
    Icon(
        icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(32.dp),
    )
    Spacer(Modifier.height(10.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(10.dp))
}

/** The shared per-page body prose, centered and muted. */
@Composable
private fun AboutBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * The pager page indicator: [count] dots with the [current] one filled in the accent and grown,
 * the rest muted and small. Each dot is tappable to jump to its page; size/color animate so a
 * swipe reads as one continuous motion.
 */
@Composable
private fun PagerDots(
    count: Int,
    current: Int,
    onDot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until count) {
            val active = i == current
            val color by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                },
                animationSpec = tween(durationMillis = 220),
                label = "pagerDotColor",
            )
            val diameter by animateFloatAsState(
                targetValue = if (active) 10f else 8f,
                animationSpec = tween(durationMillis = 220),
                label = "pagerDotSize",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 5.dp)
                    .size(diameter.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onDot(i) },
            )
        }
    }
}
