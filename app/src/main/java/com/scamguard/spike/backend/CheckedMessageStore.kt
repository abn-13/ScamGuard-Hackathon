package com.scamguard.spike.backend

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable record of every message that's been checked, keyed by a stable identity
 * (source + sender + received time -- see [keyFor]). Both the background workers (which
 * can run with no Activity alive) and the on-screen lists read/write this same store, so
 * "already checked" state survives process death instead of living only in an Activity's
 * in-memory map.
 *
 * Plain SQLiteOpenHelper rather than Room: one table, a handful of columns, no need for
 * Room's annotation-processing setup for something this small.
 *
 * Constructor is private -- use [getInstance]. A SQLiteOpenHelper is meant to be held for
 * as long as possible and reused (that's how it manages its connections safely), not
 * constructed fresh per call: checkAndPersist() used to do `CheckedMessageStore(context)`
 * on every single message checked and never closed it, which meant a background poll over
 * a 50-message inbox leaked 50 SQLiteConnectionPool objects in one run (confirmed live via
 * repeated "SQLiteConnection ... was leaked!" warnings in Logcat).
 */
class CheckedMessageStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class Result(val riskLevel: String, val reason: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                message_key TEXT PRIMARY KEY,
                risk_level TEXT NOT NULL,
                reason TEXT NOT NULL,
                checked_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun get(messageKey: String): Result? {
        readableDatabase.query(
            TABLE,
            arrayOf("risk_level", "reason"),
            "message_key = ?",
            arrayOf(messageKey),
            null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return Result(
                riskLevel = cursor.getString(0),
                reason = cursor.getString(1)
            )
        }
    }

    fun put(messageKey: String, riskLevel: String, reason: String) {
        val values = ContentValues().apply {
            put("message_key", messageKey)
            put("risk_level", riskLevel)
            put("reason", reason)
            put("checked_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    companion object {
        private const val DB_NAME = "scamguard_checked_messages.db"
        private const val DB_VERSION = 1
        private const val TABLE = "checked_messages"

        @Volatile
        private var instance: CheckedMessageStore? = null

        /**
         * One shared instance for the whole process -- callers (Activities, background
         * Workers) never construct this directly or close it; it lives for as long as the
         * process does, which is the correct/recommended SQLiteOpenHelper lifecycle.
         */
        fun getInstance(context: Context): CheckedMessageStore =
            instance ?: synchronized(this) {
                instance ?: CheckedMessageStore(context.applicationContext).also { instance = it }
            }

        /** Stable identity for one message -- same scheme a re-check must reproduce. */
        fun keyFor(source: MessageSource, sender: String, receivedAtMillis: Long): String =
            "${source.wireValue}|$sender|$receivedAtMillis"
    }
}
