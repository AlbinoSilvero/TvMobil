package com.tvmobil.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val IPTV_ORG_ALL = "https://iptv-org.github.io/iptv/index.m3u"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La pantalla no se apaga mientras la app esté abierta
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val store = Store(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App(store) } } }
    }
}

@Composable
fun App(store: Store) {
    var playlists by remember { mutableStateOf(store.load()) }
    var selected by remember { mutableStateOf<Playlist?>(null) }
    var playing by remember { mutableStateOf<Channel?>(null) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }

    when {
        playing != null -> PlayerScreen(playing!!, channels, { playing = it }) { playing = null }
        selected != null -> ChannelsScreen(selected!!, channels, { channels = it }, { playing = it }) { selected = null }
        else -> PlaylistsScreen(playlists, { playlists = it; store.save(it) }) { channels = emptyList(); selected = it }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(list: List<Playlist>, onChange: (List<Playlist>) -> Unit, onOpen: (Playlist) -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Mis playlists") }) },
        floatingActionButton = { FloatingActionButton(onClick = { dialog = true }) { Icon(Icons.Default.Add, "Agregar") } }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (list.isEmpty()) {
                Column(Modifier.padding(24.dp)) {
                    Text("Aún no tienes playlists. Agrega una URL M3U (por ejemplo de iptv-org).")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onChange(list + Playlist("iptv-org (todos)", IPTV_ORG_ALL)) }) { Text("Agregar iptv-org") }
                }
            }
            LazyColumn {
                items(list) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text(p.url, maxLines = 1) },
                        trailingContent = { IconButton(onClick = { onChange(list - p) }) { Icon(Icons.Default.Delete, "Borrar") } },
                        modifier = Modifier.clickable { onOpen(p) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    if (dialog) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://iptv-org.github.io/iptv/") }
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("Agregar playlist") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(url, { url = it }, label = { Text("URL .m3u") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (url.startsWith("http")) onChange(list + Playlist(name.ifBlank { url.substringAfterLast('/') }, url.trim()))
                    dialog = false
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("Cancelar") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    playlist: Playlist, channels: List<Channel>, setChannels: (List<Channel>) -> Unit,
    onPlay: (Channel) -> Unit, onBack: () -> Unit
) {
    var loading by remember { mutableStateOf(channels.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    LaunchedEffect(playlist) {
        if (channels.isEmpty()) {
            try {
                val text = withContext(Dispatchers.IO) { M3uParser.download(playlist.url) }
                setChannels(M3uParser.parse(text))
            } catch (e: Exception) { error = e.message ?: "Error al descargar" }
            loading = false
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(playlist.name) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Atrás") } })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(query, { query = it }, label = { Text("Buscar canal o grupo") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(8.dp))
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                error != null -> Text("Error: $error", Modifier.padding(16.dp))
                else -> {
                    val shown = channels.filter {
                        query.isBlank() || it.name.contains(query, true) || (it.group?.contains(query, true) == true)
                    }
                    LazyColumn {
                        items(shown) { c ->
                            ListItem(
                                leadingContent = { AsyncImage(c.logo, null, Modifier.size(48.dp)) },
                                headlineContent = { Text(c.name) },
                                supportingContent = { c.group?.let { Text(it) } },
                                modifier = Modifier.clickable { onPlay(c) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Busca la Activity real dentro de un Context (puede venir envuelto). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Mientras está en pantalla: gira a horizontal y oculta las barras del sistema (pantalla completa).
 * Al salir: vuelve a la orientación normal y muestra las barras.
 */
@Composable
private fun FullscreenLandscape() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val activity = ctx.findActivity()
        val window = activity?.window
        if (activity == null || window == null) return@DisposableEffect onDispose { }

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        if (Build.VERSION.SDK_INT >= 28) {
            // Usar también la zona del notch/cámara en horizontal
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(
    channel: Channel,
    channels: List<Channel>,
    onSelect: (Channel) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    var showList by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    // Atrás: si la lista está abierta la cierra; si no, sale del reproductor
    BackHandler(onBack = onBack)
    BackHandler(enabled = showList) { showList = false }

    FullscreenLandscape()

    val player = remember(channel) {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).apply {
            channel.userAgent?.let { setUserAgent(it) }
            channel.referrer?.let { setDefaultRequestProperties(mapOf("Referer" to it)) }
        }
        ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().apply {
            setMediaItem(MediaItem.fromUri(channel.url)); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == View.VISIBLE }
                    )
                }
            },
            update = { it.player = player }, // se actualiza al cambiar de canal
            modifier = Modifier.fillMaxSize()
        )

        // Botones superiores (atrás + lista), visibles junto con los controles del reproductor
        AnimatedVisibility(
            visible = controlsVisible && !showList,
            modifier = Modifier.align(Alignment.TopStart),
            enter = fadeIn(), exit = fadeOut()
        ) {
            Row(Modifier.displayCutoutPadding().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.background(Color(0x66000000), CircleShape)
                ) { Icon(Icons.Default.ArrowBack, "Atrás", tint = Color.White) }
                IconButton(
                    onClick = { showList = true },
                    modifier = Modifier.background(Color(0x66000000), CircleShape)
                ) { Icon(Icons.Default.Menu, "Lista de canales", tint = Color.White) }
            }
        }

        // Toque fuera del panel = cerrar la lista
        if (showList) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showList = false }
            )
        }

        // Lista de canales sobre el video
        AnimatedVisibility(
            visible = showList,
            modifier = Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it }
        ) {
            ChannelPanel(
                current = channel,
                channels = channels,
                onSelect = { showList = false; onSelect(it) },
                onClose = { showList = false }
            )
        }
    }
}

@Composable
fun ChannelPanel(
    current: Channel,
    channels: List<Channel>,
    onSelect: (Channel) -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, channels) {
        channels.filter {
            query.isBlank() || it.name.contains(query, true) || (it.group?.contains(query, true) == true)
        }
    }
    val listState = rememberLazyListState()
    // Al abrir, ubicar la lista en el canal que se está viendo
    LaunchedEffect(Unit) {
        val i = shown.indexOf(current)
        if (i >= 0) listState.scrollToItem(maxOf(0, i - 2))
    }

    Column(
        Modifier
            .fillMaxHeight()
            .width(340.dp)
            .background(Color(0xE6000000))
            .displayCutoutPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Canales", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cerrar") }
        }
        OutlinedTextField(
            query, { query = it },
            label = { Text("Buscar canal o grupo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        )
        LazyColumn(state = listState) {
            items(shown) { c ->
                ListItem(
                    leadingContent = { AsyncImage(c.logo, null, Modifier.size(40.dp)) },
                    headlineContent = { Text(c.name, maxLines = 1) },
                    supportingContent = { c.group?.let { Text(it, maxLines = 1) } },
                    colors = ListItemDefaults.colors(
                        containerColor = if (c == current) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        else Color.Transparent
                    ),
                    modifier = Modifier.clickable { onSelect(c) }
                )
            }
        }
    }
}
