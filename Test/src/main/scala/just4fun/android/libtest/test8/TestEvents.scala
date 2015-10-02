package just4fun.android.libtest.test8

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.widget.Button
import just4fun.android.core.app._
import just4fun.android.core.async.{FutureX, ThreadPoolContextHolder}
import just4fun.android.libtest.{TestModule, R}
import just4fun.utils.logger.Logger._

import scala.concurrent.Future
import scala.util.{Failure, Success}

/*
TEST [ModuleEvent] API to trigger Module_1 ability to instantly serve.
Expected: MainModule should receive [ModuleServabilityEvent]s from Moduke_1 when clicking on TestActivity buttons.
*/

class TestActivity extends Activity {
	implicit val context = this
	val m = Modules.bind[MainModule]

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)
		findViewById(R.id.button1).asInstanceOf[Button].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = {
				m.m1.execSomething()
			}
		})
		findViewById(R.id.button2).asInstanceOf[Button].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = {
				m.m1.togglePauseTests()
			}
		})
		findViewById(R.id.button3).asInstanceOf[Button].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = {
				m.m1.setAbsolutelyFailed(new Exception("Oops.."))
			}
		})
	}
}


class MainModule extends TestModule
//with OwnThreadHolderX
//with ThreadPoolContextHolder
with ModuleServabilityListener{

	startAfter = 1000
	stopAfter = 1000
	val m1 = bind[Module_1]

	override def onAbleToServe(e: AbleToServeEvent): Unit = {
		logD(s"${moduleID}: got AbleToServeEvent [${e.source.moduleID}]")
	}
	override def onUnableToServe(e: UnableToServeEvent): Unit = {
		logD(s"${moduleID}: got onUnableToServe [${e.source.moduleID}]")
		if (e.source.isDead) unbind[Module_1]
	}
}



class Module_1 extends TestModule {
	startAfter = 1000
	stopAfter = 1000
	override protected[this] val serveInParallel: Boolean = true
	standbyMode = true

	def execSomething(): Unit = serveAsync{
		logD(s"${moduleID}:  execSomething")
	}
	def togglePauseTests(): Unit = {
		if (isServingRequestsPaused) resumeServingRequests() else pauseServingRequests()
		logD(s"${moduleID}: serving paused?  $isServingRequestsPaused;  queueSize= ${requests.length};  states: [${requests.map(_.state).mkString(", ")}]  ")
	}
	def setAbsolutelyFailed(err: Throwable): Unit = {
		setFailed(err)
	}
}