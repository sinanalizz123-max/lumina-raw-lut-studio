package com.lumina.studio.core.data.local

import android.content.Context
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import androidx.room.Database
import androidx.room.Room
import com.lumina.studio.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DatabaseFailure {
    CORRUPT,
    MIGRATION,
    LOCKED,
    IO,
    UNKNOWN
}

class DatabaseUnavailableException(
    val failure: DatabaseFailure,
    cause: Throwable
) : RuntimeException("Lumina database unavailable: $failure", cause)

data class DatabaseRecoveryInfo(
    val failure: DatabaseFailure,
    val stamp: String,
    val forensicPath: String?,
    val message: String?
)

object DatabaseFailureClassifier {
    fun classify(error: Throwable): DatabaseFailure {
        var current: Throwable? = error
        while (current != null) {
            // Fatal VM/linkage failures are never database-recovery events.
            if (current is VirtualMachineError || current is LinkageError) {
                return DatabaseFailure.UNKNOWN
            }
            failureOf(current)?.let { return it }
            current = current.cause
        }
        return DatabaseFailure.UNKNOWN
    }

    private fun failureOf(error: Throwable): DatabaseFailure? {
        val message = error.message.orEmpty()
        if (message.contains("migration", ignoreCase = true)) return DatabaseFailure.MIGRATION
        if (error is SQLiteDatabaseCorruptException) return DatabaseFailure.CORRUPT
        if (message.contains("not a database", ignoreCase = true) ||
            message.contains("malformed", ignoreCase = true) ||
            message.contains("corrupt", ignoreCase = true)
        ) {
            return DatabaseFailure.CORRUPT
        }
        if (error is SQLiteDatabaseLockedException) return DatabaseFailure.LOCKED
        if (message.contains("database is locked", ignoreCase = true) ||
            message.contains("database table is locked", ignoreCase = true) ||
            message.contains("database is busy", ignoreCase = true)
        ) {
            return DatabaseFailure.LOCKED
        }
        if (error is SQLiteCantOpenDatabaseException ||
            error is SQLiteDiskIOException ||
            error is SQLiteFullException
        ) {
            return DatabaseFailure.IO
        }
        if (message.contains("unable to open database file", ignoreCase = true) ||
            message.contains("cannot open database", ignoreCase = true) ||
            message.contains("disk i/o error", ignoreCase = true) ||
            message.contains("disk full", ignoreCase = true)
        ) {
            return DatabaseFailure.IO
        }
        return null
    }
}

object DatabaseRecovery {
    const val RECOVERY_DIR = "db-recovery"

    private const val PREFS = "lumina_db_health"
    private const val KEY_REQUIRED = "recovery_required"
    private const val KEY_FAILURE = "recovery_failure"
    private const val KEY_STAMP = "recovery_stamp"
    private const val KEY_PATH = "recovery_path"
    private const val KEY_MESSAGE = "recovery_message"

    fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    fun isRequired(appContext: Context): Boolean =
        prefs(appContext).getBoolean(KEY_REQUIRED, false)

    fun snapshot(appContext: Context): DatabaseRecoveryInfo? {
        val stored = prefs(appContext)
        if (!stored.getBoolean(KEY_REQUIRED, false)) return null
        val failure = runCatching {
            DatabaseFailure.valueOf(stored.getString(KEY_FAILURE, null) ?: "")
        }.getOrDefault(DatabaseFailure.UNKNOWN)
        return DatabaseRecoveryInfo(
            failure = failure,
            stamp = stored.getString(KEY_STAMP, null).orEmpty(),
            forensicPath = stored.getString(KEY_PATH, null),
            message = stored.getString(KEY_MESSAGE, null)
        )
    }

    fun markRequired(
        appContext: Context,
        failure: DatabaseFailure,
        stamp: String,
        forensicPath: String?,
        message: String?
    ) {
        prefs(appContext).edit()
            .putBoolean(KEY_REQUIRED, true)
            .putString(KEY_FAILURE, failure.name)
            .putString(KEY_STAMP, stamp)
            .putString(KEY_PATH, forensicPath)
            .putString(KEY_MESSAGE, message?.replace('\n', ' ')?.take(500))
            .apply()
    }

    fun clear(appContext: Context) {
        prefs(appContext).edit()
            .remove(KEY_REQUIRED)
            .remove(KEY_FAILURE)
            .remove(KEY_STAMP)
            .remove(KEY_PATH)
            .remove(KEY_MESSAGE)
            .apply()
    }

    private fun prefs(appContext: Context) =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object DatabaseGate {
    sealed interface State {
        data object Checking : State
        data object Ready : State
        data class RecoveryRequired(val info: DatabaseRecoveryInfo) : State
    }

    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    // Test seam: production always opens the real Room database. Tests replace
    // this to simulate open success/failure without touching SQLite.
    internal var opener: (Context) -> Unit = { context ->
        DatabaseProvider.get(context)
        Unit
    }

    fun initialize(appContext: Context) {
        synchronized(this) {
            update(appContext)
        }
    }

    fun retry(appContext: Context) {
        synchronized(this) {
            update(appContext)
        }
    }

    internal fun resetForTest() {
        opener = { context ->
            DatabaseProvider.get(context)
            Unit
        }
        _state.value = State.Checking
    }

    private fun update(appContext: Context) {
        _state.value = State.Checking
        DatabaseRecovery.snapshot(appContext)?.let {
            _state.value = State.RecoveryRequired(it)
            return
        }
        try {
            opener(appContext)
            DatabaseRecovery.clear(appContext)
            _state.value = State.Ready
        } catch (e: DatabaseUnavailableException) {
            val info = DatabaseRecovery.snapshot(appContext) ?: DatabaseRecoveryInfo(
                failure = e.failure,
                stamp = DatabaseRecovery.stamp(),
                forensicPath = null,
                message = e.cause?.message ?: e.message
            )
            _state.value = State.RecoveryRequired(info)
        } catch (e: Exception) {
            val info = DatabaseRecovery.snapshot(appContext) ?: DatabaseRecoveryInfo(
                failure = DatabaseFailure.UNKNOWN,
                stamp = DatabaseRecovery.stamp(),
                forensicPath = null,
                message = e.message
            )
            _state.value = State.RecoveryRequired(info)
        }
    }
}

object DatabaseProvider {
    @Volatile
    private var instance: LuminaDatabase? = null

    fun get(context: Context): LuminaDatabase {
        return instance ?: synchronized(this) {
            instance ?: openOrRecover(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): LuminaDatabase {
        return Room.databaseBuilder(
            appContext,
            LuminaDatabase::class.java,
            LuminaDatabase.DATABASE_NAME
        ).addMigrations(LuminaMigrations.MIGRATION_1_2, LuminaMigrations.MIGRATION_2_3).build()
    }

    private fun openOrRecover(appContext: Context): LuminaDatabase {
        val database = build(appContext)
        try {
            database.openHelper.writableDatabase
            return database
        } catch (e: Exception) {
            runCatching { database.close() }
            val failure = DatabaseFailureClassifier.classify(e)
            // LOCKED retries happen before any recovery flag or forensic work.
            // A persistent lock is reported as LOCKED, never reclassified as corruption.
            if (failure == DatabaseFailure.LOCKED) {
                reopenLocked(appContext)?.let { return it }
                throw DatabaseUnavailableException(DatabaseFailure.LOCKED, e)
            }
            if (failure == DatabaseFailure.CORRUPT) {
                val stamp = DatabaseRecovery.stamp()
                val forensicDir = preserveForensics(appContext, stamp, failure, e)
                DatabaseRecovery.markRequired(
                    appContext,
                    failure,
                    stamp,
                    forensicDir?.absolutePath,
                    e.message
                )
                throw DatabaseUnavailableException(failure, e)
            }
            throw DatabaseUnavailableException(failure, e)
        }
    }

    private fun reopenLocked(appContext: Context): LuminaDatabase? {
        // Bounded immediate retries only; the recovery screen offers an explicit
        // user-driven retry for anything that remains locked.
        repeat(2) {
            val retry = build(appContext)
            try {
                retry.openHelper.writableDatabase
                return retry
            } catch (_: Exception) {
                runCatching { retry.close() }
            }
        }
        return null
    }

    private fun databaseFiles(appContext: Context): List<File> {
        val main = appContext.getDatabasePath(LuminaDatabase.DATABASE_NAME)
        return listOf(main, File(main.path + "-shm"), File(main.path + "-wal"))
    }

    private fun preserveForensics(
        appContext: Context,
        stamp: String,
        failure: DatabaseFailure,
        cause: Throwable
    ): File? {
        val dir = File(File(appContext.filesDir, DatabaseRecovery.RECOVERY_DIR), "recovery-$stamp")
        if (!dir.mkdirs() && !dir.isDirectory) return null
        for (file in databaseFiles(appContext)) {
            if (file.isFile) {
                runCatching { file.copyTo(File(dir, file.name), overwrite = true) }
            }
        }
        runCatching {
            File(dir, "manifest.txt").writeText(
                "stamp=$stamp\n" +
                    "database=${LuminaDatabase.DATABASE_NAME}\n" +
                    "failure=${failure.name}\n" +
                    "cause=${cause.javaClass.name}\n" +
                    "app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "schema=${schemaVersion()}\n" +
                    "message=${cause.message?.replace('\n', ' ')?.take(200)}\n"
            )
        }
        runCatching { pruneRecoveryBundles(appContext) }
        return dir
    }

    private fun schemaVersion(): String {
        return runCatching {
            LuminaDatabase::class.java.getAnnotation(Database::class.java)?.version?.toString()
        }.getOrNull() ?: "unknown"
    }

    private fun pruneRecoveryBundles(appContext: Context) {
        val root = File(appContext.filesDir, DatabaseRecovery.RECOVERY_DIR)
        val bundles = root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("recovery-") }
            ?.sortedByDescending { it.name }
            ?: return
        bundles.drop(2).forEach { bundle ->
            runCatching { bundle.deleteRecursively() }
        }
    }
}
