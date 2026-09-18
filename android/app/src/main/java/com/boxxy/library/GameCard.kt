package com.boxxy.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(game: GameEntry, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    /*
     * The click handling sits on the inner Box rather than Card's own onClick
     * overload, because long-press needs combinedClickable and Card has no such
     * overload. Keeping it inside the Card's content preserves the clipping —
     * placing it on the Card's modifier let the ripple spill past the rounded
     * corners.
     */
    Card(
        modifier = Modifier.aspectRatio(0.75f),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
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
