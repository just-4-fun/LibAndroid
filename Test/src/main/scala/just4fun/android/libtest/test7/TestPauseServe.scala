package just4fun.android.libtest.test7

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.widget.{Button, TextView}
import just4fun.android.core.app.{Module, Modules}
import just4fun.android.core.async._
import just4fun.android.libtest.{TestModule, R}
import just4fun.utils.logger.Logger._

import scala.concurrent.Future
import scala.util.{Failure, Success}

/*
TEST pause requests in MainModule.
Expected: when Activity text is clicked MainModule is bound. It generates and serves requests. At some moment pauseServingRequests is called. remaining requests should be deactivated. Later resumeServingRequests is called. Requests should be activated again.
*/

class TestActivity extends Activity {
	implicit val context = this
	val m = Modules.bind[MainModule]

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)
		findViewById(R.id.button1).asInstanceOf[Button].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = {
				m.test()
				m.test2()
			}
		})
		findViewById(R.id.button2).asInstanceOf[Button].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = {
				m.togglePauseTests()
			}
		})
	}
}


class MainModule extends Module
//with OwnThreadHolderX
with ThreadPoolContextHolder
with TestModule {

	startAfter = 1000
	stopAfter = 1000
	var N = 0
	override protected[this] val serveRequestsInParallel: Boolean = true

//	setPassiveMode(true)

	def togglePauseTests(): Unit = {
		if (isServingRequestsPaused) resumeServingRequests() else pauseServingRequests()
		logD(s"SERVING PAUSED?  $isServingRequestsPaused;  queueSize= ${requests.length};  states: [${requests.map(_.state).mkString(", ")}]  ")
	}
	def test(): FutureX[Int] = {
		val id = N
		N += 1
		val fx = serveAsync {
			logD(s"REQUEST EXEC id= $id;  queueSize= ${requests.length};  ${Thread.currentThread().getName}")
			Thread.sleep(2000)
			1
		}
		//		val fx = execAsyncFuture {
		//			val infx = FutureX {
		//				Thread.sleep(2000)
		//			}
		//			infx.id = s"internal $id"
		//			infx
		//		}
		fx.id = id.toString
		fx.onCompleteInMainThread {
			case Success(_) => logD(s"REQUEST DONE id= $id;  queueSize= ${requests.length};  ")
			case Failure(e) => logE(e)
		}
		logD(s"REQUEST GENERATED id= $id;  queueSize= ${requests.length};  ")
		fx
	}
	def test2(): Unit = {
		var n1 = 0
		val fx: FutureX[Int] = FutureX{
			FutureX{3}
		}
		val ff: FutureX[Int] = FutureX{
			Future{2}
		}
		fx.thanTaskSeq{n => n1 = n; ff}.onSuccess{case res => logD(s"TEST2 ${res+n1}")}
	}
}


trait OwnThreadHolderX extends OwnThreadContextHolder {
	override implicit val futureContext: FutureContext = new HandlerContext(getClass.getSimpleName, false) {
		override protected[this] def handle(r: Runnable): Unit = r match {
			case fx: FutureX[_] /*if fx.id != "" */ =>
//				logD(s"REQUEST BEFORE id= ${fx.id}")
				super.handle(r)
//				logD(s"REQUEST AFTER id= ${fx.id}")
			case r => super.handle(r)
		}
	}
}
