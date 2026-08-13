package com.bugzapperlabs.mycasts.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.ui.components.excludeFromSystemGestures

/** issue #186: bigger than the default 48dp/24dp IconButton so transport controls stay easy to
 *  hit at a glance (e.g. while driving), with play/pause sized up further as the primary action. */
private val TRANSPORT_BUTTON_SIZE = 64.dp
private val TRANSPORT_ICON_SIZE = 40.dp
private val PLAY_BUTTON_SIZE = 88.dp
private val PLAY_ICON_SIZE = 56.dp

/**
 * The full transport player (issue #96) -- artwork/title/slider/chapter label/transport/
 * speed/volume-boost, everything [PlaybackController] exposes. Used as the expanded content of the
 * Next Up screen's player sheet (`QueueScreen`), revealed by dragging [NowPlayingMiniStrip] (that
 * sheet's peeked content) up -- [androidx.compose.material3.BottomSheetScaffold] handles the
 * expand/collapse drag itself, but [expansionProgress] drives a grow-in of the artwork/title (issue
 * #129) so that reveal reads as the mini strip's content expanding into place rather than a hard cut.
 */
@Composable
fun MiniPlayerBar(
    playbackState: PlaybackUiState,
    onOpenEpisode: () -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onVolumeBoostChange: (Int) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    applyNavigationBarsPadding: Boolean = true,
    // How far the sheet is between peeked and fully expanded, 0f..1f (issue #129), read fresh each
    // draw rather than passed as a plain Float -- this composable has a Slider, artwork, and
    // several icon rows, so taking a Float that changes every drag pixel as a parameter would force
    // all of that to fully recompose every frame instead of just redrawing the two graphicsLayers
    // that actually use it. `{ 1f }` (the default) covers every other caller/preview that doesn't
    // animate; QueueScreen passes a remember'd reader of the sheet's live drag progress.
    expansionProgress: () -> Float = { 1f },
) {
    val hasChapters = playbackState.chapters.isNotEmpty()
    Surface(
        modifier = modifier.fillMaxWidth(),
        // issue #96: inverseOnSurface, not another neutral surfaceContainer* step -- this app's
        // (often dynamic-color-derived) palette can render those tones almost identically to the
        // plain queue list behind it. inverseOnSurface is specifically meant to read as a distinct
        // elevated surface (the same token Snackbars use) regardless of the rest of the palette.
        color = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Blurred cover art as a backdrop. The scrim fades from mostly-transparent at the top
            // to the bar's own solid surface color at the bottom -- rather than a flat dim -- so
            // the art visually merges into the plain-colored Next Up list below it, instead of
            // cutting off abruptly.
            if (playbackState.artworkUrl != null) {
                AsyncImage(
                    model = playbackState.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().blur(24.dp).alpha(0.6f),
                )
                Box(
                    modifier = Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            0f to MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                            1f to MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ),
                )
            }
            Column(
                // statusBarsPadding here (issue #130) because this content can now reach the very
                // top of the screen once expanded, where it would otherwise render under the status
                // bar icons.
                modifier = Modifier.fillMaxSize().statusBarsPadding()
                    .then(if (applyNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier),
                verticalArrangement = Arrangement.Center,
            ) {
                Column(
                    // Descends and grows into its resting position as one unit -- artwork, title,
                    // and every control below -- rather than popping in at full size (issue #129).
                    // This wrapper is sized to its own content (not fillMaxSize like its parent
                    // above), which matters: an earlier attempt put this same graphicsLayer on that
                    // fillMaxSize parent instead, so transformOrigin/scale/translate all pivoted
                    // around a box spanning nearly the whole sheet with this content merely centered
                    // inside it -- scaling around a point far from what's actually visible produced
                    // a large, jumbled displacement instead of a clean grow. Anchoring to this
                    // tightly-wrapped Column instead means the transform pivots on the content
                    // itself. Negative translationY at progress 0 starts it up near where the mini
                    // strip sits above, animating down to 0 (natural position) as the sheet expands.
                    modifier = Modifier.graphicsLayer {
                        val progress = expansionProgress()
                        val scale = lerp(0.4f, 1f, progress)
                        scaleX = scale
                        scaleY = scale
                        translationY = lerp(-160.dp.toPx(), 0f, progress)
                        alpha = progress
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
                ) {
                    Column(
                        // Tapping the artwork/title/feed area (issue #96) opens this episode's own
                        // details page -- the rest of the player (slider/transport/speed/volume) below
                        // keeps its own gestures, so only this header block is a tap target for it.
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenEpisode)
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (playbackState.artworkUrl != null) {
                            AsyncImage(
                                model = playbackState.artworkUrl,
                                contentDescription = null,
                                modifier = Modifier.size(280.dp).clip(RoundedCornerShape(12.dp)),
                            )
                        }
                        Text(
                            text = playbackState.title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        if (playbackState.feedTitle != null) {
                            Text(
                                text = playbackState.feedTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Column {
                        // issue #93: seeks directly from onValueChange rather than buffering a separate
                        // "currently dragging" position, same as EpisodeDetailsScreen's in-page Slider --
                        // trusts PlaybackController's own position ticker to echo the seek back fast enough
                        // that the thumb doesn't visibly fight the finger.
                        Slider(
                            value = playbackState.positionMs.toFloat(),
                            onValueChange = { onSeek(it.toLong()) },
                            valueRange = 0f..playbackState.durationMs.coerceAtLeast(1L).toFloat(),
                            // A full-width Slider drag starting near either screen edge otherwise gets
                            // intercepted as system back/forward-edge gesture navigation instead of moving
                            // the slider (issue #114, same fix as issue #302's Settings/Feed Properties sliders).
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).excludeFromSystemGestures(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                        ) {
                            Text(formatDuration(playbackState.positionMs), style = MaterialTheme.typography.labelSmall)
                            Text(
                                formatDuration((playbackState.durationMs - playbackState.positionMs).coerceAtLeast(0L)),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        // Moved to its own prominent, centered row above the transport buttons rather than
                        // packed in small text alongside the title/feed name up top (issue #94) -- reads as a
                        // focal point of the expanded player instead of crowded metadata.
                        if (hasChapters) {
                            Text(
                                text = chapterLabel(playbackState),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        // Same control layout as ExpandedPlayerBar/the reader's inline player (issue #194):
                        // main row is always just rewind/play/forward/stop, chapter nav flanks the speed
                        // selector on its own row below.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onSkipBackward, modifier = Modifier.size(TRANSPORT_BUTTON_SIZE)) {
                                Icon(
                                    Icons.Filled.Replay,
                                    contentDescription = stringResource(R.string.cd_rewind),
                                    modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                                )
                            }
                            IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(PLAY_BUTTON_SIZE)) {
                                if (playbackState.isBuffering) {
                                    CircularProgressIndicator(modifier = Modifier.size(TRANSPORT_ICON_SIZE), strokeWidth = 3.dp)
                                } else {
                                    Icon(
                                        if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = stringResource(if (playbackState.isPlaying) R.string.cd_pause else R.string.cd_play),
                                        modifier = Modifier.size(PLAY_ICON_SIZE),
                                    )
                                }
                            }
                            IconButton(onClick = onSkipForward, modifier = Modifier.size(TRANSPORT_BUTTON_SIZE)) {
                                Icon(
                                    Icons.Filled.Replay,
                                    contentDescription = stringResource(R.string.cd_forward),
                                    modifier = Modifier.size(TRANSPORT_ICON_SIZE).graphicsLayer(scaleX = -1f),
                                )
                            }
                            IconButton(onClick = onStop, modifier = Modifier.size(TRANSPORT_BUTTON_SIZE)) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.cd_stop_playback),
                                    modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (hasChapters) {
                                IconButton(onClick = onPreviousChapter) {
                                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.cd_previous_chapter))
                                }
                            }
                            TextButton(onClick = {
                                val currentIndex = PLAYBACK_SPEEDS.indexOfFirst { it >= playbackState.speed }.coerceAtLeast(0)
                                onSpeedChange(PLAYBACK_SPEEDS[(currentIndex + 1) % PLAYBACK_SPEEDS.size])
                            }) {
                                Text(formatSpeed(playbackState.speed))
                            }
                            // issue #202: cycles the same discrete levels as Feed Properties, so the value
                            // stays consistent whichever surface changed it last.
                            TextButton(onClick = {
                                val currentIndex = VOLUME_BOOST_LEVELS.indexOf(playbackState.volumeBoostMillibels).let {
                                    if (it < 0) 0 else it
                                }
                                onVolumeBoostChange(VOLUME_BOOST_LEVELS[(currentIndex + 1) % VOLUME_BOOST_LEVELS.size])
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = stringResource(R.string.cd_volume_boost),
                                    modifier = Modifier.size(18.dp),
                                )
                                if (playbackState.volumeBoostMillibels > 0) {
                                    Text(
                                        text = "+${playbackState.volumeBoostMillibels / 100}dB",
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            }
                            if (hasChapters) {
                                IconButton(onClick = onNextChapter) {
                                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.cd_next_chapter))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The persistent "now playing" strip (issue #96) shown wherever [PlaybackController] has an
 * episode loaded -- enough to see what's playing and control it without opening the full player.
 * Pinned above the bottom nav on every tab except Next Up, where tapping it takes you there; used
 * again inside that screen's own player sheet as the peeked (collapsed) row, where tapping instead
 * expands the sheet.
 */
@Composable
fun NowPlayingMiniStrip(
    playbackState: PlaybackUiState,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
    // True where this sits at the screen's actual bottom edge (MainActivity's detail-route usage,
    // with no bottom nav bar below it there). False wherever a `NavigationBar` -- which reserves
    // this padding itself -- or another surface already follows it, e.g. on the top-level tabs or
    // as the peeked row of `QueueScreen`'s own player sheet, where padding here would double up.
    applyNavigationBarsPadding: Boolean = true,
    // False once QueueScreen's player sheet is expanded (issue #96) -- MiniPlayerBar right below
    // this same peeked row already has its own transport row at that point, so keeping these too
    // would just be the same three buttons twice on screen at once.
    showControls: Boolean = true,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        // issue #96: inverseOnSurface, not another neutral surfaceContainer* step -- this app's
        // (often dynamic-color-derived) palette can render those tones almost identically to the
        // plain queue list behind it. inverseOnSurface is specifically meant to read as a distinct
        // elevated surface (the same token Snackbars use) regardless of the rest of the palette.
        color = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = (if (applyNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (playbackState.artworkUrl != null) {
                AsyncImage(
                    model = playbackState.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                )
            }
            Text(
                text = playbackState.title.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            if (showControls) {
                IconButton(onClick = onSkipBackward) {
                    Icon(Icons.Filled.Replay, contentDescription = stringResource(R.string.cd_rewind))
                }
                IconButton(onClick = onTogglePlayPause) {
                    if (playbackState.isBuffering) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (playbackState.isPlaying) R.string.cd_pause else R.string.cd_play),
                        )
                    }
                }
                IconButton(onClick = onSkipForward) {
                    Icon(
                        Icons.Filled.Replay,
                        contentDescription = stringResource(R.string.cd_forward),
                        modifier = Modifier.graphicsLayer(scaleX = -1f),
                    )
                }
            }
        }
    }
}

/** "Chapter N of M[: Title]" (issue #95). */
@Composable
private fun chapterLabel(playbackState: PlaybackUiState): String {
    val label = stringResource(
        R.string.reader_chapter_label,
        playbackState.currentChapterIndex + 1,
        playbackState.chapters.size,
    )
    val title = playbackState.currentChapter?.title
    return if (title != null) "$label: $title" else label
}

private val PLAYBACK_SPEEDS = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/** Millibel gain levels cycled by the player's volume boost button (issue #202) -- matches the
 *  Off/Low/Medium/High levels offered in Feed Properties. */
private val VOLUME_BOOST_LEVELS = listOf(0, 600, 1200, 1800)

private fun formatSpeed(speed: Float): String =
    "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x"

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
