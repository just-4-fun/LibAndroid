package just4fun.android.core.vars

import android.os.Bundle
import just4fun.core.schemify.{TypeWriter, SchemaType, IterableType, TypeReader}
import just4fun.utils.schema.typefactory.{JsonArrayWriter, JsonReader}


/* BUNDLE READER */
object BundleReader extends TypeReader[Bundle, Bundle, String] {
	override def toObject[T](v: Bundle, typ: SchemaType[T]): T = {
		if (v == null) null.asInstanceOf[T]
		else typ.createFinding { onFind =>
			val itr = v.keySet().iterator()
			while (itr.hasNext) onFind(itr.next(), p => p.typ.read(v, p.name))
		}
	}
	override def toArray[E, T](v: Bundle, typ: IterableType[E, T]): T = {
		if (v == null) null.asInstanceOf[T]
		else if (v.isEmpty) null.asInstanceOf[T]
		else readArray(typ, v, v.keySet().iterator().next())
	}

	override def readObject[T](typ: SchemaType[T], d: Bundle, k: String): T = d.getString(k) match {
		case null => null.asInstanceOf[T]
		case v => typ.createFrom(v)(JsonReader)
	}
	override def readArray[E, T](typ: IterableType[E, T], d: Bundle, k: String): T = d.getString(k) match {
		case null => null.asInstanceOf[T]
		case v => typ.createFrom(v)(JsonReader)
	}
	override def readBytes(d: Bundle, k: String): Any = d.getByteArray(k)
	override def readString(d: Bundle, k: String, ascii: Boolean): Any = d.getString(k)
	override def readLong(d: Bundle, k: String): Any = d.getLong(k)
	override def readInt(d: Bundle, k: String): Any = d.getInt(k)
	override def readShort(d: Bundle, k: String): Any = d.getShort(k)
	override def readChar(d: Bundle, k: String): Any = d.getChar(k)
	override def readByte(d: Bundle, k: String): Any = d.getByte(k)
	override def readDouble(d: Bundle, k: String): Any = d.getDouble(k)
	override def readFloat(d: Bundle, k: String): Any = d.getFloat(k)
	override def readBoolean(d: Bundle, k: String): Any = d.getBoolean(k)
	override def readNull(d: Bundle, k: String): Any = null
}





/* BUNDLE READER */
object BundleWriter extends TypeWriter[Bundle, Bundle, String] {
	override def fromObject[T](v: T, typ: SchemaType[T]): Bundle = {
		if (v == null) null
		else {
			val d = new Bundle
			typ.valuesReadingAll(v) { (p, v) => p.typ.write(v, d, p.name) }
			d
		}
	}
	override def fromArray[E, T](v: T, typ: IterableType[E, T]): Bundle = {
		if (v == null) null
		else {
			val d = new Bundle
			d.putString("values", typ.valuesTo(v)(JsonArrayWriter))
			d
		}
	}

	override def writeObject[T](typ: SchemaType[T], v: T, d: Bundle, k: String): Unit = {
		d.putString(k, typ.valuesTo(v)(JsonArrayWriter))
	}
	override def writeArray[E, T](typ: IterableType[E, T], v: T, d: Bundle, k: String): Unit = {
		d.putString(k, typ.valuesTo(v)(JsonArrayWriter))
	}
	override def writeBytes(v: Array[Byte], d: Bundle, k: String): Unit = d.putByteArray(k, v)
	override def writeString(v: String, d: Bundle, k: String, ascii: Boolean): Unit = d.putString(k, v)
	override def writeLong(v: Long, d: Bundle, k: String): Unit = d.putLong(k, v)
	override def writeInt(v: Int, d: Bundle, k: String): Unit = d.putInt(k, v)
	override def writeShort(v: Short, d: Bundle, k: String): Unit = d.putShort(k, v)
	override def writeChar(v: Char, d: Bundle, k: String): Unit = d.putChar(k, v)
	override def writeByte(v: Byte, d: Bundle, k: String): Unit = d.putByte(k, v)
	override def writeDouble(v: Double, d: Bundle, k: String): Unit = d.putDouble(k, v)
	override def writeFloat(v: Float, d: Bundle, k: String): Unit = d.putFloat(k, v)
	override def writeBoolean(v: Boolean, d: Bundle, k: String): Unit = d.putBoolean(k, v)
	override def writeNull(d: Bundle, k: String): Unit = ()
}