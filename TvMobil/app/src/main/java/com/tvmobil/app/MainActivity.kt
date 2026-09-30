package com.tvmobil.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
        playing != null -> PlayerScreen(playing!!) { playing = null }
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

@Composable
fun PlayerScreen(channel: Channel, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    BackHandler(onBack = onBack)
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
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.fillMaxSize())
}
