
package com.sri2461.votersearch

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var db: VoterDb
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var results: LinearLayout
    private lateinit var progress: ProgressBar

    private val worker = Executors.newSingleThreadExecutor()

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmImport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = VoterDb(this)
        makeUi()
        showState()
    }

    private fun makeUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 20, 18, 10)
        }

        root.addView(TextView(this).apply {
            text = "Voter Search"
            textSize = 25f
            gravity = Gravity.CENTER
        })

        root.addView(Button(this).apply {
            text = "Import / Replace voter PDF"
            setOnClickListener {
                picker.launch(arrayOf("application/pdf"))
            }
        })

        input = EditText(this).apply {
            hint = "Search English / Telugu name, voter ID or serial"
            setSingleLine(true)
        }
        root.addView(input)

        root.addView(Button(this).apply {
            text = "Search"
            setOnClickListener { search() }
        })

        status = TextView(this)
        root.addView(status)

        progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress)

        val scroll = ScrollView(this)

        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    private fun showState() {
        val n = db.count()
        status.text = if (n > 0) {
            "$n card entries saved on this phone. Search offline."
        } else {
            "Select your voter PDF to import it."
        }
    }

    private fun confirmImport(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Import voter list?")
            .setMessage(
                "The selected PDF will be saved inside this app. " +
                    "Importing a new PDF will replace the currently saved list."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Import") { _, _ -> importPdf(uri) }
            .show()
    }

    private fun importPdf(uri: Uri) {
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Copying PDF. Keep app open…"

        worker.execute {
            var renderer: PdfRenderer? = null
            var pfd: ParcelFileDescriptor? = null
            var ocr: TessBaseAPI? = null

            try {
                val pdf = File(filesDir, "voter_source.pdf")

                contentResolver.openInputStream(uri).use { src ->
                    requireNotNull(src)
                    FileOutputStream(pdf).use { dst ->
                        src.copyTo(dst)
                    }
                }

                val td = File(filesDir, "tesseract/tessdata")
                td.mkdirs()

                downloadLang(td, "eng")
                downloadLang(td, "tel")

                ocr = TessBaseAPI()

                check(
                    ocr.init(
                        File(filesDir, "tesseract").absolutePath,
                        "eng+tel"
                    )
                ) {
                    "OCR initialization failed"
                }

                db.clear()

                pfd = ParcelFileDescriptor.open(
                    pdf,
                    ParcelFileDescriptor.MODE_READ_ONLY
                )

                renderer = PdfRenderer(pfd)

                val total = renderer.pageCount
                var serial = 0

                for (pi in 2 until total) {
                    val page = renderer.openPage(pi)

                    try {
                        val bmp = Bitmap.createBitmap(
                            page.width * 2,
                            page.height * 2,
                            Bitmap.Config.ARGB_8888
                        )

                        try {
                            page.render(
                                bmp,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                            )

                            val l = (bmp.width * 0.095f).roundToInt()
                            val r = (bmp.width * 0.91f).roundToInt()
                            val t = (bmp.height * 0.115f).roundToInt()
                            val b = (bmp.height * 0.915f).roundToInt()

                            val cw = (r - l) / 3
                            val ch = (b - t) / 9

                            for (row in 0..8) {
                                for (col in 0..2) {
                                    val x = l + col * cw
                                    val y = t + row * ch

                                    val rect = Rect(
                                        x,
                                        y,
                                        (x + cw).coerceAtMost(bmp.width),
                                        (y + ch).coerceAtMost(bmp.height)
                                    )

                                    if (rect.width() <= 0 ||
                                        rect.height() <= 0
                                    ) continue

                                    val card = Bitmap.createBitmap(
                                        bmp,
                                        rect.left,
                                        rect.top,
                                        rect.width(),
                                        rect.height()
                                    )

                                    try {
                                        ocr.setImage(card)

                                        val raw = ocr.getUTF8Text() ?: ""
                                        serial++

                                        val photo = File(
                                            filesDir,
                                            "cards/card_$serial.jpg"
                                        )

                                        photo.parentFile?.mkdirs()

                                        FileOutputStream(photo).use {
                                            card.compress(
                                                Bitmap.CompressFormat.JPEG,
                                                78,
                                                it
                                            )
                                        }

                                        db.insert(
                                            serial,
                                            pi + 1,
                                            raw.replace(
                                                Regex("\\s+"),
                                                " "
                                            ).trim(),
                                            photo.absolutePath
                                        )
                                    } finally {
                                        card.recycle()
                                    }
                                }
                            }

                            val pct = (
                                ((pi - 1).toFloat() /
                                    (total - 2).coerceAtLeast(1)) * 100
                            ).roundToInt()

                            runOnUiThread {
                                progress.progress = pct
                                status.text =
                                    "Processing page ${pi + 1}/$total • $serial cards"
                            }
                        } finally {
                            bmp.recycle()
                        }
                    } finally {
                        page.close()
                    }
                }

                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text =
                        "${db.count()} card entries saved. Search is available offline."

                    Toast.makeText(
                        this,
                        "Import complete",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text =
                        "Import failed: ${e.message}. Check connection and try again."
                }
            } finally {
                try {
                    ocr?.end()
                } catch (_: Exception) {
                }

                try {
                    renderer?.close()
                } catch (_: Exception) {
                }

                try {
                    pfd?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun downloadLang(dir: File, lang: String) {
        val f = File(dir, "$lang.traineddata")

        if (f.exists() && f.length() > 100000) return

        URL(
            "https://github.com/tesseract-ocr/tessdata_fast/raw/main/$lang.traineddata"
        ).openConnection().apply {
            connectTimeout = 20000
            readTimeout = 60000
        }.getInputStream().use { src ->
            FileOutputStream(f).use {
                src.copyTo(it)
            }
        }

        check(f.length() > 100000) {
            "Unable to get $lang OCR data"
        }
    }

    private fun search() {
        val q = input.text.toString().trim()
        results.removeAllViews()

        if (q.isEmpty()) {
            status.text = "Enter a search term."
            return
        }

        val rows = db.search(q)

        status.text =
            "${rows.size} matching cards. Duplicate names are listed separately."

        rows.forEach { v ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(3, 10, 3, 10)
            }

            val photo = ImageView(this).apply {
                val f = File(v.image)

                if (f.exists()) {
                    setImageURI(Uri.fromFile(f))
                }

                scaleType = ImageView.ScaleType.CENTER_CROP
            }

            row.addView(photo, LinearLayout.LayoutParams(110, 130))

            val details = TextView(this).apply {
                text = "Entry ${v.serial} • PDF page ${v.page}\n${v.text}"
                textSize = 14f
                setPadding(10, 3, 3, 3)
            }

            row.addView(
                details,
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            results.addView(row)

            results.addView(
                View(this).apply {
                    setBackgroundColor(0xFFDDDDDD.toInt())
                },
                LinearLayout.LayoutParams(-1, 1)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}

data class VoterRow(
    val serial: Int,
    val page: Int,
    val text: String,
    val image: String
)

class VoterDb(
    private val appContext: android.content.Context
) : android.database.sqlite.SQLiteOpenHelper(
    appContext,
    "voters.db",
    null,
    1
) {

    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE voters(serial INTEGER PRIMARY KEY, page INTEGER, ocr TEXT, image TEXT)"
        )

        db.execSQL("CREATE INDEX voter_text_idx ON voters(ocr)")
    }

    override fun onUpgrade(
        db: android.database.sqlite.SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
    }

    fun clear() {
        writableDatabase.delete("voters", null, null)
        File(appContext.filesDir, "cards").deleteRecursively()
    }

    fun count(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM voters",
            null
        ).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun insert(s: Int, p: Int, t: String, i: String) {
        val values = android.content.ContentValues().apply {
            put("serial", s)
            put("page", p)
            put("ocr", t)
            put("image", i)
        }

        writableDatabase.insert("voters", null, values)
    }

    fun search(q: String): List<VoterRow> {
        val out = mutableListOf<VoterRow>()

        readableDatabase.rawQuery(
            "SELECT serial,page,ocr,image FROM voters WHERE ocr LIKE ? ORDER BY serial LIMIT 500",
            arrayOf("%$q%")
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    VoterRow(
                        c.getInt(0),
                        c.getInt(1),
                        c.getString(2) ?: "",
                        c.getString(3) ?: ""
                    )
                )
            }
        }

        return out
    }
}
