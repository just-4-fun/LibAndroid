package just4fun.android.core.async

import java.util.concurrent.CancellationException
import just4fun.utils.logger.Logger

import scala.language.implicitConversions
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}
import Logger._

object FutureX extends Enumeration {
	val NONE, WAIT, EXEC, DONE = Value
	
	implicit def ext2future[T](f: FutureX[T]): Future[T] = f.future
	private val failure = Failure(new CancellationException)

	def apply[T](code: => T)(implicit c: FutureContext): FutureX[T] = {
		new FutureX[T].task(code)(c).activate()
	}
	def post[T](id: Any = null, delay: Long = 0, replace: Boolean = true)(code: => T)(implicit c: FutureContext): FutureX[T] = {
		new FutureX[T].task(code)(c).activate(id, delay, replace)
	}
	def cancel(id: Any)(implicit c: FutureContext): Unit = {
		c.cancel(id)
	}
}

class FutureX[T] extends Runnable {
	import FutureX._
	protected[this] var _state = NONE
	protected[this] var _context: FutureContext = ThreadPoolContext
	protected[this] var task: FutureTask[T] = null
	protected var parentFx: FutureX[_] = null
	protected[this] val promise = Promise[T]()
	val future: Future[T] = promise.future
	

	/* USAGE */
	override def toString: String = super.toString + "#" + _state
	def state: FutureX.Value = _state
	def context: FutureContext = _context

	def cancel(): Unit = cancelExecute()

	def onCompleteInUiThread[U](f: Try[T] => U): Unit = future.onComplete(f)(UiThreadContext)
	def onSuccessInUiThread[U](pf: PartialFunction[T, U]): Unit = future.onSuccess(pf)(UiThreadContext)
	def onFailureInUiThread[U](pf: PartialFunction[Throwable, U]): Unit = future.onFailure(pf)(UiThreadContext)

	def task(code: => T)(implicit c: FutureContext): FutureX[T] = {
		_context = c
		task = new FutureTaskSync[T](this, code)
		this
	}
	def taskSeq(code: => FutureX[T])(implicit c: FutureContext): FutureX[T] = {
		_context = c
		task = new FutureTaskAsyncFX[T](this, code)
		this
	}
	def taskFuture(code: => Future[T])(implicit c: FutureContext): FutureX[T] = {
		_context = c
		task = new FutureTaskAsyncF[T](this, code)
		this
	}
	def thanTask[V](code: T => V)(implicit c: FutureContext): FutureX[V] = {
		new FutureX[V].postTask(this, code)(c)
	}
	def thanTaskSeq[V](code: T => FutureX[V])(implicit c: FutureContext): FutureX[V] = {
		new FutureX[V].postTaskSeq(this, code)(c)
	}
	def thanTaskFuture[V](code: T => Future[V])(implicit c: FutureContext): FutureX[V] = {
		new FutureX[V].postTaskFuture(this, code)(c)
	}

	/* INTERNAL */

	protected def postTask[V](parent: FutureX[V], code: V => T)(implicit c: FutureContext): FutureX[T] = {
		_context = c
		_state = WAIT
		parentFx = parent
		task = new FutureTaskSyncPost[T, V](this, parent, code)
		this
	}
	protected def postTaskSeq[V](parent: FutureX[V], code: V => FutureX[T])(implicit c: FutureContext): FutureX[T] = {
		_context = c
		_state = WAIT
		parentFx = parent
		task = new FutureTaskAsyncPostFX[T, V](this, parent, code)
		this
	}
	protected def postTaskFuture[V](parent: FutureX[V], code: V => Future[T])(implicit c: FutureContext): FutureX[T] = {
		_context = c
		_state = WAIT
		parentFx = parent
		task = new FutureTaskAsyncPostF[T, V](this, parent, code)
		this
	}

	protected def isHead: Boolean = parentFx == null
	
	protected[core] def activate(id: Any = null, delay: Long = 0, replace: Boolean = true): FutureX[T] = synchronized {
		if (isHead) {
			if (_state < WAIT && _context != null) {
				_state = WAIT
				_context.execute(id, delay, replace, this)
			}
		}
		else parentFx.activate(id, delay, replace)
		this
	}

	override def run(): Unit = startExecute()

	protected[async] def startExecute(): Unit = if (_state < EXEC) synchronized {
		_state = EXEC
		task.execute()
	}
	protected[async] def finishExecute(v: Try[T]): Unit = {
		_state = DONE
		v match {
			case Success(v) => promise.trySuccess(v)
			case Failure(e) => promise.tryFailure(e); logE(e)
		}
	}
	protected[this] def cancelExecute(): Unit = if (_state < DONE) synchronized {
		if (!isHead) parentFx.cancel()
		else if (_state > NONE && _context != null) _context.cancel(this)
		finishExecute(failure)
	}
}





/* FUTURE TASKs */

private[async] abstract class FutureTask[T] {
	val future: FutureX[T]
	def execute(): Unit
}

private[async] class FutureTaskSync[T](val future: FutureX[T], code: => T) extends FutureTask[T] {
	override def execute(): Unit = {
		future.finishExecute(Try(onExecute()))
	}
	def onExecute(): T = code
}

private[async] class FutureTaskAsyncFX[T](val future: FutureX[T], code: => FutureX[T]) extends FutureTask[T] {
	override def execute(): Unit = {
		Try(onExecute()) match {
			case v: Failure[_] => future.finishExecute(v.asInstanceOf[Failure[T]])
			case Success(fx) => fx.activate().onComplete(future.finishExecute)(future.context)
		}
	}
	def onExecute(): FutureX[T] = code
}

private[async] class FutureTaskAsyncF[T](val future: FutureX[T], code: => Future[T]) extends FutureTask[T] {
	override def execute(): Unit = {
		Try(onExecute()) match {
			case v: Failure[_] => future.finishExecute(v.asInstanceOf[Failure[T]])
			case Success(f) => f.onComplete(future.finishExecute)(future.context)
		}
	}
	def onExecute(): Future[T] = code
}

private[async] trait PostTask[T, V] {
	this: FutureTask[T] =>
	val parent: FutureX[V]
	var value: V = _
	parent.onComplete {
		case v: Failure[_] => future.finishExecute(v.asInstanceOf[Failure[T]])
		case Success(v) => value = v; future.startExecute()
	}(future.context)
}

private[async] class FutureTaskSyncPost[T, V](override val future: FutureX[T], val parent: FutureX[V], code: V => T) extends FutureTaskSync[T](future, null.asInstanceOf[T]) with PostTask[T, V] {
	override def onExecute(): T = code(value)
}

private[async] class FutureTaskAsyncPostFX[T, V](override val future: FutureX[T], val parent: FutureX[V], code: V => FutureX[T]) extends FutureTaskAsyncFX[T](future, null) with PostTask[T, V] {
	override def onExecute(): FutureX[T] = code(value)
}

private[async] class FutureTaskAsyncPostF[T, V](override val future: FutureX[T], val parent: FutureX[V], code: V => Future[T]) extends FutureTaskAsyncF[T](future, null) with PostTask[T, V] {
	override def onExecute(): Future[T] = code(value)
}


