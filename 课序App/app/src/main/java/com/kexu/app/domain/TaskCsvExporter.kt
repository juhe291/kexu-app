package com.kexu.app.domain

/** 任务导出为 CSV（UTF-8 BOM 由写入方决定），便于在 Excel/WPS 中查看。 */
object TaskCsvExporter {

    fun export(tasks: List<Task>): String {
        val sb = StringBuilder()
        sb.append("标题,课程,优先级,预计分钟,截止日期,状态,教学周,星期,开始,结束\n")
        tasks.forEach { task ->
            sb.append(csv(task.title)).append(',')
            sb.append(csv(task.courseId ?: "")).append(',')
            sb.append(csv(priorityLabel(task.priority))).append(',')
            sb.append(task.estimatedMinutes?.toString() ?: "").append(',')
            sb.append(task.deadline?.toString() ?: "").append(',')
            sb.append(if (task.done) "已完成" else "未完成").append(',')
            sb.append(task.week?.toString() ?: "").append(',')
            sb.append(task.weekday?.let { if (it in 0..6) KexuCore.WEEKDAY_CN[it] else "" } ?: "").append(',')
            sb.append(task.from ?: "").append(',')
            sb.append(task.to ?: "").append('\n')
        }
        return sb.toString()
    }

    private fun csv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(',') || escaped.contains('"') || escaped.contains('\n')) "\"$escaped\"" else escaped
    }

    private fun priorityLabel(priority: String): String = when (priority) {
        "high" -> "高"
        "low" -> "低"
        else -> "中"
    }
}
