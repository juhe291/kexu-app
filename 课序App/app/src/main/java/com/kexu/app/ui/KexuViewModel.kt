package com.kexu.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kexu.app.data.KexuDatabase
import com.kexu.app.data.KexuRepository
import com.kexu.app.data.SettingsStore
import com.kexu.app.domain.Course
import com.kexu.app.domain.CourseException
import com.kexu.app.domain.CourseOccurrence
import com.kexu.app.domain.Goal
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.ImportedCourse
import com.kexu.app.domain.Meeting
import com.kexu.app.domain.Period
import com.kexu.app.domain.Prefs
import com.kexu.app.domain.RecurringTaskPlanner
import com.kexu.app.domain.Semester
import com.kexu.app.domain.Task
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

data class AppUiState(
    val loading: Boolean = true,
    val semester: Semester = Semester(),
    val periods: List<Period> = KexuCore.defaultPeriods(),
    val courses: List<Course> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val exceptions: List<CourseException> = emptyList(),
    val prefs: Prefs = Prefs()
) {
    fun courseById(id: String?): Course? = id?.let { cid -> courses.firstOrNull { it.id == cid } }
    fun courseColor(courseId: String?): String = courseById(courseId)?.colorHex ?: "#475569"
    fun currentWeek(date: LocalDate = LocalDate.now()): Int =
        KexuCore.weekIndexForDate(semester.startDate, date).coerceIn(1, semester.totalWeeks)

    /** 应用停课/调课后的某天课程，课表与今天页共用。 */
    fun occurrencesOn(week: Int, weekday: Int): List<CourseOccurrence> =
        KexuCore.effectiveCoursesOnDay(courses, exceptions, week, weekday)

    fun occurrencesOnWeek(week: Int): List<List<CourseOccurrence>> =
        KexuCore.effectiveCoursesOnWeek(courses, exceptions, week)
}

class KexuViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = KexuRepository(KexuDatabase.get(app).dao())
    private val settings = SettingsStore(app)

    val state: StateFlow<AppUiState> = combine(repository.dataFlow(), settings.prefs) { data, prefs ->
        AppUiState(
            loading = false,
            semester = data.semester,
            periods = data.periods,
            courses = data.courses,
            tasks = data.tasks,
            goals = data.goals,
            exceptions = data.exceptions,
            prefs = prefs
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    init {
        viewModelScope.launch { repository.ensureSeeded() }
    }

    fun updateTask(task: Task) = viewModelScope.launch { repository.updateTask(task) }

    fun updateCourse(course: Course) = viewModelScope.launch { repository.updateCourse(course) }

    fun deleteCourse(courseId: String) = viewModelScope.launch { repository.deleteCourse(courseId) }

    /** 停课一次：只影响该教学周的这一次课。 */
    fun cancelOccurrence(occurrence: CourseOccurrence, note: String = "") = viewModelScope.launch {
        repository.addException(
            CourseException(
                id = UUID.randomUUID().toString(),
                courseId = occurrence.course.id,
                week = occurrence.week,
                weekday = occurrence.meeting.weekday,
                startPeriod = occurrence.meeting.startPeriod,
                endPeriod = occurrence.meeting.endPeriod,
                kind = CourseException.KIND_CANCEL,
                note = note
            )
        )
    }

    /** 调课一次：把该教学周的这一次课改到新的星期/节次/教室，不影响后续周。 */
    fun moveOccurrence(
        occurrence: CourseOccurrence,
        newWeekday: Int,
        newStartPeriod: Int,
        newEndPeriod: Int,
        newRoom: String?,
        note: String = ""
    ) = viewModelScope.launch {
        repository.addException(
            CourseException(
                id = UUID.randomUUID().toString(),
                courseId = occurrence.course.id,
                week = occurrence.week,
                weekday = occurrence.meeting.weekday,
                startPeriod = occurrence.meeting.startPeriod,
                endPeriod = occurrence.meeting.endPeriod,
                kind = CourseException.KIND_MOVE,
                newWeekday = newWeekday,
                newStartPeriod = newStartPeriod,
                newEndPeriod = newEndPeriod,
                newRoom = newRoom?.trim()?.takeIf { it.isNotBlank() },
                note = note
            )
        )
    }

    /** 撤销某次停课/调课，恢复原安排。 */
    fun deleteException(exceptionId: String) = viewModelScope.launch { repository.deleteException(exceptionId) }

    fun duplicateCourse(course: Course) = viewModelScope.launch {
        val palette = listOf("#2563EB", "#0D9488", "#7C3AED", "#D97706", "#DB2777", "#0891B2")
        val index = palette.indexOfFirst { it.equals(course.colorHex, ignoreCase = true) }
        val nextColor = palette[if (index < 0) 0 else (index + 1) % palette.size]
        repository.addCourse(course.copy(id = java.util.UUID.randomUUID().toString(), name = course.name + "（副本）", colorHex = nextColor))
    }

    fun duplicateTask(task: Task) = viewModelScope.launch {
        repository.addTask(task.copy(id = java.util.UUID.randomUUID().toString(), title = task.title + "（副本）", done = false))
    }

    fun importCourses(courses: List<ImportedCourse>) = viewModelScope.launch {
        val palette = listOf("#2563EB", "#0D9488", "#7C3AED", "#D97706", "#DB2777", "#0891B2", "#65A30D", "#EA580C")
        val mapped = courses.mapIndexed { index, imported ->
            Course(
                id = java.util.UUID.randomUUID().toString(),
                name = imported.name,
                teacher = imported.teacher,
                room = imported.room,
                colorHex = palette[index % palette.size],
                meetings = imported.meetings.map { m ->
                    Meeting(
                        weekday = m.weekday,
                        startPeriod = m.startPeriod,
                        endPeriod = m.endPeriod,
                        parity = m.parity,
                        weeksCsv = m.weeksCsv
                    )
                }
            )
        }
        repository.importCourses(mapped)
    }

    fun addTask(
        title: String,
        courseId: String?,
        priority: String,
        estimatedMinutes: Int,
        deadline: LocalDate?,
        from: String?,
        to: String?,
        weekday: Int?,
        week: Int?,
        repeatKind: String = RecurringTaskPlanner.NONE,
        repeatUntil: LocalDate? = null
    ) = viewModelScope.launch {
        val id = UUID.randomUUID().toString()
        val kind = if (RecurringTaskPlanner.isValidKind(repeatKind)) repeatKind else RecurringTaskPlanner.NONE
        repository.addTask(
            Task(
                id = id,
                title = title,
                courseId = courseId,
                priority = priority,
                estimatedMinutes = estimatedMinutes,
                deadline = deadline,
                week = week,
                weekday = weekday,
                from = from,
                to = to,
                repeatKind = kind,
                repeatUntil = repeatUntil,
                repeatSeriesId = if (kind == RecurringTaskPlanner.NONE) null else id
            )
        )
    }

    /**
     * 勾选完成/取消完成。
     * 完成一条重复任务时生成下一次任务（同一系列只保留一条未完成任务，避免反复勾选产生重复）。
     */
    fun toggleTask(taskId: String, done: Boolean) = viewModelScope.launch {
        val task = state.value.tasks.firstOrNull { it.id == taskId }
        repository.setTaskDone(taskId, done)
        if (!done || task == null || !task.isRepeating || state.value.loading) return@launch
        val seriesId = task.repeatSeriesId ?: task.id
        val hasPendingNext = state.value.tasks.any { other ->
            other.id != task.id && other.repeatSeriesId == seriesId && !other.done
        }
        if (hasPendingNext) return@launch
        val next = RecurringTaskPlanner.nextOccurrence(task, state.value.semester) ?: return@launch
        repository.addTask(next)
    }

    fun deleteTask(taskId: String) = viewModelScope.launch { repository.deleteTask(taskId) }

    fun addCourse(name: String, teacher: String, room: String, weekday: Int, startPeriod: Int, endPeriod: Int, parity: String?) =
        viewModelScope.launch {
            val palette = listOf("#2563EB", "#0D9488", "#7C3AED", "#D97706", "#DB2777", "#0891B2", "#65A30D")
            val color = palette[kotlin.random.Random.nextInt(palette.size)]
            repository.addCourse(
                Course(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    teacher = teacher,
                    room = room,
                    colorHex = color,
                    meetings = listOf(Meeting(weekday = weekday, startPeriod = startPeriod, endPeriod = endPeriod, parity = parity))
                )
            )
        }

    fun scheduleTask(task: Task, week: Int, weekday: Int, from: String, to: String) = viewModelScope.launch {
        repository.addTask(task.copy(week = week, weekday = weekday, from = from, to = to, source = "auto"))
    }

    fun updateCourseColor(courseId: String, hex: String) = viewModelScope.launch {
        repository.updateCourseColor(courseId, hex)
    }

    fun savePeriods(periods: List<Period>) = viewModelScope.launch { repository.savePeriods(periods) }

    fun updateSemester(startDate: LocalDate, totalWeeks: Int) = viewModelScope.launch {
        repository.updateSemester(startDate.toString(), totalWeeks.coerceIn(4, 30))
    }

    fun updatePrefs(transform: (Prefs) -> Prefs) = viewModelScope.launch {
        settings.save(transform(state.value.prefs))
    }

    fun setModuleOpen(key: String, open: Boolean) = updatePrefs { prefs ->
        prefs.copy(moduleOpen = prefs.moduleOpen + (key to open))
    }

    fun addGoal(title: String, totalWeeks: Int, weeklyHours: Int, next: String) = viewModelScope.launch {
        repository.addGoal(
            Goal(
                id = "goal-" + UUID.randomUUID(),
                title = title,
                totalWeeks = totalWeeks,
                currentWeek = 1,
                weeklyHours = weeklyHours,
                doneHours = 0,
                progress = 0,
                next = next
            )
        )
    }

    fun deleteGoal(goalId: String) = viewModelScope.launch { repository.deleteGoal(goalId) }

    fun clearDoneTasks() = viewModelScope.launch { repository.clearDoneTasks() }

    fun advanceGoal(goal: Goal) = viewModelScope.launch {
        val nextWeek = (goal.currentWeek + 1).coerceAtMost(goal.totalWeeks)
        val progress = (((nextWeek - 1).toFloat() / goal.totalWeeks) * 100).toInt().coerceIn(0, 100)
        repository.updateGoal(goal.copy(currentWeek = nextWeek, doneHours = 0, progress = progress))
    }

    suspend fun createBackupJson(): String = repository.createBackupJson()

    suspend fun restoreBackupJson(json: String): Boolean = repository.restoreBackupJson(json)

    fun resetDemoData() = viewModelScope.launch { repository.resetDemoData() }

    fun clearAllData() = viewModelScope.launch { repository.clearAllData() }
}

