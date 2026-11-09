package com.rescata.app

import android.Manifest
import android.app.Activity
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
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

class Item(val uri: Uri, val name: String, val mime: String, val size: Long) {
    var state = 0          // 0 pendiente, 1 guardando, 2 guardado, 3 error
    var out: Uri? = null
}

class MainActivity : Activity() {

    companion object {
        const val REQ_TREE = 1
        const val REQ_PICK = 2
        const val REQ_PERM = 3
    }

    private val items = ArrayList<Item>()
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var busy = false
    private var lastWhere = ""

    private lateinit var list: LinearLayout
    private lateinit var empty: View
    private lateinit var bar: View
    private lateinit var doneNote: TextView
    private lateinit var btnGallery: Button
    private lateinit var btnFolder: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        bar = findViewById(R.id.bar)
        doneNote = findViewById(R.id.doneNote)
        btnGallery = findViewById(R.id.btnGallery)
        btnFolder = findViewById(R.id.btnFolder)

        btnGallery.setOnClickListener { saveAll(null) }
        btnFolder.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
        }
        findViewById<Button>(R.id.btnSearch).setOnClickListener { openPicker() }

        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM)
        }

        handleIntent(intent)
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
            REQ_TREE -> data.data?.let { saveAll(it) }
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

    // ---------- Guardar ----------

    private fun saveAll(tree: Uri?) {
        if (busy) return
        val todo = items.filter { it.state != 2 }
        if (todo.isEmpty()) return
        busy = true
        lastWhere = if (tree == null)
            "¡Listo! Búscalo en tu galería, carpeta «Rescata»."
        else "¡Listo! Guardado en la carpeta que elegiste."
        for (t in todo) t.state = 1
        render()
        io.execute {
            var ok = 0
            for (t in todo) {
                try {
                    t.out = if (tree == null) saveToGallery(t) else saveToTree(t, tree)
                    t.state = 2
                    ok++
                } catch (e: Exception) {
                    t.state = 3
                }
            }
            ui.post {
                busy = false
                render()
                val msg = if (ok == todo.size) "Guardado ✓" else "No se pudieron guardar ${todo.size - ok} archivo(s)"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copy(from: Uri, to: Uri) {
        val ins = contentResolver.openInputStream(from) ?: throw IOException("sin lectura")
        val outs = contentResolver.openOutputStream(to) ?: throw IOException("sin escritura")
        ins.use { i -> outs.use { o -> i.copyTo(o) } }
    }

    private fun saveToTree(item: Item, tree: Uri): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(contentResolver, parent, item.mime, item.name)
            ?: throw IOException("no se pudo crear")
        copy(item.uri, doc)
        return doc
    }

    private fun saveToGallery(item: Item): Uri? {
        val m = item.mime
        val dir = when {
            m.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            m.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            m.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val collection = when {
                m.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                m.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                m.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val v = ContentValues()
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            v.put(MediaStore.MediaColumns.MIME_TYPE, m)
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Rescata")
            v.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val target = contentResolver.insert(collection, v) ?: throw IOException("MediaStore")
            try {
                copy(item.uri, target)
                val done = ContentValues()
                done.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(target, done, null, null)
                return target
            } catch (e: Exception) {
                contentResolver.delete(target, null, null)
                throw e
            }
        }

        // Android 8 y 9
        val folder = File(Environment.getExternalStoragePublicDirectory(dir), "Rescata")
        if (!folder.exists() && !folder.mkdirs()) throw IOException("carpeta")
        val clean = item.name.replace('/', '_')
        val base = clean.substringBeforeLast('.', clean)
        val ext = if (clean.contains('.')) "." + clean.substringAfterLast('.') else ""
        var f = File(folder, clean)
        var n = 1
        while (f.exists()) {
            f = File(folder, "$base ($n)$ext")
            n++
        }
        val ins = contentResolver.openInputStream(item.uri) ?: throw IOException("sin lectura")
        ins.use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
        MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf(m), null)
        return null
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

    private fun render() {
        list.removeAllViews()
        val inf = LayoutInflater.from(this)
        for (item in items) {
            val v = inf.inflate(R.layout.item_file, list, false)
            v.findViewById<TextView>(R.id.icon).text = emoji(item.mime)
            v.findViewById<TextView>(R.id.name).text = item.name
            val size = if (item.size >= 0) Formatter.formatShortFileSize(this, item.size) else ""
            v.findViewById<TextView>(R.id.meta).text =
                listOf(kind(item.mime), size).filter { it.isNotEmpty() }.joinToString(" · ")
            val st = v.findViewById<TextView>(R.id.status)
            when (item.state) {
                1 -> st.text = "Guardando…"
                2 -> {
                    st.text = "✓ Guardado"
                    st.setTextColor(getColor(R.color.ok))
                }
                3 -> {
                    st.text = "Error"
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
        doneNote.text = lastWhere
        btnGallery.isEnabled = !busy
        btnFolder.isEnabled = !busy
        bar.alpha = if (busy) 0.5f else 1f
    }
}
