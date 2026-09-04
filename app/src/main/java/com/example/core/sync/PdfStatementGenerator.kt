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
import com.example.core.ledger.NoteCleaner
import com.example.core.ledger.TransactionTypeNormalizer
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

    private fun drawBidiText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        width: Int = 500,
        align: android.text.Layout.Alignment = android.text.Layout.Alignment.ALIGN_NORMAL
    ) {
        val textPaint = android.text.TextPaint(paint)
        val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.text.StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
                .setAlignment(align)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .setTextDirection(android.text.TextDirectionHeuristics.RTL)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.text.StaticLayout(text, textPaint, width, align, 1f, 0f, false)
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
        // Palette definition (Slate & Emerald Pro, Option B)
        val headerBgColor = Color.parseColor("#0F172A") // Deep Slate Navy
        val tableHeaderBgColor = Color.parseColor("#1E293B") // Dark Slate Header
        val cardBgColor = Color.parseColor("#F8FAFC")
        val cardBorderColor = Color.parseColor("#E2E8F0")

        val greenMain = Color.parseColor("#16A34A")
        val greenDark = Color.parseColor("#14532D")
        val greenLight = Color.parseColor("#F0FDF4")
        val greenBorder = Color.parseColor("#BBF7D0")

        val redMain = Color.parseColor("#DC2626")
        val redDark = Color.parseColor("#7F1D1D")
        val redLight = Color.parseColor("#FEF2F2")
        val redBorder = Color.parseColor("#FECACA")

        val slateText = Color.parseColor("#0F172A")
        val slateMuted = Color.parseColor("#64748B")
        val slateLightMuted = Color.parseColor("#94A3B8")

        // Paints
        val headerTitlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val headerSubPaint = Paint().apply {
            color = slateLightMuted
            textSize = 8.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val textPrimaryPaint = Paint().apply {
            color = slateText
            textSize = 9.2f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val textBoldPaint = Paint().apply {
            color = slateText
            textSize = 9.2f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val textSecondaryPaint = Paint().apply {
            color = slateMuted
            textSize = 8.8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val tableHeaderPaint = Paint().apply {
            color = Color.WHITE
            textSize = 9f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val borderPaint = Paint().apply {
            color = cardBorderColor
            strokeWidth = 0.8f
            style = Paint.Style.STROKE
        }

        val subtleLinePaint = Paint().apply {
            color = Color.parseColor("#F1F5F9")
            strokeWidth = 0.6f
            style = Paint.Style.STROKE
        }

        val paidGreenTextPaint = Paint(textBoldPaint).apply {
            color = greenMain
        }

        val chargeRedTextPaint = Paint(textBoldPaint).apply {
            color = redMain
        }

        val greenStripPaint = Paint().apply {
            color = greenMain
            style = Paint.Style.FILL
        }

        val redStripPaint = Paint().apply {
            color = redMain
            style = Paint.Style.FILL
        }

        val paymentRowBgPaint = Paint().apply {
            color = greenLight
            style = Paint.Style.FILL
        }

        val chargeRowBgPaint = Paint().apply {
            color = Color.parseColor("#FFF8F8")
            style = Paint.Style.FILL
        }

        val zebraPaint = Paint().apply {
            color = cardBgColor
            style = Paint.Style.FILL
        }

        // Timezone formatting for Baghdad
        val baghdadTimezone = TimeZone.getTimeZone("Asia/Baghdad")
        val baghdadDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = baghdadTimezone
        }
        val generatedAtString = baghdadDateFormat.format(Date())

        // 1. Draw Page Header (Option B: Slate Navy Banner + Debt Card + Profile Card)
        fun drawHeader(canvas: Canvas) {
            val leftMargin = 30f
            val rightMargin = (PAGE_WIDTH - 30).toFloat()

            // Header Banner (Slate Navy Box)
            val headerRectPaint = Paint().apply {
                color = headerBgColor
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(leftMargin, 24f, rightMargin, 86f, 8f, 8f, headerRectPaint)

            // Header Content - Right side (RTL)
            drawBidiText(canvas, "كشف حساب المشترك", 195f, 46f, headerTitlePaint, width = 340, align = android.text.Layout.Alignment.ALIGN_NORMAL)
            drawBidiText(canvas, "تطبيق وكيل إيرثلنك • تقرير الحركات المالية والرصيد", 195f, 62f, headerSubPaint, width = 340, align = android.text.Layout.Alignment.ALIGN_NORMAL)
            drawBidiText(canvas, "تاريخ إصدار الكشف: $generatedAtString", 195f, 75f, headerSubPaint, width = 340, align = android.text.Layout.Alignment.ALIGN_NORMAL)

            // Header Debt Card - Left side (RTL)
            val debtBoxLeft = 40f
            val debtBoxRight = 180f
            val debtBoxPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(debtBoxLeft, 32f, debtBoxRight, 78f, 6f, 6f, debtBoxPaint)

            val debtHasBalance = account.debtIqd > 0.0
            val debtValColor = if (debtHasBalance) redMain else greenMain
            val debtTitlePaint = Paint(textBoldPaint).apply { color = slateMuted; textSize = 8f }
            val debtValPaint = Paint(textBoldPaint).apply { color = debtValColor; textSize = 13.5f }

            val debtWidth = (debtBoxRight - debtBoxLeft).toInt()
            drawBidiText(canvas, "صافي الدين المستحق", debtBoxLeft, 47f, debtTitlePaint, width = debtWidth, align = android.text.Layout.Alignment.ALIGN_CENTER)
            val formattedDebt = String.format(Locale.US, "%,.0f", account.debtIqd.toDouble())
            drawBidiText(canvas, "$formattedDebt د.ع", debtBoxLeft, 67f, debtValPaint, width = debtWidth, align = android.text.Layout.Alignment.ALIGN_CENTER)

            // Subscriber Info Card (2 columns, clean borders)
            val profileTop = 96f
            val profileBottom = 154f
            val profileCardPaint = Paint().apply {
                color = cardBgColor
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(leftMargin, profileTop, rightMargin, profileBottom, 6f, 6f, profileCardPaint)
            canvas.drawRoundRect(leftMargin, profileTop, rightMargin, profileBottom, 6f, 6f, borderPaint)

            // Vertical divider down middle
            canvas.drawLine((PAGE_WIDTH / 2).toFloat(), profileTop + 6f, (PAGE_WIDTH / 2).toFloat(), profileBottom - 6f, borderPaint)

            // Subscriber Info - Right Column (Primary RTL)
            drawBidiText(canvas, "اسم المشترك: ${account.displayName}", 305f, 114f, textBoldPaint, width = 250, align = android.text.Layout.Alignment.ALIGN_NORMAL)
            drawBidiText(canvas, "اسم المستخدم: ${account.earthlinkUsername ?: "غير متوفر"}", 305f, 130f, textPrimaryPaint, width = 250, align = android.text.Layout.Alignment.ALIGN_NORMAL)
            drawBidiText(canvas, "رقم الهاتف: ${account.phone1 ?: "غير متوفر"}", 305f, 146f, textPrimaryPaint, width = 250, align = android.text.Layout.Alignment.ALIGN_NORMAL)

            // Subscriber Info - Left Column
            val expiryText = account.expiresAt?.let { isoStr ->
                try {
                    var parsed: java.util.Date? = null
                    try {
                        val utcParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        parsed = utcParser.parse(isoStr)
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; }
                    if (parsed == null) {
                        try {
                            val fall = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Baghdad") }
                            parsed = fall.parse(isoStr)
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; }
                    }
                    parsed?.let { date -> baghdadDateFormat.format(date) }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
            } ?: "غير متوفر"

            drawBidiText(canvas, "الباقة الحالية: ${account.packageName ?: "الباقة الافتراضية"}", 40f, 114f, textBoldPaint, width = 250, align = android.text.Layout.Alignment.ALIGN_NORMAL)
            drawBidiText(canvas, "تاريخ انتهاء الاشتراك: $expiryText", 40f, 130f, textPrimaryPaint, width = 250, align = android.text.Layout.Alignment.ALIGN_NORMAL)
            
            // Status: "حالة الحساب: فعال"
            val statusPaint = Paint(textBoldPaint).apply { color = greenMain }
            drawBidiText(canvas, "حالة الحساب: فعال", 40f, 146f, statusPaint, width = 250, align = android.text.Layout.Alignment.ALIGN_NORMAL)

            // Table Header Bar (Dark Slate)
            val tableHeaderTop = 166f
            val tableHeaderBottom = 192f
            val thPaint = Paint().apply {
                color = tableHeaderBgColor
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(leftMargin, tableHeaderTop, rightMargin, tableHeaderBottom, 6f, 6f, thPaint)

            drawBidiText(canvas, "التاريخ والوقت", 460f, 182f, tableHeaderPaint, width = 105, align = android.text.Layout.Alignment.ALIGN_CENTER)
            drawBidiText(canvas, "نوع العملية", 385f, 182f, tableHeaderPaint, width = 75, align = android.text.Layout.Alignment.ALIGN_CENTER)
            drawBidiText(canvas, "المبلغ", 300f, 182f, tableHeaderPaint, width = 85, align = android.text.Layout.Alignment.ALIGN_CENTER)
            drawBidiText(canvas, "الدين بعد الحركة", 210f, 182f, tableHeaderPaint, width = 90, align = android.text.Layout.Alignment.ALIGN_CENTER)
            drawBidiText(canvas, "البيان / الملاحظات", 30f, 182f, tableHeaderPaint, width = 180, align = android.text.Layout.Alignment.ALIGN_CENTER)
        }

        drawHeader(canvas)

        var y = 210f
        val bottomMargin = 85f
        var zebra = false

        for (tx in transactions) {
            // Check page wrap
            if (y > PAGE_HEIGHT - bottomMargin) {
                // Draw footer for previous page
                drawBidiText(canvas, "تطبيق وكيل إيرثلنك • تقرير حساب المشترك", 265f, (PAGE_HEIGHT - 35).toFloat(), textSecondaryPaint, width = 300, align = android.text.Layout.Alignment.ALIGN_NORMAL)
                drawBidiText(canvas, "الصفحة $pageNumber", 30f, (PAGE_HEIGHT - 35).toFloat(), textSecondaryPaint, width = 100, align = android.text.Layout.Alignment.ALIGN_NORMAL)
                canvas.drawLine(30f, (PAGE_HEIGHT - 48).toFloat(), (PAGE_WIDTH - 30).toFloat(), (PAGE_HEIGHT - 48).toFloat(), borderPaint)
                
                document.finishPage(page)
                
                pageNumber++
                page = document.startPage(pageInfo)
                canvas = page.canvas
                drawHeader(canvas)
                y = 210f
                zebra = false
            }

            val normType = TransactionTypeNormalizer.normalizeTransactionType(tx.typeRaw)
            val isPayment = normType == "gave"
            val isDebtOrRenewal = normType == "took" || normType == "renewal"

            // Row Background & Option B Accent Strip
            val rowTop = y - 12f
            val rowBottom = y + 11f
            val leftEdge = 30f
            val rightEdge = (PAGE_WIDTH - 30).toFloat()

            if (isPayment) {
                // Soft green tint for payment rows
                canvas.drawRect(leftEdge, rowTop, rightEdge, rowBottom, paymentRowBgPaint)
                // 4px Green Accent Strip on the right edge (RTL)
                canvas.drawRect(rightEdge - 4f, rowTop, rightEdge, rowBottom, greenStripPaint)
            } else if (isDebtOrRenewal) {
                // Subtle red tint for debt / renewal rows
                canvas.drawRect(leftEdge, rowTop, rightEdge, rowBottom, chargeRowBgPaint)
                // 4px Red Accent Strip on the right edge (RTL)
                canvas.drawRect(rightEdge - 4f, rowTop, rightEdge, rowBottom, redStripPaint)
            } else if (zebra) {
                canvas.drawRect(leftEdge, rowTop, rightEdge, rowBottom, zebraPaint)
            }
            zebra = !zebra

            // Date
            val txDateString = baghdadDateFormat.format(Date(tx.occurredAt))
            drawBidiText(canvas, txDateString, 460f, y, textPrimaryPaint, width = 105, align = android.text.Layout.Alignment.ALIGN_CENTER)

            // Transaction Type: Per user directive, "تسجيل دين" and "تجديد اشتراك" both treated as RED
            val (typeText, typePaint) = when (normType) {
                "gave" -> "تسديد دفعة" to paidGreenTextPaint
                "renewal" -> "تجديد اشتراك" to chargeRedTextPaint
                "took" -> "تسجيل دين" to chargeRedTextPaint
                else -> normType to textBoldPaint
            }
            drawBidiText(canvas, typeText, 385f, y, typePaint, width = 75, align = android.text.Layout.Alignment.ALIGN_CENTER)

            // Amount
            val amountFormatted = String.format(Locale.US, "%,.0f", tx.amountIqd.toDouble())
            val amountPaint = if (isPayment) paidGreenTextPaint else textBoldPaint
            drawBidiText(canvas, "$amountFormatted د.ع", 300f, y, amountPaint, width = 85, align = android.text.Layout.Alignment.ALIGN_CENTER)

            // Debt after transaction
            val balanceFormatted = String.format(Locale.US, "%,.0f", tx.debtAfterIqd.toDouble())
            val (debtText, debtPaint) = if (tx.debtAfterIqd <= 0.0) {
                "0 د.ع (خالص)" to paidGreenTextPaint
            } else {
                "$balanceFormatted د.ع" to chargeRedTextPaint
            }
            drawBidiText(canvas, debtText, 210f, y, debtPaint, width = 90, align = android.text.Layout.Alignment.ALIGN_CENTER)

            // Genuine Note
            val genuineNote = NoteCleaner.extractGenuineNote(tx.note, tx.amountIqd.toDouble())
            val noteToDraw = if (genuineNote.isNotBlank()) {
                if (genuineNote.length > 25) genuineNote.substring(0, 22) + "..." else genuineNote
            } else {
                "-"
            }
            val notePaint = if (noteToDraw == "-") textSecondaryPaint else Paint(textBoldPaint).apply { color = Color.parseColor("#1E3A8A") }
            drawBidiText(canvas, noteToDraw, 30f, y, notePaint, width = 180, align = android.text.Layout.Alignment.ALIGN_CENTER)

            // Row divider line
            canvas.drawLine(leftEdge, rowBottom, rightEdge, rowBottom, subtleLinePaint)
            
            y += 23f
        }

        // Summary block
        if (y > PAGE_HEIGHT - 135f) {
            // Start a new page if summary block doesn't fit
            drawBidiText(canvas, "تطبيق وكيل إيرثلنك • تقرير حساب المشترك", 265f, (PAGE_HEIGHT - 35).toFloat(), textSecondaryPaint, width = 300, align = android.text.Layout.Alignment.ALIGN_NORMAL)
            drawBidiText(canvas, "الصفحة $pageNumber", 30f, (PAGE_HEIGHT - 35).toFloat(), textSecondaryPaint, width = 100, align = android.text.Layout.Alignment.ALIGN_NORMAL)
            canvas.drawLine(30f, (PAGE_HEIGHT - 48).toFloat(), (PAGE_WIDTH - 30).toFloat(), (PAGE_HEIGHT - 48).toFloat(), borderPaint)
            
            document.finishPage(page)
            pageNumber++
            page = document.startPage(pageInfo)
            canvas = page.canvas
            drawHeader(canvas)
            y = 210f
        }

        // Draw 3 Summary KPI Cards
        y += 14f
        val cardW = 170f
        val cardH = 58f
        val gap = 12.5f

        val totalPayments = transactions.filter {
            TransactionTypeNormalizer.normalizeTransactionType(it.typeRaw) == "gave"
        }.sumOf { it.amountIqd }
        val totalCharges = transactions.filter {
            val norm = TransactionTypeNormalizer.normalizeTransactionType(it.typeRaw)
            norm == "took" || norm == "renewal"
        }.sumOf { it.amountIqd }

        // Card 1 (Right): Total Charges
        val c1X1 = (PAGE_WIDTH - 30).toFloat() - cardW
        canvas.drawRoundRect(c1X1, y, c1X1 + cardW, y + cardH, 6f, 6f, Paint().apply { color = cardBgColor; style = Paint.Style.FILL })
        canvas.drawRoundRect(c1X1, y, c1X1 + cardW, y + cardH, 6f, 6f, borderPaint)
        drawBidiText(canvas, "إجمالي التكاليف والاشتراكات", c1X1, y + 19f, textSecondaryPaint, width = cardW.toInt(), align = android.text.Layout.Alignment.ALIGN_CENTER)
        drawBidiText(canvas, "${String.format(Locale.US, "%,.0f", totalCharges.toDouble())} د.ع", c1X1, y + 40f, textBoldPaint, width = cardW.toInt(), align = android.text.Layout.Alignment.ALIGN_CENTER)

        // Card 2 (Center): Total Payments
        val c2X1 = c1X1 - gap - cardW
        canvas.drawRoundRect(c2X1, y, c2X1 + cardW, y + cardH, 6f, 6f, Paint().apply { color = greenLight; style = Paint.Style.FILL })
        canvas.drawRoundRect(c2X1, y, c2X1 + cardW, y + cardH, 6f, 6f, Paint().apply { color = greenBorder; style = Paint.Style.STROKE; strokeWidth = 0.8f })
        val payTitlePaint = Paint(textBoldPaint).apply { color = greenDark; textSize = 8.5f }
        drawBidiText(canvas, "إجمالي المبالغ المسددة", c2X1, y + 19f, payTitlePaint, width = cardW.toInt(), align = android.text.Layout.Alignment.ALIGN_CENTER)
        drawBidiText(canvas, "${String.format(Locale.US, "%,.0f", totalPayments.toDouble())} د.ع", c2X1, y + 40f, paidGreenTextPaint, width = cardW.toInt(), align = android.text.Layout.Alignment.ALIGN_CENTER)

        // Card 3 (Left): Remaining Debt
        val c3X1 = 30f
        val debtHasBalance = account.debtIqd > 0.0
        val debtBgColor = if (debtHasBalance) redLight else greenLight
        val debtBorder = if (debtHasBalance) redBorder else greenBorder
        val debtTitleColor = if (debtHasBalance) redDark else greenDark
        val debtValColor = if (debtHasBalance) redMain else greenMain

        canvas.drawRoundRect(c3X1, y, c3X1 + cardW, y + cardH, 6f, 6f, Paint().apply { color = debtBgColor; style = Paint.Style.FILL })
        canvas.drawRoundRect(c3X1, y, c3X1 + cardW, y + cardH, 6f, 6f, Paint().apply { color = debtBorder; style = Paint.Style.STROKE; strokeWidth = 0.8f })
        val debtSummaryTitlePaint = Paint(textBoldPaint).apply { color = debtTitleColor; textSize = 8.5f }
        val debtSummaryValPaint = Paint(textBoldPaint).apply { color = debtValColor; textSize = 13f }
        drawBidiText(canvas, "صافي الدين المتبقي", c3X1, y + 19f, debtSummaryTitlePaint, width = cardW.toInt(), align = android.text.Layout.Alignment.ALIGN_CENTER)
        drawBidiText(canvas, "${String.format(Locale.US, "%,.0f", account.debtIqd.toDouble())} د.ع", c3X1, y + 40f, debtSummaryValPaint, width = cardW.toInt(), align = android.text.Layout.Alignment.ALIGN_CENTER)

        // Page footer (drawn on final page)
        canvas.drawLine(30f, (PAGE_HEIGHT - 48).toFloat(), (PAGE_WIDTH - 30).toFloat(), (PAGE_HEIGHT - 48).toFloat(), borderPaint)
        drawBidiText(canvas, "تطبيق وكيل إيرثلنك • تقرير حساب المشترك", 265f, (PAGE_HEIGHT - 35).toFloat(), textSecondaryPaint, width = 300, align = android.text.Layout.Alignment.ALIGN_NORMAL)
        drawBidiText(canvas, "الصفحة $pageNumber", 30f, (PAGE_HEIGHT - 35).toFloat(), textSecondaryPaint, width = 100, align = android.text.Layout.Alignment.ALIGN_NORMAL)

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

            // Explicitly grant read permissions on both the wrapped share intent and the chooser intent so target receiver apps safely obtain read access to FileProvider URI
            val chooser = Intent.createChooser(shareIntent, "Share Statement PDF via").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)

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
