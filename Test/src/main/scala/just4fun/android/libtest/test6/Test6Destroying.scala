package just4fun.android.libtest.test6

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.widget.TextView
import just4fun.android.core.app.{Modules, Module, TwixActivity, TwixModule}
import just4fun.android.core.async.{FutureX, ThreadPoolContextHolder}
import just4fun.android.libtest.{R, TestModule}
import just4fun.utils.logger.Logger._

import scala.util.{Failure, Success}

/* Test newly initiated instance waits it's predecessor to be finally destroyed.
Expected: After MainActivity launched click text > MainModule will be inited and test() called. Than it will being destroyed.
 While it is destoying click text again. New instance of MainModule will be created. It will wait till predecessor is destroyed, and then will start.
  */

class TestActivity extends Activity {
	implicit val context = this
	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)
		findViewById(R.id.text).asInstanceOf[TextView].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = {
				Modules.use[MainModule].test().onCompleteInMainThread{
					case Success(_) =>logD(s"<<<  TEST   >>>")
					case Failure(e) => logE(e)
				}
			}
		})
	}
}


class MainModule extends Module with TestModule {
	//	override def restoreAfterCrashPolicy = RestoreAfterCrashPolicy.IF_SELFBOUND
	logD(s"<<<   CREATED   >>>")
	startAfter = 0
	stopAfter = 10000
//	val m1 = bind[Module_1]
	val m1 = bindSync[Module_1]

	override protected[this] def onActivatingStart(firstTime: Boolean): Unit = {
		super.onActivatingStart(firstTime)
		logD(s"first? $firstTime")
	}
	override protected[this] def onActivatingFinish(firstTime: Boolean): Unit = {
		super.onActivatingFinish(firstTime)
		logD(s"first? $firstTime")
	}
	override protected[this] def onDeactivatingStart(lastTime: Boolean): Unit = {
		super.onDeactivatingStart(lastTime)
		logD(s"last? $lastTime")
	}
	override protected[this] def onDeactivatingFinish(lastTime: Boolean): Unit = {
		super.onDeactivatingFinish(lastTime)
		logD(s"last? $lastTime")
	}
	def test(): FutureX[Unit] = serveAsync{
		m1.test().onSuccess{case _ => logD(s"<<<  TEST  >>>")}
	}
}



class Module_1 extends Module with TestModule {
	startAfter = 1000
	stopAfter = 1000
	def test(): FutureX[Unit] = serveAsync{
		logD(s"<<<  TEST  >>>")
	}
}
