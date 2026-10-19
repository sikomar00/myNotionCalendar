package com.ysdoing.notioncal.core.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File

/**
 * PF-09: 마이그레이션이 실패해도 앱이 열리게 한다. Room이 DB를 처음 열 때(백그라운드 첫 쿼리) 마이그레이션 경로가
 * 없거나(다운그레이드 포함) 스키마 검증에 실패하면, 지울 수 없는 것만 꺼내 두고 파일을 새로 만든 뒤 되돌려 넣는다.
 *
 * - 남기는 것: DB 등록·속성 매핑(data_sources), 위젯 설정(widget_configs), 미전송 쓰기(outbox)와 그 일정(events.pendingOps > 0),
 *   가계부 중복 '둘 다 유지' 선택(ledger_dup_keeps, LD-08).
 * - 버리는 것: 일정 캐시·동기화 기록·충돌 기록. 다음 동기화가 전체 조회로 다시 채운다.
 * - 토큰·일반 설정은 DataStore에 있어 영향이 없다.
 *
 * [beforeOpen]은 진단용 고장 주입(PF-10)이다. 던진 예외는 복구하지 않고 그대로 올라간다.
 * [forceRecover]가 true를 돌려주면 오류가 없어도 같은 방식으로 새로 만든다(진단 화면의 'DB 다시 만들기', PF-10).
 */
class RecoveringOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
    private val now: () -> Long = System::currentTimeMillis,
    private val beforeOpen: () -> Unit = {},
    private val forceRecover: () -> Boolean = { false },
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        RecoveringOpenHelper(delegate.create(configuration), configuration)

    private inner class RecoveringOpenHelper(
        private val inner: SupportSQLiteOpenHelper,
        private val configuration: SupportSQLiteOpenHelper.Configuration,
    ) : SupportSQLiteOpenHelper by inner {
        override val writableDatabase: SupportSQLiteDatabase
            get() = open { inner.writableDatabase }

        override val readableDatabase: SupportSQLiteDatabase
            get() = open { inner.readableDatabase }

        @Synchronized
        private fun open(get: () -> SupportSQLiteDatabase): SupportSQLiteDatabase {
            beforeOpen()
            val name = configuration.name
            if (name != null && forceRecover()) return recreate(name, get, REASON_RESET_REQUESTED)
            return try {
                get()
            } catch (e: IllegalStateException) {
                if (name == null || !isRecoverable(e)) throw e
                recreate(name, get, e.javaClass.simpleName)
            }
        }

        private fun recreate(name: String, get: () -> SupportSQLiteDatabase, reason: String): SupportSQLiteDatabase {
            val saved = salvage(configuration.context.getDatabasePath(name))
            inner.close()
            configuration.context.deleteDatabase(name)
            return get().also { restore(it, saved, reason) }
        }
    }

    private class Saved(val table: String, val rows: List<ContentValues>)

    /** 원시 SQLite로 열어(Room 검증을 거치지 않음) 남길 행을 읽는다. 표나 열이 없으면 건너뛴다. */
    private fun salvage(file: File): List<Saved> {
        if (!file.exists()) return emptyList()
        val db = runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()
            ?: return emptyList()
        return db.use { raw ->
            KEEP.mapNotNull { (table, where) ->
                val columns = columns(raw, table)
                if (columns.isEmpty()) return@mapNotNull null
                if (where != null && where.column !in columns) return@mapNotNull null
                val rows = runCatching {
                    raw.query(table, null, where?.clause, null, null, null, null).use { c -> c.readAll() }
                }.getOrDefault(emptyList())
                Saved(table, rows)
            }
        }
    }

    /** 새로 만든 DB에 현재 스키마와 겹치는 열만 넣는다. 필수 열이 비어 들어갈 수 없는 행은 버린다. */
    private fun restore(db: SupportSQLiteDatabase, saved: List<Saved>, reason: String) {
        db.beginTransaction()
        try {
            for (s in saved) {
                val current = columns(db, s.table)
                for (row in s.rows) {
                    val values = ContentValues()
                    row.keySet().filter { it in current }.forEach { key -> copy(row, values, key) }
                    runCatching { db.insert(s.table, SQLiteDatabase.CONFLICT_IGNORE, values) }
                }
            }
            // 복구한 DB는 일정이 비었으므로 다음 동기화가 증분이 아니라 전체 조회가 되게 한다.
            db.execSQL("UPDATE data_sources SET lastSyncAt = NULL, lastFullSyncAt = NULL")
            val log = ContentValues().apply {
                put("at", now())
                put("trigger", "CACHE_RESET")
                put("result", RESULT_RECOVERED)
                put("durationMs", 0L)
                put("pulled", 0)
                put("pushed", 0)
                put("error", reason) // 예외 종류만 남기고 메시지는 남기지 않는다(SEC-02)
            }
            db.insert("sync_log", SQLiteDatabase.CONFLICT_NONE, log)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.readAll(): List<ContentValues> {
        val rows = ArrayList<ContentValues>(count)
        while (moveToNext()) {
            val values = ContentValues()
            for (i in 0 until columnCount) {
                val key = getColumnName(i)
                when (getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> values.putNull(key)
                    Cursor.FIELD_TYPE_INTEGER -> values.put(key, getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> values.put(key, getDouble(i))
                    Cursor.FIELD_TYPE_BLOB -> values.put(key, getBlob(i))
                    else -> values.put(key, getString(i))
                }
            }
            rows += values
        }
        return rows
    }

    private fun copy(from: ContentValues, to: ContentValues, key: String) {
        when (val v = from.get(key)) {
            null -> to.putNull(key)
            is Long -> to.put(key, v)
            is Double -> to.put(key, v)
            is ByteArray -> to.put(key, v)
            else -> to.put(key, v.toString())
        }
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { c -> c.names() }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { c -> c.names() }

    private fun Cursor.names(): Set<String> {
        val index = getColumnIndexOrThrow("name")
        return buildSet { while (moveToNext()) add(getString(index)) }
    }

    private class Where(val column: String, val clause: String)

    companion object {
        /** sync_log.result 값. 진단 화면에서 '최근 실패'로 보인다. */
        const val RESULT_RECOVERED = "RECOVERED"
        const val REASON_RESET_REQUESTED = "ResetRequested"

        private val KEEP = listOf(
            "data_sources" to null,
            "widget_configs" to null,
            "outbox" to null,
            "ledger_dup_keeps" to null,
            "events" to Where("pendingOps", "pendingOps > 0"),
        )

        /** Room이 던지는 마이그레이션·스키마 검증 실패만 복구한다. 그 밖의 오류는 진단 화면으로 보낸다(PF-10). */
        fun isRecoverable(e: IllegalStateException): Boolean {
            val message = e.message ?: return false
            return "migration" in message.lowercase() || "verify the data integrity" in message
        }
    }
}
