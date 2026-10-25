package com.kexu.app.data

import org.json.JSONArray
import org.json.JSONObject

/** 本地 JSON 备份：课程、节次、任务、长期目标、单次例外与学期信息。 */
object BackupCodec {

    /** v2 增加 courseExceptions；恢复时兼容 v1 备份（没有该数组则视为空）。 */
    private const val VERSION = 2

    suspend fun export(dao: KexuDao): String {
        val root = JSONObject()
            .put("app", "kexu")
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())

        dao.semesterSnapshot()?.let { semester ->
            root.put(
                "semester",
                JSONObject()
                    .put("name", semester.name)
                    .put("startDate", semester.startDate)
                    .put("totalWeeks", semester.totalWeeks)
            )
        }

        val periods = JSONArray()
        dao.periodsSnapshot().forEach { period ->
            periods.put(JSONObject().put("from", period.fromTime).put("to", period.toTime))
        }
        root.put("periods", periods)

        val courses = JSONArray()
        dao.coursesSnapshot().forEach { courseWithMeetings ->
            val meetings = JSONArray()
            courseWithMeetings.meetings.forEach { meeting ->
                meetings.put(
                    JSONObject()
                        .put("weekday", meeting.weekday)
                        .put("startPeriod", meeting.startPeriod)
                        .put("endPeriod", meeting.endPeriod)
                        .put("parity", meeting.parity ?: JSONObject.NULL)
                        .put("weeksCsv", meeting.weeksCsv ?: JSONObject.NULL)
                        .put("room", meeting.room ?: JSONObject.NULL)
                )
            }
            val course = courseWithMeetings.course
            courses.put(
                JSONObject()
                    .put("id", course.id)
                    .put("name", course.name)
                    .put("teacher", course.teacher)
                    .put("room", course.room)
                    .put("colorHex", course.colorHex)
                    .put("meetings", meetings)
            )
        }
        root.put("courses", courses)

        val tasks = JSONArray()
        dao.tasksSnapshot().forEach { task ->
            tasks.put(
                JSONObject()
                    .put("id", task.id)
                    .put("title", task.title)
                    .put("courseId", task.courseId ?: JSONObject.NULL)
                    .put("priority", task.priority)
                    .put("estimatedMinutes", task.estimatedMinutes ?: JSONObject.NULL)
                    .put("deadline", task.deadline ?: JSONObject.NULL)
                    .put("done", task.done)
                    .put("source", task.source)
                    .put("week", task.week ?: JSONObject.NULL)
                    .put("weekday", task.weekday ?: JSONObject.NULL)
                    .put("fromTime", task.fromTime ?: JSONObject.NULL)
                    .put("toTime", task.toTime ?: JSONObject.NULL)
                    .put("goalId", task.goalId ?: JSONObject.NULL)
                    .put("repeatKind", task.repeatKind)
                    .put("repeatUntil", task.repeatUntil ?: JSONObject.NULL)
                    .put("repeatSeriesId", task.repeatSeriesId ?: JSONObject.NULL)
            )
        }
        root.put("tasks", tasks)

        val goals = JSONArray()
        dao.goalsSnapshot().forEach { goal ->
            goals.put(
                JSONObject()
                    .put("id", goal.id)
                    .put("title", goal.title)
                    .put("totalWeeks", goal.totalWeeks)
                    .put("currentWeek", goal.currentWeek)
                    .put("weeklyHours", goal.weeklyHours)
                    .put("doneHours", goal.doneHours)
                    .put("progress", goal.progress)
                    .put("next", goal.next)
            )
        }
        root.put("goals", goals)

        val exceptions = JSONArray()
        dao.exceptionsSnapshot().forEach { exception ->
            exceptions.put(
                JSONObject()
                    .put("id", exception.id)
                    .put("courseId", exception.courseId)
                    .put("week", exception.week)
                    .put("weekday", exception.weekday)
                    .put("startPeriod", exception.startPeriod)
                    .put("endPeriod", exception.endPeriod)
                    .put("kind", exception.kind)
                    .put("newWeekday", exception.newWeekday ?: JSONObject.NULL)
                    .put("newStartPeriod", exception.newStartPeriod ?: JSONObject.NULL)
                    .put("newEndPeriod", exception.newEndPeriod ?: JSONObject.NULL)
                    .put("newRoom", exception.newRoom ?: JSONObject.NULL)
                    .put("note", exception.note)
            )
        }
        root.put("courseExceptions", exceptions)
        return root.toString(2)
    }

    suspend fun restore(dao: KexuDao, json: String): Boolean {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return false
        if (root.optString("app") != "kexu") return false
        if (root.optInt("version", 0) > VERSION) return false

        dao.clearAll()
        root.optJSONObject("semester")?.let { semester ->
            dao.upsertSemester(
                SemesterEntity(
                    id = 1,
                    name = semester.optString("name", "当前学期"),
                    startDate = semester.optString("startDate", "2026-09-07"),
                    totalWeeks = semester.optInt("totalWeeks", 20)
                )
            )
        }
        val periodsJson = root.optJSONArray("periods")
        if (periodsJson != null) {
            val list = ArrayList<PeriodEntity>()
            for (i in 0 until periodsJson.length()) {
                val period = periodsJson.optJSONObject(i) ?: continue
                list.add(PeriodEntity(i, period.optString("from"), period.optString("to")))
            }
            if (list.isNotEmpty()) dao.upsertPeriods(list)
        }
        val coursesJson = root.optJSONArray("courses")
        if (coursesJson != null) {
            for (i in 0 until coursesJson.length()) {
                val course = coursesJson.optJSONObject(i) ?: continue
                val id = course.optString("id").ifBlank { "course-$i" }
                dao.upsertCourse(
                    CourseEntity(
                        id = id,
                        name = course.optString("name", "未命名课程"),
                        teacher = course.optString("teacher"),
                        room = course.optString("room"),
                        colorHex = course.optString("colorHex", "#2563EB")
                    )
                )
                val meetings = course.optJSONArray("meetings") ?: continue
                val list = ArrayList<MeetingEntity>()
                for (j in 0 until meetings.length()) {
                    val meeting = meetings.optJSONObject(j) ?: continue
                    list.add(
                        MeetingEntity(
                            courseId = id,
                            weekday = meeting.optInt("weekday", 0),
                            startPeriod = meeting.optInt("startPeriod", 1),
                            endPeriod = meeting.optInt("endPeriod", 2),
                            parity = meeting.optNullableString("parity"),
                            weeksCsv = meeting.optNullableString("weeksCsv"),
                            room = meeting.optNullableString("room")
                        )
                    )
                }
                if (list.isNotEmpty()) dao.insertMeetings(list)
            }
        }
        val tasksJson = root.optJSONArray("tasks")
        if (tasksJson != null) {
            val list = ArrayList<TaskEntity>()
            for (i in 0 until tasksJson.length()) {
                val task = tasksJson.optJSONObject(i) ?: continue
                list.add(
                    TaskEntity(
                        id = task.optString("id").ifBlank { "task-$i" },
                        title = task.optString("title", "未命名任务"),
                        courseId = task.optNullableString("courseId"),
                        priority = task.optString("priority", "medium"),
                        estimatedMinutes = task.optNullableInt("estimatedMinutes"),
                        deadline = task.optNullableString("deadline"),
                        done = task.optBoolean("done", false),
                        source = task.optString("source", "manual"),
                        week = task.optNullableInt("week"),
                        weekday = task.optNullableInt("weekday"),
                        fromTime = task.optNullableString("fromTime"),
                        toTime = task.optNullableString("toTime"),
                        goalId = task.optNullableString("goalId"),
                        repeatKind = task.optString("repeatKind", "none").ifBlank { "none" },
                        repeatUntil = task.optNullableString("repeatUntil"),
                        repeatSeriesId = task.optNullableString("repeatSeriesId")
                    )
                )
            }
            if (list.isNotEmpty()) dao.upsertTasks(list)
        }
        val goalsJson = root.optJSONArray("goals")
        if (goalsJson != null) {
            val list = ArrayList<GoalEntity>()
            for (i in 0 until goalsJson.length()) {
                val goal = goalsJson.optJSONObject(i) ?: continue
                list.add(
                    GoalEntity(
                        id = goal.optString("id").ifBlank { "goal-$i" },
                        title = goal.optString("title", "长期目标"),
                        totalWeeks = goal.optInt("totalWeeks", 12),
                        currentWeek = goal.optInt("currentWeek", 1),
                        weeklyHours = goal.optInt("weeklyHours", 4),
                        doneHours = goal.optInt("doneHours", 0),
                        progress = goal.optInt("progress", 0),
                        next = goal.optString("next")
                    )
                )
            }
            if (list.isNotEmpty()) dao.upsertGoals(list)
        }
        val exceptionsJson = root.optJSONArray("courseExceptions")
        if (exceptionsJson != null) {
            val list = ArrayList<CourseExceptionEntity>()
            for (i in 0 until exceptionsJson.length()) {
                val exception = exceptionsJson.optJSONObject(i) ?: continue
                val courseId = exception.optString("courseId")
                if (courseId.isBlank()) continue
                list.add(
                    CourseExceptionEntity(
                        id = exception.optString("id").ifBlank { "exception-$i" },
                        courseId = courseId,
                        week = exception.optInt("week", 1),
                        weekday = exception.optInt("weekday", 0),
                        startPeriod = exception.optInt("startPeriod", 1),
                        endPeriod = exception.optInt("endPeriod", 2),
                        kind = exception.optString("kind", "cancel"),
                        newWeekday = exception.optNullableInt("newWeekday"),
                        newStartPeriod = exception.optNullableInt("newStartPeriod"),
                        newEndPeriod = exception.optNullableInt("newEndPeriod"),
                        newRoom = exception.optNullableString("newRoom"),
                        note = exception.optString("note")
                    )
                )
            }
            if (list.isNotEmpty()) dao.upsertExceptions(list)
        }
        return true
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)
}
