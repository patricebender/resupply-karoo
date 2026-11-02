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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** The GitHub "new issue" form. Scanning the feedback QR lands the rider straight here. */
private const val REPORT_ISSUE_URL = "https://github.com/patricebender/resupply-karoo/issues/new"

/**
 * "Privacy & Data", reached from Settings → About. A full page (back-arrow + title top bar) with a
 * three-page [HorizontalPager], one reassurance per page, navigation dots pinned at the bottom:
 *  1. **Offline by default**: search runs on-device; the internet is only used for Google hours.
 *  2. **Your location stays with you**: used to find places, never leaves the device.
 *  3. **Powered by OpenStreetMap**: the data source, refreshed regularly, with an accuracy note.
 */
@Composable
fun PrivacyDataScreen(onBack: () -> Unit) {
    val pageCount = 3
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        AboutTopBar("Privacy & Data", onBack)
        HorizontalDivider()

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (page) {
                    0 -> AboutPage(
                        Icons.Filled.Code,
                        "Offline by default",
                        "Search runs 100% on your Karoo. The only time the internet is used is when " +
                            "you request opening hours via Google Maps.",
                    )
                    1 -> AboutPage(
                        Icons.Filled.LocationOn,
                        "Your location stays with you",
                        "The app uses your location to find water, food and shelter along your " +
                            "route and nearby. That location never leaves your Karoo. Nothing is " +
                            "uploaded, shared or tracked.",
                    )
                    else -> AboutPage(
                        Icons.Filled.Storage,
                        "Powered by OpenStreetMap",
                        "The on-device places database is built from OpenStreetMap data and " +
                            "refreshed regularly. OSM is community-made, so a detail can sometimes " +
                            "be wrong or out of date.",
                    )
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
}

/**
 * "Give Feedback", reached from Settings → About. A single centered page: the open-source pitch,
 * the bug/idea invitation, and a "Get in touch" button that opens the GitHub issue-form QR big
 * enough to scan with a phone.
 */
@Composable
fun FeedbackScreen(onBack: () -> Unit) {
    var showReportQr by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        AboutTopBar("Give Feedback", onBack)
        HorizontalDivider()

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AboutPage(
                Icons.Filled.BugReport,
                "Open source",
                "Resupply is open source and actively maintained. Found a bug or have an idea for a " +
                    "new feature?",
            )
            Spacer(Modifier.height(20.dp))
            // Accent-filled pill, same vocabulary as the update CTA. Opens the QR big in a dialog.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { showReportQr = true }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Get in touch",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                )
            }
        }
    }

    if (showReportQr) {
        ReportQrDialog(onDismiss = { showReportQr = false })
    }
}

/** The shared back-arrow + title top bar for the About sub-screens. */
@Composable
private fun AboutTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

/** A centered About page: an accent icon over a bold title over muted body prose. */
@Composable
private fun AboutPage(icon: ImageVector, title: String, body: String) {
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
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * The "get in touch" QR, shown big in a dialog so it's comfortably readable and scannable. Scanning
 * it opens the GitHub issue form on a phone. Same QR styling as the rest of the app.
 */
@Composable
private fun ReportQrDialog(onDismiss: () -> Unit) {
    AlertDialog(
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
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

/**
 * The pager page indicator: [count] dots with the [current] one filled in the accent and grown, the
 * rest muted and small. Each dot is tappable to jump to its page; size/color animate so a swipe
 * reads as one continuous motion.
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
