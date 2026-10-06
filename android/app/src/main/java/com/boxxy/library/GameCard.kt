package com.boxxy.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(
    game: GameEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    /** Non-null when a quick-resume point exists: the alternate way in. */
    onResume: (() -> Unit)? = null,
    /** The selected card: a white border and a slight lift. */
    highlighted: Boolean = false,
) {
    val borderColor by animateColorAsState(
        if (highlighted) Color.White else Color.Transparent, label = "cardBorder")
    val scale by animateFloatAsState(if (highlighted) 1.04f else 1f, label = "cardScale")
    /*
     * The click handling sits on the inner Box rather than Card's own onClick
     * overload, because long-press needs combinedClickable and Card has no such
     * overload. Keeping it inside the Card's content preserves the clipping —
     * placing it on the Card's modifier let the ripple spill past the rounded
     * corners.
     */
    Card(
        modifier = Modifier
            .aspectRatio(0.75f)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        elevation = CardDefaults.cardElevation(defaultElevation = if (highlighted) 10.dp else 4.dp),
        border = BorderStroke(3.dp, borderColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            if (game.coverUri != null) {
                /*
                 * Real covers are portrait and fill the card. The disc's own
                 * title image is square and low-resolution, so cropping it to
                 * this aspect cuts the logo — fit it on a neutral ground
                 * instead, which looks chosen rather than broken.
                 */
                AsyncImage(
                    model = game.coverUri,
                    contentDescription = game.displayName,
                    contentScale = if (game.coverIsDiscArt) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (game.coverIsDiscArt) MaterialTheme.colorScheme.surfaceVariant
                            else Color.Transparent
                        ),
                )
            }
            /* Quick resume, as a separate target: tapping the card itself
             * still boots the game from the start. */
            if (onResume != null) {
                Surface(
                    onClick = onResume,
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    Text(
                        "Resume",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            // Title bar at bottom — always shown; semi-transparent over art, solid when no art
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        if (game.coverUri != null) Color(0xCC000000) else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = game.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
