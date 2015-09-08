package just4fun.android.core.async

import java.util.concurrent.ThreadPoolExecutor.{AbortPolicy, CallerRunsPolicy}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent._

import android.os._
import just4fun.android.core.app.Modules
import just4fun.utils.logger.Logger
import Logger._

import scala.concurrent.ExecutionContext


/* INTERFACE */
trait FutureContext extends ExecutionContext  {
	def execute[T](id: Any, delayMs: Long, replace: Boolean, runnable: Runnable): Unit = {
		if (replace && id != null) cancel(id)
		if (id == null && delayMs == 0) execute(runnable)
		else execute(id, delayMs, runnable)
	}
	def start(): Unit
	def execute(id: Any, delay: Long, r: Runnable): Unit
	def cancel(idOrRunnable: Any): Unit
	def clear(): Unit
	def quit(softly: Boolean = false): Unit
	def isAlive: Boolean
	override def prepare(): FutureContext = {
		start()
		this
	}
	override def reportFailure(t: Throwable): Unit = logE(t)
}





/* HANDLER   implementation */
class HandlerContext(name: String, mainThread: Boolean = true) extends FutureContext {
	protected[this] var handler: Handler = null
	private[this] val QUIT = 0x911

	def start(): Unit = if (!isAlive) {
		val looper = if (mainThread) Looper.getMainLooper
		else {
			val thread = new HandlerThread(name)
			thread.start()
			thread.getLooper
		}
		handler = new Handler(looper) {
			override def dispatchMessage(msg: Message): Unit = msg.getCallback match {
				case r: Runnable => handle(r)
				case null if msg.what == QUIT => quit()
				case _ => logW(s" Unknown callback:  what= ${msg.what}, token= ${msg.obj}")
			}
		}
	}

	protected[this] def handle(r: Runnable): Unit = r.run()
	def isMainThread = mainThread

	override def execute(r: Runnable): Unit = {
		start()
		handler.post(r)
	}
	override def execute(id: Any, delay: Long, r: Runnable): Unit = {
		start()
		val token = if (id == null) r else id
		handler.postAtTime(r, token, SystemClock.uptimeMillis() + delay)
	}
	override def cancel(idORrunnable: Any): Unit = if (isAlive) idORrunnable match {
		case r: Runnable => handler.removeCallbacks(r)
		case id => if (id != null) handler.removeCallbacksAndMessages(id)
	}
	override def quit(safely: Boolean = false) = synchronized {
		if (handler != null)  {
			if (mainThread) clear()
			else if (safely) handler.sendEmptyMessage(QUIT)
			else handler.getLooper.quit()
			handler = null
		}
	}
	override def clear(): Unit = if (isAlive) {
		handler.removeCallbacksAndMessages(null)
	}
	override def isAlive: Boolean = synchronized(handler != null)
}




/* THREAD POOL user implementation */

class ThreadPoolContext(name: String) extends HandlerContext(name) {
	override protected[this] def handle(runnable: Runnable): Unit = {
		ThreadPoolContext.execute(runnable)
	}
	override def execute(runnable: Runnable): Unit = {
		ThreadPoolContext.execute(runnable)
	}
	override def cancel(idORrunnable: Any): Unit = {
		super.cancel(idORrunnable)
		ThreadPoolContext.cancel(idORrunnable)
	}
}




/* UI Thread implementation */

/** Re-posts runnable if UI is reconfiguring */
object UiThreadContext extends HandlerContext("Main") {
	override def handle(runnable: Runnable): Unit = Modules.uiContext match {
		case Some(a) =>
			if (a.isChangingConfigurations) handler.post(runnable)
			else super.handle(runnable)
		case None =>
	}
}




/* THREAD POOL global  implementation */

object ThreadPoolContext extends FutureContext {
	private[this] var executor: ThreadPoolExecutor = _
	private[this] var handler: Handler = _
	// Can be replaced by more specific ThreadPoolExecutor before using this object
	var constructThreadPool: () => ThreadPoolExecutor = () => {
		val cpus = Runtime.getRuntime.availableProcessors
		val corePoolSize = cpus + 1
		val maxPoolSize = cpus * 2 + 1
		val keepAlive = 1
		val factory = new ThreadFactory {
			private val threadNo = new AtomicInteger(1)
			def newThread(r: Runnable) = new Thread(r, "AsyncContext #" + threadNo.getAndIncrement)
		}
		val policy = new AbortPolicy // CallerRunsPolicy // DiscardOldestPolicy
		val queue = new LinkedBlockingQueue[Runnable](128)
		new ThreadPoolExecutor(corePoolSize, maxPoolSize, keepAlive, TimeUnit.SECONDS, queue, factory, policy)
	}


	def start(): Unit = if (!isAlive) {
		executor = constructThreadPool()
		handler = new Handler(Looper.getMainLooper) {
			override def dispatchMessage(msg: Message) {
				msg.getCallback match {
					case r: Runnable => execute(r)
					case _ =>
				}
			}
		}
	}

	override def execute(r: Runnable): Unit = {
		start()
		try executor.execute(r) catch {case e: RejectedExecutionException => logE(e)}
	}
	override def execute(id: Any, delay: Long, r: Runnable): Unit = {
		start()
		val token = if (id == null) r else id
		handler.postAtTime(r, token, SystemClock.uptimeMillis() + delay)
	}
	override def cancel(idORrunnable: Any): Unit = if (isAlive) {
		idORrunnable match {
			case r: Runnable => handler.removeCallbacks(r); executor.remove(r)
			case id => if (id != null) handler.removeCallbacksAndMessages(id)
		}
	}
	override def quit(softly: Boolean = false): Unit = synchronized {
		if (executor != null) {
			handler.removeCallbacksAndMessages(null)
			if (softly) executor.shutdown() else executor.shutdownNow()
			executor = null
			handler = null
		}
	}
	override def clear(): Unit = if (isAlive) {
		import scala.collection.JavaConverters._
		handler.removeCallbacksAndMessages(null)
		executor.getQueue.asScala.toSeq.foreach(executor.remove)
	}
	override def isAlive: Boolean = synchronized(executor != null)
}
