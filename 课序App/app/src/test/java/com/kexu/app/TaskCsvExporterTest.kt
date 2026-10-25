package com.kexu.app

import com.kexu.app.domain.Task
import com.kexu.app.domain.TaskCsvExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskCsvExporterTest {

    @Test
    fun exportsHeaderAndRows() {
        val tasks = listOf(
            Task(
                id = "t1",
                title = "复习, 第一章",
                priority = "high",
                estimatedMinutes = 60,
                deadline = LocalDate.of(2026, 10, 30),
                week = 8,
                weekday = 2,
                from = "14:00",
                to = "15:00"
            ),
            Task(id = "t2", title = "写作业", priority = "low", done = true)
        )
        val csv = TaskCsvExporter.export(tasks)
        val lines = csv.trim().split('\n')
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("标题,课程,优先级"))
        assertTrue(csv.contains("\"复习, 第一章\""))
        assertTrue(csv.contains("高,60,2026-10-30,未完成,8,周三,14:00,15:00"))
        assertTrue(csv.contains("低,,,已完成"))
    }

    @Test
    fun emptyListKeepsHeader() {
        val csv = TaskCsvExporter.export(emptyList())
        assertEquals(1, csv.trim().split('\n').size)
    }
}
