package just4fun.android.core.app

import android.annotation.TargetApi
import android.app.{Dialog, DialogFragment, Activity}
import android.content.{Context, DialogInterface, Intent}
import android.content.pm.{PackageInfo, PermissionGroupInfo, PermissionInfo, PackageManager}
import android.content.pm.PackageManager._
import android.graphics.Color
import android.net.Uri
import android.os.{Bundle, Build, Process}
import android.provider.Settings
import android.util.TypedValue
import android.view.View.OnClickListener
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams._
import android.view._
import android.widget._
import just4fun.android.core.R
import just4fun.android.core.async.{MainThreadContext, FutureX}
import just4fun.android.core.utils.ATools
import just4fun.android.core.vars.Prefs
import just4fun.android.core.vars.Prefs._
import just4fun.utils.Utils._
import just4fun.utils.logger.Logger._
import just4fun.utils.schema.ArrayBufferType

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Promise, Future}
import Permission._
import Modules._


/* PERMISSION SUBSYSTEM */

private[app] trait PermissionSubsystem {
	mgr: ModuleManager =>
	import PackageManager.{PERMISSION_GRANTED => GRANTED, PERMISSION_DENIED => DENIED}
	protected[this] implicit val buffType = new ArrayBufferType[String]()
	private[this] var handling = false
	private[app] var staticPermissions: Seq[PermissionExtra] = null
	private[app] var dynamicPermissions: List[PermissionExtra] = null
	private[this] var askedPermissions: ArrayBuffer[String] = null

	def exitPermissionSubsystem(): Unit = {
		handling = false
		staticPermissions = null
		dynamicPermissions = null
		askedPermissions = null
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
		val pms = pkgInfo.requestedPermissions.toIndexedSeq
		// detect asked and new pms
		askedPermissions = Prefs[ArrayBuffer[String]](KEY_ASKED_PMS, ArrayBuffer[String]())
		val firstAsk = askedPermissions.isEmpty
		val newPms = pms.filterNot(askedPermissions.contains(_))
//		logD(s"INITIAL PMS:  ${pms.zip(flags).map(pair => s"[${pair._1}=${pair._2}]").mkString(", ")}")
		// detect non-granted pms
		var pmsDenied: IndexedSeq[String] = for (n <- pms.indices if (flags(n) & GRANTED) == 0) yield pms(n)
		if (pmsDenied.isEmpty) return
		// do not request dynamic permissions
		pmsDenied = pmsDenied.filterNot(p => infos.exists(i => i.name == p && i.typ == DYNAMIC))
		if (pmsDenied.isEmpty) return
		// if <don't ask>  request only critical
//		logD(s"askedPms= ${askedPermissions.mkString(", ")}\nnewPms= ${newPms.mkString(", ")}\npmsDenied= ${pmsDenied.mkString(", ")}\ndont ask= ${Prefs.contains(KEY_DONT_ASK)}")
		//
		val dontAsk = Prefs.contains(KEY_DONT_ASK)
		val hasNew = pmsDenied.exists(newPms.contains(_))
		if (dontAsk && hasNew) Prefs.remove(KEY_DONT_ASK)
		//
		if (dontAsk && !firstAsk && !hasNew) {
			pmsDenied = pmsDenied.filter(p => infos.exists(i => i.name == p && i.typ == CRITICAL))
			if (pmsDenied.isEmpty) return
		}
		// convert to [[PermissionExtra]] and take onlt dangerous
		staticPermissions = pmsDenied.map(p => infos.find(i => i.name == p).getOrElse(PermissionOptional(p))).filter(_.isDangerous)
		//
		val critGroups = collection.mutable.Set[String]()
		staticPermissions.foreach { p =>
			p.asked = askedPermissions.contains(p.name)
			if (p.typ == CRITICAL) critGroups.add(p.info.group)
		}
		// reassign typ if at least one in the group is critical
		staticPermissions.foreach(p => if (critGroups.contains(p.info.group)) p.typ = CRITICAL)
		// order by typ (Critical on top)
		staticPermissions = staticPermissions.sortBy(_.typ)
//		logD(s"STATIC PMS:\n$staticPermissions")
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
//		logD(s"START HANDLING")
		tryAdd()
		//DEFs
		def tryAdd(): Unit = {
			if (uiContext.isEmpty) onPermissionsRequested(CANCEL_REQUEST)
			else if (uiAlive) {
				uiContext.get.getFragmentManager.beginTransaction().add(handler, "permissions_h").commit()
//				logD(s"ADD   HANDLER")
			}
			else FutureX.post(id = "start_permit_h")(tryAdd())(MainThreadContext)
		}
	}
	def onPermissionsRequested(id: Int): Boolean = if (!inited) false else {
		val cancelled = id == CANCEL_REQUEST
		val askedLen = askedPermissions.length
		// DEFs
		def staticDone() = if (hasStaticPermissions) {
			staticPermissions = if (cancelled) null
			else staticPermissions.filterNot { p =>
				val has = hasPermission(p.name)
				if (!askedPermissions.contains(p.name)) askedPermissions += p.name
				p.asked = true
				has
			}
			if (!hasStaticPermissions) modules.foreach(_.waitPermissions(false))
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
//		logD(s"ON REQUESTED PMS id= $id")
		handling = hasStaticPermissions || hasDynamicPermissions
//		if (!handling) logD(s"FINISH HANDING")
		handling
	}
}




/* HANDLER  DIALOG FRAGMENT */
class PermissionsRequestHandler extends DialogFragment with PermissionDialogListener {
	implicit val cache = Prefs.syscache
	val manager = Modules.mManager
	var staticDialog: PermissionDialogHelper = null
	var nextId = 10
	var expectResult = true

	override def onDestroyView(): Unit = {
		staticDialog = null
		super.onDestroyView()
	}
	override def onCreate(state: Bundle): Unit = {
		var theme = android.R.style.Theme_DeviceDefault_Dialog_Alert
		if (manager.hasStaticPermissions) {
			staticDialog = Modules.libResources.permissionsDialog(this)(getActivity)
			if (staticDialog.theme != null) theme = staticDialog.theme.resId
		}
		setStyle(DialogFragment.STYLE_NO_TITLE, theme)
		setCancelable(false)
		if (state == null && !manager.hasStaticPermissions) {
			if (manager.hasDynamicPermissions) requestDynamic() else cancel()
		}
		super.onCreate(state)
	}
	override def onCreateView(inflater: LayoutInflater, container: ViewGroup, state: Bundle): View = {
		if (state != null) onRestoreInstanceState(state)
		//
		if (manager.hasStaticPermissions) {
			val v = staticDialog.createView()
			staticDialog.update(groupStatic())
//			logD(s"CREATED STATIC DIALOG")
			v
		}
		else {
//			logD(s"CREATED DYNAMIC DIALOG")
			new ProgressBar(getActivity, null, android.R.attr.progressBarStyleLarge)
		}
	}
	@TargetApi(23) private[this] def requestStatic(): Unit = {
		expectResult = !manager.staticPermissions.exists(p => p.asked && !getActivity.shouldShowRequestPermissionRationale(p.name))
		if (expectResult) {
			val distinct = groupStatic().map(_.name)
//			logD(s"REQ STATIC :: ${distinct.mkString(", ")}")
			requestPermissions(distinct.toArray, STATIC_REQUEST)
		}
		else {
			val intent = new Intent()
			intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
			val uri = Uri.fromParts("package", getActivity.getPackageName, null)
			intent.setData(uri)
			getActivity.startActivity(intent)
		}
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
//		logD(s"REQ DYNAMIC :: ${pms.mkString(", ")}")
		requestPermissions(pms.toArray, nextId)
		//
		if (!isHidden) getFragmentManager.beginTransaction().hide(this).commit()
	}
	override def onResume(): Unit = {
		if (!expectResult) onRequestPermissionsResult(STATIC_REQUEST, null, null)
		super.onResume()
	}
	private[this] def cancel(): Unit = {
//		logD(s"CANCELED")
		onRequestPermissionsResult(CANCEL_REQUEST, null, null)
	}
	override def onDismiss(dialog: DialogInterface): Unit = {
//		logD(s"DISMISSED")
		super.onDismiss(dialog)
	}
	override def onRequestPermissionsResult(id: Int, pms: Array[String], res: Array[Int]): Unit = {
//		logD(s"ON REQUESTED ID= $id; results:: ${if (pms != null) pms.zip(res).map(p => s"${p._1}=${p._2}").mkString(", ") else "null"}")
		manager.onPermissionsRequested(id) match {
			case false => dismiss()
			case true => manager.hasStaticPermissions match {
				case false => staticDialog = null; requestDynamic()
				case true => if (staticDialog != null) staticDialog.update(groupStatic())
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
	def onOk(): Unit = requestStatic()
	def onCancel(): Unit = cancel()
	def onDontAsk(checked: Boolean): Unit = {
		import Permission._
		if (checked) Prefs(KEY_DONT_ASK) = 1
		else Prefs.remove(KEY_DONT_ASK)
//		logD(s"Checked DONTASK= ${Prefs.contains(KEY_DONT_ASK)}")
	}
}







/* DIALOG */
abstract class PermissionDialogHelper {
	import Permission._
	implicit val context: Activity
	val listener: PermissionDialogListener
	val titleResId = 0
	val subtitleResId = 0
	val dontAskResId = 0
	val theme: ThemeInfo = null
	var titleV: TextView = null
	var subtitleV: TextView = null
	var okV: Button = null
	var cancelV: Button = null
	var listV: ListView = null
	var dontAskV: CheckBox = null
	private[this] var permissions: Seq[PermissionExtra] = Nil
	private[this] var adapter: BaseAdapter = null
	private[this] var first = true

	protected def onCreateView(): View
	protected def onCreateListItem(permiInfo: PermissionExtra): PermissionDialogListItemHelper

	def update(permissions: Seq[PermissionExtra]) = {
		this.permissions = permissions
		adapter.notifyDataSetChanged()
		if (!first) dontAskV.setVisibility(if (permissions.exists(_.typ == CRITICAL)) View.GONE else View.VISIBLE)
		first = false
	}
	final def createView(): View = {
		val v = onCreateView()
		if (titleV != null) titleV.setText(if (titleResId == 0) "Permissions required" else context.getString(titleResId))
		if (subtitleV != null) subtitleV.setText(if (subtitleResId == 0) "To operate properly app requires permissions" else context.getString(subtitleResId))
		if (okV != null) okV.setOnClickListener(new OnClickListener {def onClick(v: View): Unit = listener.onOk()})
		if (cancelV != null) cancelV.setOnClickListener(new OnClickListener {def onClick(v: View): Unit = listener.onCancel()})
		if (dontAskV != null) {
			dontAskV.setVisibility(View.GONE)
			dontAskV.setText(if (dontAskResId == 0) "don't show again" else context.getString(dontAskResId))
			dontAskV.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
				def onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean): Unit = listener.onDontAsk(isChecked)
			})
		}
		adapter = new BaseAdapter {
			override def getItemId(position: Int): Long = position
			override def getCount: Int = permissions.length
			override def getItem(position: Int): AnyRef = permissions(position)
			override def getView(position: Int, convertView: View, parent: ViewGroup): View = {
				val h = onCreateListItem(permissions(position))
				h.createView()
			}
		}
		listV.setAdapter(adapter)
		v
	}

	case class ThemeInfo(resId: Int, light: Boolean)
}





/* LIST ITEM */
abstract class PermissionDialogListItemHelper {
	import Permission._
	implicit val context: Activity
	val permission: PermissionExtra
	val colorCritical: Int
	val colorNormal: Int
	var iconV: ImageView = null
	var titleV: TextView = null
	var subtitleV: TextView = null

	protected def onCreateView(): View

	final def createView(): View = {
		val v = onCreateView()
		if (iconV != null) iconV.setImageResource(permission.grpInfo.icon) // TODO set default if icon is not available
		if (iconV != null) iconV.setColorFilter(if (permission.typ == CRITICAL) colorCritical else colorNormal)
		if (titleV != null) titleV.setText(permission.label)
		if (titleV != null) titleV.setTextColor(if (permission.typ == CRITICAL) colorCritical else colorNormal)
		if (subtitleV != null) subtitleV.setText(permission.description)
		v
	}
}






/* DIALOG IMPL */
class DefaultPermissionDialogHelper(val listener: PermissionDialogListener)(implicit val context: Activity) extends PermissionDialogHelper {
	import LayoutParams.{MATCH_PARENT => MATCH, WRAP_CONTENT => WRAP}
	override val theme = ThemeInfo(android.R.style.Theme_DeviceDefault_Dialog_Alert, false)
	lazy val light = theme.light
	lazy val firstTextColor = if (light) 0xFF555555 else 0xFFCCCCCC
	lazy val secondTextColor = if (light) 0xFF888888 else 0xFFAAAAAA

	override protected def onCreateView(): View = {
		val dp10 = ATools.dp2pix(10)
		val dp16 = ATools.dp2pix(16)
		//
		val root = new LinearLayout(context)
		root.setOrientation(LinearLayout.VERTICAL)
		root.setLayoutParams(new LayoutParams(MATCH, WRAP))
		//
		titleV = new TextView(context)
		titleV.setId(1)
		titleV.setTextColor(firstTextColor)
		titleV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20)
		titleV.setPadding(dp10, dp10, dp10, dp10)
		var params = new LinearLayout.LayoutParams(WRAP, WRAP)
		params.gravity = Gravity.CENTER
		titleV.setLayoutParams(params)
		root.addView(titleV)
		//
		subtitleV = new TextView(context)
		subtitleV.setId(2)
		subtitleV.setTextColor(secondTextColor)
		subtitleV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16)
		subtitleV.setPadding(dp16, 0, dp16, 0)
		subtitleV.setLayoutParams(new LayoutParams(WRAP, WRAP))
		root.addView(subtitleV)
		//
		listV = new ListView(context)
		listV.setId(3)
		listV.setLayoutParams(new LinearLayout.LayoutParams(WRAP, WRAP, 1f))
		listV.setPadding(dp16, dp10, dp16, dp10)
		root.addView(listV)
		//
		dontAskV = new CheckBox(new ContextThemeWrapper(context, theme.resId))
		dontAskV.setId(4)
		dontAskV.setPadding(dp16, 0, dp16, 0)
		dontAskV.setLayoutParams(new LayoutParams(WRAP, WRAP))
		root.addView(dontAskV)
		//
		val subroot = new LinearLayout(context)
		subroot.setOrientation(LinearLayout.HORIZONTAL)
		subroot.setPadding(dp10, 0, dp10, 0)
		params = new LinearLayout.LayoutParams(WRAP, WRAP)
		params.gravity = Gravity.RIGHT
		subroot.setLayoutParams(params)
		root.addView(subroot)
		//
		cancelV = new Button(context) //, null, android.R.attr.buttonBarNeutralButtonStyle)
		cancelV.setId(5)
		cancelV.setText(android.R.string.cancel)
		cancelV.setBackgroundColor(0x00FFFFFF)
		cancelV.setTextColor(firstTextColor)
		cancelV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14)
		cancelV.setLayoutParams(new LayoutParams(WRAP, WRAP))
		subroot.addView(cancelV)
		//
		okV = new Button(context) //, null, android.R.attr.buttonBarPositiveButtonStyle)
		okV.setId(6)
		okV.setText(android.R.string.ok)
		okV.setBackgroundColor(0x00FFFFFF)
		okV.setTextColor(firstTextColor)
		okV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14)
		okV.setLayoutParams(new LayoutParams(WRAP, WRAP))
		subroot.addView(okV)
		//
		root
	}
	override protected def onCreateListItem(permission: PermissionExtra) = {
		new DefaultPermissionDialogListItemHelper(permission)
	}

	/* LIST ITEM IMPL */
	class DefaultPermissionDialogListItemHelper(val permission: PermissionExtra)(implicit val context: Activity) extends PermissionDialogListItemHelper {
		override val colorCritical: Int = 0xFFFE8C79
		override val colorNormal: Int = 0xFF6787F0
		override protected def onCreateView(): View = {
			val dp10 = ATools.dp2pix(10)
			//
			val root = new LinearLayout(context)
			root.setPadding(0, 0, 0, dp10)
			root.setOrientation(LinearLayout.VERTICAL)
			root.setLayoutParams(new LayoutParams(MATCH, WRAP))
			//
			val s1Lay = new LinearLayout(context)
			s1Lay.setOrientation(LinearLayout.HORIZONTAL)
			s1Lay.setLayoutParams(new LayoutParams(MATCH, WRAP))
			root.addView(s1Lay)
			//
			iconV = new ImageView(context)
			iconV.setId(1)
			iconV.setPadding(dp10, 0, dp10, 0)
			iconV.setLayoutParams(new LayoutParams(WRAP, WRAP))
			s1Lay.addView(iconV)
			//
			titleV = new TextView(context)
			titleV.setId(2)
			titleV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18)
			titleV.setGravity(Gravity.CENTER_VERTICAL)
			titleV.setLayoutParams(new LayoutParams(WRAP, WRAP))
			s1Lay.addView(titleV)
			//
			subtitleV = new TextView(context)
			subtitleV.setId(3)
			subtitleV.setTextColor(secondTextColor)
			subtitleV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14)
			subtitleV.setLayoutParams(new LayoutParams(WRAP, WRAP))
			root.addView(subtitleV)
			//
			root
		}
	}
}







/* LISTENER */
trait PermissionDialogListener {
	def onOk(): Unit
	def onCancel(): Unit
	def onDontAsk(checked: Boolean): Unit
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
	import PackageManager._
	val name: String
	val rationaleResId: Int
	var typ: Int = Permission.OPTIONAL
	var asked = false
	lazy val info = context.getPackageManager.getPermissionInfo(name, GET_META_DATA)
	lazy val grpInfo = context.getPackageManager.getPermissionGroupInfo(info.group, GET_META_DATA)
	private[app] var requestId = 0
	private[app] var callbackPromise: Promise[Boolean] = null

	def isDangerous: Boolean = info.protectionLevel == PermissionInfo.PROTECTION_DANGEROUS
	def label = grpInfo.loadLabel(context.getPackageManager)
	def description = if (rationaleResId == 0) grpInfo.loadDescription(context.getPackageManager) else context.getString(rationaleResId)
	override def equals(o: Any): Boolean = o match {
		case pe: PermissionExtra => name == pe.name
		case _ => false
	}
}

case class PermissionDynamic(name: String, rationaleResId: Int = 0) extends PermissionExtra {typ = Permission.DYNAMIC}

case class PermissionOptional(name: String, rationaleResId: Int = 0) extends PermissionExtra

case class PermissionCritical(name: String, rationaleResId: Int = 0) extends PermissionExtra {typ = Permission.CRITICAL}
