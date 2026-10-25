package com.kexu.app.pdf

/** 与 PDF 库无关的版面模型：坐标已按页面上边缘为 y=0 的方向归一（旋转页面在提取时统一）。 */
data class PdfWord(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val page: Int
) {
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f
}

data class PdfPageLayout(
    val pageIndex: Int,
    val width: Float,
    val height: Float,
    val rotation: Int,
    val words: List<PdfWord>
)

data class PdfDocumentLayout(val pages: List<PdfPageLayout>) {
    val words: List<PdfWord> get() = pages.flatMap { it.words }
}

data class WeekdayColumn(val day: Int, val left: Float, val right: Float, val headerX: Float)

data class PeriodRow(
    val startPeriod: Int,
    val endPeriod: Int,
    val top: Float,
    val bottom: Float,
    val labelY: Float
)

data class TimetableGrid(
    val columns: List<WeekdayColumn>,
    val rows: List<PeriodRow>,
    val pageWidth: Float,
    val pageHeight: Float
) {
    val isValid: Boolean get() = columns.size >= 3 && rows.isNotEmpty()
}

/** 表格结构恢复：先从文字布局恢复星期列与节次行，供学校适配器做单元格解析。 */
object TableStructureRecovery {

    private val WEEKDAY_NAMES = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日", "星期天")
    private val PERIOD_LABEL = Regex("^(\\d{1,2})\\s*[-—–~]\\s*(\\d{1,2})$")

    fun recover(page: PdfPageLayout, maxColumns: Int = 10): TimetableGrid? {
        val words = page.words
        val headers = words.mapNotNull { w ->
            val day = WEEKDAY_NAMES.indexOfFirst { name -> w.text.contains(name) || name.contains(w.text.trim()) }
            if (day < 0) null else (if (day == 7) 6 else day) to w
        }.filter { it.second.text.trim().length <= 4 }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.minByOrNull { it.centerX }!! }
        if (headers.size < 3) return null

        val sorted = headers.toSortedMap()
        val sortedHeads = sorted.entries.sortedBy { it.value.centerX }
        val bounds = mutableListOf<Float>()
        sortedHeads.forEachIndexed { i, entry ->
            val left = if (i == 0) 0f else (sortedHeads[i - 1].value.centerX + entry.value.centerX) / 2f
            bounds += left
            bounds += if (i == sortedHeads.size - 1) page.width else (entry.value.centerX + sortedHeads[i + 1].value.centerX) / 2f
        }
        val columns = sortedHeads.mapIndexed { i, entry ->
            WeekdayColumn(entry.key, bounds[i * 2], bounds[i * 2 + 1], entry.value.centerX)
        }.take(maxColumns)

        val labels = words.mapNotNull { w ->
            val m = PERIOD_LABEL.matchEntire(w.text.trim()) ?: return@mapNotNull null
            if (w.centerX > page.width * 0.3f) return@mapNotNull null
            val start = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val end = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            if (start < 1 || end < start) return@mapNotNull null
            Triple(start, end, w.centerY)
        }.sortedBy { it.third }
        if (labels.isEmpty()) return null

        val rowAnchors = mutableListOf<Triple<Int, Int, Float>>()
        labels.forEach { label ->
            val existing = rowAnchors.lastOrNull()
            if (existing == null || kotlin.math.abs(existing.third - label.third) > 10f) {
                rowAnchors += label
            }
        }
        val rows = rowAnchors.mapIndexed { i, (start, end, y) ->
            val top = if (i == 0) y - 10f else (rowAnchors[i - 1].third + y) / 2f
            val bottom = if (i == rowAnchors.size - 1) page.height else (y + rowAnchors[i + 1].third) / 2f
            PeriodRow(start, end, top, bottom, y)
        }
        if (rows.isEmpty()) return null
        return TimetableGrid(columns, rows, page.width, page.height)
    }

    fun wordsInCell(words: List<PdfWord>, column: WeekdayColumn, row: PeriodRow): List<PdfWord> =
        words.filter { it.centerX in column.left..column.right && it.centerY in row.top..row.bottom }
            .sortedWith(compareBy({ it.centerY }, { it.x }))
}


/** 把 PDF 页面旋转统一到显示坐标系（y 从上到下），供行列检测使用。 */
object PdfRotationNormalizer {
    fun normalize(page: PdfPageLayout): PdfPageLayout {
        val r = ((page.rotation % 360) + 360) % 360
        if (r == 0) return page
        val w = page.width
        val h = page.height
        val words = page.words.map { p ->
            when (r) {
                90 -> PdfWord(p.text, h - (p.y + p.height), p.x, p.height, p.width, p.page)
                180 -> PdfWord(p.text, w - (p.x + p.width), h - (p.y + p.height), p.width, p.height, p.page)
                270 -> PdfWord(p.text, p.y, w - (p.x + p.width), p.height, p.width, p.page)
                else -> p
            }
        }
        val newWidth = if (r == 90 || r == 270) h else w
        val newHeight = if (r == 90 || r == 270) w else h
        return PdfPageLayout(page.pageIndex, newWidth, newHeight, 0, words)
    }
}
