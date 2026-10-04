package com.t1dm.core.design

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** A4 at 72 dpi, laid top-down from [y]; [need] breaks the page before a block that won't fit. */
class PdfPager {
    private val doc = PdfDocument()
    private var pageNo = 0
    private lateinit var page: PdfDocument.Page
    lateinit var canvas: Canvas
        private set
    var y = 0f

    val titleP = paint(Color.BLACK, 20f, bold = true)
    val sectionP = paint(Color.rgb(30, 60, 100), 13f, bold = true)
    val keyP = paint(Color.DKGRAY, 11f)
    val valP = paint(Color.BLACK, 11f)
    val capP = paint(Color.GRAY, 9.5f)
    val footP = paint(Color.GRAY, 9f)
    val axisP = paint(Color.GRAY, 8.5f)
    private val headP = paint(Color.DKGRAY, 8f, bold = true)
    private val cellP = paint(Color.BLACK, 8f)

    init { newPage() }

    private fun newPage() {
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        canvas = page.canvas
        canvas.drawColor(Color.WHITE)
        y = M + 8f
    }

    fun need(h: Float) {
        if (y + h > H - FOOT) {
            stampFooter()
            doc.finishPage(page)
            newPage()
        }
    }

    private fun stampFooter() {
        canvas.drawText("Page $pageNo", W - M - 40f, H - 18f, footP)
    }

    fun title(t: String) { canvas.drawText(t, M, y, titleP); y += 22f }
    fun body(t: String) { need(16f); canvas.drawText(t, M, y + 12f, keyP); y += 15f }
    fun caption(t: String) { need(14f); canvas.drawText(t, M, y + 10f, capP); y += 13f }
    fun gap(h: Float) { y += h }

    /** [keepWith]: height of the block below that must share the header's page. */
    fun section(t: String, keepWith: Float = 0f) {
        need(30f + keepWith)
        y += 16f
        canvas.drawText(t, M, y, sectionP)
        canvas.drawLine(M, y + 4f, W - M, y + 4f, axisP)
        y += 12f
    }

    fun kv(k: String, v: String) {
        need(16f)
        y += 15f
        canvas.drawText(k, M, y, keyP)
        canvas.drawText(v, W / 2f - 30f, y, valP)
    }

    /** Never split across pages; [columns] weights share the content width. */
    fun table(columns: List<TableColumn>, rows: List<List<String>>) {
        val rowH = 11f
        need(rowH * (rows.size + 1) + 8f)
        y += 4f
        val total = columns.sumOf { it.weight.toDouble() }.toFloat()
        val unit = (W - 2 * M) / total
        val edges = FloatArray(columns.size + 1)
        for (i in columns.indices) edges[i + 1] = edges[i] + columns[i].weight * unit
        fun line(cells: List<String>, p: Paint) {
            y += rowH
            columns.forEachIndexed { i, c ->
                val text = cells.getOrNull(i) ?: return@forEachIndexed
                val x = if (c.numeric) M + edges[i + 1] - 3f - p.measureText(text) else M + edges[i]
                canvas.drawText(text, x, y - 2f, p)
            }
        }
        line(columns.map { it.header }, headP)
        canvas.drawLine(M, y + 1f, W - M, y + 1f, axisP)
        rows.forEach { line(it, cellP) }
        y += 4f
    }

    fun finish(out: OutputStream) {
        stampFooter()
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }

    companion object {
        const val W = 595
        const val H = 842
        const val M = 40f
        private const val FOOT = 34f

        fun paint(c: Int, size: Float, bold: Boolean = false) = Paint().apply {
            color = c; textSize = size; isFakeBoldText = bold; isAntiAlias = true
        }
    }
}
