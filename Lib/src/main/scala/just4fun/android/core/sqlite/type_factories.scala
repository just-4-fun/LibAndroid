package just4fun.android.core.sqlite

import android.content.ContentValues
import android.database.Cursor
import just4fun.core.schemify.{TypeReader, IterableType, SchemaType, TypeWriter}
import just4fun.utils.schema.typefactory.{JsonReader, JsonArrayWriter}

/* Cursor READER */

object CursorReader extends TypeReader[Null, Cursor, Int] {
	def toObject[T <: DbObject](cursor: Cursor, typ: DbSchema[T], cols: Iterable[Column[T, _]]): T = {
		typ.createReading(cols) { (ix, p) =>
			p.typ.read(cursor, ix)
		}
	}
	private def readType(cursor: Cursor, ix: Int) = cursor.getType(ix) match {
		case Cursor.FIELD_TYPE_STRING => cursor.getString(ix)
		case Cursor.FIELD_TYPE_INTEGER => cursor.getLong(ix)
		case Cursor.FIELD_TYPE_FLOAT => cursor.getDouble(ix)
		case Cursor.FIELD_TYPE_BLOB => cursor.getBlob(ix)
		case _ => null
	}
	
	override def readObject[T](typ: SchemaType[T], d: Cursor, k: Int): T = d.getType(k) match {
		case Cursor.FIELD_TYPE_STRING => typ.createFrom(d.getString(k))(JsonReader)
		case _ => null.asInstanceOf[T]
	}
	override def readArray[E, T](typ: IterableType[E, T], d: Cursor, k: Int): T = d.getType(k) match {
		case Cursor.FIELD_TYPE_STRING => typ.createFrom(d.getString(k))(JsonReader)
		case _ => null.asInstanceOf[T]
	}
	override def readChar(d: Cursor, k: Int): Any = readType(d, k)
	override def readLong(d: Cursor, k: Int): Any = readType(d, k)
	override def readFloat(d: Cursor, k: Int): Any = readType(d, k)
	override def readByte(d: Cursor, k: Int): Any = readType(d, k)
	override def readShort(d: Cursor, k: Int): Any = readType(d, k)
	override def readInt(d: Cursor, k: Int): Any = readType(d, k)
	override def readBytes(d: Cursor, k: Int): Any = readType(d, k)
	override def readString(d: Cursor, k: Int, ascii: Boolean): Any = readType(d, k)
	override def readBoolean(d: Cursor, k: Int): Any = readType(d, k)
	override def readDouble(d: Cursor, k: Int): Any = readType(d, k)
	override def readNull(d: Cursor, k: Int): Any = readType(d, k)
	
	override def toObject[T](v: Null, typ: SchemaType[T]): T = ???
	override def toArray[E, T](v: Null, typ: IterableType[E, T]): T = ???
}




/* ContentValues WRITER */

object ContentValuesWriter extends TypeWriter[Null, ContentValues, String] {
	def fromObject[T <: DbObject](obj: T, typ: DbSchema[T], cols: Iterable[Column[T, _]]): ContentValues = {
		val vals = new ContentValues
		typ.valuesReading(obj, cols) { (ix, p, v) =>
			if (p == typ._id && v == 0) () //  empty _id > skip
			else if (v == null) vals.putNull(p.dbName)
			else p.typ.write(v, vals, p.dbName)
		}
		vals
	}
	def fromObject[T <: DbObject](obj: T, objOld: T, typ: DbSchema[T], cols: Iterable[Column[T, _]]): ContentValues = {
		val vals = new ContentValues
		if (obj._id == 0 && objOld != null && objOld._id > 0) obj._id = objOld._id
		typ.valuesReading(obj, cols) { (ix, p, v) =>
			if (p == typ._id || (objOld != null && v == p.getter(objOld))) () // no update or _id > skip
			else if (v == null) vals.putNull(p.dbName)
			else p.typ.write(v, vals, p.dbName)
		}
		vals
	}
	override def writeObject[T](typ: SchemaType[T], v: T, d: ContentValues, k: String): Unit = {
		d.put(k, typ.valuesTo(v)(JsonArrayWriter))
	}
	override def writeArray[E, T](typ: IterableType[E, T], v: T, d: ContentValues, k: String): Unit = {
		d.put(k, typ.valuesTo(v)(JsonArrayWriter))
	}
	override def writeString(v: String, d: ContentValues, k: String, ascii: Boolean): Unit = d.put(k, v)
	override def writeBytes(v: Array[Byte], d: ContentValues, k: String): Unit = d.put(k, v)
	override def writeFloat(v: Float, d: ContentValues, k: String): Unit = d.put(k, v: java.lang.Float)
	override def writeDouble(v: Double, d: ContentValues, k: String): Unit = d.put(k, v)
	override def writeShort(v: Short, d: ContentValues, k: String): Unit = d.put(k, v: java.lang.Short)
	override def writeInt(v: Int, d: ContentValues, k: String): Unit = d.put(k, v: java.lang.Integer)
	override def writeBoolean(v: Boolean, d: ContentValues, k: String): Unit = d.put(k, v)
	override def writeChar(v: Char, d: ContentValues, k: String): Unit = d.put(k, v.toString)
	override def writeLong(v: Long, d: ContentValues, k: String): Unit = d.put(k, v: java.lang.Long)
	override def writeByte(v: Byte, d: ContentValues, k: String): Unit = d.put(k, v: java.lang.Byte)
	override def writeNull(d: ContentValues, k: String): Unit = d.putNull(k)
	
	override def fromObject[T](v: T, typ: SchemaType[T]): Null = ???
	override def fromArray[E, T](v: T, typ: IterableType[E, T]): Null = ???
}