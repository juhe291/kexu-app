package com.kexu.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kexu.app.data.BackupCodec
import com.kexu.app.data.CourseEntity
import com.kexu.app.data.CourseExceptionEntity
import com.kexu.app.data.GoalEntity
import com.kexu.app.data.KexuDatabase
import com.kexu.app.data.MeetingEntity
import com.kexu.app.data.PeriodEntity
import com.kexu.app.data.SemesterEntity
import com.kexu.app.data.TaskEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupCodecTest {

    private fun database(context: Context): KexuDatabase =
        Room.inMemoryDatabaseBuilder(context, KexuDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private suspend fun seed(db: KexuDatabase) {
        val dao = db.dao()
        dao.upsertSemester(SemesterEntity(name = "2026 秋", startDate = "2026-09-07", totalWeeks = 20))
        dao.upsertPeriods(
            listOf(
                PeriodEntity(0, "08:00", "08:45"),
                PeriodEntity(1, "08:55", "09:40"),
                PeriodEntity(2, "10:00", "10:45")
            )
        )
        dao.upsertCourse(CourseEntity("c1", "高等数学", "张老师", "A101", "#2563EB"))
        dao.insertMeetings(
            listOf(MeetingEntity(courseId = "c1", weekday = 0, startPeriod = 1, endPeriod = 2, parity = "odd"))
        )
        dao.upsertTasks(
            listOf(
                TaskEntity(
                    id = "t1",
                    title = "复习第一章",
                    courseId = "c1",
                    estimatedMinutes = 60,
                    deadline = "2026-10-30",
                    week = 8,
                    weekday = 2,
                    fromTime = "14:00",
                    toTime = "15:00",
                    repeatKind = "weekly",
                    repeatUntil = "2026-12-31",
                    repeatSeriesId = "series-1"
                )
            )
        )
        dao.upsertGoals(listOf(GoalEntity("g1", "考研数学", 12, 3, 4, 2, 20, "刷完第一章")))
        dao.upsertExceptions(
            listOf(
                CourseExceptionEntity(
                    id = "e1",
                    courseId = "c1",
                    week = 8,
                    weekday = 0,
                    startPeriod = 1,
                    endPeriod = 2,
                    kind = "move",
                    newWeekday = 3,
                    newStartPeriod = 5,
                    newEndPeriod = 6,
                    newRoom = "B202",
                    note = "临时调课"
                )
            )
        )
    }

    @Test
    fun backupExportImportRoundTrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = database(context)
        try {
            seed(db)
            val dao = db.dao()
            val json = BackupCodec.export(dao)
            assertTrue(json.contains("\"app\""))
            assertTrue(json.contains("高等数学"))
            assertTrue(json.contains("复习第一章"))

            dao.clearAll()
            assertEquals(0, dao.coursesSnapshot().size)
            assertEquals(0, dao.tasksSnapshot().size)

            assertTrue(BackupCodec.restore(dao, json))
            val courses = dao.coursesSnapshot()
            assertEquals(1, courses.size)
            assertEquals("高等数学", courses[0].course.name)
            assertEquals(1, courses[0].meetings.size)
            assertEquals("odd", courses[0].meetings[0].parity)

            val tasks = dao.tasksSnapshot()
            assertEquals(1, tasks.size)
            assertEquals("复习第一章", tasks[0].title)
            assertEquals("2026-10-30", tasks[0].deadline)
            assertEquals("weekly", tasks[0].repeatKind)
            assertEquals("2026-12-31", tasks[0].repeatUntil)
            assertEquals("series-1", tasks[0].repeatSeriesId)

            assertEquals(1, dao.goalsSnapshot().size)
            assertEquals(3, dao.periodsSnapshot().size)
            assertEquals(20, dao.semesterSnapshot()?.totalWeeks)

            val exceptions = dao.exceptionsSnapshot()
            assertEquals(1, exceptions.size)
            val exception = exceptions[0]
            assertEquals("c1", exception.courseId)
            assertEquals(8, exception.week)
            assertEquals("move", exception.kind)
            assertEquals(3, exception.newWeekday)
            assertEquals(5, exception.newStartPeriod)
            assertEquals("B202", exception.newRoom)
            assertEquals("临时调课", exception.note)
        } finally {
            db.close()
        }
    }

    @Test
    fun rejectsNonKexuPayload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = database(context)
        try {
            assertFalse(BackupCodec.restore(db.dao(), "{}"))
            assertFalse(BackupCodec.restore(db.dao(), "not json"))
        } finally {
            db.close()
        }
    }
}
