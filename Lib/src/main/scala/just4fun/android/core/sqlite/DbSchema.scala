package just4fun.android.core.sqlite

import android.content.ContentValues
import android.database.Cursor
import just4fun.core.schemify.{StringValueType, BytesValueType, OptionType, PropType}
import just4fun.core.schemify.PropType._
import just4fun.utils.schema.SchemaType


class Column[O <: DbObject, T] protected[sqlite](override val schema: DbSchema[O])(implicit override val typ: PropType[T]) extends just4fun.core.schemify.Prop[O, T](schema) {
	var constraint: String = _
	var indexBasedName = true
	val columnPrefix = "x"
	lazy val dbName = if (indexBasedName) columnPrefix + index else name
	def sqlType: String = calcSqlType(typ)
	def sqlCreate = s"$dbName $sqlType${if (constraint == null) "" else " " + constraint}"

	private[this] def calcSqlType(typ: PropType[_]): String = typ match {
		case StringType | _: StringValueType[_] => "STRING"
		case LongType | IntType | ShortType | CharType | ByteType => "INTEGER"
		case DoubleType | FloatType => "FLOAT"
		case t: OptionType[_] => calcSqlType(t.elementType)
		case BytesType | _: BytesValueType[_] => "BLOB"
		case _ => "STRING"
	}

}



class DbSchema[T <: DbObject](implicit override val mft: Manifest[T]) extends SchemaType[T] {
	override type P[t <: T, v] = Column[t, v]

	val _id = PROP[Long].config { c => c.constraint = "PRIMARY KEY AUTOINCREMENT"; c.indexBasedName = false }

	override protected[this] def newProp[W](implicit t: PropType[W]): Column[T, W] = new Column[T, W](this)
}



trait DbObject {
	var _id: Long = 0
}