package com.kexu.app

import com.kexu.app.domain.imports.ImportTemplates
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportTemplateTest {

    @Test
    fun csvTemplateHasHeaderAndExamples() {
        val csv = ImportTemplates.csvTemplate()
        val lines = csv.lines()
        assertTrue(lines.first().startsWith("课程名,教师,地点,星期,开始节次,结束节次,周次,单双周"))
        assertTrue(csv.contains("高等数学"))
        assertTrue(csv.contains("单周"))
    }

    @Test
    fun jsonTemplateDescribesSchema() {
        val json = ImportTemplates.jsonTemplate()
        assertTrue(json.contains("kexu-timetable/v1"))
        assertTrue(json.contains("\"meetings\""))
        assertTrue(json.contains("\"weeks\""))
    }

    @Test
    fun aiPromptExplainsWeekRules() {
        val prompt = ImportTemplates.aiPromptTemplate()
        assertTrue(prompt.contains("1-5,7-11"))
        assertTrue(prompt.contains("单双周"))
        assertTrue(prompt.contains("不要编造"))
    }
}
