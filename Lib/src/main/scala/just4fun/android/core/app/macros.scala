package just4fun.android.core.app

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

object Macros {
	def use[T: c.WeakTypeTag](c: Context)(m: c.Tree, context: c.Tree): c.Tree = {
		checkT[T](c, "Method bindSelf is for that purpose.")
		import c.universe._
		q"${symbolOf[Modules].companion}.unchecked_use[${weakTypeOf[T]}]($m, $context)"
	}
	def bindS[T: c.WeakTypeTag](c: Context)(m: c.Tree): c.Tree = {
		checkT[T](c, "Method bindSelf is for that purpose.")
		import c.universe._
		q"unchecked_bind[${weakTypeOf[T]}]($m)"
	}
	def bindA[T: c.WeakTypeTag](c: Context)(m: c.Tree, activity: c.Tree): c.Tree = {
		checkT[T](c, "Method bindSelf is for that purpose.")
		import c.universe._
		q"${symbolOf[Modules].companion}.unchecked_bind[${weakTypeOf[T]}]($m, $activity)"
	}
	def bindAC[T: c.WeakTypeTag](c: Context)(clas: c.Tree)(m: c.Tree, activity: c.Tree): c.Tree = {
		checkT[T](c, "Method bindSelf is for that purpose.")
		import c.universe._
		q"${symbolOf[Modules].companion}.unchecked_bind[${weakTypeOf[T]}]($clas)($m, $activity)"
	}
	def unbindS[T: c.WeakTypeTag](c: Context)(m: c.Tree): c.Tree = {
		checkT[T](c, "Method unbindSelf is for that purpose.", false)
		import c.universe._
		q"unchecked_unbind[${weakTypeOf[T]}]($m)"
	}
	def unbindA[T: c.WeakTypeTag](c: Context)(m: c.Tree, activity: c.Tree): c.Tree = {
		checkT[T](c, "Method unbindSelf is for that purpose.", false)
		import c.universe._
		q"${symbolOf[Modules].companion}.unchecked_unbind[${weakTypeOf[T]}]($m, $activity)"
	}
	def unbindAC[T: c.WeakTypeTag](c: Context)(clas: c.Tree)(m: c.Tree, activity: c.Tree): c.Tree = {
		checkT[T](c, "Method unbindSelf is for that purpose.", false)
		import c.universe._
		q"${symbolOf[Modules].companion}.unchecked_unbind[${weakTypeOf[T]}]($clas)($m, $activity)"
	}
	def bindSync[T: c.WeakTypeTag](c: Context)(m: c.Tree): c.Tree = {
		checkT[T](c, "Module can not be bound with sync to itself.")
		import c.universe._
		q"unchecked_bindSync[${weakTypeOf[T]}]($m)"
	}
//	def bindSelf[T: c.WeakTypeTag](c: Context): c.Tree = {
//		checkT[T](c, null)
//		import c.universe._
//		q"${symbolOf[Modules].companion}.unchecked_bindSelf[${weakTypeOf[T]}]"
//	}
	//	def bindSelfC[T: c.WeakTypeTag](c: Context)(clas: c.Tree)(m: c.Tree, context: c.Tree): c.Tree = {
	//		checkT[T](c, null)
	//		import c.universe._
	//		q"${symbolOf[Modules].companion}.unchecked_bindSelf[${weakTypeOf[T]}]($clas)($m, $context)"
	//	}
	private def checkT[T: c.WeakTypeTag](c: Context, selfMsg: String, chkConst: Boolean = true): Unit = {
		import c.universe._
		implicit val cxt = c
		val mt = symbolOf[T]
		val ot = c.internal.enclosingOwner.owner
		if (selfMsg != null && mt == ot) abort(selfMsg)
		else if (mt == symbolOf[Nothing]) abort(s"Type parameter [M <: Module] should be specified explicitly.")
		if (chkConst && !hasConstr[T](c)) abort(s"Module [${mt.name}] should have public zero-argument or one-boolean-argument constructor.")
	}
	private def hasConstr[T: c.WeakTypeTag](c: Context): Boolean = {
		c.symbolOf[T].toType.decls.exists { d =>
			d.isConstructor && d.isPublic && {
				val paramss = d.asMethod.paramLists
				paramss.isEmpty || paramss.head.isEmpty || (paramss match {
					case (param :: Nil) :: Nil => param.info =:= c.typeOf[Boolean]
					case _ => false
				})
//				val params = paramss.map(pms => pms.map(p => s"${p.fullName}:${p.info}").mkString(", ")).mkString(" : ")
//				prn(s"T=${c.symbolOf[T]} ;  D= $d;  Constr? ${d.isConstructor};  isPublic? ${d.isPublic};  nullable? ${d.asMethod.paramLists.isEmpty};  isEmpty? ${d.asMethod.paramLists.head.isEmpty} ;   paramss>> $params;  ok? $ok")(c)
			}
		}
	}


	def prn(msg: String)(implicit c: Context): Unit = {
		c.info(c.enclosingPosition, msg, true)
	}
	def abort(msg: String)(implicit c: Context): Unit = {
		c.abort(c.enclosingPosition, msg)
	}
}