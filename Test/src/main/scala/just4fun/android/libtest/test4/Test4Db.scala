package just4fun.android.libtest.test4

import android.os.Bundle
import just4fun.android.core.app.{Modules, TwixModule, TwixActivity}
import just4fun.android.core.async.{ThreadPoolContext, UiThreadContext}
import just4fun.android.core.sqlite.{DbModule, DbTableModule, DbSchema, DbObject}
import just4fun.android.core.vars.Prefs
import just4fun.android.libtest.{TestModule, R}
import just4fun.core.schemify.Schemify
import just4fun.utils.logger.Logger
import Logger._
import just4fun.utils.schema.ReachSchema

import scala.util.{Failure, Success}

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
	var time = 0L
	Modules.use[TestTable].select().onComplete {
		case Success(list) => logV(s"<<< SELECTED ALL  ${list.length} >>>>>>>>>>>>> ")
		case Failure(e) => logE(e)
	}
	val testTab = bind[TestTable]

	def resetTime() = time = System.currentTimeMillis()
	def timeDiff: Long = { val t = System.currentTimeMillis() - time; resetTime(); t }

	override protected[this] def onActivatingFinish(firstTime: Boolean): Unit = {
		logV(s"<<< VERSION ${Prefs[Int](s"${testTab.schema.schemaName} version")}")
		resetTime()
		testTab.save(new TestObject(System.currentTimeMillis(), System.currentTimeMillis(), "ok"))
		  .thanTaskSeq { obj =>
			logV(s"<<< SAVED (INS) ${testTab.schema.valuesToJsonMap(obj)}>>>>>>>>>>>>> $timeDiff")
			val obj2 = testTab.schema.copy(obj)
			obj2.name = "oops.."
			testTab.save(obj2, obj)
		}(ThreadPoolContext)
		  .thanTaskSeq { obj =>
			logV(s"<<< SAVED (UPD) ${testTab.schema.valuesToJsonMap(obj)}>>>>>>>>>>>>> $timeDiff")
			obj.name = "bla"
			testTab.insert(obj)
		}
		  .thanTaskSeq { obj =>
			logV(s"<<< INSERTED ${testTab.schema.valuesToJsonMap(obj)}>>>>>>>>>>>>> $timeDiff")
			testTab.select()
		}(UiThreadContext)
		  .thanTask { list =>
			logV(s"<<< SELECT ALL size= ${list.size} ${if (list.nonEmpty) testTab.schema.valuesToJsonMap(list.head) else ""}>>>>>>>>>>>>> $timeDiff")
			list.head
		}
		  .thanTaskSeq { obj =>
			logV(s"<<< DELETE obj= ${if (obj == null) "null" else testTab.schema.valuesToJsonMap(obj)}>>>>>>>>>>>>> $timeDiff")
			testTab.delete(obj)
		}
		  .onComplete {
			case Success(res) => logV(s"<<< DELETED $res >>>>>>>>>>>>> $timeDiff")
				logV(s"<<< PROPS ${testTab.schema.props.mkString(", ")}")
			case Failure(e) => logE(e)
		}
		//		//
		//		testTab.save(new TestObject(System.currentTimeMillis(), System.currentTimeMillis(), "ok")).onComplete {
		//			case Success(v) => logV(s"<<<<<<<<<<<<< SAVED3 ${testTab.schema.valuesToJsonMap(v)}>>>>>>>>>>>>>")
		//				val obj2 = testTab.schema.copy(v)
		//				obj2.name = "oops.."
		//				//				testTab.save(v, columns = List(testTab.schema.name)).onComplete{
		//				testTab.save(obj2, v).onComplete {
		//					case Success(v) => logV(s"<<<<<<<<<<<<< SAVED4 ${testTab.schema.valuesToJsonMap(v)}>>>>>>>>>>>>>")
		//						testTab.select(s"_id=${v._id}").onComplete {
		//							case Success(list) => logV(s"<<<<<<<<<<<<< SELECT3 size= ${list.size} ${if (list.nonEmpty) testTab.schema.valuesToJsonMap(list.head) else ""}>>>>>>>>>>>>>")
		//							case Failure(e) => logE(e, "<<<<<<<<<<<<< SELECT3 FAILED >>>>>>>>>>>>>")
		//						}
		//					case Failure(e) => logE(e, "<<<<<<<<<<<<< FAILED4 >>>>>>>>>>>>>")
		//				}
		//			case Failure(e) => logE(e, "<<<<<<<<<<<<< FAILED3 >>>>>>>>>>>>>")
		//		}
		//		//
		//		testTab.select(s"_id=0").onComplete {
		//			case Success(list) => logV(s"<<<<<<<<<<<<< SELECT size= ${list.size} ${if (list.nonEmpty) testTab.schema.valuesToJsonMap(list.head) else ""}>>>>>>>>>>>>>")
		//			case Failure(e) => logE(e, "<<<<<<<<<<<<< SELECT FAILED >>>>>>>>>>>>>")
		//		}
	}
}


class TestTable extends DbTableModule[DbModule, TestObject] {
	implicit val schema = TestSchema
	import schema._
	override protected[this] def indexes = Set(DbTableIndex(name))
	override protected[this] def upgrades = List(DbTableUpgrade(3, s"UPDATE ${schemaName} SET x3 = NULL"))
}

@Schemify object TestSchema extends DbSchema[TestObject] with BlaSchema[TestObject] {
	val x = PROP[Long]
	val y = PROP[Double]
	val name = PROP[String]
	val isOk = STUB
}

class TestObject(var x: Long, var y: Double, var name: String) extends DbObject with BlaObj {
	var isOk = true
}


trait BlaObj {
	var p0 = 0
}

trait BlaSchema[T <: BlaObj] extends just4fun.core.schemify.SchemaType[T] {
	val p0 = PROP[Int]
}

