package com.rescata.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

class Item(val uri: Uri, val name: String, val mime: String, val size: Long) {
    var state = 0          // 0 pendiente, 1 trabajando, 2 listo, 3 error
    var out: Uri? = null   // dónde quedó (para abrirlo)
    var note = ""          // Movido / Copiado / Ya estaba / error
    var place = ""         // carpeta final
}

class MainActivity : Activity() {

    companion object {
        const val REQ_TREE = 1
        const val REQ_PICK = 2
        const val REQ_PERM = 3
        const val MARGIN = 50L * 1024 * 1024
    }

    private val items = ArrayList<Item>()
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var busy = false

    private lateinit var list: LinearLayout
    private lateinit var empty: View
    private lateinit var bar: View
    private lateinit var permCard: View
    private lateinit var doneNote: TextView
    private lateinit var btnGallery: Button
    private lateinit var btnFolder: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        bar = findViewById(R.id.bar)
        permCard = findViewById(R.id.permCard)
        doneNote = findViewById(R.id.doneNote)
        btnGallery = findViewById(R.id.btnGallery)
        btnFolder = findViewById(R.id.btnFolder)

        btnGallery.setOnClickListener { saveAll(null, null) }
        btnFolder.setOnClickListener {
            if (canMove()) chooseFolder()
            else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
        }
        findViewById<Button>(R.id.btnSearch).setOnClickListener { openPicker() }
        findViewById<Button>(R.id.btnPerm).setOnClickListener { requestAccess() }

        if (!canMove()) requestAccess()
        handleIntent(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ---------- Permisos ----------

    private fun canMove(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                }
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_PERM
            )
        }
    }

    // ---------- Recibir archivos ----------

    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = ArrayList<Uri>()
        if (i.action == Intent.ACTION_SEND) {
            val u: Uri? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra(Intent.EXTRA_STREAM)
            if (u != null) uris.add(u)
        } else {
            val l: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            if (l != null) uris.addAll(l)
        }
        if (uris.isEmpty()) {
            val cd = i.clipData
            if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) }
        }
        addUris(uris)
    }

    private fun addUris(uris: List<Uri>) {
        for (u in uris) if (items.none { it.uri == u }) items.add(describe(u))
    }

    private fun describe(uri: Uri): Item {
        var name = "archivo"
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (e: Exception) {
        }
        var mime = contentResolver.getType(uri) ?: ""
        if (mime.isEmpty() || mime == "application/octet-stream") {
            val ext = name.substringAfterLast('.', "").lowercase()
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        return Item(uri, name, mime, size)
    }

    private fun openPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            i.putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"
                )
            )
        } catch (e: Exception) {
        }
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_TREE -> data.data?.let { saveAll(null, it) }
            REQ_PICK -> {
                val found = ArrayList<Uri>()
                val cd = data.clipData
                if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { found.add(it) }
                if (found.isEmpty()) data.data?.let { found.add(it) }
                addUris(found)
                render()
            }
        }
    }

    // ---------- Elegir carpeta ----------

    private fun chooseFolder() {
        val root = Environment.getExternalStorageDirectory()
        val opts = ArrayList<File>()
        for (base in listOf("Movies", "Pictures", "DCIM", "Download", "Music")) {
            val b = File(root, base)
            if (!b.isDirectory) continue
            opts.add(b)
            b.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?.let { opts.addAll(it) }
        }
        val labels = ArrayList<String>()
        for (f in opts) labels.add(rel(f))
        labels.add("➕  Carpeta nueva…")
        AlertDialog.Builder(this)
            .setTitle("¿Dónde lo guardo?")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < opts.size) saveAll(opts[which], null) else newFolderDialog()
            }
            .show()
    }

    private fun newFolderDialog() {
        val input = EditText(this)
        input.hint = "Nombre de la carpeta"
        AlertDialog.Builder(this)
            .setTitle("Carpeta nueva")
            .setView(input)
            .setPositiveButton("Crear y guardar") { _, _ ->
                val n = input.text.toString().trim().replace('/', '_')
                val first = items.firstOrNull { it.state != 2 }
                if (n.isNotEmpty() && first != null) {
                    saveAll(File(Environment.getExternalStoragePublicDirectory(baseDir(first.mime)), n), null)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Mover / guardar ----------

    private fun saveAll(dest: File?, tree: Uri?) {
        if (busy) return
        val todo = items.filter { it.state != 2 }
        if (todo.isEmpty()) return
        busy = true
        for (t in todo) {
            t.state = 1
            t.note = ""
        }
        render()
        io.execute {
            var ok = 0
            for (t in todo) {
                try {
                    process(t, dest, tree)
                    t.state = 2
                    ok++
                } catch (e: Exception) {
                    t.state = 3
                    t.note = if (e.message == "Sin espacio") "Sin espacio" else "Error"
                }
            }
            ui.post {
                busy = false
                render()
                val msg = if (ok == todo.size) "Listo ✓" else "Falló ${todo.size - ok} archivo(s)"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun process(item: Item, dest: File?, tree: Uri?) {
        if (tree != null) {
            item.out = saveToTree(item, tree)
            item.note = "Copiado"
            item.place = "Carpeta elegida"
            return
        }
        if (canMove()) {
            val dir = dest ?: File(Environment.getExternalStoragePublicDirectory(baseDir(item.mime)), "Rescata")
            item.place = rel(dir)
            val src = findSource(item)
            if (src != null) moveFile(item, src, dir) else copyToFile(item, dir)
            return
        }
        if (Build.VERSION.SDK_INT >= 29) saveToMediaStore(item) else throw IOException("Sin permiso")
    }

    private fun baseDir(m: String): String = when {
        m.startsWith("video/") -> Environment.DIRECTORY_MOVIES
        m.startsWith("image/") -> Environment.DIRECTORY_PICTURES
        m.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
        else -> Environment.DIRECTORY_DOWNLOADS
    }

    private fun rel(f: File): String =
        f.absolutePath.removePrefix(Environment.getExternalStorageDirectory().absolutePath + "/")

    // Busca el archivo real que WhatsApp ya descargó (mismo nombre y tamaño)
    private fun findSource(item: Item): File? {
        val r = Environment.getExternalStorageDirectory()
        val roots = listOf(
            "Android/media/com.whatsapp/WhatsApp/Media",
            "WhatsApp/Media",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
            "WhatsApp Business/Media"
        ).map { File(r, it) }.filter { it.isDirectory }
        val sizeOnly = ArrayList<File>()
        for (dir in roots) {
            val hit = walk(dir, 4, item, sizeOnly)
            if (hit != null) return hit
        }
        return if (sizeOnly.size == 1) sizeOnly[0] else null
    }

    private fun walk(dir: File, depth: Int, item: Item, sizeOnly: MutableList<File>): File? {
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (!f.isFile) continue
            val sameSize = item.size < 0 || f.length() == item.size
            if (f.name == item.name && sameSize) return f
            if (item.size > 0 && f.length() == item.size && f.path.contains("Documents")) sizeOnly.add(f)
        }
        if (depth > 0) {
            for (f in files) {
                if (f.isDirectory && !f.name.startsWith(".")) {
                    val hit = walk(f, depth - 1, item, sizeOnly)
                    if (hit != null) return hit
                }
            }
        }
        return null
    }

    private fun moveFile(item: Item, src: File, dir: File) {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("carpeta")
        var target = File(dir, src.name)
        if (src.absolutePath == target.absolutePath ||
            (target.exists() && target.length() == src.length())
        ) {
            item.note = "Ya estaba"
            scan(item, target)
            return
        }
        if (target.exists()) target = uniqueFile(dir, src.name)

        if (src.renameTo(target)) {
            item.note = "Movido"        // mismo disco: instantáneo, sin gastar espacio
        } else {
            checkSpace(src.length())    // otro disco: copiar y borrar el original
            try {
                FileInputStream(src).use { i -> FileOutputStream(target).use { o -> i.copyTo(o) } }
            } catch (e: Exception) {
                target.delete()
                throw e
            }
            if (target.length() != src.length()) {
                target.delete()
                throw IOException("incompleto")
            }
            item.note = if (src.delete()) "Movido" else "Copiado"
        }
        scan(item, target)
    }

    private fun copyToFile(item: Item, dir: File) {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("carpeta")
        var f = File(dir, item.name.replace('/', '_'))
        if (f.exists()) {
            if (item.size > 0 && f.length() == item.size) {
                item.note = "Ya estaba"
                scan(item, f)
                return
            }
            f = uniqueFile(dir, f.name)
        }
        checkSpace(item.size)
        val ins = contentResolver.openInputStream(item.uri) ?: throw IOException("sin lectura")
        try {
            ins.use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
        } catch (e: Exception) {
            f.delete()
            throw e
        }
        item.note = "Copiado"
        scan(item, f)
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 1
        var f = File(dir, "$base ($n)$ext")
        while (f.exists()) {
            n++
            f = File(dir, "$base ($n)$ext")
        }
        return f
    }

    private fun checkSpace(need: Long) {
        if (need <= 0) return
        val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        if (free < need + MARGIN) throw IOException("Sin espacio")
    }

    private fun scan(item: Item, f: File) {
        MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf(item.mime)) { _, uri ->
            ui.post {
                item.out = uri
                render()
            }
        }
    }

    // Sin "todos los archivos": copia por MediaStore (evita duplicar si ya existe)
    private fun saveToMediaStore(item: Item) {
        val m = item.mime
        val dir = baseDir(m)
        val collection = when {
            m.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            m.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            m.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        item.place = "$dir/Rescata"

        if (item.size > 0) {
            contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}=?",
                arrayOf(item.name, item.size.toString()),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    item.out = ContentUris.withAppendedId(collection, c.getLong(0))
                    item.note = "Ya estaba"
                    return
                }
            }
        }
        checkSpace(item.size)

        val v = ContentValues()
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
        v.put(MediaStore.MediaColumns.MIME_TYPE, m)
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Rescata")
        v.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val target = contentResolver.insert(collection, v) ?: throw IOException("MediaStore")
        try {
            val ins = contentResolver.openInputStream(item.uri) ?: throw IOException("sin lectura")
            val outs = contentResolver.openOutputStream(target) ?: throw IOException("sin escritura")
            ins.use { i -> outs.use { o -> i.copyTo(o) } }
            val done = ContentValues()
            done.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(target, done, null, null)
        } catch (e: Exception) {
            contentResolver.delete(target, null, null)
            throw e
        }
        item.out = target
        item.note = "Copiado"
    }

    private fun saveToTree(item: Item, tree: Uri): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(contentResolver, parent, item.mime, item.name)
            ?: throw IOException("no se pudo crear")
        val ins = contentResolver.openInputStream(item.uri) ?: throw IOException("sin lectura")
        val outs = contentResolver.openOutputStream(doc) ?: throw IOException("sin escritura")
        ins.use { i -> outs.use { o -> i.copyTo(o) } }
        return doc
    }

    private fun openSaved(item: Item) {
        val u = item.out ?: return
        try {
            val v = Intent(Intent.ACTION_VIEW)
            v.setDataAndType(u, item.mime)
            v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(v)
        } catch (e: Exception) {
            Toast.makeText(this, "No hay una app para abrirlo", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Pantalla ----------

    private fun emoji(m: String) = when {
        m.startsWith("video/") -> "🎬"
        m.startsWith("image/") -> "🖼️"
        m.startsWith("audio/") -> "🎵"
        else -> "📄"
    }

    private fun kind(m: String) = when {
        m.startsWith("video/") -> "Video"
        m.startsWith("image/") -> "Imagen"
        m.startsWith("audio/") -> "Audio"
        else -> "Archivo"
    }

    private fun summary(): String {
        val moved = items.count { it.note == "Movido" }
        val copied = items.count { it.note == "Copiado" }
        return when {
            copied > 0 -> "Listo. Se copió (ocupa espacio doble). Toca el archivo para verlo."
            moved > 0 -> "¡Listo! Se movió sin duplicar. Toca el archivo para verlo."
            else -> "Ya estaba guardado. Toca el archivo para verlo."
        }
    }

    private fun render() {
        val can = canMove()
        permCard.visibility = if (can) View.GONE else View.VISIBLE
        btnGallery.text = if (can) "Mover a la galería" else "Copiar a la galería (usa el doble de espacio)"

        list.removeAllViews()
        val inf = LayoutInflater.from(this)
        for (item in items) {
            val v = inf.inflate(R.layout.item_file, list, false)
            v.findViewById<TextView>(R.id.icon).text = emoji(item.mime)
            v.findViewById<TextView>(R.id.name).text = item.name
            val size = if (item.size >= 0) Formatter.formatShortFileSize(this, item.size) else ""
            val where = if (item.state == 2) item.place else ""
            v.findViewById<TextView>(R.id.meta).text =
                listOf(kind(item.mime), size, where).filter { it.isNotEmpty() }.joinToString(" · ")
            val st = v.findViewById<TextView>(R.id.status)
            when (item.state) {
                1 -> st.text = "Trabajando…"
                2 -> {
                    st.text = "✓ " + item.note
                    st.setTextColor(getColor(R.color.ok))
                }
                3 -> {
                    st.text = item.note.ifEmpty { "Error" }
                    st.setTextColor(0xFFC0392B.toInt())
                }
            }
            if (item.state == 2 && item.out != null) v.setOnClickListener { openSaved(item) }
            list.addView(v)
        }
        val pending = items.any { it.state != 2 }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        bar.visibility = if (pending) View.VISIBLE else View.GONE
        val allDone = items.isNotEmpty() && !pending
        doneNote.visibility = if (allDone) View.VISIBLE else View.GONE
        if (allDone) doneNote.text = summary()
        btnGallery.isEnabled = !busy
        btnFolder.isEnabled = !busy
        bar.alpha = if (busy) 0.5f else 1f
    }
}
