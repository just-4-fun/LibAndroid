package just4fun.android.core.inet

import just4fun.android.core.app.{ModuleRequest, Module}
import just4fun.android.core.async.OwnThreadContextHolder
import just4fun.android.core.inet.InetModule.{Method, ContentType, HttpCode}
import just4fun.utils.TryNClose
import just4fun.utils.Utils._
import just4fun.utils.logger.Logger._

import scala.collection.mutable.Map
import org.apache.http.NoHttpResponseException
import java.io._
import java.net.{HttpURLConnection, NoRouteToHostException, SocketTimeoutException, URL, URLConnection, URLEncoder, UnknownHostException}
import javax.net.ssl.SSLException
import scala.collection.mutable.Map
import scala.util.{Failure, Success, Try}

object InetModule {
	val UTF8 = "UTF-8"

	object Method extends Enumeration {val GET, POST, PUT, DELETE = Value}

	object ContentType {
		val Form = "application/x-www-form-urlencoded"
		val Json = "application/json; charset=utf-8"
	}

	object HttpCode {
		val OFFLINE = 1
		val CANCELED = 2
	}

	private[inet] def streamToString(in: InputStream): Try[String] = {
		TryNClose(new BufferedReader(new InputStreamReader(in, UTF8))) { bufRd =>
			val res = new StringBuilder
			Stream.continually(bufRd.readLine()).takeWhile(_ != null).foreach(res.append)
			res.toString()
		}
	}
	private[inet] def streamToBytes(in: InputStream): Try[Array[Byte]] = {
		TryNClose(new BufferedInputStream(in)) { bufIn =>
			TryNClose(new ByteArrayOutputStream(1024)) { out =>
				val buf: Array[Byte] = new Array[Byte](1024)
				Stream.continually(bufIn.read(buf)).takeWhile(_ != -1).foreach(out.write(buf, 0, _))
				out.toByteArray
			}
		}.flatten
	}

}




/* INET MODULE */
class InetModule extends Module with OwnThreadContextHolder {
	standbyMode = true
	//todo persist and track requests via db. No bindSelf / restorable
	// todo event onUnbound instead onBeforeDeact...

	override protected[this] def onRequestComplete(): Unit = {

	}

}





/* INET REQUEST */
class InetRequest[T](implicit module: Module) extends ModuleRequest[T] {
}


/*
case class InetRequest[T](opts: InetOptions, consumer: InputStream => Try[T], canceled: () => Boolean) {
	import InetModule._
	private[this] val startTime = now
	private[this] var attempt = 0
	private[this] var httpCode = 0
	private[this] var httpMessage: String = _
	private[this] var conn: HttpURLConnection = _

	def execute(): Try[T] = {
		var result: Try[T] = null
		do result = tryExec() while (result.isFailure && needRetry(result))
		result
	}

	private[this] def tryExec(): Try[T] = {
		try {
			if (canceled()) throw InetRequestCancelled
			if (!InetService.online) throw OfflineException
			attempt += 1
			prepare()
			logV(startMessage)
			request() match {
				case result@Success(value) => logV(resultMessage(value)); result
				case Failure(ex) => throw ex
			}
		}
		catch {case ex: Throwable => onError(ex); logW(errorMessage(ex)); Failure(ex)}
		finally finalise()
	}
	private[this] def prepare() {
		if (opts.authenticator != null) opts.authenticator.onPrepareRequest(opts)
		// URL
		opts.fullUrl = opts.url
		if (nonEmpty(opts.urlArgs)) opts.fullUrl += "?" + argsToString(opts.urlArgs)
		val urlObject: URL = new URL(opts.fullUrl)
		// CONNECTION
		conn = urlObject.openConnection.asInstanceOf[HttpURLConnection]
		conn.setRequestMethod(opts.method.toString)
		conn.setConnectTimeout(opts.connectTimeoutMs)
		conn.setReadTimeout(opts.readTimeoutMs)
		conn.setInstanceFollowRedirects(opts.followRedirects)
		conn.setRequestProperty("Accept-Charset", UTF8)
		conn.setRequestProperty("Content-Type", opts.contentType)
		//conn.setRequestProperty("User-Agent", "?")
		//if (opts.authenticator != null) conn.setRequestProperty(opts.authenticator.getHeaderName(), opts.authenticator.getHeaderValue());
		setHeaders(conn)
		// PAYLOAD
		if (nonEmpty(opts.payloadArgs)) opts.payload(argsToString(opts.payloadArgs))
		if (opts.method != Method.GET && opts.payload != null) {
			conn.setDoOutput(true)
			conn.setFixedLengthStreamingMode(opts.payload.length)
			conn.getOutputStream.write(opts.payload)
		}
	}
	private[this] def argsToString(args: Map[String, Any]): String = {
		val body: StringBuilder = new StringBuilder
		args.foreach { case (k, v) =>
			body ++= s"${if (body.length > 0) "&" else ""}$k=${URLEncoder.encode(String.valueOf(v), UTF8)}"
		}
		body.toString()
	}
	private[this] def setHeaders(conn: URLConnection) = if (opts.headers != null && opts.headers.nonEmpty) {
		opts.headers.foreach { case (k, v) => conn.addRequestProperty(k, v.toString) }
	}
	private[this] def request(): Try[T] = {
		conn.connect()
		httpCode = conn.getResponseCode
		if (httpCode >= 200 && httpCode < 300) consumer(conn.getInputStream)
		else Failure(new IOException)
	}
	private[this] def wasteStream(in: InputStream) {
		TryNClose(in) { in =>
			val buf: Array[Byte] = new Array[Byte](1024)
			while (in.read(buf) != -1) {}
		}
	}
	private[this] def onError(ex: Throwable) = ex match {
		case ex: IOException => if (conn != null) try {
			httpMessage = conn.getResponseMessage
			val errStream = conn.getErrorStream
			val errInfo = if (errStream != null) streamToString(errStream).getOrElse("") else "ErrorStream = null"
			httpMessage = (if (httpMessage == null) "" else httpMessage + ";  ") + errInfo
		} catch loggedE
		case ex: InetRequestException => httpCode = ex.code
		case _ =>
	}
	private[this] def finalise() = if (conn != null) {
		//TryLog { wasteStream(conn.getInputStream)} // TODO ?
		Try {conn.getInputStream.close()}
		Try {conn.getOutputStream.close()}
		Try {conn.disconnect()}
		httpCode = 0
		httpMessage = null
	}
	private[this] def startMessage = s"STARTED...  attempt= $attempt;  method= ${opts.method};   url= ${opts.fullUrl};   payload len= ${if (opts.payload == null) 0 else opts.payload.length}"
	private[this] def errorMessage(e: Throwable) = s"FAILED  ::  httpCode= $httpCode;  Duration= ${now - startTime};  err= ${e.getClass.getSimpleName}:: ${e.getMessage};   httpMessage= $httpMessage"
	private[this] def resultMessage(result: Any) = s"OK  ::  ${
		result match {
			case r1: String => r1.take(200) + (if (r1.length > 200) "..." else "")
			case r2: Array[_] => r2.length + " length"
			case r3 => r3.getClass.getSimpleName + ".class"
		}
	}"
	private[this] def needRetry(result: Try[_]): Boolean = {
		val Failure(ex) = result
		var retry: Option[Boolean] = None
		//
		if (attempt > opts.maxAttempts || (opts.maxDuration > 0 && now - startTime > opts.maxDuration))
			retry = Some(false)
		else {
			if (opts.authenticator != null) retry = opts.authenticator.checkRetry(httpCode)
			if (retry.isEmpty && opts.errorHandler != null)
				retry = opts.errorHandler.handleErrorForRetry(httpCode, httpMessage, ex, opts)
			if (retry.isEmpty) retry = ex match {
				case _: NoHttpResponseException | _: NoRouteToHostException | _: UnknownHostException |
				     _: SocketTimeoutException | _: SSLException | _: EOFException => Some(true)
				case _ => Some(false)
			}
		}
		val value = retry.getOrElse(false)
		if (value) Try {Thread.sleep(500)}
		value
	}
}
*/






/* OPTIONS */
case class InetOptions(url: String, var method: Method.Value = Method.GET, var contentType: String = ContentType.Form, var connectTimeoutMs: Int = 20000, var readTimeoutMs: Int = 65000, var maxAttempts: Int = 1, var maxDuration: Long = 0L, var followRedirects: Boolean = true) {
	// request params
	var fullUrl: String = _
	var headers: Map[String, Any] = _
	var urlArgs: Map[String, Any] = _
	var payloadArgs: Map[String, Any] = _
	var payload: Array[Byte] = _
	// optional params
	var authenticator: InetAuthenticator = _
	var errorHandler: ErrorHandler = _

	def payload(v: Array[Byte]): InetOptions = { payload = v; this }
	def payload(v: String): InetOptions = { payload = v.getBytes; this }
	def addPayloadArg(name: String, nonEncodedV: Any): InetOptions = {
		if (payloadArgs == null) payloadArgs = Map.empty
		payloadArgs += (name -> nonEncodedV)
		this
	}
	def urlArgs(v: TraversableOnce[(String, Any)]): InetOptions = {
		if (urlArgs == null) urlArgs = Map.empty
		urlArgs ++= v
		this
	}
	def addUrlArg(name: String, nonEncodedV: Any): InetOptions = {
		if (urlArgs == null) urlArgs = Map.empty
		urlArgs += (name -> nonEncodedV)
		this
	}
	def headers(v: TraversableOnce[(String, Any)]): InetOptions = {
		if (headers == null) headers = Map.empty
		headers ++= v
		this
	}
	def addHeader(name: String, v: Any): InetOptions = {
		if (headers == null) headers = Map.empty
		headers += (name -> v)
		this
	}
	def authenticator(v: InetAuthenticator): InetOptions = { authenticator = v; this }
	def errorHandler(v: ErrorHandler): InetOptions = { errorHandler = v; this }
	override def clone: InetOptions = {
		val cpy = copy()
		cpy.headers = headers
		cpy.urlArgs = urlArgs
		cpy.payloadArgs = payloadArgs
		cpy.payload = payload
		cpy.authenticator = authenticator
		cpy.errorHandler = errorHandler
		//...
		cpy
	}
}







/* EXCEPTIONS */
case class InetRequestException(code: Int) extends Exception

object InetRequestCancelled extends InetRequestException(HttpCode.CANCELED)

object OfflineException extends InetRequestException(HttpCode.OFFLINE)





/* ERROR HANDLER   */
trait ErrorHandler {
	def handleErrorForRetry(httpCode: Int, httpMessage: String, exception: Throwable, opts: InetOptions): Option[Boolean]
}





/* INET AUTHENTICATOR   */
trait InetAuthenticator {
	val retryCode: Int = 401
	var scope: String = _
	def getToken: String
	def requestToken: String
	def onPrepareRequest(opts: InetOptions)
	def checkRetry(httpCode: Int): Option[Boolean] = {
		if (httpCode != retryCode) None
		else Some(requestToken != null)
	}
}
