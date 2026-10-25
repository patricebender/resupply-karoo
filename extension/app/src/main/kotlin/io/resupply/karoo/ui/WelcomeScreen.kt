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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.resupply.karoo.R

/**
 * First-run welcome for the lean edition (no bundled data). Shown once when the app opens with
 * nothing installed. A two-page [HorizontalPager] (same pattern as [AboutScreen]) so the rider
 * gets both halves of the pitch before the Waybook:
 *  1. **Set up your homebase** — download region data (or skip). The only page with the CTAs;
 *     both buttons mark onboarding seen.
 *  2. **The data field** — what the on-ride field does, with a screenshot.
 *
 * It's never a hard gate — [onChooseRegion] opens the picker (where the Wi‑Fi guidance lives),
 * [onSkip] goes to the Waybook, and swiping between pages leaves both choices reachable.
 */
@Composable
fun WelcomeScreen(
    onChooseRegion: () -> Unit,
    onSkip: () -> Unit,
) {
    val pageCount = 2
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Column(modifier = Modifier.fillMaxSize()) {
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
                    0 -> WelcomePageHomebase(onChooseRegion = onChooseRegion, onSkip = onSkip)
                    else -> WelcomePageDataField()
                }
            }
        }

        PagerDots(
            count = pageCount,
            current = pagerState.currentPage,
            onDot = null,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }
}

/** Page 1: the homebase pitch + the download / skip CTAs. */
@Composable
private fun WelcomePageHomebase(
    onChooseRegion: () -> Unit,
    onSkip: () -> Unit,
) {
    Icon(
        Icons.Filled.Download,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(40.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        "Set up your homebase.",
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "Resupply maps the water, food and shops along your route. Fully offline. " +
            "Download the area you ride most and add more any time.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onChooseRegion, modifier = Modifier.fillMaxWidth()) {
        Text("Download region data")
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
        Text("Maybe later")
    }
}

/** Page 2: what the on-ride data field does, with a screenshot of it in action. */
@Composable
private fun WelcomePageDataField() {
    Icon(
        Icons.Filled.Navigation,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(32.dp),
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "See what's ahead, while you ride.",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Add a Resupply data field to any ride profile. It shows the next water, food and shops " +
            "ahead, right on your ride screen.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    Image(
        painter = painterResource(R.drawable.field_preview),
        contentDescription = "A Resupply data field on the Karoo ride screen",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
    )
}

/**
 * The pager page indicator: [count] dots with the [current] one filled in the accent and grown,
 * the rest muted and small. Size/color animate so a swipe reads as one continuous motion. Pass
 * [onDot] to make a dot tappable to jump to its page; null leaves them swipe-only.
 */
@Composable
private fun PagerDots(
    count: Int,
    current: Int,
    onDot: ((Int) -> Unit)?,
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
                label = "welcomeDotColor",
            )
            val diameter by animateFloatAsState(
                targetValue = if (active) 10f else 8f,
                animationSpec = tween(durationMillis = 220),
                label = "welcomeDotSize",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 5.dp)
                    .size(diameter.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (onDot != null) {
                            Modifier.clickable { onDot(i) }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}
