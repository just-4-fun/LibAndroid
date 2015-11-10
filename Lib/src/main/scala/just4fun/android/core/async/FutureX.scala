package just4fun.android.core.async

import java.util.concurrent.CancellationException
import just4fun.utils.logger.Logger

import scala.language.implicitConversions
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}
import Logger._


object FutureX {
	object State extends Enumeration {val NONE, WAIT, EXEC, DONE = Value}

	implicit def fx2future[T](f: FutureX[T]): Future[T] = f.future

	def apply[T](code: => T)(implicit c: FutureContext): FutureX[T] = {
		new FutureX[T].task(code).activate()
	}
	def apply[T](code: => FutureX[T])(implicit c: FutureContext, d: DummyImplicit): FutureX[T] = {
		new FutureX[T].task(code).activate()
	}
	def apply[T](code: => Future[T])(implicit c: FutureContext, d: DummyImplicit, d2: DummyImplicit2 = null): FutureX[T] = {
		new FutureX[T].task(code).activate()
	}
	def post[T](delay: Long = 0, id: Any = null, replace: Boolean = true)(code: => T)(implicit c: FutureContext): FutureX[T] = {
		new FutureX[T].task(code)(c).activate(id, delay, replace)
	}
	def postSeq[T](delay: Long = 0, id: Any = null, replace: Boolean = true)(code: => FutureX[T])(implicit c: FutureContext): FutureX[T] = {
		new FutureX[T].task(code).activate(id, delay, replace)
	}
	def postFuture[T](delay: Long = 0, id: Any = null, replace: Boolean = true)(code: => Future[T])(implicit c: FutureContext): FutureX[T] = {
		new FutureX[T].task(code).activate(id, delay, replace)
	}
	def cancel(id: Any)(implicit c: FutureContext): Unit = {
		c.cancel(id)
	}

	class DummyImplicit2
}





/* FUTURE  BASE */

sealed class FutureXBase[T] extends Runnable {
	import FutureX._
	import State._
	var id: Any = ""
	protected[this] var _state = NONE
	protected[this] var _context: FutureContext = ThreadPoolContext
	protected[this] var _task: FutureTask[T] = null
	protected[this] val promise = Promise[T]()
	val future: Future[T] = promise.future
	private[async] val root: FutureXBase[_] = this

	/* USAGE */
	def state: State.Value = _state
	def context: FutureContext = _context
	def isActivated = state > NONE
	def isExecuting = state == EXEC
	def isDone = state == DONE

	/** Override to execute containing code. Use either task(...) or override execute. */
	//TODO return T
	def execute(): Try[T] = Failure(new Exception(s"There is nothing to execute in this ${getClass.getSimpleName}"))

	def task(t: FutureTask[T])(implicit c: FutureContext): this.type = {
		_context = c
		_task = t
		this
	}
	def activate(id: Any = null, delay: Long = 0, replace: Boolean = true): this.type = synchronized {
		if (_state == NONE) {
			_state = WAIT
			if (_context != null) _context.execute(id, delay, replace, this)
			else finishExecute(Failure(new Exception("Context is null")))
		}
		this
	}
	def deactivate(): Unit = synchronized {
		if (_state == WAIT) {
			_state = NONE
			if (_context != null) _context.cancel(this)
		}
	}
	def cancel(err: Throwable = null): Unit = synchronized {
		if (_state < DONE) {
			if (_state == WAIT && _context != null) {
				_context.cancel(this)
				_task match {
					case t: TaskCancellable => t.cancel()
					case _ =>
				}
			}
			finishExecute(Failure(if (err == null) new CancellationException else err))
		}
	}


	def onCompleteInMainThread[U](f: Try[T] => U): Unit = future.onComplete(f)(MainThreadContext)
	def onSuccessInMainThread[U](pf: PartialFunction[T, U]): Unit = future.onSuccess(pf)(MainThreadContext)
	def onFailureInMainThread[U](pf: PartialFunction[Throwable, U]): Unit = future.onFailure(pf)(MainThreadContext)
	def onCompleteInUi[U](f: Try[T] => U): Unit = future.onComplete(f)(UiThreadContext)
	def onSuccessInUi[U](pf: PartialFunction[T, U]): Unit = future.onSuccess(pf)(UiThreadContext)
	def onFailureInUi[U](pf: PartialFunction[Throwable, U]): Unit = future.onFailure(pf)(UiThreadContext)

	protected[this] def onFinishExecute(v: Try[T]): Unit = {}

	/* INTERNAL */

	override final def run(): Unit = startExecute()

	private[async] def startExecute(): Unit = {
		val exec = root synchronized {
			_state match {
				case WAIT => _state = EXEC; true
				case _ => false
			}
		}
		if (exec) {
			if (_task != null) _task.execute(this)
			else finishExecute(try execute() catch {case e: Throwable=> Failure(e)})
		}
		else if (_state != DONE) finishExecute(Failure(new IllegalStateException(s"Can't execute in state ${_state}")))
	}
	private[async] def finishExecute(v: Try[T]): Unit = root synchronized {
		_state = DONE
		try onFinishExecute(v) catch loggedE
		v match {
			case Success(v) => promise.trySuccess(v)
			case Failure(e: CancellationException) => promise.tryFailure(e)
			case Failure(e) => logE(e); promise.tryFailure(e)
		}
	}
}





/* FUTURE */

class FutureX[T] extends FutureXBase[T] {
	import FutureX._

	def task(code: => T)(implicit c: FutureContext): this.type = {
		_context = c
		_task = new FutureTaskSync[T](code)
		this
	}
	def task(code: => FutureX[T])(implicit c: FutureContext, d: DummyImplicit): this.type = {
		_context = c
		_task = new FutureTaskFX[T](code)
		this
	}
	def task(code: => Future[T])(implicit c: FutureContext, d: DummyImplicit, d2: DummyImplicit2 = null): this.type = {
		_context = c
		_task = new FutureTaskF[T](code)
		this
	}
	def taskCancellable(code: (() => Boolean) => T)(implicit c: FutureContext): this.type = {
		_context = c
		_task = new FutureTaskCancellableSync[T](code)
		this
	}
	def taskCancellable(code: (() => Boolean) => FutureX[T])(implicit c: FutureContext, d: DummyImplicit): this.type = {
		_context = c
		_task = new FutureTaskCancellableFX[T](code)
		this
	}
	def taskCancellable(code: (() => Boolean) => Future[T])(implicit c: FutureContext, d: DummyImplicit, d2: DummyImplicit2 = null): this.type = {
		_context = c
		_task = new FutureTaskCancellableF[T](code)
		this
	}
	def thanTask[V](code: T => V)(implicit c: FutureContext): FutureX[V] = {
		new FutureXP[V, T](this).postTask(code)(c)
	}
	def thanTaskSeq[V](code: T => FutureX[V])(implicit c: FutureContext): FutureX[V] = {
		new FutureXP[V, T](this).postTaskSeq(code)(c)
	}
	def thanTaskFuture[V](code: T => Future[V])(implicit c: FutureContext): FutureX[V] = {
		new FutureXP[V, T](this).postTaskFuture(code)(c)
	}
}






/* POST FUTURE */

class FutureXP[T, V] private[async](val parent: FutureX[V]) extends FutureX[T] {
	import FutureX._
	import State._
	override private[async] val root = parent.root
	_state = parent.state

	override def activate(id: Any = null, delay: Long = 0, replace: Boolean = true): this.type = root synchronized {
		if (root.state == NONE) {
			_state = WAIT
			parent.activate(id, delay, replace)
		}
		this
	}
	override def deactivate(): Unit = root synchronized {
		if (root.state == WAIT) {
			_state = NONE
			parent.deactivate()
		}
	}
	override def cancel(err: Throwable = null): Unit = root synchronized {
		if (_state < DONE) {
			parent.cancel()
			finishExecute(Failure(if (err == null) new CancellationException else err))
		}
	}
	
	/* INTERNAL */
	
	private[async] def postTask(code: V => T)(implicit c: FutureContext): this.type = {
		_context = c
		_task = new FuturePostTaskSync[T, V](this, code)
		this
	}
	private[async] def postTaskSeq(code: V => FutureX[T])(implicit c: FutureContext): this.type = {
		_context = c
		_task = new FuturePostTaskFX[T, V](this, code)
		this
	}
	private[async] def postTaskFuture(code: V => Future[T])(implicit c: FutureContext): this.type = {
		_context = c
		_task = new FuturePostTaskF[T, V](this, code)
		this
	}
}






/* FUTURE TASK */

trait FutureTask[T] {
	def execute(future: FutureXBase[T]): Unit
}



/* TASKs */

private[async] abstract class FutureTaskSyncBase[T] extends FutureTask[T] {
	def execute(future: FutureXBase[T]): Unit = {
		future.finishExecute(Try(onExecute()))
	}
	def onExecute(): T
}
private[async] abstract class FutureTaskFXBase[T] extends FutureTask[T] {
	def execute(future: FutureXBase[T]): Unit = {
		Try(onExecute()) match {
			case v: Failure[_] => future.finishExecute(v.asInstanceOf[Failure[T]])
			case Success(fx) => fx.activate().onComplete(future.finishExecute)(future.context)
		}
	}
	def onExecute(): FutureX[T]
}
private[async] abstract class FutureTaskFBase[T] extends FutureTask[T] {
	def execute(future: FutureXBase[T]): Unit = {
		Try(onExecute()) match {
			case v: Failure[_] => future.finishExecute(v.asInstanceOf[Failure[T]])
			case Success(f) => f.onComplete(future.finishExecute)(future.context)
		}
	}
	def onExecute(): Future[T]
}



private[async] class FutureTaskSync[T](code: => T) extends FutureTaskSyncBase[T] {
	def onExecute(): T = code
}
private[async] class FutureTaskFX[T](code: => FutureX[T]) extends FutureTaskFXBase[T] {
	def onExecute(): FutureX[T] = code
}
private[async] class FutureTaskF[T](code: => Future[T]) extends FutureTaskFBase[T] {
	def onExecute(): Future[T] = code
}



private[async] trait TaskCancellable {
	var cancelled = false
	def cancel() = cancelled = true
}
private[async] class FutureTaskCancellableSync[T](code: (() => Boolean) => T) extends FutureTaskSyncBase[T] with TaskCancellable {
	def onExecute(): T = code(() => cancelled)
}
private[async] class FutureTaskCancellableFX[T](code: (() => Boolean) => FutureX[T]) extends FutureTaskFXBase[T] with TaskCancellable {
	def onExecute(): FutureX[T] = code(() => cancelled)
}
private[async] class FutureTaskCancellableF[T](code: (() => Boolean) => Future[T]) extends FutureTaskFBase[T] with TaskCancellable {
	def onExecute(): Future[T] = code(() => cancelled)
}





private[async] trait PostTask[T, V] extends FutureTask[T] {
	val future: FutureXP[T, V]
	var value: V = _
	future.parent.onComplete {
		case v: Failure[_] => future.finishExecute(v.asInstanceOf[Failure[T]])
		case Success(v) => value = v; future.startExecute()
	}(future.context)
}
private[async] class FuturePostTaskSync[T, V](val future: FutureXP[T, V], code: V => T) extends FutureTaskSyncBase[T] with PostTask[T, V] {
	def onExecute(): T = code(value)
}
private[async] class FuturePostTaskFX[T, V](val future: FutureXP[T, V], code: V => FutureX[T]) extends FutureTaskFXBase[T] with PostTask[T, V] {
	def onExecute(): FutureX[T] = code(value)
}
private[async] class FuturePostTaskF[T, V](val future: FutureXP[T, V], code: V => Future[T]) extends FutureTaskFBase[T] with PostTask[T, V] {
	def onExecute(): Future[T] = code(value)
}


