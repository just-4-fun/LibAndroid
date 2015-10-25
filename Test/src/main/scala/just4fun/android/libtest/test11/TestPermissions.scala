package just4fun.android.libtest.test11

import android.app.Activity
import android.content.Intent
import android.os.{Build, Bundle}
import android.view.View
import android.view.View.OnClickListener
import android.widget.Button
import just4fun.android.core.app._
import just4fun.android.core.async.FutureX
import just4fun.android.libtest.{test11, TestModule, R}
import just4fun.utils.logger.Logger._

import scala.util.{Failure, Success}

class TestActivity extends TwixActivity[TestActivity, MainModule] {
	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)
		findViewById(R.id.button1).asInstanceOf[Button].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = {
				module.m1.useCamera()
			}
		})
	}
}


/**
<uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.READ_PHONE_STATE"/>
    <uses-permission android:name="android.permission.CALL_PHONE"/>
    <uses-permission android:name="android.permission.READ_CONTACTS"/>
    <uses-permission android:name="android.permission.WRITE_CONTACTS"/>
    <uses-permission android:name="android.permission.RECEIVE_SMS"/>
    <uses-permission android:name="android.permission.SEND_SMS"/>
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
    <uses-permission android:name="android.permission.READ_CALENDAR"/>
    <uses-permission android:name="android.permission.FLASHLIGHT"/>
    <uses-permission android:name="android.permission.CAMERA"/>

 */
object Permissions {
	import android.Manifest.permission._
	def apply() = WRITE_CONTACTS ::
	  READ_PHONE_STATE ::
	  CALL_PHONE ::
	  RECEIVE_SMS ::
	  SEND_SMS ::
	  INTERNET ::
	  FLASHLIGHT ::
	  Nil
}

class MainModule extends TwixModule[TestActivity, MainModule]
with TestModule {
	startAfter = 1000
	stopAfter = 1000
	val m1 = bind[Module_1]

	override protected[this] def onActivatingStart(firstTime: Boolean): Unit = {
		logD(s"PERMISSIONS: \n${Permissions().map(p => p+" ? "+hasPermission(p)).mkString("\n")}")
		super.onActivatingStart(firstTime)
	}
}


class Module_1 extends TestModule {
	startAfter = 1000
	stopAfter = 1000

	override protected[this] def permissions: Seq[PermissionExtra] = Permissions().map(PermissionCritical(_))

	def useCamera(): Unit = serveAsync {
		requestPermission(android.Manifest.permission.CAMERA).onComplete{
			case Success(res) => logD(s"REQ PERMISSIONS: [${android.Manifest.permission.CAMERA}] granted ? $res")
			case Failure(e) => logW(s"REQ PERMISSIONS: failed with: $e")
		}
	}
}

