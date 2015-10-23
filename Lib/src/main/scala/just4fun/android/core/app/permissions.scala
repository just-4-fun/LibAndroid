package just4fun.android.core.app

import android.annotation.TargetApi
import android.app.{Dialog, DialogFragment, Activity}
import android.content.{Context, DialogInterface, Intent}
import android.content.pm.{PackageInfo, PermissionGroupInfo, PermissionInfo, PackageManager}
import android.content.pm.PackageManager._
import android.net.Uri
import android.os.{Bundle, Build, Process}
import android.provider.Settings
import android.view.View.OnClickListener
import android.view._
import android.widget._
import just4fun.android.core.R
import just4fun.android.core.async.{MainThreadContext, FutureX}
import just4fun.android.core.vars.Prefs
import just4fun.android.core.vars.Prefs._
import just4fun.utils.Utils._
import just4fun.utils.logger.Logger._
import just4fun.utils.schema.ArrayBufferType

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Promise, Future}


/* PERMISSION SUBSYSTEM */

private[app] trait PermissionSubsystem {
	mgr: ModuleManager =>
	import PackageManager.{PERMISSION_GRANTED => GRANTED, PERMISSION_DENIED => DENIED}
	import Permission._
	import Modules._
	protected[this] implicit val buffType = new ArrayBufferType[String]()
	private[this] var handling = false
	private[app] var staticPermissions: Seq[PermissionExtra] = null
	private[app] var dynamicPermissions: List[PermissionExtra] = null
	private[this] var askedPermissions: ArrayBuffer[String] = null

	def exitPermissionSubsystem(): Unit = {
		handling = false
		staticPermissions = null
		dynamicPermissions = null
	}
	def initPermissionSubsystem(): Unit = if (Build.VERSION.SDK_INT >= 23) {
		import PackageInfo.{REQUESTED_PERMISSION_GRANTED => GRANTED}
		val pkgInfo = app.getPackageManager.getPackageInfo(app.getPackageName, GET_PERMISSIONS)
		// skip if all granted
		val flags = pkgInfo.requestedPermissionsFlags
		if (isEmpty(flags) || flags.forall(f => (f & GRANTED) != 0)) return
		//
		val extras = app.libResources.permissionsInfo
		val infos = if (extras == null) Nil else extras
		val pms = pkgInfo.requestedPermissions
		// detect asked and new pms
		askedPermissions = Prefs[ArrayBuffer[String]](KEY_ASKED_PMS, ArrayBuffer[String]())
		val firstAsk = askedPermissions.isEmpty
		val newPms = pms.filterNot(askedPermissions.contains(_))
		logD(s"INITIAL PMS:  ${pms.zip(flags).map(pair => s"[${pair._1}=${pair._2}]").mkString(", ")}")
		// detect non-granted pms
		var pmsDenied: IndexedSeq[String] = if (nonEmpty(pms) && nonEmpty(flags)) {
			for (n <- 0 until pms.length if (flags(n) & GRANTED) == 0) yield pms(n)
		} else IndexedSeq.empty
		//
		if (pmsDenied.isEmpty) return
		// do not request dynamic permissions
		pmsDenied = pmsDenied.filterNot(p => infos.exists(i => i.name == p && i.typ == DYNAMIC))
		if (pmsDenied.isEmpty) return
		// if <don't ask>  request only critical
		logD(s"askedPms= ${askedPermissions.mkString(", ")}\nnewPms= ${newPms.mkString(", ")}\npmsDenied= ${pmsDenied.mkString(", ")}\ndont ask= ${Prefs.contains(KEY_DONT_ASK)}")
		//
		val dontAsk = Prefs.contains(KEY_DONT_ASK)
		val hasNew = pmsDenied.exists(newPms.contains(_))
		if (hasNew && dontAsk) Prefs.remove(KEY_DONT_ASK)
		//
		if (dontAsk && !firstAsk && !hasNew) {
			pmsDenied = pmsDenied.filter(p => infos.exists(i => i.name == p && i.typ == CRITICAL))
			if (pmsDenied.isEmpty) return
		}
		// convert to [[PermissionExtra]]
		staticPermissions = pmsDenied.map(p => infos.find(i => i.name == p).getOrElse(PermissionOptional(p))).filter(_.isDangerous)
		//
		val critGroups = collection.mutable.Set[String]()
		staticPermissions.foreach{p =>
			p.asked = askedPermissions.contains(p.name)
			if (p.typ == CRITICAL) critGroups.add(p.info.group)
		}
		// reassign typ if at least one in the group is critical
		staticPermissions.foreach(p => if (critGroups.contains(p.info.group)) p.typ = CRITICAL)
		logD(s"STATIC PMS:\n$staticPermissions")
	}

	def hasPermission(pm: String): Boolean = {
		app.checkPermission(pm, processID, processUID) == GRANTED
	}
	def requestStaticPermissions(pms: Seq[PermissionExtra], m: Module): Unit = if (hasStaticPermissions && uiContext.nonEmpty) {
		if (pms.exists(staticPermissions.contains(_))) {
			m.waitPermissions(true)
			startPermissionsHandler()
		}
	}
	def requestDynamicPermission(p: String)(implicit m: Module): Future[Boolean] = {
		val promise = Promise[Boolean]()
		val granted = hasPermission(p)
		if (granted || Build.VERSION.SDK_INT < 23) promise.success(granted)
		else if (uiContext.isEmpty) promise.failure(new NoUiForPermissionRequestException)
		else {
			val dp = PermissionDynamic(p)
			dp.callbackPromise = promise
			dynamicPermissions = if (dynamicPermissions == null) dp :: Nil else dp :: dynamicPermissions
			startPermissionsHandler()
		}
		promise.future
	}
	def hasStaticPermissions: Boolean = staticPermissions != null && staticPermissions.nonEmpty
	def hasDynamicPermissions: Boolean = dynamicPermissions != null && dynamicPermissions.nonEmpty
	
	private[this] def startPermissionsHandler(): Unit = if (!handling) {
		// EXEC
		handling = true
		val handler = new PermissionsRequestHandler
		logD(s"START HANDLING")
		tryAdd()
		//DEFs
		def tryAdd(): Unit = {
			if (uiContext.isEmpty) onPermissionsRequested(CANCEL_REQUEST)
			else if (uiAlive) {
				uiContext.get.getFragmentManager.beginTransaction().add(handler, "permissions_h").commit()
				logD(s"ADD   HANDLER")
			}
			else FutureX.post(id = "start_permit_h")(tryAdd())(MainThreadContext)
		}
	}
	def onPermissionsRequested(id: Int): Boolean = {
		val cancelled = id == CANCEL_REQUEST
		val askedLen = askedPermissions.length
		// DEFs
		def staticDone() = if (hasStaticPermissions) {
			staticPermissions = if (cancelled) null
			else staticPermissions.filterNot { p =>
				val has = hasPermission(p.name)
				if (!askedPermissions.contains(p.name)) askedPermissions += p.name
				has
			}
			if (!hasStaticPermissions) modules.foreach(_.waitPermissions(false))
			else staticPermissions.foreach(_.asked = true)
		}
		def dynamicDone() = if (hasDynamicPermissions) {
			val (done, undone) = if (cancelled) (dynamicPermissions, Nil)
			else dynamicPermissions.partition(p => p.requestId != 0 && p.requestId <= id)
			dynamicPermissions = if (undone.isEmpty) null else undone
			done.foreach(p => p.callbackPromise.trySuccess(hasPermission(p.name)))
		}
		// EXEC
		id match {
			case CANCEL_REQUEST => staticDone(); dynamicDone() // cancelled
			case STATIC_REQUEST => staticDone()
			case _ => dynamicDone()
		}
		// update asked pms
		if (askedLen != askedPermissions.length) Prefs(KEY_ASKED_PMS) = askedPermissions
		// recheck if recently granted
		if (hasDynamicPermissions) dynamicPermissions = dynamicPermissions.filter { p =>
			hasPermission(p.name) match {
				case true => p.callbackPromise.trySuccess(true); false
				case false => true
			}
		}
		if (isEmpty(dynamicPermissions)) dynamicPermissions = null
		if (isEmpty(staticPermissions)) staticPermissions = null
		logD(s"ON REQUESTED PMS id= $id")
		handling = hasStaticPermissions || hasDynamicPermissions
		if (!handling) logD(s"FINISH HANDING")
		handling
	}
}




/* HANDLER  DIALOG FRAGMENT */
class PermissionsRequestHandler extends DialogFragment {
	import Permission._
	implicit val cache = Prefs.syscache
	val manager = Modules.mManager
	var staticDialog: PRDialog = null
	var nextId = 10
	var expectResult = true


	override def onCreate(savedInstanceState: Bundle): Unit = {
		setStyle(DialogFragment.STYLE_NO_TITLE, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
		super.onCreate(savedInstanceState)
	}
	override def onCreateView(inflater: LayoutInflater, container: ViewGroup, state: Bundle): View = {
		if (state != null) onRestoreInstanceState(state)
		//
		if (manager.hasStaticPermissions) {
			val root = inflater.inflate(R.layout.pms, container, false) // TODO switch to runtime layout
			//			val root = inflater.inflate(R.layout.permissions_request, container, false) // TODO switch to runtime layout
			//			root.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
			staticDialog = new PRDialog(root)
			logD(s"CREATED STATIC DIALOG")
			root
		}
		else {
			logD(s"CREATED DYNAMIC DIALOG")
			new ProgressBar(getActivity, null, android.R.attr.progressBarStyleLarge)
		}
	}
	override def onCreateDialog(state: Bundle): Dialog = {
		val d = super.onCreateDialog(state)
//		d.requestWindowFeature(Window.FEATURE_NO_TITLE)
		setCancelable(false)
		if (state == null && !manager.hasStaticPermissions) {
			if (manager.hasDynamicPermissions) requestDynamic() else cancel()
		}
		// else wait user action on dialog
		d
	}
	@TargetApi(23) private[this] def requestStatic(): Unit = {
		val distinct = groupStatic().map(_.name)
		logD(s"REQ STATIC :: ${distinct.mkString(", ")}")
		requestPermissions(distinct.toArray, STATIC_REQUEST)
	}
	/** Leaves only first from group as if one permission is granted than others are granted either. */
	private[this] def groupStatic(): Seq[PermissionExtra] = {
		var groups = List[String]()
		manager.staticPermissions.filter { p =>
			val g = p.info.group
			groups.contains(g) match {
				case true => false
				case false => groups = g :: groups; true
			}
		}
	}
	@TargetApi(23) private[this] def requestDynamic(): Unit = {
		nextId += 1
		val pms = manager.dynamicPermissions.withFilter(_.requestId == 0).map { e =>
			e.requestId = nextId
			e.name
		}
		logD(s"REQ DYNAMIC :: ${pms.mkString(", ")}")
		requestPermissions(pms.toArray, nextId)
		//
		if (!isHidden) getFragmentManager.beginTransaction().hide(this).commit()
	}
	override def onResume(): Unit = {
		if (!expectResult) onRequestPermissionsResult(STATIC_REQUEST, null, null)
		super.onResume()
	}
	private[this] def cancel(): Unit = {
		logD(s"CANCELED")
		onRequestPermissionsResult(CANCEL_REQUEST, null, null)
	}
	override def onDismiss(dialog: DialogInterface): Unit = {
		logD(s"DISMISSED")
		super.onDismiss(dialog)
	}
	override def onRequestPermissionsResult(id: Int, pms: Array[String], res: Array[Int]): Unit = {
		logD(s"ON REQUESTED ID= $id; results:: ${if (pms != null) pms.zip(res).map(p => s"${p._1}=${p._2}").mkString(", ") else "null"}")
		manager.onPermissionsRequested(id) match {
			case false => dismiss()
			case true => manager.hasStaticPermissions match {
				case false => staticDialog = null; requestDynamic()
				case true => staticDialog.update()
			}
		}
	}

	def onRestoreInstanceState(state: Bundle): Unit = {
		nextId = state.getInt(s"${getTag}_nextId")
		expectResult = state.getBoolean(s"${getTag}_expectResult")
	}
	override def onSaveInstanceState(state: Bundle): Unit = {
		state.putInt(s"${getTag}_nextId", nextId)
		state.putBoolean(s"${getTag}_expectResult", expectResult)
		super.onSaveInstanceState(state)
	}

	
	
	/* DIALOG VIEW */
	class PRDialog(val root: View) {
		val title = root.findViewById(R.id.title).asInstanceOf[TextView]
		val message = root.findViewById(R.id.info).asInstanceOf[TextView]
		val buttonNeg = root.findViewById(R.id.buttonNeg).asInstanceOf[Button]
		val buttonPos = root.findViewById(R.id.buttonPos).asInstanceOf[Button]
		val list = root.findViewById(R.id.list).asInstanceOf[ListView]
		val dontAsk = root.findViewById(R.id.notAgain).asInstanceOf[CheckBox]
		//
		if (Modules.libResources.permDialogTitle > 0) title.setText(Modules.libResources.permDialogTitle)
		if (Modules.libResources.permDialogMessage > 0) message.setText(Modules.libResources.permDialogMessage)
		if (Modules.libResources.permDialogDontAsk > 0) dontAsk.setText(Modules.libResources.permDialogDontAsk)
		dontAsk.setVisibility(View.GONE)
		buttonNeg.setOnClickListener(new OnClickListener {override def onClick(v: View): Unit = cancel()})
		buttonPos.setOnClickListener(new OnClickListener {override def onClick(v: View): Unit = request()})
		dontAsk.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			override def onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean): Unit = dontAskChecked(isChecked)
		})
		var data: Seq[PermissionExtra] = groupStatic()
		val adapter = new BaseAdapter {
			val inflater = getActivity.getSystemService(Context.LAYOUT_INFLATER_SERVICE).asInstanceOf[LayoutInflater]
			override def getCount: Int = data.length
			override def getItemId(position: Int): Long = position
			override def getItem(position: Int): AnyRef = data(position)
			override def getView(position: Int, convertView: View, parent: ViewGroup): View = {
				var v = convertView
				if (v == null) v = inflater.inflate(R.layout.pms_item, null)
				val icon = v.findViewById(R.id.pm_icon).asInstanceOf[ImageView]
				val title = v.findViewById(R.id.pm_title).asInstanceOf[TextView]
				val info = v.findViewById(R.id.pm_info).asInstanceOf[TextView]
				val indi = v.findViewById(R.id.pm_indicator).asInstanceOf[View]
				val pm = data(position)
				indi.setBackgroundColor(if (pm.typ == CRITICAL) 0xFFFE8C79 else 0xFF9FBEFE)
				icon.setImageResource(pm.grpInfo.icon)// TODO set default if icon is not available
				title.setText(pm.label)
				info.setText(pm.description)
				v
			}
		}
		list.setAdapter(adapter)

		def update(): Unit = {
			dontAsk.setVisibility(if (manager.staticPermissions.exists(_.typ == CRITICAL)) View.GONE else View.VISIBLE)
			updateList()
		}
		private[this] def updateList(): Unit = {
			//			val adapter = new ArrayAdapter[String](getActivity, R.layout.pms_item, toArray())
			//			list.setAdapter(adapter)
			data = groupStatic()
			adapter.notifyDataSetChanged()
		}
		@TargetApi(23) private[this] def request(): Unit = {
			expectResult = !manager.staticPermissions.exists(p => p.asked && !getActivity.shouldShowRequestPermissionRationale(p.name))
			if (expectResult) requestStatic()
			else {
				val intent = new Intent()
				intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
				val uri = Uri.fromParts("package", getActivity.getPackageName, null)
				intent.setData(uri)
				getActivity.startActivity(intent)
			}
		}
		private[this] def dontAskChecked(on: Boolean): Unit = {
			import Permission._
			if (on) Prefs(KEY_DONT_ASK) = 1
			else Prefs.remove(KEY_DONT_ASK)
			logD(s"Checked DONTASK= ${Prefs.contains(KEY_DONT_ASK)}")
		}
	}
}






/* PERMISSION  EXTRA */
object Permission {
	private[app] val KEY_DONT_ASK = "dontAskPms_"
	private[app] val KEY_ASKED_PMS = "askedPms_"
	private[app] val CANCEL_REQUEST = 0
	private[app] val STATIC_REQUEST = 1

	val CRITICAL = -1
	val OPTIONAL = 0
	val DYNAMIC = 1
}

abstract class PermissionExtra {
	import Modules._
	import PackageManager._
	val name: String
	val rationaleResId: Int
	var typ: Int = 0
	var asked = false
	lazy val info = context.getPackageManager.getPermissionInfo(name, GET_META_DATA)
	lazy val grpInfo = context.getPackageManager.getPermissionGroupInfo(info.group, GET_META_DATA)
	private[app] var requestId = 0
	private[app] var callbackPromise: Promise[Boolean] = null

	def isDangerous: Boolean = info.protectionLevel == PermissionInfo.PROTECTION_DANGEROUS
	def label = grpInfo.loadLabel(context.getPackageManager)
	def description = if (rationaleResId == 0) grpInfo.loadDescription(context.getPackageManager) else context.getString(rationaleResId)
}

case class PermissionDynamic(name: String, rationaleResId: Int = 0) extends PermissionExtra {
	typ = 1
	override def equals(o: Any): Boolean = o match {
		case pe: PermissionExtra => name == pe.name
		case _ => false
	}
}

case class PermissionOptional(name: String, rationaleResId: Int = 0) extends PermissionExtra {
	override def equals(o: Any): Boolean = o match {
		case pe: PermissionExtra => name == pe.name
		case _ => false
	}
}

case class PermissionCritical(name: String, rationaleResId: Int = 0) extends PermissionExtra {
	typ = -1
	override def equals(o: Any): Boolean = o match {
		case pe: PermissionExtra => name == pe.name
		case _ => false
	}
}
