package just4fun.android.libtest

import android.os.Looper
import just4fun.android.core.app.Module
import just4fun.android.core.async.{OwnThreadContextHolder, NewThreadContextHolder, Tiker}
import just4fun.utils.devel.ILogger._

class TikTester extends Module  with OwnThreadContextHolder with TestModule {
	startAfter = 1000
	stopAfter = 4000
	override protected[this] def onFinishActivating(firstTime: Boolean): Unit = {
		val t0 = System.nanoTime()
		execAsync {
			val limit = 100000
			var count = 0
			val tiker = new Tiker {
				override def handle(id: Int, token: AnyRef): Unit = {
					if (id == 0 || id == limit) logD(s":main? ${Looper.getMainLooper == Looper.myLooper()}; timeNs= ${System.nanoTime() - t0}                             handled= $id")
				}
			}
			logD(s":timeNs= ${System.nanoTime() - t0}")
			while (count <= limit) {
				tiker.sendEmptyMessage(count)
				if (count == limit) logD(s":main? ${Looper.getMainLooper == Looper.myLooper()}; timeNs= ${System.nanoTime() - t0};    sent= $count")
				count += 1
			}
		}
	}
}
