package just4fun.android.libtest.test5

import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.widget.TextView
import just4fun.android.core.app.{Module, TwixActivity, TwixModule}
import just4fun.android.core.async.NewThreadContextHolder
import just4fun.android.libtest.{R, TestModule}
import just4fun.utils.logger.Logger._


/* Test abandoned module Module_1
 * Expected behavior: Launch and close app before Module_1 activated > broken activating progress of Module_1 causes Abandoned error. */

class TestActivity extends TwixActivity[TestActivity, MainModule] {
	//class TestActivity extends Activity with Loggable {
	implicit val context = this
	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)
		findViewById(R.id.text).asInstanceOf[TextView].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = {
				module.m1.resumeActivation()
				//startActivity(new Intent(TestActivity.this, classOf[TestActivity]))
			}
		})
	}
}


class MainModule extends TwixModule[TestActivity, MainModule] with TestModule {
	//class MainModule extends Module with TestModule {
	//	override def restoreAfterCrashPolicy = RestoreAfterCrashPolicy.IF_SELFBOUND
	startAfter = 1000
	stopAfter = 1000
	val m1 = bind[Module_1]
	//	dependOn[Module_1]

}



class Module_1 extends Module with TestModule with NewThreadContextHolder {
	startAfter = 10000
	stopAfter = 10000

	override protected[this] def onActivatingStart(firstTime: Boolean): Unit = {
		pauseActivatingProgress()
		super.onActivatingStart(firstTime)
	}
	override protected[this] def onActivatingProgress(firstTime: Boolean, seconds: Int): Boolean = {
		logV(s"first? $firstTime;  secs: $seconds")
		logV("(Module.scala:279)")
		super.onActivatingProgress(firstTime, seconds)
	}
	override protected[this] def onDeactivatingProgress(lastTime: Boolean, seconds: Int): Boolean = {
		logV(s"last? $lastTime;  secs: $seconds")
		super.onDeactivatingProgress(lastTime, seconds)
	}
	def resumeActivation(): Unit = {
		resumeActivatingProgress()
	}
}
