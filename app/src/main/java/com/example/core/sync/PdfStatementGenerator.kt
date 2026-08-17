package com.example.core.sync

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object PdfStatementGenerator {

    private const val TAG = "PdfStatementGenerator"
    private const val PAGE_WIDTH = 595 // A4 standard width in points
    private const val PAGE_HEIGHT = 842 // A4 standard height in points

    private fun drawBidiText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        val textPaint = android.text.TextPaint(paint)
        val width = 500
        val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.text.StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .setTextDirection(android.text.TextDirectionHeuristics.ANYRTL_LTR)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.text.StaticLayout(text, textPaint, width, android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
        }
        canvas.save()
        canvas.translate(x, y - textPaint.textSize)
        staticLayout.draw(canvas)
        canvas.restore()
    }

    fun generateAndShare(
        context: Context,
        account: LocalAccount,
        transactions: List<LocalLedgerEntry>
    ) {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        
        var pageNumber = 1
        var page = document.startPage(pageInfo)
        var canvas = page.canvas

        // Common Paints
        val primaryPaint = Paint().apply {
            color = Color.parseColor("#1E3A8A") // Dark Blue Header
            style = Paint.Style.FILL
        }

        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val textBoldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val headerTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val dividerPaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }

        val zebraPaint = Paint().apply {
            color = Color.parseColor("#F3F4F6") // Very light gray
            style = Paint.Style.FILL
        }

        // Timezone formatting for Baghdad
        val baghdadTimezone = TimeZone.getTimeZone("Asia/Baghdad")
        val baghdadDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = baghdadTimezone
        }
        val generatedAtString = baghdadDateFormat.format(Date())

        // 1. Draw Page Header
        fun drawHeader(canvas: Canvas) {
            // Header bar
            canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 70f, primaryPaint)
            
            // App & Document title
            drawBidiText(canvas, "ACCOUNT STATEMENT / كشف حساب", 25f, 40f, headerTextPaint)
            
            // Subtitle
            val subTextPaint = Paint(headerTextPaint).apply { textSize = 9f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL) }
            drawBidiText(canvas, "Earthlink Reseller Network Service", 25f, 55f, subTextPaint)

            // Subscriber Info box background
            val infoBgPaint = Paint().apply { color = Color.parseColor("#F9FAFB"); style = Paint.Style.FILL }
            canvas.drawRect(25f, 85f, (PAGE_WIDTH - 25).toFloat(), 155f, infoBgPaint)
            canvas.drawRect(25f, 85f, (PAGE_WIDTH - 25).toFloat(), 155f, dividerPaint)

            // Subscriber Info left column
            drawBidiText(canvas, "Subscriber / المشترك:", 35f, 105f, textBoldPaint)
            drawBidiText(canvas, account.displayName, 145f, 105f, textPaint)

            drawBidiText(canvas, "Username / اسم الحساب:", 35f, 122f, textBoldPaint)
            drawBidiText(canvas, account.earthlinkUsername ?: "N/A", 145f, 122f, textPaint)

            drawBidiText(canvas, "Phone / الهاتف:", 35f, 139f, textBoldPaint)
            drawBidiText(canvas, account.phone1 ?: "N/A", 145f, 139f, textPaint)

            // Subscriber Info right column
            drawBidiText(canvas, "Generated / إصدار الكشف:", 330f, 105f, textBoldPaint)
            drawBidiText(canvas, generatedAtString, 450f, 105f, textPaint)

            drawBidiText(canvas, "Current Package / الباقة:", 330f, 122f, textBoldPaint)
            drawBidiText(canvas, account.packageName ?: "Default Package", 450f, 122f, textPaint)

            val expiryText = account.expiresAt?.let { isoStr ->
                try {
                    var parsed: java.util.Date? = null
                    try {
                        val utcParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        parsed = utcParser.parse(isoStr)
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;}
                    if (parsed == null) {
                        try {
                            val fall = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Baghdad") }
                            parsed = fall.parse(isoStr)
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;}
                    }
                    parsed?.let { date -> baghdadDateFormat.format(date) }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
            } ?: "N/A"
            drawBidiText(canvas, "Expires At / تاريخ النفاذ:", 330f, 139f, textBoldPaint)
            drawBidiText(canvas, expiryText, 450f, 139f, textPaint)

            // Table Header Labels
            canvas.drawRect(25f, 175f, (PAGE_WIDTH - 25).toFloat(), 195f, Paint().apply { color = Color.parseColor("#E5E7EB"); style = Paint.Style.FILL })
            canvas.drawRect(25f, 175f, (PAGE_WIDTH - 25).toFloat(), 195f, dividerPaint)

            drawBidiText(canvas, "Date / التاريخ", 35f, 188f, textBoldPaint)
            drawBidiText(canvas, "Type / العملية", 135f, 188f, textBoldPaint)
            drawBidiText(canvas, "Note / الملاحظات", 230f, 188f, textBoldPaint)
            drawBidiText(canvas, "Amount / المبلغ", 400f, 188f, textBoldPaint)
            drawBidiText(canvas, "Debt After / المتبقي", 490f, 188f, textBoldPaint)
        }

        drawHeader(canvas)

        var y = 210f
        val bottomMargin = 80f
        var zebra = false

        for (tx in transactions) {
            // Check if we need to wrap to a new page
            if (y > PAGE_HEIGHT - bottomMargin) {
                // Draw footer for previous page
                drawBidiText(canvas, "Page / الصفحة $pageNumber", (PAGE_WIDTH - 80).toFloat(), (PAGE_HEIGHT - 35).toFloat(), textPaint)
                canvas.drawLine(25f, (PAGE_HEIGHT - 50).toFloat(), (PAGE_WIDTH - 25).toFloat(), (PAGE_HEIGHT - 50).toFloat(), dividerPaint)
                
                document.finishPage(page)
                
                pageNumber++
                page = document.startPage(pageInfo)
                canvas = page.canvas
                drawHeader(canvas)
                y = 210f
                zebra = false
            }

            // Draw Zebra background for alternating rows
            if (zebra) {
                canvas.drawRect(25f, y - 12f, (PAGE_WIDTH - 25).toFloat(), y + 12f, zebraPaint)
            }
            zebra = !zebra

            // Format date of transaction aligned to Baghdad
            val txDateString = baghdadDateFormat.format(Date(tx.occurredAt))
            drawBidiText(canvas, txDateString, 35f, y, textPaint)

            // Format transaction type
            val typeText = when (tx.typeRaw) {
                "gave", "deposit", "payment" -> "Payment / دفع"
                "took", "renewal", "renew" -> "Renewal / تجديد"
                "debt", "debt_added" -> "Debt / دين"
                else -> tx.typeRaw.replaceFirstChar { it.uppercase() }
            }
            drawBidiText(canvas, typeText, 135f, y, textPaint)

            // Format note (clean it to fit table width)
            val cleanNote = (tx.note ?: "")
                .removePrefix("[RENEW]")
                .removePrefix("[RENEW_PAY]")
                .removePrefix("[DEBT]")
                .removePrefix("[DEPOSIT]")
                .trim()
            val noteToDraw = if (cleanNote.length > 25) cleanNote.substring(0, 22) + "..." else cleanNote
            drawBidiText(canvas, noteToDraw, 230f, y, textPaint)

            // Format amounts
            val amountFormatted = String.format(Locale.US, "%,.0f", tx.amountIqd.toDouble())
            val balanceFormatted = String.format(Locale.US, "%,.0f", tx.debtAfterIqd.toDouble())
            
            drawBidiText(canvas, "$amountFormatted IQD", 400f, y, textPaint)
            drawBidiText(canvas, "$balanceFormatted IQD", 490f, y, textPaint)

            // Draw bottom border line for row
            canvas.drawLine(25f, y + 12f, (PAGE_WIDTH - 25).toFloat(), y + 12f, dividerPaint)
            
            y += 24f
        }

        // Summary block
        if (y > PAGE_HEIGHT - 130f) {
            // Start a new page if summary block doesn't fit
            drawBidiText(canvas, "Page / الصفحة $pageNumber", (PAGE_WIDTH - 80).toFloat(), (PAGE_HEIGHT - 35).toFloat(), textPaint)
            canvas.drawLine(25f, (PAGE_HEIGHT - 50).toFloat(), (PAGE_WIDTH - 25).toFloat(), (PAGE_HEIGHT - 50).toFloat(), dividerPaint)
            
            document.finishPage(page)
            pageNumber++
            page = document.startPage(pageInfo)
            canvas = page.canvas
            drawHeader(canvas)
            y = 210f
        }

        // Draw summary section
        y += 10f
        canvas.drawRect(250f, y, (PAGE_WIDTH - 25).toFloat(), y + 65f, Paint().apply { color = Color.parseColor("#EFF6FF"); style = Paint.Style.FILL })
        canvas.drawRect(250f, y, (PAGE_WIDTH - 25).toFloat(), y + 65f, dividerPaint)

        val totalPayments = transactions.filter { it.typeRaw == "gave" || it.typeRaw == "deposit" || it.typeRaw == "payment" }.sumOf { it.amountIqd }
        val totalCharges = transactions.filter { it.typeRaw == "took" || it.typeRaw == "renewal" || it.typeRaw == "renew" || it.typeRaw == "debt" }.sumOf { it.amountIqd }

        drawBidiText(canvas, "Total Payments / مجموع المدفوعات:", 265f, y + 20f, textBoldPaint)
        drawBidiText(canvas, "${String.format(Locale.US, "%,.0f", totalPayments.toDouble())} IQD", 440f, y + 20f, textPaint)

        drawBidiText(canvas, "Total Charges / مجموع التكاليف:", 265f, y + 38f, textBoldPaint)
        drawBidiText(canvas, "${String.format(Locale.US, "%,.0f", totalCharges.toDouble())} IQD", 440f, y + 38f, textPaint)

        drawBidiText(canvas, "Outstanding Debt / الدين المتبقي:", 265f, y + 56f, Paint(textBoldPaint).apply { color = Color.parseColor("#B91C1C") })
        drawBidiText(canvas, "${String.format(Locale.US, "%,.0f", account.debtIqd.toDouble())} IQD", 440f, y + 56f, Paint(textBoldPaint).apply { color = Color.parseColor("#B91C1C") })

        // Page footer (drawn on final page)
        drawBidiText(canvas, "Page / الصفحة $pageNumber", (PAGE_WIDTH - 80).toFloat(), (PAGE_HEIGHT - 35).toFloat(), textPaint)
        canvas.drawLine(25f, (PAGE_HEIGHT - 50).toFloat(), (PAGE_WIDTH - 25).toFloat(), (PAGE_HEIGHT - 50).toFloat(), dividerPaint)
        drawBidiText(canvas, "This statement is electronically generated offline and verified secured.", 25f, (PAGE_HEIGHT - 35).toFloat(), Paint(textPaint).apply { textSize = 8f; color = Color.GRAY })

        document.finishPage(page)

        // Write PDF to cache directory for secure sharing
        try {
            val statementsDir = File(context.cacheDir, "statements")
            if (!statementsDir.exists()) {
                statementsDir.mkdirs()
            }
            
            val safeUsername = (account.earthlinkUsername ?: "account").replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val pdfFile = File(statementsDir, "Statement_${safeUsername}_${System.currentTimeMillis()}.pdf")
            
            FileOutputStream(pdfFile).use { fos ->
                document.writeTo(fos)
            }

            Log.i(TAG, "PDF Statement generated successfully: ${pdfFile.absolutePath}")

            // Trigger Share Intent using FileProvider
            val fileUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "Statement for ${account.displayName}")
                putExtra(Intent.EXTRA_TEXT, "Hello, here is the statement for subscriber account ${account.displayName} (${account.earthlinkUsername ?: "N/A"}).")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Share Statement PDF via"); chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(chooser)

        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                Log.e(TAG, "Error generating or sharing statement PDF", e)
                Toast.makeText(context, "Failed to generate statement PDF: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } else {
                throw e
            }
        } finally {
            try { document.close() } catch (ex: Exception) { if (ex is kotlinx.coroutines.CancellationException) throw ex }
        }
    }
}
