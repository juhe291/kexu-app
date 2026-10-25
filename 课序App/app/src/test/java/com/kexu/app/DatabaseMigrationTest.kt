package com.kexu.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.kexu.app.data.KexuDatabase
import com.kexu.app.data.TaskEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 数据库迁移测试：模拟 v1（没有单次例外、没有重复任务字段）的老库，
 * 用 Room + 正式迁移链升级到 v3，验证：
 * 1) 旧课程/任务/节次/学期数据不丢；
 * 2) 旧任务默认 repeatKind="none"、repeatUntil/repeatSeriesId 为 null；
 * 3) 新表 course_exceptions 可用、新列可写入。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DatabaseMigrationTest {

    @Test
    fun migrateV1ToV3KeepsDataAndAddsRepeatDefaults() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "kexu-migration-test.db"
        context.deleteDatabase(name)
        createV1Database(context, name)
        val database = Room.databaseBuilder(context, KexuDatabase::class.java, name)
            .addMigrations(KexuDatabase.MIGRATION_1_2, KexuDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.dao()
            val tasks = dao.tasksSnapshot()
            assertEquals(1, tasks.size)
            assertEquals("复习第一章", tasks[0].title)
            assertEquals("none", tasks[0].repeatKind)
            assertNull(tasks[0].repeatUntil)
            assertNull(tasks[0].repeatSeriesId)

            val courses = dao.coursesSnapshot()
            assertEquals(1, courses.size)
            assertEquals("数据结构", courses[0].course.name)
            assertEquals(1, courses[0].meetings.size)
            assertEquals("odd", courses[0].meetings[0].parity)
            assertEquals(0, dao.exceptionsSnapshot().size)
            assertEquals(20, dao.semesterSnapshot()?.totalWeeks)
            assertEquals(1, dao.goalsSnapshot().size)

            dao.upsertTask(
                TaskEntity(
                    id = "t2",
                    title = "每天背单词",
                    repeatKind = "daily",
                    repeatUntil = "2026-12-31",
                    repeatSeriesId = "series-1"
                )
            )
            val repeat = dao.tasksSnapshot().first { it.id == "t2" }
            assertEquals("daily", repeat.repeatKind)
            assertEquals("2026-12-31", repeat.repeatUntil)
            assertEquals("series-1", repeat.repeatSeriesId)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    /** 按 v1 的真实表结构建库并写入一条旧数据（不含 repeat* 列与 course_exceptions 表）。 */
    private fun createV1Database(context: Context, name: String) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    V1_DDL.forEach { db.execSQL(it) }
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO `semester` (`id`,`name`,`startDate`,`totalWeeks`) VALUES (1,'2026 秋','2026-09-07',20)")
                db.execSQL("INSERT INTO `periods` (`position`,`fromTime`,`toTime`) VALUES (0,'08:30','09:15')")
                db.execSQL("INSERT INTO `courses` (`id`,`name`,`teacher`,`room`,`colorHex`) VALUES ('c1','数据结构','张老师','A101','#2563EB')")
                db.execSQL(
                    "INSERT INTO `meetings` (`courseId`,`weekday`,`startPeriod`,`endPeriod`,`parity`,`weeksCsv`,`room`) " +
                        "VALUES ('c1',0,1,2,'odd',NULL,NULL)"
                )
                db.execSQL(
                    "INSERT INTO `tasks` (`id`,`title`,`courseId`,`priority`,`estimatedMinutes`,`deadline`,`done`,`source`," +
                        "`week`,`weekday`,`fromTime`,`toTime`,`goalId`) " +
                        "VALUES ('t1','复习第一章','c1','high',60,'2026-10-30',0,'manual',8,0,'14:00','15:00',NULL)"
                )
                db.execSQL(
                    "INSERT INTO `goals` (`id`,`title`,`totalWeeks`,`currentWeek`,`weeklyHours`,`doneHours`,`progress`,`next`) " +
                        "VALUES ('g1','考研数学',12,3,4,2,20,'刷完第一章')"
                )
                db.version = 1
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            helper.close()
        }
    }

    private companion object {
        /** v1 建表语句：与 app/schemas/.../3.json 中的 v3 结构一致，仅去掉 v2/v3 新增内容。 */
        val V1_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `semester` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `startDate` TEXT NOT NULL, `totalWeeks` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `periods` (`position` INTEGER NOT NULL, `fromTime` TEXT NOT NULL, `toTime` TEXT NOT NULL, PRIMARY KEY(`position`))",
            "CREATE TABLE IF NOT EXISTS `courses` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `teacher` TEXT NOT NULL, `room` TEXT NOT NULL, `colorHex` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `meetings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `courseId` TEXT NOT NULL, `weekday` INTEGER NOT NULL, `startPeriod` INTEGER NOT NULL, `endPeriod` INTEGER NOT NULL, `parity` TEXT, `weeksCsv` TEXT, `room` TEXT, FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_meetings_courseId` ON `meetings` (`courseId`)",
            "CREATE TABLE IF NOT EXISTS `tasks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `courseId` TEXT, `priority` TEXT NOT NULL, `estimatedMinutes` INTEGER, `deadline` TEXT, `done` INTEGER NOT NULL, `source` TEXT NOT NULL, `week` INTEGER, `weekday` INTEGER, `fromTime` TEXT, `toTime` TEXT, `goalId` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `goals` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `totalWeeks` INTEGER NOT NULL, `currentWeek` INTEGER NOT NULL, `weeklyHours` INTEGER NOT NULL, `doneHours` INTEGER NOT NULL, `progress` INTEGER NOT NULL, `next` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
    }
}
