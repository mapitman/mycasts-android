package com.bugzapperlabs.myfeeds.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.articlelist.ArticleDateFormatter
import com.bugzapperlabs.myfeeds.data.local.QueuedEpisode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val QUEUE_ROW_HEIGHT = 84.dp
private val THUMBNAIL_SIZE = 48.dp

/** How close to the visible viewport's edge the dragged row has to be to trigger auto-scroll
 *  (issue #211), as a fraction of a row height -- half a row, so it kicks in a bit before the row
 *  would actually press against the boundary rather than right at it. */
private const val AUTO_SCROLL_EDGE_FRACTION = 0.5f

/** Auto-scroll speed while dragging near an edge (issue #211): dp (not raw px, so the physical
 *  speed is comparable across screen densities) per [AUTO_SCROLL_TICK_MS] tick. */
private val AUTO_SCROLL_SPEED = 7.dp
private const val AUTO_SCROLL_TICK_MS = 16L

/**
 * The Next Up queue as a drag-to-reorder list (issue #106), shared by wherever it's shown.
 *
 * The backing [items] list is never mutated mid-drag (issue #209: continuously splicing it on
 * every row-threshold crossing -- the previous approach -- left whichever row a drag settled on
 * rendering at a fraction of [QUEUE_ROW_HEIGHT], covered by the row below it, a state that
 * persisted even after leaving and re-entering the screen). Instead, only the actively-dragged
 * row moves freely, following the finger 1:1; every other row between its origin and the current
 * target slot animates out of the way by exactly one row height to open a gap, purely visually.
 * The real reorder is computed and committed to [onReorder] exactly once, at drop.
 *
 * The dragged row itself is drawn as an overlay *outside* the LazyColumn (in the wrapping Box),
 * positioned by [dragVisualTopY], while its in-list twin turns invisible but keeps occupying its
 * slot. That's what makes edge auto-scroll (issue #211) workable: the overlay's position is
 * viewport-relative, so scrolling the list under a stationary finger needs no per-frame
 * compensation, and the row can't be affected by lazy-item recycling. The in-list twin is
 * additionally pinned via [LocalPinnableContainer] for the duration of the drag -- without the
 * pin, the moment auto-scroll moves the row's own slot out of the viewport, LazyColumn disposes
 * the item and the pointerInput driving the whole gesture is cancelled mid-drag.
 */
@Composable
fun ReorderableQueueList(
    modifier: Modifier,
    queue: List<QueuedEpisode>,
    onReorder: (List<String>, onComplete: () -> Unit) -> Unit,
    onRemove: (String) -> Unit,
    onClick: (QueuedEpisode) -> Unit,
) {
    // Reassigned from `queue` whenever nothing is being dragged so remote changes (e.g.
    // auto-queue, issue #68) stay reflected too. Never spliced mid-drag -- see the class doc.
    var items by remember { mutableStateOf(queue) }
    var draggedItemId by remember { mutableStateOf<String?>(null) }
    // Where the dragged row is currently drawn: the y of its top edge relative to the viewport
    // (i.e. relative to the LazyColumn's own top), NOT a translation from its origin slot. Keeping
    // this viewport-relative is deliberate (issue #211): auto-scrolling the list under a
    // stationary finger then simply doesn't move the row, no compensation math needed.
    var dragVisualTopY by remember { mutableStateOf(0f) }
    // The slot the dragged row would land in if dropped right now, recomputed every onDrag frame
    // and every auto-scroll tick. -1 (no drag in progress) so the "make room" displacement below
    // is a no-op.
    var targetIndex by remember { mutableStateOf(-1) }
    // The item-id order just committed to the repository at drop, until `queue` catches up to it.
    // `QueueRepository.reorder` writes one position per item as separate, sequential DB updates
    // (not one transaction), so `observeQueue()` can emit several partially-reordered intermediate
    // states while that loop is still running -- a plain "reordering in progress" boolean flag
    // guarding those out isn't enough, because it gets cleared (the write coroutine returns) before
    // Room's invalidation-driven re-query for the *final* write has necessarily reached this
    // composable, letting one of those stale intermediates land right after the guard opens and
    // silently revert the just-dropped order (issue #209). Comparing against the exact order we
    // committed -- instead of trusting timing -- means only a `queue` value that actually matches
    // is ever accepted while a commit is pending.
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
    val itemHeightPx = with(LocalDensity.current) { QUEUE_ROW_HEIGHT.toPx() }
    val autoScrollSpeedPx = with(LocalDensity.current) { AUTO_SCROLL_SPEED.toPx() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    // -1/0/1: whether the dragged row is currently held near the top/bottom edge of the visible
    // viewport, set from onDrag below. Drives the auto-scroll loop (issue #211) so a queue longer
    // than the viewport can be dragged to its very top/bottom, not just reordered within
    // whatever's currently on-screen.
    var autoScrollDirection by remember { mutableStateOf(0) }

    // Every row is exactly QUEUE_ROW_HEIGHT tall, so the list's absolute scroll position -- and
    // from it, which slot a given viewport y currently overlays -- is plain arithmetic, valid even
    // when the slot in question is nowhere near the composed/visible item range.
    fun absoluteScrollOffset(): Float =
        listState.firstVisibleItemIndex * itemHeightPx + listState.firstVisibleItemScrollOffset

    fun targetIndexFor(visualTopY: Float): Int =
        ((absoluteScrollOffset() + visualTopY) / itemHeightPx).roundToInt().coerceIn(0, items.lastIndex)

    fun viewportHeight(): Float =
        (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset).toFloat()

    // Keeps the dragged row fully inside the viewport (it can't slide under whatever is laid out
    // above/below the list, e.g. MiniPlayerBar -- issue #209/#163; reaching further content is
    // auto-scroll's job now) and never past the list's real first/last slot when those are
    // on-screen, so it comes to rest pinned at the end slot like every other reorderable list.
    fun clampVisualTop(visualTopY: Float): Float {
        val maxTop = minOf(
            viewportHeight() - itemHeightPx,
            items.lastIndex * itemHeightPx - absoluteScrollOffset(),
        ).coerceAtLeast(0f)
        return visualTopY.coerceIn(0f, maxTop)
    }

    LaunchedEffect(queue) {
        val pending = pendingOrder
        if (pending == null) {
            items = queue
        } else if (queue.map { it.item.id } == pending) {
            items = queue
            pendingOrder = null
        }
    }

    LaunchedEffect(autoScrollDirection) {
        if (autoScrollDirection == 0) return@LaunchedEffect
        while (isActive) {
            val scrolled = listState.scrollBy(autoScrollDirection * autoScrollSpeedPx)
            if (scrolled == 0f) break // reached the very top/bottom of the queue
            // The overlay (dragVisualTopY) is viewport-relative and the finger isn't moving, so
            // the row stays put on its own -- only the drop target shifts as new slots scroll in
            // under it.
            targetIndex = targetIndexFor(dragVisualTopY)
            delay(AUTO_SCROLL_TICK_MS)
        }
    }

    // fillMaxWidth only, not fillMaxSize -- callers that want this to shrink-to-fit a short queue
    // (issue #197) rather than always claim a forced/weighted height pass a bounded heightIn(max=)
    // instead, which a plain LazyColumn already respects by sizing to its actual content within it
    // (and the Box in turn sizes to the LazyColumn).
    Box(modifier = modifier.fillMaxWidth()) {
        // userScrollEnabled is off for the duration of a drag: the drag handle's own pointerInput
        // consumes move events for our custom reordering, but LazyColumn's built-in scroll gesture
        // detection still picks up the same finger movement (a nested-scrolling conflict) and
        // scrolls its own content underneath -- so a row correctly pinned at index 0 by the clamp
        // above still ends up pushed above the viewport as the list itself scrolls out from under
        // it (issue #209). Programmatic scrollBy from the auto-scroll loop is unaffected.
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
            userScrollEnabled = draggedItemId == null,
        ) {
            itemsIndexed(items, key = { _, episode -> episode.item.id }) { index, episode ->
                val isDragged = episode.item.id == draggedItemId
                val originIndex = if (draggedItemId != null) items.indexOfFirst { it.item.id == draggedItemId } else -1
                // Non-dragged rows between the drag's origin and its current target slot shift out
                // of the way by one row height, in whichever direction makes room -- purely visual,
                // the backing list order doesn't actually change until drop.
                val rawDisplacement = if (!isDragged && originIndex != -1) {
                    when {
                        originIndex < targetIndex && index in (originIndex + 1)..targetIndex -> -itemHeightPx
                        originIndex > targetIndex && index in targetIndex until originIndex -> itemHeightPx
                        else -> 0f
                    }
                } else {
                    0f
                }
                // Snapped, not spring-animated: the dragged row tracks the finger continuously
                // every frame, but an animated displacement here eases into place over ~200-300ms
                // and can't keep pace with a normal-speed drag -- the growing gap between where
                // the dragged row currently is and where a still-easing neighbor hasn't caught up
                // to yet is exactly the "everything I drag over disappears" bug (issue #209).
                val displacement = rawDisplacement
                // Keeps this item *composed* while it's the one being dragged, even after
                // auto-scroll (issue #211) has moved its slot outside the viewport. Without the
                // pin, LazyColumn disposes the off-screen item -- and with it the pointerInput
                // below that is driving the whole gesture, cancelling the drag mid-flight.
                val pinnableContainer = LocalPinnableContainer.current
                DisposableEffect(isDragged, pinnableContainer) {
                    val pin = if (isDragged) pinnableContainer?.pin() else null
                    onDispose { pin?.release() }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(QUEUE_ROW_HEIGHT)
                        // The dragged row's visuals are drawn by the overlay outside the
                        // LazyColumn (see below); its in-list twin here goes fully transparent but
                        // keeps occupying its slot, so the layout -- and the displacement math
                        // above -- are unaffected.
                        .graphicsLayer {
                            translationY = displacement
                            alpha = if (isDragged) 0f else 1f
                        }
                        // Clips any content that overflows this row's fixed height (e.g. at larger
                        // system font scales) to its own bounds -- otherwise that overflow paints
                        // visibly on top of the row underneath it instead of being hidden beneath
                        // it as it normally would be. Must come *after* graphicsLayer above, not
                        // before: clipToBounds() clips to this node's own local bounds, and a clip
                        // applied before (outside) the translation stays anchored to the row's
                        // untranslated position instead of moving with it (issue #209).
                        .clipToBounds()
                        .clickable { onClick(episode) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QueueRowContent(
                        episode = episode,
                        onRemove = onRemove,
                        dragHandleModifier = Modifier.pointerInput(episode.item.id) {
                            // Dragging starts immediately here (no long-press) -- unlike a
                            // draggable row with no separate handle, this icon isn't also a tap
                            // target, so there's nothing to disambiguate from.
                            detectDragGestures(
                                onDragStart = {
                                    val info = listState.layoutInfo.visibleItemsInfo
                                        .find { it.key == episode.item.id }
                                    draggedItemId = episode.item.id
                                    dragVisualTopY = (info?.offset ?: 0).toFloat()
                                    targetIndex = items.indexOfFirst { it.item.id == episode.item.id }
                                },
                                onDragEnd = {
                                    val originIdx = items.indexOfFirst { it.item.id == episode.item.id }
                                    val drop = targetIndex
                                    draggedItemId = null
                                    targetIndex = -1
                                    autoScrollDirection = 0
                                    if (originIdx != -1 && drop != -1 && drop != originIdx) {
                                        val reordered = items.toMutableList()
                                        val moved = reordered.removeAt(originIdx)
                                        reordered.add(drop, moved)
                                        items = reordered
                                        pendingOrder = reordered.map { it.item.id }
                                        onReorder(reordered.map { it.item.id }) {}
                                        // The list's own scroll position doesn't track a moved
                                        // item to its new slot on its own -- without this, dropping
                                        // near the top/bottom can leave the row just-dropped above
                                        // or below the visible viewport, looking like it vanished
                                        // (issue #209).
                                        coroutineScope.launch { listState.scrollToItem(drop) }
                                    }
                                },
                                onDragCancel = {
                                    draggedItemId = null
                                    targetIndex = -1
                                    autoScrollDirection = 0
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (items.indexOfFirst { it.item.id == episode.item.id } == -1) {
                                        return@detectDragGestures
                                    }
                                    dragVisualTopY = clampVisualTop(dragVisualTopY + dragAmount.y)
                                    targetIndex = targetIndexFor(dragVisualTopY)
                                    // Edge proximity for auto-scroll (issue #211).
                                    val edge = itemHeightPx * AUTO_SCROLL_EDGE_FRACTION
                                    autoScrollDirection = when {
                                        dragVisualTopY < edge -> -1
                                        dragVisualTopY + itemHeightPx > viewportHeight() - edge -> 1
                                        else -> 0
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
        // The dragged row's actual visuals, drawn over the LazyColumn at the finger's position --
        // see the class doc for why this lives outside the list.
        val draggedEpisode = draggedItemId?.let { id -> items.find { it.item.id == id } }
        if (draggedEpisode != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(QUEUE_ROW_HEIGHT)
                    .offset { IntOffset(0, dragVisualTopY.roundToInt()) }
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    // Same font-scale overflow clip as the in-list rows above.
                    .clipToBounds()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QueueRowContent(episode = draggedEpisode, onRemove = onRemove, dragHandleModifier = Modifier)
            }
        }
    }
}

/** One queue row's contents, shared between the in-list rows and the mid-drag overlay copy so the
 *  two can't drift apart visually (issue #211). The overlay passes an inert [dragHandleModifier];
 *  the in-list rows attach the drag gesture through it. */
@Composable
private fun RowScope.QueueRowContent(
    episode: QueuedEpisode,
    onRemove: (String) -> Unit,
    dragHandleModifier: Modifier,
) {
    Icon(
        Icons.Filled.DragHandle,
        contentDescription = stringResource(R.string.cd_reorder_queue_item),
        modifier = Modifier
            .padding(end = 12.dp)
            .then(dragHandleModifier),
    )
    val thumbnailUrl = episode.item.imageUrl ?: episode.feedImageUrl
    if (thumbnailUrl != null) {
        AsyncImage(
            model = thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(end = 12.dp)
                .size(THUMBNAIL_SIZE)
                .clip(RoundedCornerShape(8.dp))
                // Isolates the thumbnail into its own compositing layer so dragging a row (which
                // redraws it every frame) doesn't force Coil's image draw to be re-evaluated as
                // part of the parent Row's drawing, which was visibly choppy mid-drag.
                .graphicsLayer(),
        )
    }
    androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
        Text(
            text = episode.item.title.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
        )
        if (episode.feedTitle != null) {
            Text(
                text = episode.feedTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = ArticleDateFormatter.format(episode.item.publishDate),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
    IconButton(onClick = { onRemove(episode.item.id) }) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_remove_from_queue))
    }
}
