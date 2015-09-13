package just4fun.android.core.vars

import scala.language.implicitConversions
import scala.language.experimental.macros

import android.os.Bundle
import just4fun.android.core.app.{ActivityModule, Module, Modules}
import just4fun.android.core.async.{FutureX, ThreadPoolContext}
import just4fun.core.schemify._
import just4fun.utils.schema.ReachSchema

/* VAR */
trait Var[T] {
	val name: String
}

trait SyncVar[T] extends Var[T] {
	def apply(): T
	def update(v: T): Unit
}

trait AsyncVar[T] extends Var[FutureX[T]] {
	def apply: FutureX[T]
	def update(v: T)
	def releaseValue()
}



/*PREF*/
object PrefVar {
	implicit def var2value[T](v: PrefVar[T]): T = v.apply
	def apply[T]: PrefVar[T] = macro MacroDefs.genPrefVar[T]
	/** WARN: sync refactoring with macros */
	def apply[T: Manifest](name: String, value: => Any)(implicit context: Module, typ: PropType[T]): PrefVar[T] = {
		val v = new PrefVar[T](name+" "+context.getClass.getName)
		if (value != None && !Modules.afterCrash) v.update(value.asInstanceOf[T])
		v
	}
}

final class PrefVar[T] private (val name: String)(implicit typ: PropType[T]) extends SyncVar[T] {
	private[this] var value: T = null.asInstanceOf[T]
	private[this] var inited = false
	/** WARN: Calling before Application.onCreate throws error as SharedPreferences is not yet inited. */
	def apply(): T = {
		if (!inited) {value = Prefs[T](name); inited = true}
		value
	}
	def update(v: T) = {
		value = v
		if (!inited) inited = true
		Prefs(name) = value
	}
	def reset() = { inited = false; value = null.asInstanceOf[T] }
}

/*TEMP*/
object TempVar {
	implicit def var2value[T](v: TempVar[T]): T = v.apply
	def apply[T](value: T): TempVar[T] = macro MacroDefs.genTempVar[T]
	/** WARN: sync refactoring with macros */
	def apply[T: Manifest](name: String, value: => T)(implicit context: ActivityModule, typ: PropType[T]): TempVar[T] = {
		val v = new TempVar[T](name)
		context.registerTempVar(v)
		v.update(value)
		v
	}
}

final class TempVar[T] private (val name: String)(implicit typ: PropType[T]) extends SyncVar[T] {
	private[this] var value: T = null.asInstanceOf[T]
	def apply(): T = value
	def update(v: T) = value = v
	private[core] def load(state: Bundle) = {
		value = typ.eval(typ.read(state, name)(BundleReader))
	}
	private[core] def save(state: Bundle) = {
		typ.write(value, state, name)(BundleWriter)
	}
}

/*FILE*/
object FileVar {
	implicit def var2future[T](v: FileVar[T]): FutureX[T] = v.apply
	def apply[T](filename: String): FileVar[T] = macro MacroDefs.genFileVar[T]
	/** WARN: sync refactoring with macros */
	def apply[T: Manifest](name: String, fileName: => String)(implicit context: Module, typ: ReachSchema[T]): FileVar[T] = {
		val v = new FileVar[T](name, fileName)
		context.registerAsyncVar(v)
		v
	}
}

final class FileVar[T] private (val name: String, fileName: String)(implicit context: Module, typ: ReachSchema[T]) extends AsyncVar[T] {
	private[this] implicit val futureContext = ThreadPoolContext
	private[this] var value: FutureX[T] = null.asInstanceOf[FutureX[T]]
	private[this] var inited = false
	def apply(): FutureX[T] = {
		if (!inited) {value = load(); inited = true}
		value
	}
	def update(v: T) = {
		value = ??? // todo Promise.successful(v).future
		save(v)
	}
	def releaseValue(): Unit = {
		inited = false
		value = null.asInstanceOf[FutureX[T]]
	}
	private def load(): FutureX[T] = FutureX {
		val bytes: Array[Byte] = Array('a', 'b', 'c')
		// TODO typ.setValuesBytes(bytes)
		null.asInstanceOf[T]
	}
	private def save(v: T): FutureX[T] = FutureX {
		// TODO val bytes = typ.getValuesBytes(v)
		// save
		null.asInstanceOf[T]
	}
}
