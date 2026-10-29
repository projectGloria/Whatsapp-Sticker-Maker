@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.stickerstudio

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun EditorScreen(target: EditorTarget, onDismiss: () -> Unit, onSave: (Edit) -> Unit) {
    var draft by target::draft
    val undo = target.undo
    val redo = target.redo
    var tool by remember { mutableStateOf("Crop") }
    var brush by remember { mutableFloatStateOf(36f) }
    var dark by remember { mutableStateOf(false) }
    var play by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun checkpoint() {
        undo.add(draft)
        if (undo.size > 80) undo.removeAt(0)
        redo.clear()
    }
    fun change(edit: Edit) {
        if (edit != draft) {
            checkpoint()
            draft = edit
        }
    }
    fun leave() {
        if (draft != target.edit) discard = true else onDismiss()
    }
    val source by
        produceState<Bitmap?>(null, target.media.file) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { MediaEngine.firstFrame(target.media.file) }
                        .onFailure { error = it.message }
                        .getOrNull()
                }
        }
    val preview by
        produceState<Bitmap?>(null, source, draft) {
            val b = source
            if (b != null)
                value =
                    withContext(Dispatchers.Default) { MediaEngine.preview(target.media, b, draft) }
        }
    val current by rememberUpdatedState(draft)
    Dialog(
        onDismissRequest = { leave() },
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            BackHandler { leave() }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(if (target.media.animated) "Animation studio" else "Photo studio")
                        },
                        navigationIcon = {
                            IconButton(onClick = { leave() }) {
                                Icon(Icons.Outlined.Close, "Close editor")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = { onSave(draft) },
                                enabled =
                                    source != null &&
                                        (!target.media.animated ||
                                            draft.end - draft.start in 8..10000),
                            ) {
                                Text("Save")
                            }
                        },
                    )
                }
            ) { insets ->
                Column(
                    Modifier.padding(insets)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        if (target.media.animated)
                            "Crop and trim your loop, then play the selected frames to preview it."
                        else "Make it yours. Drag on the canvas to erase, restore or move text.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Box(
                        Modifier.fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .checker(dark)
                            .pointerInput(tool, brush, play) {
                                if (play) return@pointerInput
                                var points = mutableListOf<BrushPoint>()
                                detectDragGestures(
                                    onDragStart = { position ->
                                        if (tool == "Erase" || tool == "Restore") {
                                            checkpoint()
                                            points =
                                                mutableListOf(
                                                    BrushPoint(
                                                        (position.x / size.width).coerceIn(0f, 1f),
                                                        (position.y / size.height).coerceIn(0f, 1f),
                                                    )
                                                )
                                            draft =
                                                current.copy(
                                                    strokes =
                                                        current.strokes +
                                                            Stroke(
                                                                points.toList(),
                                                                brush,
                                                                tool == "Restore",
                                                            )
                                                )
                                        } else if (tool == "Text") checkpoint()
                                    },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        if (tool == "Erase" || tool == "Restore") {
                                            points.add(
                                                BrushPoint(
                                                    (change.position.x / size.width).coerceIn(
                                                        0f,
                                                        1f,
                                                    ),
                                                    (change.position.y / size.height).coerceIn(
                                                        0f,
                                                        1f,
                                                    ),
                                                )
                                            )
                                            draft =
                                                current.copy(
                                                    strokes =
                                                        current.strokes.dropLast(1) +
                                                            Stroke(
                                                                points.toList(),
                                                                brush,
                                                                tool == "Restore",
                                                            )
                                                )
                                        } else if (tool == "Text")
                                            draft =
                                                current.copy(
                                                    textX =
                                                        (current.textX + delta.x / size.width)
                                                            .coerceIn(0f, 1f),
                                                    textY =
                                                        (current.textY + delta.y / size.height)
                                                            .coerceIn(0f, 1f),
                                                )
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (play && target.media.animated)
                            AnimationPlayback(target.media, draft, Modifier.fillMaxSize())
                        else
                            preview?.let {
                                Image(
                                    it.asComposeImage(),
                                    "Edited sticker preview",
                                    Modifier.fillMaxSize(),
                                )
                            } ?: CircularProgressIndicator()
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row {
                            IconButton(
                                onClick = {
                                    if (undo.isNotEmpty()) {
                                        redo.add(draft)
                                        draft = undo.removeAt(undo.lastIndex)
                                    }
                                },
                                enabled = undo.isNotEmpty(),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Undo, "Undo")
                            }
                            IconButton(
                                onClick = {
                                    if (redo.isNotEmpty()) {
                                        undo.add(draft)
                                        draft = redo.removeAt(redo.lastIndex)
                                    }
                                },
                                enabled = redo.isNotEmpty(),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Redo, "Redo")
                            }
                        }
                        if (target.media.animated)
                            IconButton(onClick = { play = !play }) {
                                Icon(
                                    if (play) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    if (play) "Pause preview" else "Play selection",
                                )
                            }
                        TextButton(onClick = { dark = !dark }) {
                            Text(if (dark) "Light canvas" else "Dark canvas")
                        }
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        (if (target.media.animated) listOf("Crop", "Trim")
                            else listOf("Crop", "Erase", "Restore", "Text", "Outline"))
                            .forEach { label ->
                                FilterChip(
                                    selected = tool == label,
                                    onClick = {
                                        tool = label
                                        play = false
                                    },
                                    label = { Text(label) },
                                )
                            }
                    }
                    when (tool) {
                        "Crop" -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        change(
                                            draft.copy(
                                                turns = (draft.turns + 1) % 4,
                                                strokes = emptyList(),
                                            )
                                        )
                                    }
                                ) {
                                    Icon(Icons.Outlined.Rotate90DegreesCcw, null)
                                    Text("Rotate")
                                }
                                TextButton(
                                    onClick = {
                                        change(
                                            draft.copy(
                                                left = 0f,
                                                top = 0f,
                                                right = 1f,
                                                bottom = 1f,
                                                strokes = emptyList(),
                                            )
                                        )
                                    }
                                ) {
                                    Text("Reset crop")
                                }
                            }
                            Text("Crop edges", style = MaterialTheme.typography.titleMedium)
                            LabeledSlider("Left", draft.left, 0f..(draft.right - .05f)) {
                                change(draft.copy(left = it, strokes = emptyList()))
                            }
                            LabeledSlider("Right", draft.right, (draft.left + .05f)..1f) {
                                change(draft.copy(right = it, strokes = emptyList()))
                            }
                            LabeledSlider("Top", draft.top, 0f..(draft.bottom - .05f)) {
                                change(draft.copy(top = it, strokes = emptyList()))
                            }
                            LabeledSlider("Bottom", draft.bottom, (draft.top + .05f)..1f) {
                                change(draft.copy(bottom = it, strokes = emptyList()))
                            }
                            if (draft.strokes.isNotEmpty())
                                Text(
                                    "Changing crop or rotation resets brush strokes. Undo restores them.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                        }
                        "Erase",
                        "Restore" -> {
                            Text(
                                if (tool == "Erase") "Brush over the background to remove it."
                                else "Brush over erased areas to bring the image back."
                            )
                            LabeledSlider("Brush size", brush, 8f..100f) { brush = it }
                        }
                        "Text" -> {
                            OutlinedTextField(
                                value = draft.text,
                                onValueChange = {
                                    if (it.length <= 200) change(draft.copy(text = it))
                                },
                                label = { Text("Sticker text") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            LabeledSlider("Text size", draft.textSize, 20f..100f) {
                                change(draft.copy(textSize = it))
                            }
                            Text(
                                "Drag the text on the canvas to position it.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        "Outline" ->
                            LabeledSlider(
                                "White outline · ${draft.outline.toInt()} px",
                                draft.outline,
                                0f..20f,
                            ) {
                                change(draft.copy(outline = it))
                            }
                        "Trim" -> {
                            Text(
                                "${"%.2f".format(draft.start/1000f)}s – ${"%.2f".format(draft.end/1000f)}s · ${"%.2f".format((draft.end-draft.start)/1000f)}s selected",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            RangeSlider(
                                value = draft.start.toFloat()..draft.end.toFloat(),
                                onValueChange = { range ->
                                    change(
                                        draft.copy(
                                            start = range.start.toInt(),
                                            end = range.endInclusive.toInt(),
                                        )
                                    )
                                },
                                valueRange = 0f..target.media.duration.coerceAtLeast(8).toFloat(),
                            )
                            if (draft.end - draft.start !in 8..10000)
                                Text(
                                    "Select between 0.008 and 10 seconds to save.",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            Text(
                                "If a detailed animation cannot fit in 500 KB, shorten the selection and try again.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(32.dp))
                }
            }
            if (discard)
                AlertDialog(
                    onDismissRequest = { discard = false },
                    title = { Text("Discard these edits?") },
                    text = { Text("Your saved sticker and original will be kept.") },
                    confirmButton = { TextButton(onClick = onDismiss) { Text("Discard edits") } },
                    dismissButton = {
                        TextButton(onClick = { discard = false }) { Text("Keep editing") }
                    },
                )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

@Composable
private fun AnimationPlayback(media: Incoming, edit: Edit, modifier: Modifier) {
    var failure by remember { mutableStateOf<String?>(null) }
    val frame by
        produceState<Bitmap?>(null, media.file, edit) {
            try {
                while (true) {
                    withContext(Dispatchers.IO) {
                        val source = FrameSource.open(media, edit.start)
                        val clock = android.os.SystemClock.elapsedRealtime()
                        try {
                            var before = if (media.kind == "video") edit.start else 0
                            while (true) {
                                val next = source.next() ?: break
                                val after = next.second
                                try {
                                    if (after > edit.start && before < edit.end) {
                                        value =
                                            withContext(Dispatchers.Default) {
                                                Renderer.render(next.first, edit, false)
                                            }
                                        delay(
                                            (clock + minOf(after, edit.end) -
                                                    edit.start -
                                                    android.os.SystemClock.elapsedRealtime())
                                                .coerceAtLeast(0)
                                        )
                                    }
                                } finally {
                                    next.first.recycle()
                                }
                                before = after
                                if (after >= edit.end) break
                            }
                        } finally {
                            source.close()
                        }
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                failure = e.message ?: "Cannot play this media"
            }
        }
    Box(modifier, contentAlignment = Alignment.Center) {
        frame?.let {
            Image(
                it.asComposeImage(),
                "Cropped and trimmed animation preview",
                Modifier.fillMaxSize(),
            )
        } ?: CircularProgressIndicator()
        failure?.let { Text(it) }
    }
}
