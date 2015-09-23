package just4fun.android.libtest.test9

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.widget.Button
import just4fun.android.core.app._
import just4fun.android.core.async.ThreadPoolContextHolder
import just4fun.android.libtest.{TestModule, R}
import just4fun.utils.logger.Logger._

/*
TEST  terminating behavior of Module_1 if while deactivating for standby all bindings are unbound so terminating is skiped.
Expected: Click button 1 to activate Module_1 by request serving. When it will start deactivating click button 2 to make it unbound. It should start new activation and dectivation to guarantee terminal dezctivating.
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
				m.unbindModule1()
			}
		})
		findViewById(R.id.button3).asInstanceOf[Button].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = {
				m.bindModule1()
			}
		})
	}
}

object MainModule {
	private var i: MainModule = null
	def apply(): MainModule = i
}
class MainModule extends Module
//with OwnThreadHolderX
//with ThreadPoolContextHolder
with TestModule {
	MainModule.i = this
	startAfter = 1000
	stopAfter = 1000
	var m1: Module_1 = bind[Module_1]

	def bindModule1(): Unit = {
		logD(s"${moduleID}:  bound to Module_1")
		m1 = bind[Module_1]
	}
	def unbindModule1(): Unit = {
		logD(s"${moduleID}:  unbound from Module_1")
		unbind(m1)
	}
}



class Module_1 extends Module
with ThreadPoolContextHolder
with TestModule {
	var firstTime = true
	override protected[this] val serveStandbyLatency: Int = 2000
	startAfter = 1000
	stopAfter = 5000
	setStandbyMode(true)

	override protected[this] def onBeforeTerminalDeactivating(): Unit ={
		logD(s"${moduleID}: ON BEFORE TERMINAL DEACTIVATING")
		if (firstTime) {
			execSomething()
			firstTime = false
		}
	}
	override protected[this] def onDeactivatingStart(lastTime: Boolean): Unit = {
		logD(s"${moduleID}: Start deactivating:  terminal? $lastTime")
		super.onDeactivatingStart(lastTime)
	}
	def execSomething(): Unit = serveAsync{
		logD(s"${moduleID}:  execSomething")
		Thread.sleep(3000)
	}
}
