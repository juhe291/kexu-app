package com.kexu.app

import com.kexu.app.data.imports.CugXlsxImporter
import org.junit.Assert.assertTrue
import org.junit.Test

class CugXlsxImporterTest {

    data class Expected(
        val day: Int,
        val start: Int,
        val end: Int,
        val weeks: Set<Int>,
        val teacher: String,
        val room: String,
        val name: String
    )

    private fun expected(): List<Expected> {
        val text = TestFixtures.require("cug_expected.csv").bufferedReader().readText()
        return text.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
            val c = line.split(';')
            Expected(
                day = c[0].toInt(),
                start = c[1].toInt(),
                end = c[2].toInt(),
                weeks = c[3].split(',').map { it.toInt() }.toSet(),
                teacher = c[4],
                room = c[5],
                name = c[6]
            )
        }.toList()
    }

    @Test
    fun xlsxMatchesExpectedGroundTruth() {
        val bytes = TestFixtures.require("cug_wh_timetable.xlsx").readBytes()
        val records = CugXlsxImporter().import(bytes)
        println("XLSX_RECORDS=" + records.size)
        records.forEach { r ->
            println("${r.courseName} day=${r.dayOfWeek} p=${r.startSection}-${r.endSection} weeks=${r.weeks.sorted()} teacher=${r.teacher} room=${r.location}")
        }
        val scheduled = records.filter { it.dayOfWeek != null }
        val problems = mutableListOf<String>()
        var matched = 0
        expected().forEach { e ->
            val candidates = scheduled.filter {
                it.courseName == e.name && it.dayOfWeek == e.day && it.startSection == e.start && it.endSection == e.end
            }
            val r = candidates.firstOrNull { it.weeks == e.weeks } ?: candidates.firstOrNull()
            if (r == null) {
                problems += "缺少课程：" + e.name + " day=" + e.day
            } else {
                matched++
                if (r.weeks != e.weeks) problems += e.name + " 周次不一致"
                if (e.teacher.isNotBlank() && !r.teacher.contains(e.teacher)) problems += e.name + " 教师不一致"
                if (e.room.isNotBlank() && !r.location.contains(e.room)) problems += e.name + " 地点不一致"
            }
        }
        println("EXPECTED=" + expected().size + " MATCHED=" + matched + " PROBLEMS=" + problems.size)
        problems.forEach { println("PROBLEM " + it) }
        assertTrue("应匹配 XLSX 全部有时间的课程", matched >= expected().size)
        assertTrue("字段应一致：" + problems.take(6), problems.isEmpty())
        assertTrue("其他课程应保留", records.any { it.courseName.contains("数据结构课程设计") && it.dayOfWeek == null })
    }
}
