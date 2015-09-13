package just4fun.android.libtest.test2

import android.app.Activity
import android.os.Bundle
import just4fun.android.core.app.Module.RestoreAfterCrashPolicy
import just4fun.android.core.app.{TwixActivity, Module, TwixModule, Modules}
import just4fun.android.core.async.{OwnThreadContextHolder, FutureX, NewThreadContextHolder}
import just4fun.android.libtest.{TestModule, R}
import just4fun.utils.logger.Logger
import Logger._

import scala.concurrent.Future
import scala.util.{Success, Failure}

class TestActivity extends TwixActivity[TestActivity, MainModule] {
	implicit val context = this
	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)
	}
}


class MainModule extends TwixModule[TestActivity, MainModule]
//with NewThreadFeature
//with ParallelThreadFeature
with TestModule {
	startAfter = 1000
	stopAfter = 1000

	// CAUSES COMPILE-TIME ERROR
	implicit val a: Activity = null
//	dependOn[MainModule]
//	bind[MainModule]
//	val s6 = bind
//	unbind[MainModule]
//	unbind
//	Modules.bind[MainModule]
//	Modules.bind(classOf[MainModule])
//	Modules.bind(classOf[Module_4])
//	Modules.bindSelf[Module_4]
//	Modules.unbind(classOf[Module_4])
//	Modules.bind
//	Modules.unbind[MainModule]
//	Modules.unbind

	val s1 = bind[Module_1]
	val s2 = bind[Module_2]
	val s3 = bind[Module_3]
	val s4 = bind[Module_4]

	override protected[this] def onActivatingFinish(firstTime: Boolean): Unit = {
		FutureX.post(delay = 5000) {
			for (n <- 0 to 0) runRequest3()
			logV(s"Dump 0  >>>>>>  ${dumpState()}")
		}
	}
	def runRequest1(): Unit = execAsync {
		logV(s"<<<< runRequest >>>>")
	}
	def runRequest2(): Unit = execAsyncSeq {
		FutureX {
			logV("<<<<     runRequestAsyncSeq     >>>>")
		}
	}
	def runRequest3(): Unit = execAsync {
		logV("<<<< runRequestAsync >>>>")
		s1.runAsync().onComplete {
			case Failure(e) => logE(e, "ASYNC REQUEST oops...")
			case Success(v) => logV(s"<<<<<<<<  End ASYNC REQUEST:  $v  >>>>>>>>")
		}
		s1.runFuture().onComplete {
			case Failure(e) => logE(e, "FUTURE REQUEST oops...")
			case Success(v) => logV(s"<<<<<<<<  End FUTURE REQUEST:  $v  >>>>>>>>")
		}
		s1.runAsyncSeq().onCompleteInUiThread {
			case Failure(e) => logE(e, "ASYNCSEQ REQUEST oops...")
			case Success(v) => logV(s"<<<<<<<<  End ASYNCSEQ REQUEST:  $v  >>>>>>>>")
		}
	}
}


class Module_1 extends Module with TestModule
with NewThreadContextHolder
//with ParallelThreadFeature
{
	startAfter = 1000
	stopAfter = 1000
	dependOn[Module_2]
	setPassiveMode()
	def runAsync(): FutureX[Int] = execAsync {
		logV(s"<<<<<<<<  Start ASYNC REQUEST  >>>>>>>>")
		4
	}
	def runFuture(): FutureX[Int] = execAsyncFuture {
		Future {
			logV(s"<<<<<<<<  Start FUTURE REQUEST  >>>>>>>>")
			4
		}
	}
	def runAsyncSeq(): FutureX[Int] = execAsyncSeq {
		logV(s"<<<<<<<<  Start ASYNCSEQ REQUEST  >>>>>>>>")
		(new FutureX).task {
			logV("<<<<<<<<<<< 1 >>>>>>>>>>>")
			1
		}.thanTask { n =>
			logV("<<<<<<<<<<< 2 >>>>>>>>>>>")
			n + 1
		}.thanTaskFuture { n =>
			Future {
				logV("<<<<<<<<<<< 3 >>>>>>>>>>>")
				n + 1
			}
		}.thanTaskSeq { n =>
			logV("<<<<<<<<<<< 4 >>>>>>>>>>>")
			val nn = n + 1
			FutureX {
				logV("<<<<<<<<<<< 5 >>>>>>>>>>>")
				1 + nn
			}
		}
	}
}


class Module_2 extends Module with TestModule {
	startAfter = 1000
	stopAfter = 1000
	setPassiveMode()
	dependOn[Module_3]
}


class Module_3 extends Module with TestModule {
	startAfter = 1000
	stopAfter = 1000
	setPassiveMode()
	val m4 = dependOn[Module_4]
}


class Module_4 extends Module with NewThreadContextHolder with TestModule {
//	override def restoreAfterCrashPolicy = RestoreAfterCrashPolicy.IF_SELF_BOUND
	startAfter = 1000
	stopAfter = 1000
	setPassiveMode()
	bindSelf()
}

