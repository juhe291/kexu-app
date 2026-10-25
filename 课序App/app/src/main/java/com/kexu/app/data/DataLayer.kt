package com.kexu.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "semester")
data class SemesterEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val startDate: String,
    val totalWeeks: Int
)

@Entity(tableName = "periods")
data class PeriodEntity(
    @PrimaryKey val position: Int,
    val fromTime: String,
    val toTime: String
)

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val teacher: String,
    val room: String,
    val colorHex: String
)

@Entity(
    tableName = "meetings",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("courseId")]
)
data class MeetingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: String,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val parity: String? = null,
    val weeksCsv: String? = null,
    val room: String? = null
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val courseId: String? = null,
    val priority: String = "medium",
    val estimatedMinutes: Int? = null,
    val deadline: String? = null,
    val done: Boolean = false,
    val source: String = "manual",
    val week: Int? = null,
    val weekday: Int? = null,
    val fromTime: String? = null,
    val toTime: String? = null,
    val goalId: String? = null,
    @ColumnInfo(defaultValue = "none") val repeatKind: String = "none",
    val repeatUntil: String? = null,
    val repeatSeriesId: String? = null
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val title: String,
    val totalWeeks: Int,
    val currentWeek: Int,
    val weeklyHours: Int,
    val doneHours: Int,
    val progress: Int,
    val next: String
)

/**
 * 课程单次例外（停课/调课）：只影响某教学周的那一次课，不改变周期规则。
 * 通过 courseId + week + 原 weekday/startPeriod/endPeriod 定位被执行的那一次。
 */
@Entity(
    tableName = "course_exceptions",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("courseId")]
)
data class CourseExceptionEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val week: Int,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val kind: String,
    val newWeekday: Int? = null,
    val newStartPeriod: Int? = null,
    val newEndPeriod: Int? = null,
    val newRoom: String? = null,
    val note: String = ""
)

data class CourseWithMeetings(
    @Embedded val course: CourseEntity,
    @Relation(parentColumn = "id", entityColumn = "courseId")
    val meetings: List<MeetingEntity>
)

@Dao
interface KexuDao {

    @Query("SELECT * FROM semester WHERE id = 1")
    fun semesterFlow(): Flow<SemesterEntity?>

    @Query("SELECT * FROM periods ORDER BY position")
    fun periodsFlow(): Flow<List<PeriodEntity>>

    @Transaction
    @Query("SELECT * FROM courses ORDER BY rowid")
    fun coursesFlow(): Flow<List<CourseWithMeetings>>

    @Query("SELECT * FROM tasks ORDER BY rowid")
    fun tasksFlow(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM goals ORDER BY rowid")
    fun goalsFlow(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM course_exceptions ORDER BY rowid")
    fun exceptionsFlow(): Flow<List<CourseExceptionEntity>>

    @Query("SELECT * FROM semester WHERE id = 1")
    suspend fun semesterSnapshot(): SemesterEntity?

    @Query("SELECT * FROM periods ORDER BY position")
    suspend fun periodsSnapshot(): List<PeriodEntity>

    @Transaction
    @Query("SELECT * FROM courses ORDER BY rowid")
    suspend fun coursesSnapshot(): List<CourseWithMeetings>

    @Query("SELECT * FROM tasks ORDER BY rowid")
    suspend fun tasksSnapshot(): List<TaskEntity>

    @Query("SELECT * FROM goals ORDER BY rowid")
    suspend fun goalsSnapshot(): List<GoalEntity>

    @Query("SELECT * FROM course_exceptions ORDER BY rowid")
    suspend fun exceptionsSnapshot(): List<CourseExceptionEntity>

    @Query("SELECT COUNT(*) FROM semester")
    suspend fun semesterCount(): Int

    @Query("SELECT COUNT(*) FROM courses")
    suspend fun courseCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSemester(semester: SemesterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeriods(periods: List<PeriodEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCourse(course: CourseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCourses(courses: List<CourseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeetings(meetings: List<MeetingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoal(goal: GoalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoals(goals: List<GoalEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertException(exception: CourseExceptionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExceptions(exceptions: List<CourseExceptionEntity>)

    @Query("DELETE FROM course_exceptions WHERE id = :exceptionId")
    suspend fun deleteException(exceptionId: String)

    @Query("DELETE FROM course_exceptions WHERE courseId = :courseId")
    suspend fun deleteExceptionsForCourse(courseId: String)

    @Query("DELETE FROM course_exceptions")
    suspend fun deleteAllExceptions()

    @Query("UPDATE courses SET colorHex = :hex WHERE id = :courseId")
    suspend fun updateCourseColor(courseId: String, hex: String)

    @Query("UPDATE tasks SET done = :done WHERE id = :taskId")
    suspend fun updateTaskDone(taskId: String, done: Boolean)

    @Transaction
    suspend fun importCoursesAtomic(candidates: List<CourseEntity>, meetings: List<MeetingEntity>) {
        upsertCourses(candidates)
        insertMeetings(meetings)
    }

    @Query("DELETE FROM tasks WHERE done = 1")
    suspend fun deleteDoneTasks()

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: String)

    @Query("DELETE FROM meetings WHERE courseId = :courseId")
    suspend fun deleteMeetingsForCourse(courseId: String)

    @Query("DELETE FROM courses WHERE id = :courseId")
    suspend fun deleteCourse(courseId: String)

    @Query("DELETE FROM meetings")
    suspend fun deleteAllMeetings()

    @Query("DELETE FROM courses")
    suspend fun deleteAllCourses()

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    @Query("DELETE FROM goals WHERE id = :goalId")
    suspend fun deleteGoal(goalId: String)

    @Query("DELETE FROM goals")
    suspend fun deleteAllGoals()

    @Query("DELETE FROM periods")
    suspend fun deleteAllPeriods()

    @Query("DELETE FROM semester")
    suspend fun deleteSemester()

    @Transaction
    suspend fun clearAll() {
        deleteAllExceptions()
        deleteAllMeetings()
        deleteAllCourses()
        deleteAllTasks()
        deleteAllGoals()
        deleteAllPeriods()
        deleteSemester()
    }
}

@Database(
    entities = [
        SemesterEntity::class, PeriodEntity::class, CourseEntity::class, MeetingEntity::class,
        TaskEntity::class, GoalEntity::class, CourseExceptionEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class KexuDatabase : RoomDatabase() {
    abstract fun dao(): KexuDao

    companion object {
        @Volatile
        private var INSTANCE: KexuDatabase? = null

        /** v1 → v2：新增课程单次例外表（保留既有课程/任务/节次数据）。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `course_exceptions` (
                        `id` TEXT NOT NULL,
                        `courseId` TEXT NOT NULL,
                        `week` INTEGER NOT NULL,
                        `weekday` INTEGER NOT NULL,
                        `startPeriod` INTEGER NOT NULL,
                        `endPeriod` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `newWeekday` INTEGER,
                        `newStartPeriod` INTEGER,
                        `newEndPeriod` INTEGER,
                        `newRoom` TEXT,
                        `note` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_course_exceptions_courseId` ON `course_exceptions` (`courseId`)")
            }
        }

        /** v2 → v3：任务表新增重复任务字段（旧任务默认不重复）。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `repeatKind` TEXT NOT NULL DEFAULT 'none'")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `repeatUntil` TEXT")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `repeatSeriesId` TEXT")
            }
        }

        fun get(context: Context): KexuDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                KexuDatabase::class.java,
                "kexu.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}
