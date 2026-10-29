@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.stickerstudio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Purple = Color(0xFF6650A4)

@Composable
fun StudioApp(model: StudioModel, export: (Pack, String) -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme =
            if (dark) darkColorScheme(primary = Color(0xFFD0BCFF), secondary = Color(0xFF9FD6BC))
            else
                lightColorScheme(
                    primary = Purple,
                    secondary = Color(0xFF326A53),
                    background = Color(0xFFFCFAF7),
                    surface = Color(0xFFFCFAF7),
                ),
        typography = Typography(),
    ) {
        Surface(Modifier.fillMaxSize()) {
            val packs by model.packs.collectAsState()
            var create by remember { mutableStateOf(false) }
            var rename by remember { mutableStateOf<Pack?>(null) }
            var delete by remember { mutableStateOf<Pack?>(null) }
            var destination by model::destination
            var targetChooser by remember { mutableStateOf<Pack?>(null) }
            val context = LocalContext.current
            val picker =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
                    if (it.isNotEmpty()) model.receive(it)
                }
            val selected = packs.firstOrNull { it.pack.id == model.selected }
            val imports = model.pending.isNotEmpty()
            BackHandler(enabled = model.selected != null && !imports && model.editor == null) {
                model.selected = null
            }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    if (imports) "Import studio"
                                    else
                                        selected?.pack?.name
                                            ?: context.getString(R.string.app_name),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (imports) "Preview · edit · choose a pack"
                                    else if (selected != null) "${selected.stickers.size} stickers"
                                    else "A little more you, in every chat.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            if (selected != null && !imports)
                                IconButton(onClick = { model.selected = null }) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "My packs")
                                }
                        },
                        actions = {
                            if (!imports)
                                IconButton(
                                    onClick = {
                                        picker.launch(
                                            arrayOf(
                                                "image/png",
                                                "image/jpeg",
                                                "image/webp",
                                                "image/gif",
                                                "video/mp4",
                                            )
                                        )
                                    }
                                ) {
                                    Icon(Icons.Outlined.FileUpload, "Import media")
                                }
                        },
                    )
                },
                floatingActionButton = {
                    if (!imports && selected == null)
                        ExtendedFloatingActionButton(
                            modifier = Modifier.semantics { contentDescription = "New pack" },
                            onClick = { create = true },
                            icon = { Icon(Icons.Outlined.Add, null) },
                            text = { Text("New pack") },
                        )
                },
            ) { insets ->
                Box(Modifier.padding(insets).fillMaxSize()) {
                    when {
                        imports ->
                            ImportScreen(
                                model,
                                packs,
                                destination,
                                { destination = it },
                                { create = true },
                            )
                        selected != null ->
                            PackScreen(
                                model,
                                selected,
                                { rename = selected.pack },
                                { delete = selected.pack },
                                { picker.launch(arrayOf("image/*", "video/mp4")) },
                            ) {
                                model.action {
                                    val targets =
                                        listOf("com.whatsapp", "com.whatsapp.w4b").filter {
                                            packageName ->
                                            runCatching {
                                                    context.packageManager.getPackageInfo(
                                                        packageName,
                                                        0,
                                                    )
                                                }
                                                .isSuccess
                                        }
                                    if (targets.isNotEmpty()) model.repo.expose(selected.pack.id)
                                    withContext(Dispatchers.Main) {
                                        when (targets.size) {
                                            0 ->
                                                model.message =
                                                    "Install WhatsApp or WhatsApp Business to add this pack."
                                            1 -> export(selected.pack, targets[0])
                                            else -> targetChooser = selected.pack
                                        }
                                    }
                                }
                            }
                        else ->
                            PackLibrary(
                                packs,
                                model.repo,
                                { model.selected = it },
                                { picker.launch(arrayOf("image/*", "video/mp4")) },
                            )
                    }
                }
            }
            if (create)
                PackNameDialog("New pack", null, onDismiss = { create = false }) { name ->
                    model.create(name) { p ->
                        if (imports) destination = p.id else model.selected = p.id
                        create = false
                    }
                }
            rename?.let { p ->
                PackNameDialog("Rename pack", p, onDismiss = { rename = null }) { name ->
                    model.action { model.repo.rename(p.id, name) }
                    rename = null
                }
            }
            delete?.let { p ->
                AlertDialog(
                    onDismissRequest = { delete = null },
                    title = { Text("Delete ${p.name}?") },
                    text = {
                        Text(
                            "This removes the pack, originals and edits from this phone. Copies already sent in chats remain there."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                model.action { model.repo.removePack(p.id) }
                                model.selected = null
                                delete = null
                            }
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { delete = null }) { Text("Keep pack") }
                    },
                )
            }
            targetChooser?.let { p ->
                AlertDialog(
                    onDismissRequest = { targetChooser = null },
                    title = { Text("Add pack to") },
                    text = {
                        Column {
                            Button(
                                onClick = {
                                    targetChooser = null
                                    export(p, "com.whatsapp")
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("WhatsApp")
                            }
                            OutlinedButton(
                                onClick = {
                                    targetChooser = null
                                    export(p, "com.whatsapp.w4b")
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("WhatsApp Business")
                            }
                        }
                    },
                    confirmButton = {},
                )
            }
            model.editor?.let { target ->
                EditorScreen(
                    target,
                    onDismiss = { model.editor = null },
                    onSave = { model.acceptEdit(it) },
                )
            }
            model.busy?.let { label ->
                Dialog(onDismissRequest = {}) {
                    Card {
                        Column(
                            Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(label, style = MaterialTheme.typography.titleLarge)
                            LinearProgressIndicator(
                                progress = { model.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${(model.progress*100).toInt()}% · Processing on your phone",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { model.cancel() }) { Text("Cancel") }
                        }
                    }
                }
            }
            if (model.errors.isNotEmpty())
                AlertDialog(
                    onDismissRequest = { model.errors = emptyList() },
                    title = { Text("Some files need attention") },
                    text = {
                        Column(
                            Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())
                        ) {
                            model.errors.forEach { Text(it, Modifier.padding(vertical = 8.dp)) }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { model.errors = emptyList() }) { Text("Got it") }
                    },
                )
            model.message?.let { msg ->
                AlertDialog(
                    onDismissRequest = { model.message = null },
                    text = { Text(msg) },
                    confirmButton = {
                        TextButton(onClick = { model.message = null }) { Text("OK") }
                    },
                )
            }
        }
    }
}

@Composable
private fun PackLibrary(
    packs: List<PackContents>,
    repo: Repository,
    open: (String) -> Unit,
    import: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 100.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "MADE BY YOU",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Your moments.\nYour stickers.",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Turn photos, GIFs and short videos into a collection worth sharing.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(onClick = import) {
                        Icon(Icons.Outlined.FileUpload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import media")
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "My packs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text("${packs.size} collections", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (packs.isEmpty())
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.Collections,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Room for your first pack", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap New pack to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        items(packs, key = { it.pack.id }) { c ->
            Card(
                onClick = { open(c.pack.id) },
                shape = RoundedCornerShape(24.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().height(96.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (c.stickers.isEmpty())
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                null,
                                Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        else
                            c.stickers.take(2).forEach {
                                AsyncImage(
                                    repo.file(it),
                                    "Sticker preview",
                                    Modifier.weight(1f).aspectRatio(1f),
                                )
                            }
                    }
                    Text(
                        c.pack.name,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${c.stickers.size}/30 stickers",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        if (c.stickers.size >= 3) "Ready to share"
                        else "Draft · ${3-c.stickers.size} more needed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Offline by design. Your originals and edits stay on this phone.",
                Modifier.padding(vertical = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PackScreen(
    model: StudioModel,
    c: PackContents,
    rename: () -> Unit,
    delete: () -> Unit,
    import: () -> Unit,
    export: () -> Unit,
) {
    var focus by remember { mutableStateOf<Sticker?>(null) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(105.dp),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        AsyncImage(
                            File(model.repo.folder(c.pack.id), c.pack.tray),
                            "Pack icon",
                            Modifier.size(72.dp).padding(8.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${c.stickers.size} of 30 stickers",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (c.pack.exposed) "Visible to WhatsApp" else "Saved on this phone",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = rename) { Icon(Icons.Outlined.Edit, "Rename pack") }
                    IconButton(onClick = delete) { Icon(Icons.Outlined.Delete, "Delete pack") }
                }
                val missing = Limits.issues(c.stickers.size, c.pack.tray.isNotBlank())
                if (missing.isNotEmpty())
                    Text(
                        missing.joinToString("\n"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                Button(
                    onClick = export,
                    enabled = missing.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add to WhatsApp")
                }
                if (c.pack.exposed)
                    TextButton(onClick = { model.action { model.repo.hide(c.pack.id) } }) {
                        Text("Hide from WhatsApp")
                    }
                OutlinedButton(
                    onClick = import,
                    enabled = c.stickers.size < 30,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import stickers")
                }
                Text(
                    "Tap a sticker to edit, reorder or choose it as the pack icon.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        items(c.stickers, key = { it.id }) { s ->
            Card(onClick = { focus = s }, shape = RoundedCornerShape(20.dp)) {
                Box(Modifier.aspectRatio(1f).checker()) {
                    AsyncImage(
                        model.repo.file(s),
                        "Sticker ${s.position+1}",
                        Modifier.fillMaxSize().padding(8.dp),
                    )
                }
            }
        }
    }
    focus?.let { s ->
        AlertDialog(
            onDismissRequest = { focus = null },
            title = { Text("Sticker ${c.stickers.indexOfFirst{it.id==s.id}+1}") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(210.dp).checker()) {
                        AsyncImage(model.repo.file(s), "Sticker preview", Modifier.fillMaxSize())
                    }
                    Row {
                        TextButton(
                            onClick = {
                                model.action { model.repo.move(s, -1) }
                                focus = null
                            },
                            enabled = c.stickers.first().id != s.id,
                        ) {
                            Text("Move earlier")
                        }
                        TextButton(
                            onClick = {
                                model.action { model.repo.move(s, 1) }
                                focus = null
                            },
                            enabled = c.stickers.last().id != s.id,
                        ) {
                            Text("Move later")
                        }
                    }
                    TextButton(
                        onClick = {
                            model.action { model.repo.chooseTray(s) }
                            focus = null
                        }
                    ) {
                        Text("Use as pack icon")
                    }
                    TextButton(
                        onClick = {
                            model.action { model.repo.remove(s) }
                            focus = null
                        }
                    ) {
                        Text("Remove sticker", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        focus = null
                        model.openEditor(s)
                    }
                ) {
                    Text("Edit sticker")
                }
            },
            dismissButton = { TextButton(onClick = { focus = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun ImportScreen(
    model: StudioModel,
    packs: List<PackContents>,
    destination: String?,
    select: (String) -> Unit,
    create: () -> Unit,
) {
    val chosen = packs.firstOrNull { it.pack.id == destination }
    LazyColumn(
        Modifier.fillMaxSize().testTag("import-list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "${model.pending.size} files ready to preview",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Add photos, GIFs and videos to the same pack. Edit clips longer than 10 seconds before saving.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(model.pending.size, key = { model.pending[it].media.file.path }) { index ->
            val item = model.pending[index]
            Card {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MediaThumbnail(item.media, Modifier.size(64.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.media.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (item.media.animated)
                                "Animated · ${"%.1f".format(item.media.duration/1000f)}s"
                            else "Static image",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            "${item.media.width} × ${item.media.height}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    IconButton(onClick = { model.editIncoming(index) }) {
                        Icon(Icons.Outlined.Tune, "Edit ${item.media.name}")
                    }
                    IconButton(onClick = { model.drop(index) }) {
                        Icon(Icons.Outlined.Close, "Remove ${item.media.name}")
                    }
                }
            }
        }
        item {
            Text(
                "Save to a pack",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        items(packs, key = { it.pack.id }) { c ->
            OutlinedCard(onClick = { select(c.pack.id) }) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = destination == c.pack.id,
                        onClick = { select(c.pack.id) },
                    )
                    Column {
                        Text(c.pack.name, fontWeight = FontWeight.Medium)
                        Text(
                            "${c.stickers.size}/30 stickers",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = create, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, null)
                Text("Create a new pack")
            }
        }
        item {
            Button(
                onClick = { destination?.let { model.saveImports(it) } },
                enabled = chosen != null && chosen.stickers.size < 30,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save stickers")
            }
            TextButton(onClick = { model.discard() }, modifier = Modifier.fillMaxWidth()) {
                Text("Discard imports")
            }
        }
    }
}

@Composable
fun MediaThumbnail(media: Incoming, modifier: Modifier = Modifier) {
    if (media.kind == "video") {
        val bitmap by
            produceState<android.graphics.Bitmap?>(null, media.file) {
                value =
                    withContext(Dispatchers.IO) {
                        runCatching { MediaEngine.firstFrame(media.file) }.getOrNull()
                    }
            }
        bitmap?.let {
            androidx.compose.foundation.Image(it.asComposeImage(), "Video preview", modifier)
        }
            ?: Box(
                modifier.background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Movie, "Video")
            }
    } else AsyncImage(media.file, "Media preview", modifier)
}

fun android.graphics.Bitmap.asComposeImage() = this.asImageBitmap()

@Composable
private fun PackNameDialog(
    title: String,
    pack: Pack?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(pack?.name ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 128) name = it },
                    label = { Text("Pack name") },
                    singleLine = true,
                )
                if (pack == null) {
                    Text(
                        "You can keep a draft until it has 3 stickers.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) {
                Text("Save pack")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun Modifier.checker(dark: Boolean = false): Modifier = drawBehind {
    val cell = 16.dp.toPx()
    drawRect(if (dark) Color(0xFF25232B) else Color(0xFFF4F1F7))
    for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt()) if (
        (x + y) % 2 == 0
    )
        drawRect(
            if (dark) Color(0xFF37333F) else Color(0xFFE6E0EC),
            topLeft = androidx.compose.ui.geometry.Offset(x * cell, y * cell),
            size = androidx.compose.ui.geometry.Size(cell, cell),
        )
}
