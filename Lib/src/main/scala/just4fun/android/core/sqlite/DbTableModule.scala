package just4fun.android.core.sqlite

import android.database.Cursor
import android.database.sqlite.{SQLiteConstraintException, SQLiteDatabase}
import just4fun.android.core.app.Module
import just4fun.android.core.async.{FutureX, FutureContext}
import just4fun.android.core.vars.Prefs
import just4fun.utils.logger.Logger
import Logger._
import scala.util.{Try, Failure, Success}

abstract class DbTableModule[DB <: DbModule : Manifest, T <: DbObject : Manifest] extends Module {
	implicit val schema: DbSchema[T]
	protected[this] val db = unchecked_dependOn[DB]
	override implicit val futureContext: FutureContext = db.futureContext
	setPassiveMode()

	protected[this] def indexes: Set[DbTableIndex] = Set.empty
	protected[this] def upgrades: Seq[DbTableUpgrade] = List.empty

	/* LIFECYCLE */
	override protected[this] def onActivatingStart(firstTime: Boolean): Unit = if (firstTime) {
		pauseActivatingProgress()
		open().onCompleteInUiThread {
			case Success(v) => resumeActivatingProgress()
				logW(s"###############   RESUME")
			case Failure(e) => setFailed(e)
				logW(s"###############   ERR")
		}
	}


	/* USAGE */
	def select(where: String = null, columns: List[Column[T, _]] = null, groupBy: String = null, having: String = null, orderBy: String = null, limit: String = null, distinct: Boolean = false): FutureX[List[T]] = execAsync(in_select(where, columns, groupBy, having, orderBy, limit, distinct))
	def insert(obj: T, replace: Boolean = true): FutureX[T] = execAsync{
		in_insert(obj, replace)
		if (obj._id == -1) throw new SQLiteConstraintException(s"Cannot insert object ${schema.valuesToJsonMap(obj)}.")
		obj
	}
	def save(obj: T, objOld: T = null.asInstanceOf[T], columns: Iterable[Column[T, _]] = null): FutureX[T] = execAsync{
		in_save(obj, objOld, columns)
		if (obj._id == -1) throw new SQLiteConstraintException(s"Cannot save object ${schema.valuesToJsonMap(obj)}.")
		obj
	}
	def delete(objects: T*): FutureX[Int] = execAsync(in_delete(objects: _*))
	def delete(where: String = null): FutureX[Int] = execAsync(in_delete(where))

	/* USAGE Sync */
	def selectSync(where: String = null, columns: List[Column[T, _]] = null, groupBy: String = null, having: String = null, orderBy: String = null, limit: String = null, distinct: Boolean = false): Try[List[T]] = execTry(in_select(where, columns, groupBy, having, orderBy, limit, distinct))
	def insertSync(obj: T, replace: Boolean = true): Try[T] = execTry{
		in_insert(obj, replace)
		if (obj._id == -1) throw new SQLiteConstraintException(s"Cannot insert object ${schema.valuesToJsonMap(obj)}.")
		obj
	}
	def saveSync(obj: T, objOld: T = null.asInstanceOf[T], columns: Iterable[Column[T, _]] = null): Try[T] = execTry{
		in_save(obj, objOld, columns)
		if (obj._id == -1) throw new SQLiteConstraintException(s"Cannot save object ${schema.valuesToJsonMap(obj)}.")
		obj
	}
	def deleteSync(objects: T*): Try[Int] = execTry(in_delete(objects: _*))
	def deleteSync(where: String = null): Try[Int] = execTry(in_delete(where))


	/* INTERNAL */
	protected[this] def in_select(where: String = null, columns: List[Column[T, _]] = null, groupBy: String = null, having: String = null, orderBy: String = null, limit: String = null, distinct: Boolean = false): List[T] = {
		val cols = if (columns == null) schema.propsReal else if (!columns.contains(schema._id)) schema._id :: columns else columns
		val colNames = cols.map(_.dbName).toArray
		val q = db.buildQuery(schema.schemaName, colNames, where, groupBy, having, orderBy, limit, distinct)
		db.in_selectAndClose(q) { cursor =>
			db.generateFromCursor[T](cursor) {CursorReader.toObject(cursor, schema, cols)}
		}
	}
	protected[this] def in_insert(obj: T, replace: Boolean): T = {
		val vals = ContentValuesWriter.fromObject(obj, schema, schema.propsReal)
		val conflictAlg = if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_ABORT
		obj._id = db.in_insert(schema.schemaName, vals, conflictAlg)
		obj
	}
	protected[this] def in_save(obj: T, objOld: T, columns: Iterable[Column[T, _]]): T = {
		val cols = if (columns == null) schema.propsReal else columns
		val vals = ContentValuesWriter.fromObject(obj, objOld, schema, cols)
		if (obj._id > 0) db.in_update(schema.schemaName, vals, s"${schema._id.dbName} = ${obj._id}", null, SQLiteDatabase.CONFLICT_ABORT)
		else obj._id = db.in_insert(schema.schemaName, vals, SQLiteDatabase.CONFLICT_ABORT)
		obj
	}
	protected[this] def in_delete(objects: T*): Int = {
		val where = objects.map(o => o._id).mkString(s"${schema._id.dbName} in(", ",", ")")
		db.in_delete(schema.schemaName, where)
	}
	protected[this] def in_delete(where: String): Int = {
		db.in_delete(schema.schemaName, where)
	}

	protected[this] def open(): FutureX[Unit] = FutureX {
		new TableStarter
	}




	/* START HELPER */

	private class TableStarter {
		val name = schema.schemaName
		var version = Prefs[Int](s"$name version")
		val columnTypes = loadTypes()
		val newSize = columnTypes.length
		val indexes = DbTableModule.this.indexes
		if (newSize == 0) create() else update()

		def create() = {
			createTable()
			createIndexes(indexes)
		}

		def update() = {
			// Drop excessive indexes
			val oldIndexes = loadIndexes().toSet
			dropIndexes(oldIndexes diff indexes)
			// Add new columns
			schema.props.drop(newSize).foreach(addColumn)
			// Execute upgrades
			val newV = applyUpgrades()
			if (newV > version) Prefs(s"$name version") = newV
			// check if recreate table (if column types changed)
			val noRecreate = schema.props.zip(columnTypes).forall { case (prop, typ) => prop.sqlType == typ }
			// Recreate whole table or just create missing Indexes
			if (noRecreate) createIndexes(indexes diff oldIndexes)
			else recreateTable()
		}

		def loadTypes(): Seq[String] = {
			db.in_selectAndClose(s"PRAGMA table_info($name)") { cursor =>
				val ix = cursor.getColumnIndex("type")
				db.generateFromCursor(cursor) {cursor.getString(ix).toUpperCase}
			}
		}
		def createTable(): Unit = {
			val cols = schema.props.map(_.sqlCreate).mkString(",")
			execQuery(s"CREATE TABLE IF NOT EXISTS $name ($cols)", false)
		}
		def loadIndexes(): Seq[DbTableIndex] = {
			db.in_selectAndClose(s"PRAGMA index_list($name)") { cursor =>
				val ix = cursor.getColumnIndex("name")
				db.generateFromCursor(cursor) {new DbTableIndex(cursor.getString(ix))}
			}
		}
		def dropIndexes(indexes: Set[DbTableIndex]) = indexes.foreach { index =>
			execQuery(s"DROP INDEX IF EXISTS ${index.name}")
		}
		def createIndexes(indexes: Set[DbTableIndex]) = indexes.foreach { index =>
			execQuery(s"CREATE INDEX IF NOT EXISTS ${index.name} ON $name (${index.dbPropNames})")
		}
		def addColumn(col: Column[T, _]) = execQuery(s"ALTER TABLE $name ADD COLUMN ${col.sqlCreate}")
		def applyUpgrades(): Int = {
			var v = version
			upgrades.dropWhile(_.version <= version).foreach { upgrade =>
				upgrade.query.foreach { q => execQuery(q); v = upgrade.version }
			}
			v
		}
		def recreateTable(): Unit = {
			// rename old table
			val oldTable = "_" + name
			execQuery(s"ALTER TABLE $name RENAME TO $oldTable")
			// create new table
			createTable()
			// copy old values to new table
			val colStr = schema.props.take(newSize).map(_.dbName).mkString(",")
			execQuery(s"INSERT INTO $name ($colStr) SELECT $colStr FROM  $oldTable")
			// drop old table
			execQuery(s"DROP TABLE IF EXISTS $oldTable")
			// create indexes
			createIndexes(indexes)
		}
		def execQuery(q: String, silent: Boolean = true) = db.execSqlSync(q) match {
			case Failure(e) => if (silent) logE(e) else throw e
			case _ =>
		}
	}
	




	case class DbTableIndex(cols: Column[T, _]*) {
		var name = cols.foldLeft("")((res, prop) => res + prop.dbName)

		def this(_name: String) = { this(); name = _name }
		def dbPropNames = cols.map(_.dbName).mkString(",")
		override def equals(other: Any): Boolean = other match {
			case ix: DbTableIndex => name == ix.name
			case _ => false
		}
	}
	


	case class DbTableUpgrade(version: Int, query: String*)

}




