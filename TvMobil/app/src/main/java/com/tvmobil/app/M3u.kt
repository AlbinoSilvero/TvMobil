package com.tvmobil.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

data class Playlist(val name: String, val url: String)
data class Channel(
    val name: String, val url: String, val logo: String?, val group: String?,
    val userAgent: String?, val referrer: String?
)

object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name: String? = null; var logo: String? = null; var group: String? = null
        var ua: String? = null; var ref: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF", true) -> {
                    val a = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = a["tvg-logo"]?.ifBlank { null }
                    group = a["group-title"]?.ifBlank { null }
                    name = line.substringAfterLast(',').trim().ifEmpty { a["tvg-name"] ?: "Sin nombre" }
                }
                line.startsWith("#EXTVLCOPT:http-user-agent=", true) -> ua = line.substringAfter('=')
                line.startsWith("#EXTVLCOPT:http-referrer=", true) -> ref = line.substringAfter('=')
                line.startsWith("#") -> {}
                else -> {
                    if (name != null) out += Channel(name, line, logo, group, ua, ref)
                    name = null; logo = null; group = null; ua = null; ref = null
                }
            }
        }
        return out
    }

    fun download(url: String): String {
        val c = URL(url).openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }
        return c.getInputStream().bufferedReader().use { it.readText() }
    }
}

class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("tvmobil", Context.MODE_PRIVATE)

    fun load(): List<Playlist> {
        val arr = JSONArray(prefs.getString("playlists", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Playlist(o.getString("name"), o.getString("url")) } }
    }

    fun save(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
        prefs.edit().putString("playlists", arr.toString()).apply()
    }
}
