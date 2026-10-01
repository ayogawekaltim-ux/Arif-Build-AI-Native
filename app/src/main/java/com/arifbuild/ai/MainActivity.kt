
package com.arifbuild.ai

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import java.io.ByteArrayOutputStream

private val Accent = Color(0xFF087F73)
private val Bg = Color(0xFFF6FAF8)
private val Soft = Color(0xFFE4F3EF)
private val Muted = Color(0xFF64716E)

data class Msg(val role: String, val text: String)
data class FileItem(val path: String, val text: String)
data class RemoteModel(val id: String, val name: String, val context: Long, val input: String, val output: String)
data class BuildResult(val message: String, val files: List<FileItem>, val deletes: List<String>)

class MainActivity : ComponentActivity() {
    private var exportFiles: List<FileItem> = emptyList()
    private var importFiles: ((List<FileItem>) -> Unit)? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                ZipOutputStream(out).use { zip ->
                    exportFiles.forEach {
                        zip.putNextEntry(ZipEntry(it.path))
                        zip.write(it.text.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            }
            Toast.makeText(this, "ZIP project berhasil dibuat.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export gagal: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val imported = mutableListOf<FileItem>()
            contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    val buffer = ByteArray(8192)
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.isSafeProjectPath()) {
                            val out = ByteArrayOutputStream()
                            var n = zip.read(buffer)
                            while (n > 0) { out.write(buffer, 0, n); n = zip.read(buffer) }
                            imported.add(FileItem(entry.name, out.toByteArray().toString(Charsets.UTF_8)))
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            if (imported.isEmpty()) Toast.makeText(this, "ZIP tidak berisi file project yang aman.", Toast.LENGTH_LONG).show()
            else importFiles?.invoke(imported)
        } catch (e: Exception) {
            Toast.makeText(this, "Import gagal: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ArifTheme {
            ArifApp(
                exportZip = { files -> exportFiles = files; createDocument.launch("arifbuild-project.zip") },
                importZip = { receiver -> importFiles = receiver; openDocument.launch(arrayOf("application/zip", "application/octet-stream")) }
            )
        } }
    }
}

@Composable
fun ArifTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            background = Bg,
            surface = Color.White,
            onSurface = Color(0xFF17211F)
        ),
        content = content
    )
}

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("arifbuild_v44", Context.MODE_PRIVATE)
    fun get(k: String, d: String = "") = p.getString(k, d) ?: d
    fun put(k: String, v: String) { p.edit().putString(k, v).apply() }
    fun getBool(k: String, d: Boolean) = p.getBoolean(k, d)
    fun putBool(k: String, v: Boolean) { p.edit().putBoolean(k, v).apply() }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ArifApp(exportZip: (List<FileItem>) -> Unit, importZip: ((List<FileItem>) -> Unit) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf("Chat") }
    var apiKey by remember { mutableStateOf(prefs.get("apiKey")) }
    var baseUrl by remember { mutableStateOf(prefs.get("baseUrl", "https://openrouter.ai/api/v1")) }
    var selectedModel by remember { mutableStateOf(prefs.get("model", "deepseek/deepseek-chat")) }
    var auto by remember { mutableStateOf(prefs.getBool("auto", false)) }
    var messages by remember { mutableStateOf(listOf<Msg>()) }
    var models by remember { mutableStateOf(listOf<RemoteModel>()) }
    var files by remember {
        mutableStateOf(loadLocalFiles(prefs).ifEmpty {
            listOf(FileItem("index.html", "<!doctype html>\\n<html>\\n<head><meta charset=\"utf-8\"><title>ArifBuild</title></head>\\n<body>\\n</body>\\n</html>"))
        })
    }
    var selectedFile by remember { mutableStateOf("index.html") }
    var busy by remember { mutableStateOf(false) }
    var modelFilter by remember { mutableStateOf("") }
    var tierFilter by remember { mutableStateOf("ALL") }
    var status by remember { mutableStateOf("Siap") }
    var error by remember { mutableStateOf<String?>(null) }
    var serverUrl by remember { mutableStateOf(prefs.get("serverUrl")) }
    var serverToken by remember { mutableStateOf(prefs.get("serverToken")) }
    var serverStatus by remember { mutableStateOf("Belum diuji") }
    var projectId by remember { mutableStateOf(prefs.get("projectId", "arifbuild-project")) }
    var addFileDialog by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf("") }
    var buildPrompt by remember { mutableStateOf("") }
    var buildLog by remember { mutableStateOf(listOf<String>()) }
    var previewVersion by remember { mutableIntStateOf(0) }

    fun savePrefs() {
        prefs.put("apiKey", apiKey)
        prefs.put("baseUrl", baseUrl)
        prefs.put("model", selectedModel)
        prefs.putBool("auto", auto)
        prefs.put("serverUrl", serverUrl)
        prefs.put("serverToken", serverToken)
        prefs.put("projectId", projectId)
        status = "Settings disimpan."
    }

    fun persistFiles() {
        saveLocalFiles(prefs, files)
    }

    fun apiCall(block: suspend () -> String, onResult: (String) -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try { onResult(block()) }
            catch (e: Exception) { error = e.message ?: "Terjadi kesalahan"; status = "Gagal" }
            finally { busy = false }
        }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Arif Build AI", fontWeight = FontWeight.Bold)
                        Text("V4.4 · AI Build Engine", style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                },
                navigationIcon = {
                    Box(Modifier.padding(start = 12.dp).size(40.dp).background(Soft), contentAlignment = Alignment.Center) {
                        Text("AB", color = Accent, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { tab = "Model" }) { Icon(Icons.Default.Memory, "Model") }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                listOf(
                    "Chat" to Icons.Default.Chat,
                    "Build" to Icons.Default.AutoAwesome,
                    "Project" to Icons.Default.Folder,
                    "Preview" to Icons.Default.Visibility,
                    "Cloud" to Icons.Default.CloudUpload,
                    "Settings" to Icons.Default.Settings
                ).forEach { (n, i) ->
                    NavigationBarItem(tab == n, { tab = n }, icon = { Icon(i, n) }, label = { Text(n) })
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                "Chat" -> ChatPage(
                    messages, selectedModel, auto, busy,
                    onAuto = { auto = !auto; prefs.putBool("auto", auto); status = if (auto) "Auto Reply ON" else "Auto Reply OFF" },
                    onSend = { text ->
                        if (text.isBlank()) { status = "Pesan masih kosong."; return@ChatPage }
                        messages = messages + Msg("user", text)
                        if (apiKey.isBlank()) {
                            messages = messages + Msg("assistant", "API Key OpenRouter belum diatur. Buka Settings.")
                            status = "API Key diperlukan."
                        } else {
                            apiCall({
                                val body = JSONObject()
                                    .put("model", selectedModel)
                                    .put("max_tokens", 6000)
                                    .put("messages", JSONArray().apply {
                                        put(JSONObject().put("role", "system").put("content",
                                            "Kamu adalah Arif Build AI V4.4. Jangan mengarang requirement. Gunakan marker [ARIF_STATUS] dengan stage, auto_continue, needs_user_input, next_action."))
                                        messages.takeLast(20).forEach { put(JSONObject().put("role", it.role).put("content", it.text)) }
                                    }).toString()
                                openRouter(baseUrl, apiKey, body)
                            }) { reply ->
                                messages = messages + Msg("assistant", reply)
                                status = "Jawaban AI diterima."
                            }
                        }
                    },
                    onClear = { messages = emptyList(); status = "Percakapan dihapus." }
                )

                "Build" -> BuildPage(
                    prompt = buildPrompt,
                    onPrompt = { buildPrompt = it },
                    log = buildLog,
                    busy = busy,
                    model = selectedModel,
                    onBuild = {
                        val prompt = buildPrompt.trim()
                        if (prompt.isBlank()) { status = "Instruksi build masih kosong." }
                        else if (apiKey.isBlank()) { status = "Masukkan API Key OpenRouter di Settings." }
                        else {
                            buildLog = buildLog + "▶ Build dimulai dengan $selectedModel"
                            apiCall({
                                val fileManifest = files.joinToString("\n") { "- ${it.path}" }
                                val body = JSONObject()
                                    .put("model", selectedModel)
                                    .put("max_tokens", 18000)
                                    .put("messages", JSONArray().apply {
                                        put(JSONObject().put("role", "system").put("content", buildSystemPrompt()))
                                        put(JSONObject().put("role", "user").put("content", "INSTRUKSI:\n$prompt\n\nFILE SAAT INI:\n$fileManifest\n\nKirim JSON build result sesuai protokol."))
                                    }).toString()
                                openRouter(baseUrl, apiKey, body)
                            }) { raw ->
                                try {
                                    val result = parseBuildResult(raw)
                                    val map = files.associateBy { it.path }.toMutableMap()
                                    result.deletes.forEach { map.remove(it) }
                                    result.files.forEach { map[it.path] = it }
                                    files = map.values.sortedBy { it.path }
                                    if (files.isEmpty()) files = listOf(FileItem("index.html", ""))
                                    selectedFile = files.first().path
                                    persistFiles()
                                    previewVersion++
                                    buildLog = buildLog + "✓ ${result.message}" + result.files.map { "  + ${it.path}" } + result.deletes.map { "  - $it" }
                                    status = "AI Build selesai: ${result.files.size} file diubah/dibuat."
                                    buildPrompt = ""
                                } catch (e: Exception) {
                                    buildLog = buildLog + "✗ Build gagal diproses: ${e.message}"
                                    status = "Respons AI bukan format build yang valid."
                                    error = e.message
                                }
                            }
                        }
                    },
                    onClear = { buildLog = emptyList(); status = "Log build dibersihkan." }
                )

                "Model" -> ModelPage(
                    selectedModel, models, apiKey.isNotBlank(), busy, modelFilter, tierFilter,
                    onFilter = { modelFilter = it }, onTier = { tierFilter = it },
                    onLoad = {
                        if (apiKey.isBlank()) status = "Masukkan API Key di Settings."
                        else apiCall({
                            val json = openRouterGet(baseUrl, apiKey, "/models")
                            json
                        }) { json ->
                            models = parseModels(json)
                            status = "Model OpenRouter dimuat: ${models.size}"
                        }
                    },
                    onPick = { selectedModel = it; prefs.put("model", it); status = "Model aktif: $it" }
                )

                "Project" -> ProjectPage(
                    files, selectedFile,
                    onSelect = { selectedFile = it },
                    onSave = { path, text ->
                        files = files.filterNot { it.path == path } + FileItem(path, text)
                        persistFiles()
                        status = "$path disimpan."
                    },
                    onAdd = { addFileDialog = true },
                    onImport = { importZip { imported ->
                        files = imported
                        selectedFile = imported.first().path
                        persistFiles()
                        status = "ZIP diimpor: ${imported.size} file."
                    } },
                    onDelete = {
                        if (files.size <= 1) status = "Minimal satu file harus ada."
                        else { files = files.filterNot { it.path == selectedFile }; selectedFile = files.first().path; persistFiles(); status = "File dihapus." }
                    },
                    onExport = { exportZip(files) }
                )

                "Preview" -> PreviewPage(
                    context = context,
                    files = files,
                    refreshKey = previewVersion,
                    onRefresh = { previewVersion++ }
                )

                "Cloud" -> CloudPage(
                    serverUrl, { serverUrl = it },
                    serverToken, { serverToken = it },
                    projectId, { projectId = it },
                    serverStatus, busy,
                    files,
                    onTest = {
                        savePrefs()
                        apiCall({ backendRequest(serverUrl, serverToken, "GET", "/health") }) {
                            serverStatus = it
                            status = it
                        }
                    },
                    onSave = {
                        savePrefs()
                        persistFiles()
                        val body = JSONObject().put("project_id", projectId).put("files", JSONArray().apply {
                            files.forEach { put(JSONObject().put("path", it.path).put("content", it.text)) }
                        }).toString()
                        apiCall({ backendRequest(serverUrl, serverToken, "POST", "/projects", body) }) {
                            serverStatus = it; status = it
                        }
                    },
                    onLoad = {
                        apiCall({ backendRequest(serverUrl, serverToken, "GET", "/projects/$projectId") }) { raw ->
                            try {
                                val arr = JSONObject(raw).optJSONArray("files")
                                if (arr == null) {
                                    status = "Server merespons tetapi format files tidak ditemukan."
                                } else {
                                    val loaded = buildList {
                                        for (i in 0 until arr.length()) {
                                            val o = arr.getJSONObject(i)
                                            add(FileItem(o.optString("path"), o.optString("content")))
                                        }
                                    }
                                    if (loaded.isNotEmpty()) {
                                        files = loaded
                                        selectedFile = loaded.first().path
                                        status = "Project dimuat: ${loaded.size} file."
                                    } else status = "Project kosong."
                                }
                            } catch (e: Exception) { status = "Format project tidak valid: ${e.message}" }
                        }
                    },
                    onDelete = {
                        apiCall({ backendRequest(serverUrl, serverToken, "DELETE", "/projects/$projectId") }) {
                            serverStatus = it; status = it
                        }
                    },
                    onPublish = {
                        val body = JSONObject().put("project_id", projectId).put("backup", true).toString()
                        apiCall({ backendRequest(serverUrl, serverToken, "POST", "/publish", body) }) {
                            serverStatus = it; status = it
                        }
                    }
                )

                else -> SettingsPage(
                    apiKey, { apiKey = it },
                    baseUrl, { baseUrl = it },
                    auto, { auto = it },
                    onSave = { savePrefs() },
                    onTest = {
                        if (apiKey.isBlank()) status = "API Key kosong."
                        else apiCall({ openRouterGet(baseUrl, apiKey, "/models") }) { status = "OpenRouter OK." }
                    }
                )
            }
            StatusBar(status, error)
        }
    }

    if (addFileDialog) {
        AlertDialog(
            onDismissRequest = { addFileDialog = false },
            title = { Text("Tambah File") },
            text = { OutlinedTextField(newFile, { newFile = it }, label = { Text("contoh: css/style.css") }) },
            confirmButton = {
                TextButton(onClick = {
                    val n = newFile.trim()
                    if (n.isBlank() || files.any { it.path == n }) status = "Nama file kosong atau sudah ada."
                    else { files = files + FileItem(n, ""); selectedFile = n; newFile = ""; addFileDialog = false; persistFiles(); status = "$n dibuat." }
                }) { Text("Buat") }
            },
            dismissButton = { TextButton(onClick = { addFileDialog = false }) { Text("Batal") } }
        )
    }
}


private fun buildSystemPrompt(): String = """
Kamu adalah Arif Build AI V4.4, mesin pembuat website/app.
Jangan menjawab dengan penjelasan panjang. Tugasmu adalah mengubah workspace berdasarkan instruksi user.
Kembalikan SATU JSON VALID saja, tanpa markdown/fence, dengan format:
{
  "message":"ringkasan singkat",
  "files":[{"path":"index.html","content":"..."}],
  "delete":["path/yang/dihapus"]
}
Aturan:
1. files berisi file BARU atau file yang harus DIUBAH secara lengkap.
2. Jika file tidak perlu berubah, jangan kirim ulang.
3. Gunakan path relatif aman tanpa .., tanpa / di awal.
4. Untuk website multi-file, buat HTML/CSS/JS lengkap dan pastikan link relatif benar.
5. Jangan menghapus file kecuali diminta atau benar-benar diperlukan.
6. Jangan mengarang requirement yang tidak diminta.
""".trimIndent()

private fun parseBuildResult(raw: String): BuildResult {
    val cleaned = raw.trim().removePrefix("````json").removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = cleaned.indexOf('{')
    val end = cleaned.lastIndexOf('}')
    if (start < 0 || end <= start) throw IllegalArgumentException("JSON build tidak ditemukan")
    val o = JSONObject(cleaned.substring(start, end + 1))
    val files = mutableListOf<FileItem>()
    val arr = o.optJSONArray("files") ?: JSONArray()
    for (i in 0 until arr.length()) {
        val f = arr.getJSONObject(i)
        val path = f.optString("path").trim()
        if (path.isNotBlank() && path.isSafeProjectPath()) files.add(FileItem(path, f.optString("content")))
    }
    val deletes = mutableListOf<String>()
    val del = o.optJSONArray("delete") ?: JSONArray()
    for (i in 0 until del.length()) {
        val path = del.optString(i).trim()
        if (path.isNotBlank() && path.isSafeProjectPath()) deletes.add(path)
    }
    return BuildResult(o.optString("message", "Build diterapkan."), files, deletes)
}

private fun writePreview(context: Context, files: List<FileItem>): java.io.File {
    val root = java.io.File(context.cacheDir, "arifbuild-preview")
    if (root.exists()) root.deleteRecursively()
    root.mkdirs()
    files.forEach { item ->
        if (!item.path.isSafeProjectPath()) return@forEach
        val target = java.io.File(root, item.path)
        target.parentFile?.mkdirs()
        target.writeText(item.text, Charsets.UTF_8)
    }
    if (!java.io.File(root, "index.html").exists()) java.io.File(root, "index.html").writeText("<h1>index.html belum ada</h1>", Charsets.UTF_8)
    return root
}

@Composable
fun BuildPage(prompt: String, onPrompt: (String) -> Unit, log: List<String>, busy: Boolean, model: String, onBuild: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CardBox {
            Column(Modifier.padding(16.dp)) {
                Text("AI Build Engine", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Instruksi → AI mengubah file project → Preview", color = Muted)
                Spacer(Modifier.height(8.dp))
                Text("Model: ${model.substringAfterLast('/')}", style = MaterialTheme.typography.labelMedium, color = Accent)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(prompt, onPrompt, Modifier.fillMaxWidth().heightIn(min = 150.dp), minLines = 6, label = { Text("Apa yang harus dibangun/diubah?") }, placeholder = { Text("Contoh: Buat landing page toko keramik modern dengan hero, produk, WhatsApp CTA dan responsive mobile.") })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onBuild, enabled = prompt.isNotBlank() && !busy) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text(if (busy) "MEMBANGUN..." else "BUILD DENGAN AI") }
                    OutlinedButton(onClick = onClear, enabled = !busy) { Text("Bersihkan Log") }
                }
            }
        }
        CardBox {
            Column(Modifier.padding(16.dp)) {
                Text("Build Log", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (log.isEmpty()) Text("Belum ada proses build.", color = Muted)
                else log.takeLast(80).forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
    }
}

@Composable
fun PreviewPage(context: Context, files: List<FileItem>, refreshKey: Int, onRefresh: () -> Unit) {
    val root = remember(refreshKey, files) { writePreview(context, files) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Live Preview", fontWeight = FontWeight.Bold); Text("${files.size} file · index.html", color = Muted, style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Refresh") }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; webViewClient = WebViewClient() } },
            update = { web -> web.loadUrl(java.io.File(root, "index.html").toURI().toString()) }
        )
    }
}

@Composable
fun StatusBar(status: String, error: String?) {
    Surface(tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(status, style = MaterialTheme.typography.labelSmall, color = if (error == null) Muted else MaterialTheme.colorScheme.error)
            error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        content = content
    )
}

@Composable
fun ChatPage(
    messages: List<Msg>, model: String, auto: Boolean, busy: Boolean,
    onAuto: () -> Unit, onSend: (String) -> Unit, onClear: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        CardBox {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Build Engine", fontWeight = FontWeight.Bold)
                    Text("Model: ${model.substringAfterLast("/")}", color = Muted)
                }
                AssistChip(onClick = onAuto, label = { Text(if (auto) "AUTO ON" else "AUTO OFF") }, leadingIcon = { Icon(Icons.Default.Bolt, null) })
            }
        }
        Row(Modifier.padding(horizontal = 16.dp)) { OutlinedButton(onClick = onClear) { Text("Hapus Percakapan") } }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
            if (messages.isEmpty()) item {
                CardBox {
                    Column(Modifier.padding(20.dp)) {
                        Text("Bangun website atau aplikasi", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Discovery → Planning → Build → Review. Ceritakan kebutuhan Anda.", color = Muted)
                    }
                }
            }
            items(messages) {
                Row(Modifier.fillMaxWidth().padding(5.dp), horizontalArrangement = if (it.role == "user") Arrangement.End else Arrangement.Start) {
                    Surface(color = if (it.role == "user") Soft else Color.White, shape = MaterialTheme.shapes.large) {
                        Text(it.text, Modifier.padding(13.dp).widthIn(max = 350.dp))
                    }
                }
            }
        }
        CardBox {
            OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, placeholder = { Text("Jelaskan website/app yang ingin dibuat...") })
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (busy) "Menghubungi AI..." else "Siap", Modifier.weight(1f), color = Muted)
                FilledIconButton(onClick = { onSend(input); input = "" }, enabled = input.isNotBlank() && !busy) { Icon(Icons.Default.Send, "Kirim") }
            }
        }
    }
}

@Composable
fun ModelPage(
    selected: String, models: List<RemoteModel>, hasKey: Boolean, busy: Boolean,
    filter: String, tierFilter: String, onFilter: (String) -> Unit, onTier: (String) -> Unit,
    onLoad: () -> Unit, onPick: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CardBox {
            Column(Modifier.padding(16.dp)) {
                Text("OpenRouter Models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Model dipilih manual. Tidak ada AUTO model selection.", color = Muted)
                Spacer(Modifier.height(8.dp))
                Text("Aktif: $selected", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onLoad, enabled = hasKey && !busy) { Text("Muat Model OpenRouter") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(filter, onFilter, Modifier.fillMaxWidth(), label = { Text("Cari model") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ALL","FREE","HEMAT","SEDANG","PRO").forEach { t ->
                        FilterChip(selected = tierFilter == t, onClick = { onTier(t) }, label = { Text(t) })
                    }
                }
            }
        }
        if (models.isEmpty()) {
            CardBox { Text(if (hasKey) "Belum dimuat." else "Masukkan API Key di Settings.", Modifier.padding(16.dp), color = Muted) }
        }
        models.filter { 
            (tierFilter == "ALL" || tier(it.id) == tierFilter) &&
            (filter.isBlank() || it.id.contains(filter, true) || it.name.contains(filter, true))
        }.groupBy { tier(it.id) }.forEach { (t, list) ->
            Text(t, Modifier.padding(start = 20.dp, top = 10.dp), color = Accent, fontWeight = FontWeight.Bold)
            list.take(30).forEach { m ->
                CardBox {
                    Column(Modifier.padding(14.dp)) {
                        Text(m.name.ifBlank { m.id }, fontWeight = FontWeight.SemiBold)
                        Text(m.id, color = Muted, style = MaterialTheme.typography.labelSmall)
                        Text("Context: ${m.context} · Input: ${m.input} · Output: ${m.output}", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { onPick(m.id) }) { Text("Gunakan model") }
                    }
                }
            }
        }
    }
}

@Composable
fun ProjectPage(
    files: List<FileItem>, selected: String,
    onSelect: (String) -> Unit, onSave: (String, String) -> Unit,
    onAdd: () -> Unit, onImport: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit
) {
    var editor by remember(selected, files) { mutableStateOf(files.firstOrNull { it.path == selected }?.text ?: "") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CardBox {
            Column(Modifier.padding(16.dp)) {
                Text("Project Workspace", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${files.size} file", color = Muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onAdd) { Text("Tambah") }
                    OutlinedButton(onClick = onImport) { Text("Import ZIP") }
                    OutlinedButton(onClick = onDelete) { Text("Hapus") }
                    OutlinedButton(onClick = onExport) { Text("Export ZIP") }
                }
                files.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { onSelect(f.path) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, tint = Accent)
                        Spacer(Modifier.width(8.dp))
                        Text(f.path, fontWeight = if (f.path == selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        CardBox {
            Column(Modifier.padding(16.dp)) {
                Text("Editor: $selected", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(editor, { editor = it }, Modifier.fillMaxWidth().heightIn(min = 300.dp), minLines = 14)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onSave(selected, editor) }) { Text("Simpan Perubahan") }
            }
        }
    }
}

@Composable
fun CloudPage(
    server: String, setServer: (String) -> Unit,
    token: String, setToken: (String) -> Unit,
    projectId: String, setProjectId: (String) -> Unit,
    status: String, busy: Boolean, files: List<FileItem>,
    onTest: () -> Unit, onSave: () -> Unit, onLoad: () -> Unit,
    onDelete: () -> Unit, onPublish: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CardBox {
            Column(Modifier.padding(16.dp)) {
                Text("Deploy Center", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Android → Backend Arif Build → Hosting.", color = Muted)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(server, setServer, Modifier.fillMaxWidth(), label = { Text("Server API URL") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(token, setToken, Modifier.fillMaxWidth(), label = { Text("Access Token") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(projectId, setProjectId, Modifier.fillMaxWidth(), label = { Text("Project ID") })
                Spacer(Modifier.height(8.dp))
                Text("Local project: ${files.size} file · Status: $status", color = Muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onTest, enabled = !busy) { Text("TEST") }
                    Button(onClick = onSave, enabled = !busy) { Text("SIMPAN") }
                }
            }
        }
        CardBox {
            Column(Modifier.padding(16.dp)) {
                Text("Deployment", fontWeight = FontWeight.Bold)
                Text("Publish sebaiknya melakukan backup dan verifikasi di backend.", color = Muted)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onPublish, enabled = !busy) { Text("PUBLISH KE HOSTING") }
            }
        }
        CardBox {
            Column(Modifier.padding(16.dp)) {
                Text("Server Project", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onLoad, enabled = !busy) { Text("MUAT PROJECT") }
                OutlinedButton(onClick = onDelete, enabled = !busy) { Text("HAPUS PROJECT SERVER") }
            }
        }
    }
}

@Composable
fun SettingsPage(
    key: String, setKey: (String) -> Unit,
    base: String, setBase: (String) -> Unit,
    auto: Boolean, setAuto: (Boolean) -> Unit,
    onSave: () -> Unit, onTest: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CardBox {
            Column(Modifier.padding(16.dp)) {
                Text("OpenRouter", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(key, setKey, Modifier.fillMaxWidth(), label = { Text("API Key") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(base, setBase, Modifier.fillMaxWidth(), label = { Text("Base URL") })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onSave) { Text("SIMPAN") }
                    OutlinedButton(onClick = onTest) { Text("TEST CONNECTION") }
                }
            }
        }
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(auto, setAuto)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Auto Reply / Auto Build")
                    Text(if (auto) "ON — gunakan dengan sadar." else "OFF — default.", color = Muted)
                }
            }
        }
        CardBox {
            Column(Modifier.padding(16.dp)) {
                Text("V4.3 Hardening", fontWeight = FontWeight.Bold)
                Text("Settings dan koneksi dasar disimpan lokal. API key tidak dikirim ke GitHub.", color = Muted)
            }
        }
    }
}

fun String.isSafeProjectPath(): Boolean {
    val p = replace('\\', '/')
    return p.isNotBlank() && !p.startsWith("/") && !p.contains("../") && !p.contains("/..") && !p.contains(":")
}

fun loadLocalFiles(prefs: Prefs): List<FileItem> {
    return try {
        val raw = prefs.get("localFilesJson")
        if (raw.isBlank()) emptyList()
        else {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(FileItem(o.optString("path"), o.optString("content")))
                }
            }
        }
    } catch (_: Exception) { emptyList() }
}

fun saveLocalFiles(prefs: Prefs, files: List<FileItem>) {
    val arr = JSONArray()
    files.forEach { arr.put(JSONObject().put("path", it.path).put("content", it.text)) }
    prefs.put("localFilesJson", arr.toString())
}

suspend fun openRouter(base: String, key: String, body: String): String =
    withContext(Dispatchers.IO) {
        val c = URL(base.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 120000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("HTTP-Referer", "https://arifbuild.local")
        c.setRequestProperty("X-Title", "Arif Build AI")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IllegalStateException("OpenRouter HTTP $code: ${raw.take(500)}")
        JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?: "AI tidak mengembalikan content."
    }

suspend fun openRouterGet(base: String, key: String, path: String): String =
    withContext(Dispatchers.IO) {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Accept", "application/json")
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IllegalStateException("OpenRouter HTTP $code: ${raw.take(500)}")
        raw
    }

fun parseModels(raw: String): List<RemoteModel> {
    val data = JSONObject(raw).optJSONArray("data") ?: return emptyList()
    return buildList {
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val pricing = o.optJSONObject("pricing")
            add(RemoteModel(
                o.optString("id"),
                o.optString("name"),
                o.optLong("context_length"),
                pricing?.optString("prompt", "—") ?: "—",
                pricing?.optString("completion", "—") ?: "—"
            ))
        }
    }
}

fun tier(id: String): String {
    val x = id.lowercase()
    return when {
        x.contains(":free") || x.contains("free") -> "FREE"
        x.contains("deepseek") || x.contains("flash") || x.contains("mini") -> "HEMAT"
        x.contains("gemini") || x.contains("qwen") || x.contains("sonnet") -> "SEDANG"
        else -> "PRO"
    }
}

suspend fun backendRequest(base: String, token: String, method: String, path: String, body: String? = null): String =
    withContext(Dispatchers.IO) {
        if (base.isBlank()) throw IllegalStateException("Server API URL belum diisi.")
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("Accept", "application/json")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IllegalStateException("Backend HTTP $code: ${raw.take(500)}")
        raw.ifBlank { "OK ($code)" }
    }
