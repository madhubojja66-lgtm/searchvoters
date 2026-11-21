
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
            setOnClickListener {
                search()
            }
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
        val count = db.count()

        status.text = if (count > 0) {
            "$count card entries saved on this phone. Search offline."
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
            .setPositiveButton("Import") { _, _ ->
                importPdf(uri)
            }
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
                // Copy selected PDF into app storage
                val pdf = File(filesDir, "voter_source.pdf")

                contentResolver.openInputStream(uri).use { source ->
                    requireNotNull(source) {
                        "Unable to open selected PDF"
                    }

                    FileOutputStream(pdf).use { destination ->
                        source.copyTo(destination)
                    }
                }

                // Create Tesseract directories
                val tessFolder = File(filesDir, "tesseract")
                val tessDataFolder = File(tessFolder, "tessdata")

                tessDataFolder.mkdirs()

                // Download English and Telugu OCR data
                statusUpdate("Downloading English OCR data...")

                downloadLang(tessDataFolder, "eng")

                statusUpdate("Downloading Telugu OCR data...")

                downloadLang(tessDataFolder, "tel")

                // Validate OCR files
                val engFile = File(tessDataFolder, "eng.traineddata")
                val telFile = File(tessDataFolder, "tel.traineddata")

                check(engFile.exists() && engFile.length() > 100000) {
                    "English OCR data is missing or invalid"
                }

                check(telFile.exists() && telFile.length() > 100000) {
                    "Telugu OCR data is missing or invalid"
                }

                // Initialize Tesseract
                statusUpdate("Initializing OCR engine...")

                val tess = TessBaseAPI()
                ocr = tess

                val initialized = tess.init(
                    tessFolder.absolutePath + File.separator,
                    "eng+tel"
                )

                check(initialized) {
                    "Tesseract could not load English and Telugu language data."
                }

                // Clear previous voter records only after OCR initialization
                db.clear()

                // Open PDF
                pfd = ParcelFileDescriptor.open(
                    pdf,
                    ParcelFileDescriptor.MODE_READ_ONLY
                )

                renderer = PdfRenderer(pfd!!)

                val totalPages = renderer!!.pageCount
                var serial = 0

                // Process PDF pages starting from page 3
                for (pi in 2 until totalPages) {

                    val page = renderer!!.openPage(pi)

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

                            // 3 columns x 9 rows
                            val left = (bmp.width * 0.095f).roundToInt()
                            val right = (bmp.width * 0.91f).roundToInt()
                            val top = (bmp.height * 0.115f).roundToInt()
                            val bottom = (bmp.height * 0.915f).roundToInt()

                            val cellWidth = (right - left) / 3
                            val cellHeight = (bottom - top) / 9

                            for (row in 0..8) {
                                for (col in 0..2) {

                                    val x = left + col * cellWidth
                                    val y = top + row * cellHeight

                                    val rect = Rect(
                                        x,
                                        y,
                                        (x + cellWidth).coerceAtMost(bmp.width),
                                        (y + cellHeight).coerceAtMost(bmp.height)
                                    )

                                    if (rect.width() <= 0 ||
                                        rect.height() <= 0
                                    ) {
                                        continue
                                    }

                                    val card = Bitmap.createBitmap(
                                        bmp,
                                        rect.left,
                                        rect.top,
                                        rect.width(),
                                        rect.height()
                                    )

                                    try {
                                        tess.setImage(card)

                                        val rawText = tess.getUTF8Text() ?: ""

                                        serial++

                                        val photo = File(
                                            filesDir,
                                            "cards/card_$serial.jpg"
                                        )

                                        photo.parentFile?.mkdirs()

                                        FileOutputStream(photo).use { output ->
                                            card.compress(
                                                Bitmap.CompressFormat.JPEG,
                                                78,
                                                output
                                            )
                                        }

                                        db.insert(
                                            serial,
                                            pi + 1,
                                            rawText.replace(
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

                            val percentage = (
                                ((pi - 1).toFloat() /
                                    (totalPages - 2).coerceAtLeast(1)) * 100
                            ).roundToInt()

                            runOnUiThread {
                                progress.progress = percentage

                                status.text =
                                    "Processing page ${pi + 1}/$totalPages • $serial cards"
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
                        "Import failed: ${e.message}"
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

    private fun statusUpdate(message: String) {
        runOnUiThread {
            status.text = message
        }
    }

    // Download Tesseract language data
    private fun downloadLang(dir: File, lang: String) {

        dir.mkdirs()

        val file = File(dir, "$lang.traineddata")
        val tempFile = File(dir, "$lang.traineddata.tmp")

        val url =
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata/main/$lang.traineddata"

        try {
            val connection = URL(url).openConnection().apply {
                connectTimeout = 30000
                readTimeout = 120000
            }

            connection.getInputStream().use { source ->
                FileOutputStream(tempFile).use { destination ->
                    source.copyTo(destination)
                }
            }

            check(
                tempFile.exists() && tempFile.length() > 100000
            ) {
                "Downloaded $lang OCR file is invalid"
            }

            if (file.exists()) {
                file.delete()
            }

            check(tempFile.renameTo(file)) {
                "Unable to save $lang OCR data"
            }

        } catch (e: Exception) {
            tempFile.delete()

            throw Exception(
                "Failed to download $lang OCR data: ${e.message}"
            )
        }
    }

    private fun search() {

        val query = input.text.toString().trim()

        results.removeAllViews()

        if (query.isEmpty()) {
            status.text = "Enter a search term."
            return
        }

        val rows = db.search(query)

        status.text =
            "${rows.size} matching cards. Duplicate names are listed separately."

        rows.forEach { voter ->

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(3, 10, 3, 10)
            }

            val photo = ImageView(this).apply {

                val file = File(voter.image)

                if (file.exists()) {
                    setImageURI(Uri.fromFile(file))
                }

                scaleType = ImageView.ScaleType.CENTER_CROP
            }

            row.addView(
                photo,
                LinearLayout.LayoutParams(110, 130)
            )

            val details = TextView(this).apply {
                text =
                    "Entry ${voter.serial} • PDF page ${voter.page}\n${voter.text}"

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

    override fun onCreate(
        db: android.database.sqlite.SQLiteDatabase
    ) {
        db.execSQL(
            "CREATE TABLE voters(serial INTEGER PRIMARY KEY, page INTEGER, ocr TEXT, image TEXT)"
        )

        db.execSQL(
            "CREATE INDEX voter_text_idx ON voters(ocr)"
        )
    }

    override fun onUpgrade(
        db: android.database.sqlite.SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
    }

    fun clear() {
        writableDatabase.delete("voters", null, null)

        File(
            appContext.filesDir,
            "cards"
        ).deleteRecursively()
    }

    fun count(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM voters",
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursor.getInt(0)
            } else {
                0
            }
        }
    }

    fun insert(
        serial: Int,
        page: Int,
        text: String,
        image: String
    ) {
        val values = android.content.ContentValues().apply {
            put("serial", serial)
            put("page", page)
            put("ocr", text)
            put("image", image)
        }

        writableDatabase.insert(
            "voters",
            null,
            values
        )
    }

    fun search(query: String): List<VoterRow> {

        val output = mutableListOf<VoterRow>()

        readableDatabase.rawQuery(
            "SELECT serial,page,ocr,image FROM voters WHERE ocr LIKE ? ORDER BY serial LIMIT 500",
            arrayOf("%$query%")
        ).use { cursor ->

            while (cursor.moveToNext()) {

                output.add(
                    VoterRow(
                        cursor.getInt(0),
                        cursor.getInt(1),
                        cursor.getString(2) ?: "",
                        cursor.getString(3) ?: ""
                    )
                )
            }
        }

        return output
    }
}
