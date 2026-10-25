package com.kexu.app.data

import com.kexu.app.domain.Course
import com.kexu.app.domain.CourseException
import com.kexu.app.domain.Goal
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.Meeting
import com.kexu.app.domain.Period
import com.kexu.app.domain.Semester
import com.kexu.app.domain.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/** 数据仓库：把 Room 实体映射成领域模型，并统一提供种子数据与写操作。 */
class KexuRepository(private val dao: KexuDao) {

    data class CoreData(
        val semester: Semester,
        val periods: List<Period>,
        val courses: List<Course>,
        val tasks: List<Task>,
        val goals: List<Goal>,
        val exceptions: List<CourseException> = emptyList()
    )

    fun dataFlow(): Flow<CoreData> {
        val core = combine(
            dao.semesterFlow(),
            dao.periodsFlow(),
            dao.coursesFlow(),
            dao.tasksFlow(),
            dao.goalsFlow()
        ) { semester, periods, courses, tasks, goals ->
            CoreData(
                semester = semester?.toDomain() ?: Semester(),
                periods = periods.map { Period(it.fromTime, it.toTime) }.ifEmpty { KexuCore.defaultPeriods() },
                courses = courses.map { it.toDomain() },
                tasks = tasks.map { it.toDomain() },
                goals = goals.map { it.toDomain() }
            )
        }
        return core.combine(dao.exceptionsFlow()) { data, exceptions ->
            data.copy(exceptions = exceptions.map { it.toDomain() })
        }
    }

    suspend fun ensureSeeded() {
        if (dao.semesterCount() == 0 && dao.courseCount() == 0) {
            dao.upsertSemester(DemoData.semester())
            dao.upsertPeriods(DemoData.periods())
            dao.upsertCourses(DemoData.courses())
            dao.insertMeetings(DemoData.meetings())
            dao.upsertTasks(DemoData.tasks(LocalDate.now()))
            dao.upsertGoals(DemoData.goals())
        } else if (dao.semesterCount() == 0) {
            dao.upsertSemester(DemoData.semester())
        }
    }

    suspend fun addCourse(course: Course) {
        dao.upsertCourse(
            CourseEntity(
                id = course.id,
                name = course.name,
                teacher = course.teacher,
                room = course.room,
                colorHex = course.colorHex
            )
        )
        dao.insertMeetings(course.meetings.map { meeting ->
            MeetingEntity(
                courseId = course.id,
                weekday = meeting.weekday,
                startPeriod = meeting.startPeriod,
                endPeriod = meeting.endPeriod,
                parity = meeting.parity,
                weeksCsv = meeting.weeksCsv,
                room = meeting.room
            )
        })
    }

    suspend fun importCourses(courses: List<Course>) {
        if (courses.isEmpty()) return
        val courseEntities = courses.map { c ->
            CourseEntity(id = c.id, name = c.name, teacher = c.teacher, room = c.room, colorHex = c.colorHex)
        }
        val meetingEntities = courses.flatMap { c ->
            c.meetings.map { m ->
                MeetingEntity(
                    courseId = c.id,
                    weekday = m.weekday,
                    startPeriod = m.startPeriod,
                    endPeriod = m.endPeriod,
                    parity = m.parity,
                    weeksCsv = m.weeksCsv,
                    room = m.room
                )
            }
        }
        dao.importCoursesAtomic(courseEntities, meetingEntities)
    }

    suspend fun updateTask(task: Task) = dao.upsertTask(task.toEntity())

    suspend fun updateCourse(course: Course) {
        dao.upsertCourse(
            CourseEntity(
                id = course.id,
                name = course.name,
                teacher = course.teacher,
                room = course.room,
                colorHex = course.colorHex
            )
        )
        dao.deleteMeetingsForCourse(course.id)
        // 周期规则改了，旧的单次例外（按原节次定位）已不再适用，一并清理，避免复活错误的调课/停课
        dao.deleteExceptionsForCourse(course.id)
        dao.insertMeetings(course.meetings.map { meeting ->
            MeetingEntity(
                courseId = course.id,
                weekday = meeting.weekday,
                startPeriod = meeting.startPeriod,
                endPeriod = meeting.endPeriod,
                parity = meeting.parity,
                weeksCsv = meeting.weeksCsv,
                room = meeting.room
            )
        })
    }

    suspend fun addException(exception: CourseException) = dao.upsertException(exception.toEntity())

    suspend fun deleteException(exceptionId: String) = dao.deleteException(exceptionId)

    /** 某门课、某教学周的例外（用于课表里显示“本周已停课/调课”提示）。 */
    suspend fun exceptionsForWeek(courseId: String, week: Int): List<CourseException> =
        dao.exceptionsSnapshot()
            .filter { it.courseId == courseId && it.week == week }
            .map { it.toDomain() }

    suspend fun deleteCourse(courseId: String) = dao.deleteCourse(courseId)

    suspend fun updateCourseColor(courseId: String, hex: String) = dao.updateCourseColor(courseId, hex)

    suspend fun updateSemester(startDate: String, totalWeeks: Int) {
        dao.upsertSemester(SemesterEntity(1, "当前学期", startDate, totalWeeks))
    }

    suspend fun savePeriods(periods: List<Period>) {
        dao.deleteAllPeriods()
        dao.upsertPeriods(periods.mapIndexed { index, period ->
            PeriodEntity(index, period.from, period.to)
        })
    }

    suspend fun setTaskDone(taskId: String, done: Boolean) = dao.updateTaskDone(taskId, done)

    suspend fun addTask(task: Task) = dao.upsertTask(task.toEntity())

    suspend fun deleteTask(taskId: String) = dao.deleteTask(taskId)

    suspend fun clearDoneTasks() = dao.deleteDoneTasks()

    suspend fun addGoal(goal: Goal) = dao.upsertGoal(goal.toEntity())

    suspend fun deleteGoal(goalId: String) = dao.deleteGoal(goalId)

    suspend fun updateGoal(goal: Goal) = dao.upsertGoal(goal.toEntity())

    suspend fun createBackupJson(): String = BackupCodec.export(dao)

    suspend fun restoreBackupJson(json: String): Boolean = BackupCodec.restore(dao, json)

    suspend fun clearAllData() = dao.clearAll()

    suspend fun resetDemoData() {
        dao.clearAll()
        ensureSeeded()
    }

    private fun SemesterEntity.toDomain(): Semester = Semester(
        name = name,
        startDate = LocalDate.parse(startDate),
        totalWeeks = totalWeeks
    )

    private fun CourseWithMeetings.toDomain(): Course = Course(
        id = course.id,
        name = course.name,
        teacher = course.teacher,
        room = course.room,
        colorHex = course.colorHex,
        meetings = meetings.map { meeting ->
            Meeting(
                id = meeting.id,
                weekday = meeting.weekday,
                startPeriod = meeting.startPeriod,
                endPeriod = meeting.endPeriod,
                parity = meeting.parity,
                weeksCsv = meeting.weeksCsv,
                room = meeting.room
            )
        }
    )

    private fun TaskEntity.toDomain(): Task = Task(
        id = id,
        title = title,
        courseId = courseId,
        priority = priority,
        estimatedMinutes = estimatedMinutes,
        deadline = deadline?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        done = done,
        source = source,
        week = week,
        weekday = weekday,
        from = fromTime,
        to = toTime,
        goalId = goalId,
        repeatKind = repeatKind.ifBlank { "none" },
        repeatUntil = repeatUntil?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        repeatSeriesId = repeatSeriesId
    )

    private fun GoalEntity.toDomain(): Goal = Goal(
        id = id,
        title = title,
        totalWeeks = totalWeeks,
        currentWeek = currentWeek,
        weeklyHours = weeklyHours,
        doneHours = doneHours,
        progress = progress,
        next = next
    )

    private fun Goal.toEntity(): GoalEntity = GoalEntity(
        id = id,
        title = title,
        totalWeeks = totalWeeks,
        currentWeek = currentWeek,
        weeklyHours = weeklyHours,
        doneHours = doneHours,
        progress = progress,
        next = next
    )

    private fun CourseExceptionEntity.toDomain(): CourseException = CourseException(
        id = id,
        courseId = courseId,
        week = week,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        kind = kind,
        newWeekday = newWeekday,
        newStartPeriod = newStartPeriod,
        newEndPeriod = newEndPeriod,
        newRoom = newRoom,
        note = note
    )

    private fun CourseException.toEntity(): CourseExceptionEntity = CourseExceptionEntity(
        id = id,
        courseId = courseId,
        week = week,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        kind = kind,
        newWeekday = newWeekday,
        newStartPeriod = newStartPeriod,
        newEndPeriod = newEndPeriod,
        newRoom = newRoom,
        note = note
    )

    private fun Task.toEntity(): TaskEntity = TaskEntity(
        id = id,
        title = title,
        courseId = courseId,
        priority = priority,
        estimatedMinutes = estimatedMinutes,
        deadline = deadline?.toString(),
        done = done,
        source = source,
        week = week,
        weekday = weekday,
        fromTime = from,
        toTime = to,
        goalId = goalId,
        repeatKind = repeatKind,
        repeatUntil = repeatUntil?.toString(),
        repeatSeriesId = repeatSeriesId
    )
}
