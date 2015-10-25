package just4fun.android.core.sqlite

import java.sql.SQLException

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite._
import just4fun.android.core.app.Module
import just4fun.android.core.async.{FutureX, OwnThreadContextHolder}
import just4fun.utils.logger.Logger
import Logger._
import scala.util.{Success, Failure, Try}


class DbModule extends Module with OwnThreadContextHolder {
	val name: String = "main"
	var db: SQLiteDatabase = _
	standbyMode = true

	/* LIFECYCLE */
	override protected[this] def onActivatingStart(firstTime: Boolean): Unit = {
		pauseActivatingProgress()
		tryOpenDatabase()
	}
	override protected[this] def onActivatingProgress(firstTime: Boolean, seconds: Int): Boolean = {
		if (db != null && db.isOpen) true
		else {
			tryOpenDatabase()
			false
		}
	}
	override protected[this] def onDeactivatingFinish(lastTime: Boolean): Unit = {
		db.close()
		db = null
	}
	
	protected[this] def tryOpenDatabase(): Unit = {
		val dbHelper = new SQLiteOpenHelper(appContext, name, null, 1) {
			override def onCreate(db: SQLiteDatabase): Unit = {}
			override def onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit = {}
		}
		/** SQLiteException if the database cannot be opened for writing. Can be: SQLiteCantOpenDatabaseException / SQLiteAccessPermException / SQLiteDatabaseCorruptException / SQLiteDiskIOException / SQLiteFullException / SQLiteOutOfMemoryException /... */
		db = Try {dbHelper.getWritableDatabase} match {
			case Success(db) => resumeActivatingProgress(); db
			case Failure(e: SQLiteException) =>
				val resolved = () => resumeActivatingProgress()
				val unresolved = () => setFailed(e)
				resolveOpenDatabaseError(e, resolved, unresolved)
				null
			case Failure(e) => throw e
		}
	}

	def resolveOpenDatabaseError(e: SQLiteException, resolved: () => Unit, unresolved: () => Unit): Unit = {
		unresolved()
	}


	/* USAGE */
	def execSql(sql: String): FutureX[Unit] = serveAsync(in_execSql(sql))
	def select(sql: String, selectArgs: Array[String] = null): FutureX[Cursor] = serveAsync(in_select(sql, selectArgs))
	def insert(table: String, values: ContentValues, conflictAlg: Int = SQLiteDatabase.CONFLICT_ABORT): FutureX[Long] = serveAsync(in_insert(table, values, conflictAlg))
	def update(table: String, values: ContentValues, where: String = null, whereArgs: Array[String] = null, conflictAlg: Int = SQLiteDatabase.CONFLICT_ABORT): FutureX[Int] = serveAsync(in_update(table, values, where, whereArgs, conflictAlg))
	/** @note To remove all rows and get a count pass "1" as the whereClause. */
	def delete(table: String, where: String = null, whereArgs: Array[String] = null): FutureX[Int] = serveAsync(in_delete(table, where, whereArgs))
	def execInTransaction[T](code: => T): FutureX[T] = serveAsync(in_execInTransaction(code))

	def buildQuery(table: String, columns: Array[String] = null, where: String = null, groupBy: String = null, having: String = null, orderBy: String = null, limit: String = null, distinct: Boolean = false): String = {
		SQLiteQueryBuilder.buildQueryString(distinct, table, columns, where, groupBy, having, orderBy, limit)
	}
	def generateFromCursor[T](cursor: Cursor)(genT: => T): List[T] = {
		def move(list: List[T], cursor: Cursor, ok: Boolean): List[T] = {
			if (ok) move(genT :: list, cursor, cursor.moveToPrevious())
			else list
		}
		move(List[T](), cursor, cursor.moveToLast())
	}

	/* SYNC USAGE */
	def execSqlSync(sql: String): Try[Unit] = serveTry(in_execSql(sql))
	def selectSync(sql: String, selectArgs: Array[String] = null): Option[Cursor] = serveOpt(in_select(sql, selectArgs))
	def insertSync(table: String, values: ContentValues, conflictAlg: Int = SQLiteDatabase.CONFLICT_ABORT): Try[Long] = serveTry(in_insert(table, values, conflictAlg))
	def updateSync(table: String, values: ContentValues, where: String = null, whereArgs: Array[String] = null, conflictAlg: Int = SQLiteDatabase.CONFLICT_ABORT): Option[Int] = serveOpt(in_update(table, values, where, whereArgs, conflictAlg))
	/** @note To remove all rows and get a count pass "1" as the whereClause. */
	def deleteSync(table: String, where: String = null, whereArgs: Array[String] = null): Option[Int] = serveOpt(in_delete(table, where, whereArgs))
	def execInTransactionSync[T](code: => T): Try[T] = serveTry(in_execInTransaction(code))


	/* INTERNAL */
	protected[sqlite] def in_execSql(sql: String): Unit = {
		logV(s"execSql= $sql")
		db.execSQL(sql)
	}
	protected[sqlite] def in_select(sql: String, selectArgs: Array[String] = null): Cursor = {
		logV(s"select sql= $sql;  args= $selectArgs")
		db.rawQuery(sql, selectArgs)
	}
	protected[sqlite] def in_selectAndClose[T](sql: String, selectArgs: Array[String] = null)(code: Cursor => T): T = {
		val cursor = in_select(sql, selectArgs)
		try code(cursor)
		finally if (!cursor.isClosed) cursor.close()
	}
	protected[sqlite] def in_insert(table: String, values: ContentValues, conflictAlg: Int): Long = {
		logV(s"insert: $table,  values: ${values}")
		if (values.size > 0) db.insertWithOnConflict(table, null, values, conflictAlg) else -1
	}
	protected[sqlite] def in_update(table: String, values: ContentValues, where: String, whereArgs: Array[String], conflictAlg: Int): Int = {
		logV(s"update: $table,  where: $where,  values: ${values}")
		db.updateWithOnConflict(table, values, where, whereArgs, conflictAlg)
	}
	/** @note To remove all rows and get a count pass "1" as the whereClause. */
	protected[sqlite] def in_delete(table: String, where: String = null, whereArgs: Array[String] = null): Int = {
		logV(s"delete: $table,  where: $where")
		db.delete(table, where, whereArgs)
	}
	protected[sqlite] def in_execInTransaction[T](code: => T): T = {
		try {
			db.beginTransaction()
			val res = code
			db.setTransactionSuccessful()
			res
		}
		finally if (db.inTransaction()) db.endTransaction()
	}
}




/* SELECT QUERY */
import scala.language.implicitConversions
// TODO Select tab columns where ...
object Query {
	implicit def string2query[T <: DbObject](where: String): Query[T] = Query[T](where)
	//	def apply(where: String):
}
case class Query[T <: DbObject](where: String) {
	var table: String = null
	var cols: Seq[Column[T, _]] = null
	var grp: String = null
	var hav: String = null
	var ord: String = null
	var lim: String = null
	var distinct: Boolean = false
	def columns(v: Seq[Column[T, _]]): Query[T] = {
		cols = v
		this
	}
}
