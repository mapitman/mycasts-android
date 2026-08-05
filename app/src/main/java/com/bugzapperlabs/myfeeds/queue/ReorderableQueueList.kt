package com.bugzapperlabs.myfeeds.queue

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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

/** Width of the two revealed swipe-right actions (issue #285), split evenly between them -- the
 *  row settles at exactly this offset when revealed, no overscroll. */
private val SWIPE_REVEAL_WIDTH = 112.dp

/** How far left a row has to be dragged before releasing it removes the episode (issue #284),
 *  as a replacement for the old trailing X button. */
private val SWIPE_REMOVE_THRESHOLD = 96.dp

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
 *
 * Each row also carries its own independent horizontal swipe (issues #284/#285), tracked by a
 * per-row [Animatable] offset: swiping left past [SWIPE_REMOVE_THRESHOLD] removes the episode,
 * swiping right reveals move-to-top/move-to-bottom actions underneath. [detectHorizontalDragGestures]
 * only claims the gesture once movement is recognizably horizontal, so it coexists with the
 * LazyColumn's vertical scroll and with a plain tap/long-press on the same row (tap opens the
 * episode, issue #283; long-press plays it without navigating, issue #282) without either
 * needing to know about the other.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReorderableQueueList(
    modifier: Modifier,
    queue: List<QueuedEpisode>,
    currentItemId: String?,
    onReorder: (List<String>, onComplete: () -> Unit) -> Unit,
    onRemove: (String) -> Unit,
    onOpen: (QueuedEpisode) -> Unit,
    onPlay: (QueuedEpisode) -> Unit,
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
    // Set by a long-press play (issue #282) to the id of whatever *was* playing before it, which
    // PlaybackController requeues to the front of Next Up (issue #106) as part of the switch.
    // That DB write lands asynchronously, well after the tap itself, and once it does LazyColumn's
    // own key-based scroll-anchor preservation actively keeps whatever was already at the top of
    // the viewport pinned there rather than scrolling to reveal the new front item -- so scrolling
    // to top has to be driven off the *arrival* of this id at items[0] below, not off the tap.
    var pendingRequeueScrollItemId by remember { mutableStateOf<String?>(null) }
    val itemHeightPx = with(LocalDensity.current) { QUEUE_ROW_HEIGHT.toPx() }
    val autoScrollSpeedPx = with(LocalDensity.current) { AUTO_SCROLL_SPEED.toPx() }
    val swipeRevealWidthPx = with(LocalDensity.current) { SWIPE_REVEAL_WIDTH.toPx() }
    val swipeRemoveThresholdPx = with(LocalDensity.current) { SWIPE_REMOVE_THRESHOLD.toPx() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val playLabel = stringResource(R.string.cd_play_queue_item)
    val removeLabel = stringResource(R.string.cd_remove_from_queue)
    val moveToTopLabel = stringResource(R.string.cd_move_to_top_of_queue)
    val moveToBottomLabel = stringResource(R.string.cd_move_to_bottom_of_queue)
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

    // Shared by the drag-handle drop (above) and the swipe-right move actions below -- both just
    // splice `items` into a new order and commit it the same way.
    fun commitReorder(reordered: List<QueuedEpisode>) {
        items = reordered
        pendingOrder = reordered.map { it.item.id }
        onReorder(reordered.map { it.item.id }) {}
    }

    fun moveToTop(itemId: String) {
        val index = items.indexOfFirst { it.item.id == itemId }
        if (index <= 0) return
        val reordered = items.toMutableList()
        reordered.add(0, reordered.removeAt(index))
        commitReorder(reordered)
    }

    fun moveToBottom(itemId: String) {
        val index = items.indexOfFirst { it.item.id == itemId }
        if (index == -1 || index == items.lastIndex) return
        val reordered = items.toMutableList()
        reordered.add(reordered.removeAt(index))
        commitReorder(reordered)
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

    LaunchedEffect(items) {
        val pendingId = pendingRequeueScrollItemId
        if (pendingId != null && items.firstOrNull()?.item?.id == pendingId) {
            listState.scrollToItem(0)
            pendingRequeueScrollItemId = null
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
                // Independent of the drag-to-reorder state above: how far this row has been
                // swiped horizontally (issues #284/#285). Reset to 0 whenever a move action fires
                // or a swipe settles back closed.
                val swipeOffsetX = remember(episode.item.id) { Animatable(0f) }
                val isCurrentlyPlaying = episode.item.id == currentItemId
                Box(
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
                        .clipToBounds(),
                ) {
                    // The swipe-left indicator (issue #284): a red panel with a delete icon,
                    // trailing-aligned so it's exposed from the right edge as the row slides left
                    // -- otherwise there's no visual cue that releasing past the threshold removes
                    // the episode, unlike the swipe-right actions below which are self-explanatory
                    // buttons. Its width tracks the swipe distance 1:1, growing to fill the row as
                    // the drag approaches -- and passes -- the removal threshold.
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(with(LocalDensity.current) { (-swipeOffsetX.value).coerceAtLeast(0f).toDp() })
                            .background(MaterialTheme.colorScheme.errorContainer),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Icon(
                            Icons.Filled.PlaylistRemove,
                            contentDescription = stringResource(R.string.cd_remove_from_queue),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                    // The swipe-right reveal (issue #285): sits behind the row's own content,
                    // exposed as that content slides right. Occluded when the row is closed
                    // because the foreground row below carries an opaque background. Each action
                    // gets its own colored half (rather than sharing one background) so the two
                    // are visually distinct, not just two same-colored icons -- and each half
                    // fills the full row height as its tap target, not just a default-sized
                    // IconButton, since there's a full QUEUE_ROW_HEIGHT to use. Width-capped to
                    // SWIPE_REVEAL_WIDTH rather than matchParentSize -- a full-width background
                    // here would sit on top of (and permanently hide) the swipe-left panel above,
                    // since the two overlap in the same Box and this one composes later.
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .width(SWIPE_REVEAL_WIDTH),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable {
                                    moveToTop(episode.item.id)
                                    coroutineScope.launch { swipeOffsetX.animateTo(0f) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.VerticalAlignTop,
                                contentDescription = stringResource(R.string.cd_move_to_top_of_queue),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable {
                                    moveToBottom(episode.item.id)
                                    coroutineScope.launch { swipeOffsetX.animateTo(0f) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.VerticalAlignBottom,
                                contentDescription = stringResource(R.string.cd_move_to_bottom_of_queue),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(QUEUE_ROW_HEIGHT)
                            .offset { IntOffset(swipeOffsetX.value.roundToInt(), 0) }
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            // Horizontal-only: detectHorizontalDragGestures cancels out on a
                            // mostly-vertical movement (letting the LazyColumn scroll normally)
                            // and never consumes the initial down of a stationary tap/long-press,
                            // so it coexists with the combinedClickable below without either
                            // gesture detector needing to know about the other.
                            .pointerInput(episode.item.id) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        val current = swipeOffsetX.value
                                        when {
                                            current <= -swipeRemoveThresholdPx -> onRemove(episode.item.id)
                                            current >= swipeRevealWidthPx / 2f ->
                                                coroutineScope.launch { swipeOffsetX.animateTo(swipeRevealWidthPx) }
                                            else -> coroutineScope.launch { swipeOffsetX.animateTo(0f) }
                                        }
                                    },
                                    onDragCancel = {
                                        coroutineScope.launch { swipeOffsetX.animateTo(0f) }
                                    },
                                    onHorizontalDrag = { _, dragAmount ->
                                        coroutineScope.launch {
                                            swipeOffsetX.snapTo(
                                                (swipeOffsetX.value + dragAmount)
                                                    .coerceIn(-swipeRemoveThresholdPx * 1.5f, swipeRevealWidthPx),
                                            )
                                        }
                                    },
                                )
                            }
                            .combinedClickable(
                                onClick = {
                                    // A tap while revealed just closes the reveal (issue #285) --
                                    // it's not a signal to open the episode.
                                    if (swipeOffsetX.value != 0f) {
                                        coroutineScope.launch { swipeOffsetX.animateTo(0f) }
                                    } else {
                                        onOpen(episode)
                                    }
                                },
                                // Long-press plays the episode without navigating (issue #282) --
                                // not offered for the episode that's already playing, since
                                // there's nothing useful for it to do there.
                                onLongClick = if (isCurrentlyPlaying) null else {
                                    {
                                        // Arms the scroll-to-top effect above for whatever episode
                                        // is playing right now -- PlaybackController requeues it to
                                        // the front of Next Up (issue #106) as part of this switch.
                                        if (currentItemId != null) pendingRequeueScrollItemId = currentItemId
                                        onPlay(episode)
                                    }
                                },
                            )
                            // Equivalent actions for TalkBack users, who can't perform the
                            // swipe/long-press gestures above (issues #282/#284/#285).
                            .semantics {
                                customActions = buildList {
                                    if (!isCurrentlyPlaying) {
                                        add(CustomAccessibilityAction(playLabel) { onPlay(episode); true })
                                    }
                                    add(CustomAccessibilityAction(removeLabel) { onRemove(episode.item.id); true })
                                    add(CustomAccessibilityAction(moveToTopLabel) { moveToTop(episode.item.id); true })
                                    add(CustomAccessibilityAction(moveToBottomLabel) { moveToBottom(episode.item.id); true })
                                }
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QueueRowContent(
                            episode = episode,
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
                                            commitReorder(reordered)
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
                QueueRowContent(episode = draggedEpisode, dragHandleModifier = Modifier)
            }
        }
    }
}

/** One queue row's contents, shared between the in-list rows and the mid-drag overlay copy so the
 *  two can't drift apart visually (issue #211). The overlay passes an inert [dragHandleModifier].
 *  The in-list rows attach the drag gesture through it. Removal is a swipe-left gesture on the row
 *  itself now (issue #284), not a button here. */
@Composable
private fun RowScope.QueueRowContent(
    episode: QueuedEpisode,
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
}
