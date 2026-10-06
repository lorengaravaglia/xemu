package com.boxxy.library

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Native shape of a SteamGridDB hero (1920x620, 3840x1240). */
private const val HERO_ASPECT = 1920f / 620f

/* A light scrim over the blurred backdrop only; the legibility darkening is
 * one gradient over everything (see [shade]), so no part of the screen is a
 * separately darkened band. */
private val SCRIM = Color.Black.copy(alpha = 0.25f)

/** A white icon with a soft shadow, for use over artwork. */
@Composable
fun ShadowedIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String?) {
    Box {
        androidx.compose.material3.Icon(icon, null, tint = Color.Black.copy(alpha = 0.7f),
             modifier = Modifier.padding(top = 2.dp).blur(3.dp))
        androidx.compose.material3.Icon(icon, contentDescription, tint = Color.White)
    }
}

/** Soft shadow for white text over artwork. */
val TEXT_SHADOW = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 2f), 10f))

/**
 * The selected game's background art: used as the library's Showcase view, as
 * a dimmed backdrop behind the card rows, and full-screen on the top display
 * in dual-screen mode.
 *
 * Heroes are designed for a logo in the middle: the artwork sits at the left
 * and right edges and the centre is sparse.  So the hero is never centre-
 * cropped to the screen's shape -- that would keep the empty middle and cut
 * the content.  It is drawn at its own ~3.1:1 shape across the full width,
 * with the logo placed in that sparse centre as Steam places it, and the rest
 * of the screen is a blurred, darkened copy of the same art, into which the
 * hero's bottom edge fades.
 *
 * @param dim extra darkening, for use behind the card rows.
 * @param footer drawn under the hero band (dual-screen details).
 */
@Composable
fun GameShowcase(
    game: GameEntry?,
    modifier: Modifier = Modifier,
    dim: Float = 0f,
    footer: (@Composable (GameEntry) -> Unit)? = null,
) {
    Crossfade(targetState = game, animationSpec = tween(350), modifier = modifier,
              label = "showcase") { g ->
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (g == null) return@BoxWithConstraints
            val fullWidth = maxWidth
            val bandHeight = fullWidth / HERO_ASPECT

            /* Backdrop: the hero, or failing that a real cover (the disc's
             * 128x128 title image is too small to blur into anything). */
            val backdrop = g.heroUri ?: g.coverUri?.takeIf { !g.coverIsDiscArt }
            if (backdrop != null) {
                AsyncImage(
                    model = backdrop, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(32.dp),
                )
            }
            Box(Modifier.fillMaxSize().background(SCRIM))

            val hero = g.heroUri
            if (hero != null) {
                Box(Modifier.fillMaxWidth().height(bandHeight)) {
                    /*
                     * Soft edge: the hero's own transparency falls off over
                     * the lower part of the band, so it dissolves into the
                     * blurred copy of itself underneath rather than ending in
                     * a line.  The fall-off is kept to the bottom 30% so the
                     * artwork itself stays sharp.  (Darkening the edge instead still left a seam
                     * wherever the brightness differed.)  Offscreen
                     * compositing lets DstIn mask just this layer.
                     */
                    AsyncImage(
                        model = hero, contentDescription = null,
                        contentScale = ContentScale.Crop,   // ~1:1 at the native shape
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    Brush.verticalGradient(
                                        0.7f to Color.Black, 1f to Color.Transparent),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    )
                }
            }

            /* One continuous shade over the backdrop and hero together:
             * light behind the top bar, clear across the artwork, deepening
             * toward the bottom where details and cards sit.  Under the logo,
             * so the logo keeps its own colours. */
            val bandFrac = (bandHeight / maxHeight).coerceIn(0.3f, 0.9f)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.55f),
                0.13f to Color.Black.copy(alpha = 0.35f),
                0.3f to Color.Transparent,
                bandFrac * 0.85f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.6f),
            )))

            /* Logo (or name) in the hero's sparse centre; without a hero, in
             * the same place, so the layout does not jump between games. */
            Box(
                Modifier.fillMaxWidth().height(bandHeight),
                contentAlignment = Alignment.Center,
            ) {
                if (g.logoUri != null) {
                    AsyncImage(
                        model = g.logoUri, contentDescription = g.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.width(fullWidth * 0.36f).height(bandHeight * 0.6f),
                    )
                } else {
                    Text(
                        g.displayName,
                        style = MaterialTheme.typography.displaySmall.merge(
                            TEXT_SHADOW),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 48.dp),
                    )
                }
            }

            if (footer != null) {
                Column(Modifier.fillMaxWidth().padding(top = bandHeight)) { footer(g) }
            }
            if (dim > 0f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
            }
        }
    }
}
