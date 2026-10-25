package com.kexu.app.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 图片 / 扫描 PDF 的课表版面恢复（纯 Kotlin，可 JVM 单测）。
 *
 * 流程：
 * 1. 找星期表头（支持一行里多个星期、每格一行、单字表头；表头竖向排列时自动转置一次）；
 * 2. 找左侧节次标签（1-2 / 第1-2节 / 08:00-09:35…，时间标签用节次时间轴映射）；
 * 3. 用表头中心 x 切列、节次标签 y 切行，把 OCR 文字行分配到「星期列」；
 * 4. 同一列内按垂直间距聚成课程块，块的节次跨度 = 覆盖到的行范围（跨行课不会丢）；
 * 5. 没有网格的页面走「课程列表」兜底，只解析周数/教师/地点并把星期节次留空交给后续标记待确认。
 *
 * 本类只做版面 + 结构判断，不生成 [com.kexu.app.domain.imports.CourseImportRecord]；
 * 记录生成在 [OcrCourseRecordParser] 中完成，便于分开测试。
 */
object OcrTableRecovery {

    private val WEEKDAY_REGEX = Regex("(?:星期|周|礼拜)([一二三四五六日天])")
    private val SINGLE_DAY = mapOf(
        "一" to 0, "二" to 1, "三" to 2, "四" to 3, "五" to 4, "六" to 5, "日" to 6, "天" to 6
    )
    private val PERIOD_RANGE = Regex("第?\\s*(\\d{1,2})\\s*[-—–~至]\\s*(\\d{1,2})\\s*节?")
    private val PERIOD_SINGLE = Regex("第\\s*(\\d{1,2})\\s*节")
    private val TIME_RANGE = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*[-—–~至]\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})")
    private val TITLE_REGEX = Regex("(课程表|课表|学期|学年|节次|星期|周次|教师|地点|教室|备注)")
    private val DETAIL_PREFIX = Regex("^(周数|周次|教师|老师|地点|教室|教学班|备注|课程性质|学分)\\s*[:：]")

    private data class DayAnchor(val day: Int, val x: Float, val y: Float, val line: OcrLine)

    private data class HeaderInfo(val anchors: List<DayAnchor>, val top: Float, val bottom: Float)

    private data class PeriodAnchor(val start: Int, val end: Int, val y: Float, val line: OcrLine)

    fun recover(page: OcrPage, axis: OcrPeriodAxis, maxPeriods: Int = axis.size): OcrPageStructure {
        val lines = page.lines.filter { it.compactText.isNotBlank() }
        if (lines.isEmpty()) {
            return OcrPageStructure(
                page.pageIndex, emptyList(), emptyList(), emptyList(), null,
                listOf(OcrStructureIssue(OcrStructureCode.NO_COURSE_BLOCK, "第 ${page.pageIndex + 1} 页没有识别到文字", page.pageIndex))
            )
        }
        recoverInternal(page.copy(lines = lines), axis, maxPeriods.coerceIn(1, 20))?.let { return it }
        // 表头竖向排列：把坐标转置后再试一次（兼容「星期在行、节次在列」的课表）
        val swapped = OcrPage(
            pageIndex = page.pageIndex,
            width = page.height,
            height = page.width,
            lines = lines.map { OcrLine(it.text, it.top, it.left, it.bottom, it.right) }
        )
        return recoverInternal(swapped, axis, maxPeriods.coerceIn(1, 20), allowTranspose = false)
            ?: OcrPageStructure(
                page.pageIndex, emptyList(), emptyList(), emptyList(), null,
                listOf(OcrStructureIssue(OcrStructureCode.NO_WEEKDAY_HEADER, "第 ${page.pageIndex + 1} 页未识别到星期表头", page.pageIndex))
            )
    }

    private fun recoverInternal(
        page: OcrPage,
        axis: OcrPeriodAxis,
        maxPeriods: Int,
        allowTranspose: Boolean = true
    ): OcrPageStructure? {
        val lines = page.lines
        val issues = mutableListOf<OcrStructureIssue>()
        val medianHeight = median(lines.map { it.height }.filter { it > 0f }) ?: 24f

        val header = findWeekdayHeader(lines, medianHeight)
        if (header != null && allowTranspose && header.anchors.size >= 2) {
            val xs = header.anchors.map { it.x }
            val ys = header.anchors.map { it.y }
            val xSpread = (xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f)
            val ySpread = (ys.maxOrNull() ?: 0f) - (ys.minOrNull() ?: 0f)
            val verticalHeader = xSpread < page.width * 0.18f && ySpread > page.height * 0.18f
            if (verticalHeader) return null // 交给外层转置
        }
        if (header == null) {
            issues += OcrStructureIssue(
                OcrStructureCode.NO_WEEKDAY_HEADER,
                "第 ${page.pageIndex + 1} 页未识别到星期表头，星期需要人工确认",
                page.pageIndex
            )
        }

        val columns = buildColumns(header?.anchors ?: emptyList(), page.width.toFloat())
        if (header != null && columns.isEmpty()) {
            issues += OcrStructureIssue(
                OcrStructureCode.NO_WEEKDAY_HEADER,
                "星期表头无法组成有效列，星期需要人工确认",
                page.pageIndex
            )
        }

        val leftBound = columns.firstOrNull()?.left ?: (page.width * 0.22f)
        val periodScan = findPeriodAnchors(lines, leftBound, axis, maxPeriods)
        val rows = buildRows(periodScan.anchors, header?.bottom, page.height.toFloat(), medianHeight)
        if (rows.isEmpty()) {
            issues += OcrStructureIssue(
                OcrStructureCode.NO_PERIOD_LABELS,
                "第 ${page.pageIndex + 1} 页未识别到节次标签，节次需要人工确认",
                page.pageIndex
            )
        } else if (rows.size < 3) {
            issues += OcrStructureIssue(
                OcrStructureCode.NO_PERIOD_LABELS,
                "第 ${page.pageIndex + 1} 页只识别到 ${rows.size} 个节次标签，请核对节次",
                page.pageIndex
            )
        }

        val blocks = if (columns.isNotEmpty() && header != null) {
            buildBlocks(page, columns, rows, periodScan.usedLines, medianHeight, header.bottom)
        } else emptyList()

        val resultBlocks: List<OcrCourseBlock>
        if (blocks.isNotEmpty()) {
            resultBlocks = blocks
        } else {
            // 没有「星期表头 + 网格」：先试「星期分组 + 课程列表」版式（地大教务 PDF/截图常见），
            // 再退化为纯课程列表（星期/节次留空，交给后续标待确认）。
            val dayGroups = findDayGroupAnchors(lines, page.width.toFloat(), medianHeight)
            val dayBlocks = if (dayGroups.isNotEmpty()) {
                buildDayGroupBlocks(page, dayGroups, periodScan.usedLines, axis, medianHeight, rows)
            } else emptyList()
            val listBlocks = if (dayBlocks.isEmpty()) buildListBlocks(page, periodScan.usedLines, header?.bottom) else emptyList()
            if (dayBlocks.isNotEmpty()) {
                issues += OcrStructureIssue(
                    OcrStructureCode.LIST_FALLBACK,
                    "第 ${page.pageIndex + 1} 页按「星期分组 + 课程列表」恢复，请核对每条课程的星期/节次",
                    page.pageIndex
                )
            } else if (listBlocks.isNotEmpty()) {
                issues += OcrStructureIssue(
                    OcrStructureCode.LIST_FALLBACK,
                    "第 ${page.pageIndex + 1} 页未恢复出表格网格，已按课程列表解析，星期/节次需要人工确认",
                    page.pageIndex
                )
            }
            resultBlocks = if (dayBlocks.isNotEmpty()) dayBlocks else listBlocks
        }
        if (resultBlocks.isEmpty()) {
            issues += OcrStructureIssue(
                OcrStructureCode.NO_COURSE_BLOCK,
                "第 ${page.pageIndex + 1} 页没有识别到课程内容",
                page.pageIndex
            )
        }
        return OcrPageStructure(
            pageIndex = page.pageIndex,
            columns = columns,
            rows = rows,
            blocks = resultBlocks,
            headerBottom = header?.bottom,
            issues = issues
        )
    }

    // ---------------------------------------------------------------- 表头

    private fun findWeekdayHeader(lines: List<OcrLine>, medianHeight: Float): HeaderInfo? {
        val anchors = mutableListOf<DayAnchor>()
        lines.forEach { line ->
            val compact = line.compactText
            val matches = WEEKDAY_REGEX.findAll(compact).toList()
            if (matches.isNotEmpty()) {
                for (m in matches) {
                    val day = SINGLE_DAY[m.groupValues[1]] ?: continue
                    val charIndex = (m.range.first + m.range.last) / 2f
                    val fraction = if (compact.isEmpty()) 0.5f else (charIndex + 0.5f) / compact.length
                    val x = line.left + fraction * line.width.coerceAtLeast(1f)
                    anchors += DayAnchor(day, x, line.centerY, line)
                }
            } else if (compact.length == 1) {
                val day = SINGLE_DAY[compact] ?: return@forEach
                anchors += DayAnchor(day, line.centerX, line.centerY, line)
            }
        }
        if (anchors.isEmpty()) return null

        // 按 y 聚类，取「不同星期数量最多」的那一簇（通常就是表头行）
        val clusters = mutableListOf<MutableList<DayAnchor>>()
        anchors.sortedBy { it.y }.forEach { anchor ->
            val last = clusters.lastOrNull()
            if (last == null || abs(last.map { it.y }.average().toFloat() - anchor.y) > max(medianHeight * 0.9f, 9f)) {
                clusters += mutableListOf(anchor)
            } else {
                last += anchor
            }
        }
        val best = clusters.maxByOrNull { c -> c.map { it.day }.distinct().size } ?: return null
        val days = best.map { it.day }.distinct()
        if (days.size < 2) return null
        // 同一星期出现多次（OCR 重复/标题里的“周”）时保留最靠左的一个
        val compactAnchors = best.groupBy { it.day }.map { (_, list) -> list.minByOrNull { it.x }!! }
            .sortedBy { it.x }
        val top = best.minOf { it.line.top }.toFloat()
        val bottom = best.maxOf { it.line.bottom }.toFloat()
        return HeaderInfo(compactAnchors, top, bottom)
    }

    private fun buildColumns(anchors: List<DayAnchor>, pageWidth: Float): List<OcrColumn> {
        if (anchors.size < 2) return emptyList()
        val sorted = anchors.sortedBy { it.x }
        val gaps = sorted.zipWithNext { a, b -> b.x - a.x }.filter { it > 1f }
        val gap = median(gaps) ?: (pageWidth / (sorted.size + 1))
        return sorted.mapIndexed { index, anchor ->
            val left = if (index == 0) max(0f, anchor.x - gap * 0.55f) else (sorted[index - 1].x + anchor.x) / 2f
            val right = if (index == sorted.size - 1) min(pageWidth, anchor.x + gap * 0.55f) else (anchor.x + sorted[index + 1].x) / 2f
            OcrColumn(anchor.day, left, right, anchor.x)
        }.filter { it.right > it.left }
    }

    // ---------------------------------------------------------------- 节次行

    private data class PeriodScanResult(val anchors: List<PeriodAnchor>, val usedLines: Set<OcrLine>)

    private fun findPeriodAnchors(
        lines: List<OcrLine>,
        leftBound: Float,
        axis: OcrPeriodAxis,
        maxPeriods: Int
    ): PeriodScanResult {
        val raw = mutableListOf<PeriodAnchor>()
        val used = mutableSetOf<OcrLine>()
        lines.forEach { line ->
            val compact = line.compactText
            if (compact.length > 32) return@forEach
            // 「周数:3-8」「教师:…」这类课程详情行不是节次标签，避免把 3-8 误判成节次
            if (compact.contains("周") || compact.contains("教师") || compact.contains("老师") ||
                compact.contains("地点") || compact.contains("教室") || compact.contains("教学班") ||
                compact.contains("学分")
            ) {
                return@forEach
            }
            if (TITLE_REGEX.containsMatchIn(compact) && !TIME_RANGE.containsMatchIn(compact) && !PERIOD_RANGE.containsMatchIn(compact)) {
                return@forEach
            }
            val nearLeft = line.centerX < leftBound * 1.15f
            if (!nearLeft) return@forEach
            var parsed: PeriodAnchor? = null
            // 1) 时间范围 → 用节次时间轴映射
            TIME_RANGE.find(compact)?.let { m ->
                val sh = m.groupValues[1].toIntOrNull() ?: return@let
                val sm = m.groupValues[2].toIntOrNull() ?: return@let
                val eh = m.groupValues[3].toIntOrNull() ?: return@let
                val em = m.groupValues[4].toIntOrNull() ?: return@let
                val startSection = axis.sectionAt(sh * 60 + sm)
                val endSection = axis.sectionAt(eh * 60 + em) ?: startSection
                if (startSection != null) parsed = PeriodAnchor(startSection, max(startSection, endSection ?: startSection), line.centerY, line)
            }
            // 2) 数字节次范围："1-2"、"第3-4节"
            if (parsed == null) {
                PERIOD_RANGE.find(compact)?.let { m ->
                    val s = m.groupValues[1].toIntOrNull() ?: return@let
                    val e = m.groupValues[2].toIntOrNull() ?: return@let
                    if (s in 1..maxPeriods && e in s..maxPeriods) {
                        parsed = PeriodAnchor(s, e, line.centerY, line)
                    }
                }
            }
            // 3) 单节次："第5节"
            if (parsed == null) {
                PERIOD_SINGLE.find(compact)?.let { m ->
                    val s = m.groupValues[1].toIntOrNull() ?: return@let
                    if (s in 1..maxPeriods) parsed = PeriodAnchor(s, s, line.centerY, line)
                }
            }
            if (parsed != null) {
                raw += parsed!!
                used += line
            }
        }
        if (raw.isEmpty()) return PeriodScanResult(emptyList(), used)

        // 同一行的节次数字标签和上课时间标签可能被 OCR 拆成两行：按 y 合并
        val merged = mutableListOf<MutableList<PeriodAnchor>>()
        raw.sortedBy { it.y }.forEach { anchor ->
            val last = merged.lastOrNull()
            if (last == null || abs(last.map { it.y }.average().toFloat() - anchor.y) > 18f) {
                merged += mutableListOf(anchor)
            } else {
                last += anchor
            }
        }
        val anchors = merged.mapNotNull { group ->
            val numeric = group.filter { !TIME_RANGE.containsMatchIn(it.line.compactText) }
            val chosen = numeric.maxByOrNull { it.end - it.start } ?: group[0]
            val start = group.minOf { it.start }
            val end = group.maxOf { it.end }
            val y = group.map { it.y }.average().toFloat()
            PeriodAnchor(start, max(start, end), y, chosen.line)
        }.sortedBy { it.y }
        return PeriodScanResult(anchors.take(20), used)
    }

    private fun buildRows(anchors: List<PeriodAnchor>, headerBottom: Float?, pageHeight: Float, medianHeight: Float): List<OcrRow> {
        if (anchors.isEmpty()) return emptyList()
        val ys = anchors.map { it.y }
        val measuredGap = if (ys.size >= 2) median(ys.zipWithNext { a, b -> b - a }.filter { it > 0f }) else null
        val gap = measuredGap ?: (medianHeight * 3)
        return anchors.mapIndexed { index, anchor ->
            val top = when {
                index > 0 -> (ys[index - 1] + anchor.y) / 2f
                headerBottom != null -> min(headerBottom + 2f, anchor.y - medianHeight)
                else -> max(0f, anchor.y - gap / 2f)
            }
            val bottom = if (index < anchors.size - 1) (anchor.y + ys[index + 1]) / 2f else pageHeight
            OcrRow(anchor.start, anchor.end, top, max(top + 1f, bottom), anchor.y)
        }
    }

    // ---------------------------------------------------------------- 课程块

    private fun buildBlocks(
        page: OcrPage,
        columns: List<OcrColumn>,
        rows: List<OcrRow>,
        periodLabelLines: Set<OcrLine>,
        medianHeight: Float,
        headerBottom: Float
    ): List<OcrCourseBlock> {
        val candidateLines = page.lines.filter { line ->
            line !in periodLabelLines &&
                line.centerY > headerBottom + 1f &&
                columns.any { line.centerX in it.left..it.right }
        }
        if (candidateLines.isEmpty()) return emptyList()
        val rowHeights = rows.map { it.bottom - it.top }.filter { it > 0 }
        val medianRowHeight = median(rowHeights) ?: (medianHeight * 3)
        val gapThreshold = max(medianHeight * 1.55f, medianRowHeight * 0.3f)

        val blocks = mutableListOf<OcrCourseBlock>()
        columns.forEach { column ->
            val columnLines = candidateLines
                .filter { it.centerX in column.left..column.right }
                .sortedBy { it.top }
            if (columnLines.isEmpty()) return@forEach
            val clusters = mutableListOf<MutableList<OcrLine>>()
            columnLines.forEach { line ->
                val last = clusters.lastOrNull()
                val lastBottom = last?.maxOfOrNull { it.bottom }?.toFloat()
                if (last == null || lastBottom == null || line.top - lastBottom > gapThreshold) {
                    clusters += mutableListOf(line)
                } else {
                    last += line
                }
            }
            val merged = mutableListOf<MutableList<OcrLine>>()
            clusters.forEach { cluster ->
                val previous = merged.lastOrNull()
                if (previous != null && shouldMerge(previous, cluster)) {
                    previous += cluster
                } else {
                    merged += cluster
                }
            }
            merged.forEach { cluster ->
                val sortedLines = cluster.sortedWith(compareBy({ it.centerY }, { it.left }))
                val rowIndices = sortedLines.mapNotNull { line -> rows.indexOfFirst { line.centerY in it.top..it.bottom }.takeIf { it >= 0 } }
                val start = rowIndices.minOrNull()?.let { rows[it].startSection }
                val end = rowIndices.maxOrNull()?.let { rows[it].endSection }
                blocks += OcrCourseBlock(
                    day = column.day,
                    startSection = start,
                    endSection = end,
                    lines = sortedLines,
                    rowIndex = rowIndices.minOrNull(),
                    pageIndex = page.pageIndex
                )
            }
        }
        return blocks
    }

    /** 跨行合并的课程块：名称在上、详情在下时合并为一个块；两门都有周数信息的课不合并。 */
    private fun shouldMerge(upper: List<OcrLine>, lower: List<OcrLine>): Boolean {
        val upperText = upper.joinToString("") { it.compactText }
        val lowerText = lower.joinToString("") { it.compactText }
        val lowerFirst = lower.firstOrNull()?.compactText.orEmpty()
        val upperHasDetail = upperText.contains("周数") || upperText.contains("教师") || upperText.contains("地点")
        val lowerHasDetail = lowerText.contains("周数") || lowerText.contains("教师") || lowerText.contains("地点")
        if (!lowerHasDetail) return false
        // 下方第一行是纯详情字段 → 一定属于上方课程块的续行
        if (DETAIL_PREFIX.containsMatchIn(lowerFirst)) return true
        // 上方只有课程名、下方才有周数/教师/地点 → 同一块的跨行文本
        if (!upperHasDetail && lowerHasDetail) return true
        return false
    }

    // ---------------------------------------------------------------- 星期分组列表（地大 PDF/截图版式）

    private data class DayGroupAnchor(val day: Int, val y: Float, val line: OcrLine)

    /** 识别「每个星期一组课程」的列表版式：星期标签在左列、纵向分散，而不是同一行的表头。 */
    private fun findDayGroupAnchors(lines: List<OcrLine>, pageWidth: Float, medianHeight: Float): List<DayGroupAnchor> {
        val candidates = mutableListOf<DayGroupAnchor>()
        lines.forEach { line ->
            val compact = line.compactText
            if (compact.length > 5) return@forEach
            val day = weekdayOf(compact) ?: return@forEach
            if (line.centerX > pageWidth * 0.32f) return@forEach
            candidates += DayGroupAnchor(day, line.centerY, line)
        }
        if (candidates.size < 3) return emptyList()
        // 同一 y 出现多个星期 → 是表头行，交给网格路径处理
        val yClusters = mutableListOf<MutableList<DayGroupAnchor>>()
        candidates.sortedBy { it.y }.forEach { anchor ->
            val last = yClusters.lastOrNull()
            if (last == null || abs(last.map { it.y }.average().toFloat() - anchor.y) > max(medianHeight * 0.9f, 9f)) {
                yClusters += mutableListOf(anchor)
            } else {
                last += anchor
            }
        }
        if (yClusters.any { cluster -> cluster.map { it.day }.distinct().size >= 2 }) return emptyList()
        val sequence = mutableListOf<DayGroupAnchor>()
        candidates.sortedBy { it.y }.forEach { anchor ->
            if (sequence.isEmpty() || sequence.last().day != anchor.day) sequence += anchor
        }
        if (sequence.map { it.day }.distinct().size < 3) return emptyList()
        if (sequence.last().y - sequence.first().y < medianHeight * 6f) return emptyList()
        return sequence
    }

    private fun weekdayOf(compact: String): Int? {
        if (compact.isEmpty()) return null
        WEEKDAY_REGEX.find(compact)?.let { return SINGLE_DAY[it.groupValues[1]] }
        return SINGLE_DAY[compact]
    }

    /**
     * 列表版式：星期标签按组纵向排列，每行是「节次 + 课程名 + 周数/教师/地点」。
     * 星期按相邻标签中线分段归属；课程名取同一视觉行最左侧非详情文字；节次取同一行的节次标签或在最近节次行。
     */
    private fun buildDayGroupBlocks(
        page: OcrPage,
        anchors: List<DayGroupAnchor>,
        periodLabelLines: Set<OcrLine>,
        axis: OcrPeriodAxis,
        medianHeight: Float,
        rows: List<OcrRow>
    ): List<OcrCourseBlock> {
        val dayLabelLines = anchors.map { it.line }.toSet()
        val allLines = page.lines.filter {
            it.compactText.isNotBlank() && it !in periodLabelLines && it !in dayLabelLines
        }
        val details = allLines
            .filter { it.compactText.contains("周数") || it.compactText.contains("周次") }
            .sortedBy { it.centerY }
        if (details.isEmpty()) return emptyList()
        val blocks = mutableListOf<OcrCourseBlock>()
        details.forEach { detail ->
            val day = dayForY(detail.centerY, anchors) ?: return@forEach
            val sameRow = allLines.filter {
                it !== detail && abs(it.centerY - detail.centerY) <= medianHeight * 1.0f
            }
            val detailTextLines = mutableListOf(detail)
            allLines.filter {
                it !== detail && it.centerY > detail.centerY &&
                    it.centerY <= detail.centerY + medianHeight * 1.9f &&
                    it.left >= detail.left - 24f &&
                    !it.compactText.contains("周数") && !it.compactText.contains("周次")
            }.sortedBy { it.centerY }.forEach { continuation -> detailTextLines += continuation }
            val periodLine = sameRow.firstOrNull { parsePeriodRange(it.compactText, axis) != null }
            val period = periodLine?.let { parsePeriodRange(it.compactText, axis) }
            val nameLine = sameRow
                .filter { it !== periodLine && !it.compactText.contains("周数") && !it.compactText.contains("周次") }
                .filter { !isPeriodLike(it.compactText, axis) }
                .minByOrNull { it.left }
                ?: allLines.filter {
                    it.centerY < detail.centerY && it.centerY >= detail.centerY - medianHeight * 2.6f &&
                        it.left < detail.left && !it.compactText.contains("周数") && !it.compactText.contains("周次")
                }.maxByOrNull { it.centerY }
            val row = nearestRowAt(detail.centerY, rows)
            val lines = buildList {
                if (nameLine != null) add(nameLine)
                if (periodLine != null) add(periodLine)
                addAll(detailTextLines)
            }
            blocks += OcrCourseBlock(
                day = day,
                startSection = period?.first ?: row?.startSection,
                endSection = period?.second ?: row?.endSection,
                lines = lines,
                pageIndex = page.pageIndex
            )
        }
        return blocks
    }

    private fun dayForY(y: Float, anchors: List<DayGroupAnchor>): Int? {
        if (anchors.isEmpty()) return null
        var current = anchors.first().day
        for (i in 0 until anchors.size - 1) {
            val mid = (anchors[i].y + anchors[i + 1].y) / 2f
            if (y > mid) current = anchors[i + 1].day else break
        }
        return current
    }

    private fun nearestRowAt(y: Float, rows: List<OcrRow>): OcrRow? =
        rows.minByOrNull { abs(it.labelCenterY - y) }?.takeIf { abs(it.labelCenterY - y) <= 80f }

    private fun parsePeriodRange(compact: String, axis: OcrPeriodAxis): Pair<Int, Int>? {
        if (compact.length > 24) return null
        TIME_RANGE.find(compact)?.let { m ->
            val sh = m.groupValues[1].toIntOrNull()
            val sm = m.groupValues[2].toIntOrNull()
            val eh = m.groupValues[3].toIntOrNull()
            val em = m.groupValues[4].toIntOrNull()
            if (sh != null && sm != null && eh != null && em != null) {
                val start = axis.sectionAt(sh * 60 + sm)
                if (start != null) {
                    val end = axis.sectionAt(eh * 60 + em) ?: start
                    return start to max(start, end)
                }
            }
        }
        PERIOD_RANGE.find(compact)?.let { m ->
            val s = m.groupValues[1].toIntOrNull()
            val e = m.groupValues[2].toIntOrNull()
            if (s != null && e != null && s in 1..20 && e in s..20) return s to e
        }
        PERIOD_SINGLE.find(compact)?.let { m ->
            val s = m.groupValues[1].toIntOrNull()
            if (s != null && s in 1..20) return s to s
        }
        return null
    }

    private fun isPeriodLike(compact: String, axis: OcrPeriodAxis): Boolean =
        parsePeriodRange(compact, axis) != null || TITLE_REGEX.containsMatchIn(compact)

    // ---------------------------------------------------------------- 列表兜底

    private fun buildListBlocks(page: OcrPage, periodLabelLines: Set<OcrLine>, headerBottom: Float?): List<OcrCourseBlock> {
        val lines = page.lines
            .filter { it !in periodLabelLines }
            .filter { headerBottom == null || it.centerY > headerBottom }
            .sortedBy { it.centerY }
        if (lines.isEmpty()) return emptyList()
        val blocks = mutableListOf<OcrCourseBlock>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.compactText.contains("周数") || line.compactText.contains("周次")) {
                val group = mutableListOf<OcrLine>()
                val nameLine = blocks.lastOrNull()?.lines?.lastOrNull()
                if (nameLine != null && blocks.isNotEmpty()) {
                    group += nameLine
                    blocks.removeAt(blocks.size - 1)
                }
                group += line
                var next = index + 1
                while (next < lines.size && !lines[next].compactText.contains("周数") && !lines[next].compactText.contains("周次")) {
                    val text = lines[next].compactText
                    if (DETAIL_PREFIX.containsMatchIn(text) || text.length <= 30) {
                        group += lines[next]
                        next++
                    } else break
                }
                blocks += OcrCourseBlock(null, null, null, group, null, page.pageIndex)
                index = next
            } else {
                if (TITLE_REGEX.containsMatchIn(line.compactText)) {
                    index++
                } else {
                    blocks += OcrCourseBlock(null, null, null, listOf(line), null, page.pageIndex)
                    index++
                }
            }
        }
        // 只保留包含周数信息的列表块；没有周数信息的散行交给上层作为“未识别”提示
        return blocks.filter { b -> b.lines.any { it.compactText.contains("周数") || it.compactText.contains("周次") } }
    }

    // ---------------------------------------------------------------- 工具

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
