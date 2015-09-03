package just4fun.android.core.vars

import scala.language.experimental.macros
import scala.annotation.StaticAnnotation
import scala.concurrent.Future
import scala.reflect.macros.blackbox.Context

class temp extends StaticAnnotation {
	def macroTransform(annottees: Any*): Unit = macro MacroAnnots.genTempVar
}
class pref extends StaticAnnotation {
	def macroTransform(annottees: Any*): Unit = macro MacroAnnots.genPrefVar
}

object MacroDefs {
	//	def genDbVar[T: c.WeakTypeTag](c: Context)(query: c.Tree): c.Tree = {
	//		import c.universe._
	//		val constr = TermName(classOf[DbVar[_]].getSimpleName)
	//		genVar(c)(constr, query, c.weakTypeOf[T])
	//	}
		def genFileVar[T: c.WeakTypeTag](c: Context)(filename: c.Tree): c.Tree = {
			import c.universe._
			val constr = TermName(classOf[FileVar[_]].getSimpleName)
			genVar(c)(constr, filename, c.weakTypeOf[T])
		}
	def genTempVar[T: c.WeakTypeTag](c: Context)(value: c.Tree): c.Tree = {
		import c.universe._
		val constr = TermName(classOf[TempVar[_]].getSimpleName)
		genVar(c)(constr, value, c.weakTypeOf[T])
	}
	def genPrefVar[T: c.WeakTypeTag](c: Context): c.Tree = {
		import c.universe._
		val noValue = q"None" //c.parse(typeOf[None.type].termSymbol.fullName)
		val constr = TermName(classOf[PrefVar[_]].getSimpleName)
		genVar(c)(constr, noValue, c.weakTypeOf[T])
	}
	def genVar(c: Context)(constr: c.TermName, param: c.Tree, typ: c.Type): c.Tree = {
		import c.universe._
		def print(text: String) = c.info(c.enclosingPosition, text, false)
		def abort(text: String) = c.abort(c.enclosingPosition, text)
		val field = c.internal.enclosingOwner
		val idOwner = field.owner.fullName
		val idName = field.name.toString.trim
		if (!field.isPrivateThis || idName.startsWith("<")) abort(s"Expression should be assigned to val.")
		val tree = q"$constr[$typ]($idName, $param)"
//		print(s"Tree:: ${tree}")
//		print(s"Tree:: ${tree};  Owner= ${field.owner.asClass.baseClasses.map{cls=>s"${cls}[${cls.asClass.typeParams.map(p=>s"${p.asType.info.finalResultType}").mkString(", ")}]"}}")
		tree
	}
}

class MacroAnnots(val c: Context) {
	import c.universe._

	def genPrefVar(annottees: c.Tree*): c.Tree = annottees.head match {
		case ValDef(mods, nm, tpt, v) =>
			val constr = TermName(classOf[PrefVar[_]].getSimpleName)
			val noValue = q"None" //c.parse(typeOf[NoValue.type].termSymbol.fullName)
			genSyncVar(constr, noValue, mods, nm, tpt, v)
		case _ => abort(s"Only var can be annotated by @${classOf[pref].getSimpleName} ")
	}
	def genTempVar(annottees: c.Tree*): c.Tree = annottees.head match {
		case ValDef(mods, nm, tpt, v) =>
			val constr = TermName(classOf[TempVar[_]].getSimpleName)
			genSyncVar(constr, null, mods, nm, tpt, v)
		case _ => abort(s"Only var can be annotated by @${classOf[temp].getSimpleName} ")
	}
	private def genSyncVar(constr: TermName, default: c.Tree, mods: Modifiers, nm: TermName, tpt: Tree, v: Tree): c.Tree = {
		val varNm = TermName(s"_$nm")
		val setterNm = TermName(s"${nm}_$$eq")
		val idName = nm.toString
		val typ = tpt match {
			case tq"" => TypeName(c.typecheck(v).tpe.finalResultType.typeSymbol.name.toString)
			case _ => TypeName(tpt.toString())
		}
		val value = v match {
			case q"null" | q"" => if (default != null) default else q"null.asInstanceOf[$typ]"
			case _ => v
		}
		val accessors = q"def $nm = $varNm.apply(); def $setterNm(v: $typ) = $varNm.update(v);"
		val tree = q"private[this] val $varNm = $constr[$typ]($idName, $value); ..$accessors"
		print(s"${tree}")
		q"..${tree :: Nil}"
	}
	private def print(text: String) = c.info(c.enclosingPosition, text, false)
	private def abort(text: String) = c.abort(c.enclosingPosition, text)
	//	def futureArg(tpt: c.Tree): c.Tree = tpt match {
	//		case AppliedTypeTree(tp, args) => //print(s"ATT: $tp; args: $args")
	//			val tpe = c.typecheck(q"val v: $tpt").symbol.info.finalResultType
	//			if (tpe <:< typeOf[Future[_]]) args.head else null
	//		case _ => null
	//	}
}
