package com.masoomdev.mhplayer

import android.content.SharedPreferences
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private data class UserPlaylist(val id: String, val name: String, val video: Boolean, val uris: List<String>)

@Composable
fun PlaylistLibrary(prefs: SharedPreferences, media: List<LocalMedia>, onPlay: (LocalMedia) -> Unit) {
    fun load(): List<UserPlaylist> = try {
        val array = JSONArray(prefs.getString("custom_playlists_v1", "[]"))
        (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val files = obj.optJSONArray("files") ?: JSONArray()
            UserPlaylist(obj.getString("id"), obj.getString("name"), obj.optBoolean("video", true),
                (0 until files.length()).map { files.getString(it) })
        }
    } catch (_: Exception) { emptyList() }
    var playlists by remember { mutableStateOf(load()) }
    fun save(updated: List<UserPlaylist>) {
        playlists = updated
        val array = JSONArray()
        updated.forEach { entry ->
            val files = JSONArray()
            entry.uris.forEach { files.put(it) }
            array.put(JSONObject().put("id", entry.id).put("name", entry.name).put("video", entry.video).put("files", files))
        }
        prefs.edit().putString("custom_playlists_v1", array.toString()).apply()
    }
    var activeId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var videoType by remember { mutableStateOf(true) }
    var folder by remember { mutableStateOf<String?>(null) }
    val active = playlists.firstOrNull { it.id == activeId }
    val green = Color(0xFF31C66B)
    val mediaByUri = remember(media) { media.associateBy { it.uri.toString() } }
    val activeUriSet = remember(active?.uris) { active?.uris?.toHashSet() ?: emptySet<String>() }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { if (active != null) { activeId = null; adding = false } }) {
            Text(if (active == null) "My Playlists" else "‹ All Playlists")
        }
        Button(onClick = {
            if (active == null) { name = ""; videoType = true; creating = true }
            else { folder = null; adding = true }
        }) { Text(if (active == null) "+ New Playlist" else "+ Add Files") }
    }
    if (active == null) {
        if (playlists.isEmpty()) Text("No playlists yet. Tap New Playlist to create one.", modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(playlists, key = { it.id }) { entry ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.weight(1f).clickable { activeId = entry.id }.padding(10.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text("${if (entry.video) "Video" else "Audio"} • ${entry.uris.size} files", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { editing = entry.id; name = entry.name }) { Text("Rename") }
                    TextButton(onClick = { deleting = entry.id }) { Text("Delete") }
                }
                HorizontalDivider()
            }
        }
    } else if (adding) {
        Text("Select ${if (active.video) "videos" else "audio"} from any folder", modifier = Modifier.padding(8.dp))
        val eligible = remember(media, active.video) { media.filter { it.video == active.video } }
        val folders = remember(eligible) { eligible.map { it.folder }.distinct().sorted() }
        if (folder != null) TextButton(onClick = { folder = null }) { Text("‹ All folders") }
        LazyColumn {
            if (folder == null) {
                items(folders) { path ->
                    TextButton(onClick = { folder = path }) { Text("📁 ${path.substringAfterLast('/')}") }
                }
            } else {
                items(eligible.filter { it.folder == folder }, key = { it.uri.toString() }) { file ->
                    val included = file.uri.toString() in activeUriSet
                    Row(Modifier.fillMaxWidth().clickable {
                        val files = if (included) active.uris - file.uri.toString() else active.uris + file.uri.toString()
                        save(playlists.map { if (it.id == active.id) it.copy(uris = files) else it })
                    }.padding(12.dp)) {
                        Text(if (included) "☑" else "☐", color = green)
                        Spacer(Modifier.width(10.dp))
                        Text(file.title, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        TextButton(onClick = { adding = false; folder = null }) { Text("Done adding") }
    } else {
        Text("${active.name} • ${if (active.video) "Video" else "Audio"}", style = MaterialTheme.typography.titleLarge)
        if (active.uris.isEmpty()) Text("Empty playlist. Tap Add Files to choose media.", modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(active.uris) { uri ->
                val file = mediaByUri[uri]
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    TextButton(onClick = { if (file != null) onPlay(file) }, modifier = Modifier.weight(1f), enabled = file != null) {
                        Text(file?.title ?: "File unavailable", maxLines = 2)
                    }
                    TextButton(onClick = { save(playlists.map { if (it.id == active.id) it.copy(uris = it.uris - uri) else it }) }) { Text("Remove") }
                }
                HorizontalDivider()
            }
        }
    }
    if (creating || editing != null) AlertDialog(
        onDismissRequest = { creating = false; editing = null },
        title = { Text(if (creating) "Create Playlist" else "Rename Playlist") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Playlist name") }, singleLine = true)
                if (creating) {
                    Row { RadioButton(selected = videoType, onClick = { videoType = true }); Text("Video", modifier = Modifier.clickable { videoType = true }.padding(12.dp)) }
                    Row { RadioButton(selected = !videoType, onClick = { videoType = false }); Text("Audio", modifier = Modifier.clickable { videoType = false }.padding(12.dp)) }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            val clean = name.trim()
            if (creating) {
                val id = java.util.UUID.randomUUID().toString()
                save(playlists + UserPlaylist(id, clean, videoType, emptyList()))
                activeId = id
                adding = true
                folder = null
            } else {
                save(playlists.map { if (it.id == editing) it.copy(name = clean) else it })
            }
            creating = false; editing = null
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Cancel") } }
    )
    if (deleting != null) AlertDialog(
        onDismissRequest = { deleting = null },
        title = { Text("Delete playlist?") },
        text = { Text("Only the playlist is deleted. Your media files stay on the device.") },
        confirmButton = { TextButton(onClick = { save(playlists.filterNot { it.id == deleting }); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
    )
}
