package com.example.chatgpt_mealplanner.core.database

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteTransactionListener
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_3_4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.sql.DriverManager

open class RealJdbcSupportSQLiteDatabase(private val conn: java.sql.Connection) : SupportSQLiteDatabase {

    override fun execSQL(sql: String) {
        val cleanSql = sql.trim().replace("\r\n", " ").replace("\n", " ")
        conn.createStatement().use { stmt -> stmt.execute(cleanSql) }
    }

    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        val cleanSql = sql.trim().replace("\r\n", " ").replace("\n", " ")
        conn.prepareStatement(cleanSql).use { stmt ->
            bindArgs.forEachIndexed { i, arg -> stmt.setObject(i + 1, arg) }
            stmt.execute()
        }
    }

    override fun compileStatement(sql: String): SupportSQLiteStatement {
        val cleanSql = sql.trim().replace("\r\n", " ").replace("\n", " ")
        return object : SupportSQLiteStatement {
            override fun execute() { conn.createStatement().use { stmt -> stmt.execute(cleanSql) } }
            override fun executeUpdateDelete(): Int = conn.createStatement().use { stmt -> stmt.executeUpdate(cleanSql) }
            override fun executeInsert(): Long {
                conn.createStatement().use { stmt ->
                    stmt.execute(cleanSql)
                    val rs = stmt.executeQuery("SELECT last_insert_rowid()")
                    return if (rs.next()) rs.getLong(1) else 0L
                }
            }
            override fun simpleQueryForLong(): Long {
                conn.prepareStatement(cleanSql).use { stmt ->
                    val isRs = stmt.execute()
                    if (isRs) {
                        val rs = stmt.resultSet
                        return if (rs != null && rs.next()) rs.getLong(1) else 0L
                    }
                    return 0L
                }
            }
            override fun simpleQueryForString(): String {
                conn.prepareStatement(cleanSql).use { stmt ->
                    val isRs = stmt.execute()
                    if (isRs) {
                        val rs = stmt.resultSet
                        return if (rs != null && rs.next()) rs.getString(1) ?: "" else ""
                    }
                    return ""
                }
            }
            override fun bindNull(index: Int) {}
            override fun bindLong(index: Int, value: Long) {}
            override fun bindDouble(index: Int, value: Double) {}
            override fun bindString(index: Int, value: String) {}
            override fun bindBlob(index: Int, value: ByteArray) {}
            override fun clearBindings() {}
            override fun close() {}
        }
    }

    override fun beginTransaction() { conn.setAutoCommit(false) }
    override fun beginTransactionNonExclusive() { conn.setAutoCommit(false) }
    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) { conn.setAutoCommit(false) }
    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) { conn.setAutoCommit(false) }
    override fun endTransaction() { conn.commit(); conn.setAutoCommit(true) }
    override fun setTransactionSuccessful() {}
    override fun inTransaction(): Boolean = !conn.autoCommit
    override val isDbLockedByCurrentThread: Boolean = false
    override fun yieldIfContendedSafely(): Boolean = false
    override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean = false
    override fun needUpgrade(newVersion: Int): Boolean = false
    override fun setMaxSqlCacheSize(cacheSize: Int) {}
    override var version: Int = 3
    override val maximumSize: Long = 0L
    override fun setMaximumSize(numBytes: Long): Long = 0L
    override var pageSize: Long = 0L
    override fun query(query: String): Cursor = error("")
    override fun query(query: String, bindArgs: Array<out Any?>): Cursor = error("")
    override fun query(query: SupportSQLiteQuery): Cursor = error("")
    override fun query(query: SupportSQLiteQuery, cancellationSignal: android.os.CancellationSignal?): Cursor = error("")
    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long = 0L
    override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int = 0
    override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int = 0
    override val isReadOnly: Boolean = false
    override val isOpen: Boolean = !conn.isClosed
    override val path: String? = "jdbc:sqlite::memory:"
    override fun setLocale(locale: java.util.Locale) {}
    override fun setForeignKeyConstraintsEnabled(enabled: Boolean) {}
    override fun enableWriteAheadLogging(): Boolean = false
    override fun disableWriteAheadLogging() {}
    override val isWriteAheadLoggingEnabled: Boolean = false
    override val attachedDbs: List<android.util.Pair<String, String>>? = emptyList()
    override val isDatabaseIntegrityOk: Boolean = true
    override fun close() { conn.close() }
}

@RunWith(JUnit4::class)
class FollowerMigration3To4Test {

    private fun createRealV3Database(): RealJdbcSupportSQLiteDatabase {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val db = RealJdbcSupportSQLiteDatabase(conn)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `follower_sync_metadata` (
                `singletonId` INTEGER PRIMARY KEY NOT NULL,
                `lastReconciledCanonicalSequence` INTEGER NOT NULL,
                `lastSuccessfulPullAt` INTEGER
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `follower_inventory_items` (
                `itemId` TEXT PRIMARY KEY NOT NULL,
                `barcode` TEXT,
                `canonicalName` TEXT NOT NULL,
                `confirmedDisplayName` TEXT NOT NULL,
                `confirmedBrand` TEXT,
                `confirmedCategory` TEXT NOT NULL,
                `confirmedStorageLocation` TEXT NOT NULL,
                `confirmedQuantity` REAL NOT NULL,
                `confirmedQuantityUnit` TEXT,
                `confirmedNotes` TEXT,
                `confirmedStatus` TEXT NOT NULL,
                `mainConfirmedRevision` INTEGER NOT NULL,
                `displayName` TEXT NOT NULL,
                `brand` TEXT,
                `category` TEXT NOT NULL,
                `storageLocation` TEXT NOT NULL,
                `quantity` REAL NOT NULL,
                `quantityUnit` TEXT,
                `notes` TEXT,
                `status` TEXT NOT NULL,
                `optimisticRevision` INTEGER NOT NULL,
                `isPendingSync` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `follower_sync_outbox` (
                `operationId` TEXT PRIMARY KEY NOT NULL,
                `deviceId` TEXT NOT NULL,
                `deviceSequence` INTEGER NOT NULL,
                `operationType` TEXT NOT NULL,
                `targetItemId` TEXT,
                `payloadJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `retryCount` INTEGER NOT NULL,
                `lastAttemptAt` INTEGER,
                `lastErrorCode` TEXT,
                `mainCanonicalSequence` INTEGER,
                `mainResultCode` TEXT,
                `mainResultingRevision` INTEGER,
                `conflictId` TEXT,
                `approvalId` TEXT,
                `completedAt` INTEGER
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `follower_device_sequence` (
                `singletonId` INTEGER PRIMARY KEY NOT NULL,
                `lastUsedDeviceSequence` INTEGER NOT NULL
            )
        """.trimIndent())

        return db
    }

    @Test
    fun followerMigration3_4_versionNumbersValidation() {
        assertEquals(3, FOLLOWER_MIGRATION_3_4.startVersion)
        assertEquals(4, FOLLOWER_MIGRATION_3_4.endVersion)
    }

    @Test
    fun followerMigration3To4_existingOutbox_marksEstablishedNotFresh() {
        val db = createRealV3Database()

        db.execSQL("INSERT INTO `follower_sync_metadata` (singletonId, lastReconciledCanonicalSequence) VALUES (1, 0)")
        db.execSQL("""
            INSERT INTO `follower_sync_outbox` (
                operationId, deviceId, deviceSequence, operationType, targetItemId, payloadJson, createdAt, status, retryCount
            ) VALUES (
                'op_123', 'dev_456', 7, 'ADD_ITEM', 'item_99', '{"canonicalName":"Bread"}', 1000000, 'PENDING', 0
            )
        """.trimIndent())
        db.execSQL("INSERT INTO `follower_device_sequence` (singletonId, lastUsedDeviceSequence) VALUES (1, 7)")

        FOLLOWER_MIGRATION_3_4.migrate(db)

        val bootstrapCompleted = db.compileStatement("SELECT bootstrapCompleted FROM follower_sync_metadata WHERE singletonId = 1").simpleQueryForLong()
        val bootstrapCompletedAtStr = db.compileStatement("SELECT IFNULL(bootstrapCompletedAt, 'NULL') FROM follower_sync_metadata WHERE singletonId = 1").simpleQueryForString()
        val bootstrapCanonicalSeq = db.compileStatement("SELECT bootstrapCanonicalSequence FROM follower_sync_metadata WHERE singletonId = 1").simpleQueryForLong()

        assertEquals(1L, bootstrapCompleted)
        assertEquals("NULL", bootstrapCompletedAtStr)
        assertEquals(0L, bootstrapCanonicalSeq)

        val opId = db.compileStatement("SELECT operationId FROM follower_sync_outbox WHERE operationId = 'op_123'").simpleQueryForString()
        val devId = db.compileStatement("SELECT deviceId FROM follower_sync_outbox WHERE operationId = 'op_123'").simpleQueryForString()
        val devSeq = db.compileStatement("SELECT deviceSequence FROM follower_sync_outbox WHERE operationId = 'op_123'").simpleQueryForLong()
        val opType = db.compileStatement("SELECT operationType FROM follower_sync_outbox WHERE operationId = 'op_123'").simpleQueryForString()
        val targetId = db.compileStatement("SELECT targetItemId FROM follower_sync_outbox WHERE operationId = 'op_123'").simpleQueryForString()
        val payload = db.compileStatement("SELECT payloadJson FROM follower_sync_outbox WHERE operationId = 'op_123'").simpleQueryForString()
        val created = db.compileStatement("SELECT createdAt FROM follower_sync_outbox WHERE operationId = 'op_123'").simpleQueryForLong()
        val status = db.compileStatement("SELECT status FROM follower_sync_outbox WHERE operationId = 'op_123'").simpleQueryForString()

        assertEquals("op_123", opId)
        assertEquals("dev_456", devId)
        assertEquals(7L, devSeq)
        assertEquals("ADD_ITEM", opType)
        assertEquals("item_99", targetId)
        assertEquals("{\"canonicalName\":\"Bread\"}", payload)
        assertEquals(1000000L, created)
        assertEquals("PENDING", status)

        val lastSeq = db.compileStatement("SELECT lastUsedDeviceSequence FROM follower_device_sequence WHERE singletonId = 1").simpleQueryForLong()
        assertEquals(7L, lastSeq)

        db.close()
    }

    @Test
    fun followerMigration3To4_genuinelyFresh_remainsUnbootstrapped() {
        val db = createRealV3Database()

        db.execSQL("INSERT INTO `follower_sync_metadata` (singletonId, lastReconciledCanonicalSequence) VALUES (1, 0)")
        db.execSQL("INSERT INTO `follower_device_sequence` (singletonId, lastUsedDeviceSequence) VALUES (1, 0)")

        FOLLOWER_MIGRATION_3_4.migrate(db)

        val bootstrapCompleted = db.compileStatement("SELECT bootstrapCompleted FROM follower_sync_metadata WHERE singletonId = 1").simpleQueryForLong()
        assertEquals(0L, bootstrapCompleted)

        db.close()
    }
}
