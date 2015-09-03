package just4fun.android.core.async

import android.os.{Looper, Message, Handler}
import just4fun.utils.devel.ILogger._

abstract class Tiker extends Handler(Looper.getMainLooper) {
	val NO_REPLACE = -1
	val REPLACE_ID = 0
	val REPLACE_ID_TOKEN = 1

	def handle(id: Int, token: AnyRef)

	def postDelayed(id: Int, delayMs: Long, token: AnyRef = null, replace: Int = REPLACE_ID): Unit = {
		replace match {
			case REPLACE_ID => removeMessages(id)
			case REPLACE_ID_TOKEN => if (token != null) removeMessages(id, token)
			case _ =>
		}
		val msg = obtainMessage(id, token)
		sendMessageDelayed(msg, delayMs)
	}
	def postAtTime(id: Int, timeMs: Long, token: AnyRef = null, replace: Int = REPLACE_ID): Unit = {
		replace match {
			case REPLACE_ID => removeMessages(id)
			case REPLACE_ID_TOKEN => if (token != null) removeMessages(id, token)
			case _ =>
		}
		val msg = obtainMessage(id, token)
		sendMessageAtTime(msg, timeMs)
	}
	def postAtFront(id: Int, token: AnyRef = null, replace: Int = REPLACE_ID): Unit = {
		replace match {
			case REPLACE_ID => removeMessages(id)
			case REPLACE_ID_TOKEN => if (token != null) removeMessages(id, token)
			case _ =>
		}
		val msg = obtainMessage(id, token)
		sendMessageAtFrontOfQueue(msg)
	}
	def cancel(id: Int, token: AnyRef = null): Unit = {
		if (token == null) removeMessages(id) else removeMessages(id, token)
	}
	def clear(): Unit = removeCallbacksAndMessages(null)

	final override def dispatchMessage(msg: Message): Unit = {
		handle(msg.what, msg.obj)
	}
}
