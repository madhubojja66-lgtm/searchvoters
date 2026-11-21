        runOnUiThread { status.text = message }
    }

    private fun search() {
        val query = input.text.toString().trim()
        results.removeAllViews()
        if (query.isEmpty()) {
            status.text = "Enter a name, voter ID or serial number."
            return
        }

        val matches = db.search(query)
        status.text = "${matches.size} matching cards. Duplicate names are listed separately."
        matches.forEach { voter ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(3, 10, 3, 10)
            }
            val photo = ImageView(this).apply {
                val imageFile = File(voter.imagePath)
                if (imageFile.exists()) setImageURI(Uri.fromFile(imageFile))
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            row.addView(photo, LinearLayout.LayoutParams(110, 130))
            row.addView(
                TextView(this).apply {
                    text = "Serial ${voter.serial} • PDF page ${voter.pdfPage}\n${voter.ocrText}"
                    textSize = 14f
                    setPadding(10, 3, 3, 3)
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            results.addView(row)
            results.addView(View(this).apply {
                setBackgroundColor(0xFFDDDDDD.toInt())
            }, LinearLayout.LayoutParams(-1, 1))
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}

data class VoterRow(
    val serial: Int,
    val pdfPage: Int,
    val ocrText: String,
    val imagePath: String
)

class VoterDb(context: android.content.Context) :
    android.database.sqlite.SQLiteOpenHelper(context, "voters.db", null, 1) {

    override fun onCreate(database: android.database.sqlite.SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE voters (serial INTEGER PRIMARY KEY, page INTEGER NOT NULL, ocr TEXT, image TEXT NOT NULL)"
        )
        database.execSQL("CREATE INDEX voter_text_idx ON voters(ocr)")
    }

    override fun onUpgrade(
        database: android.database.sqlite.SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // Version 1 schema is used by this app.
    }

    fun clear() {
        writableDatabase.delete("voters", null, null)
        File(contextFilesDir, "cards").deleteRecursively()
    }

    private val contextFilesDir: File = context.filesDir

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM voters", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun insert(serial: Int, pdfPage: Int, text: String, imagePath: String) {
        val values = android.content.ContentValues().apply {
            put("serial", serial)
            put("page", pdfPage)
            put("ocr", text)
            put("image", imagePath)
        }
        val result = writableDatabase.insert("voters", null, values)
        check(result != -1L) { "Could not save voter card $serial to the database" }
    }

    fun search(query: String): List<VoterRow> {
        val output = mutableListOf<VoterRow>()
        val pattern = "%$query%"
        readableDatabase.rawQuery(
            "SELECT serial, page, ocr, image FROM voters " +
                "WHERE ocr LIKE ? OR CAST(serial AS TEXT) LIKE ? " +
                "ORDER BY serial LIMIT 500",
            arrayOf(pattern, pattern)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                output.add(
                    VoterRow(
                        serial = cursor.getInt(0),
                        pdfPage = cursor.getInt(1),
                        ocrText = cursor.getString(2).orEmpty(),
                        imagePath = cursor.getString(3).orEmpty()
                    )
                )
            }
        }
        return output
    }
}
