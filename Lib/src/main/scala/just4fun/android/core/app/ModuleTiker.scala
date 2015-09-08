package just4fun.android.core.app

import just4fun.android.core.async.Tiker
import just4fun.utils.logger.Logger
import Logger._

import scala.collection.mutable

private[app] object ModuleTiker extends Tiker  {
	private val manager = Modules.mManager
	private val UPDATE = 0xf0
	/*todo test */ var requests = 0
	/*todo test */ var updates = 0
	/*todo test */ var changes = 0


	def requestUpdate(time: Long)(implicit s: Module): Unit = {
		postAtTime(UPDATE, time, s, REPLACE_ID_TOKEN)
		requests += 1
//		logI(s">  TIK REQUEST  $requests : $updates : $changes")
	}
	def cancelUpdates(implicit s: Module): Unit = {
		cancel(UPDATE, s)
	}

	override def handle(id: Int, token: AnyRef): Unit = {
		id match {
			case UPDATE => val s = token.asInstanceOf[Module]
				updates += 1
//				logI(s">  TIK UPDATE  $requests : $updates : $changes")
				if (s.engine.onUpdate()) {
					changes += 1
					logI(s">  TIK UPDATE with change  $requests : $updates : $changes")
				}
			case _ =>
		}
	}

}
