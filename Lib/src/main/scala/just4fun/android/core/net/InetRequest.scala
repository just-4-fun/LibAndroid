package just4fun.android.core.net

import java.io.{ByteArrayOutputStream, EOFException, InputStream}
import java.net._
import javax.net.ssl.SSLException

import just4fun.android.core.app.MemoryState
import just4fun.android.core.app.MemoryState._
import just4fun.android.core.app.{AppSingleton, ModuleRequest}
import just4fun.utils.Utils._
import just4fun.utils.logger.Logger._

import scala.collection.mutable
import scala.util.{Failure, Success, Try}


object InetRequest {
	def apply[T](
	  connectTimeout: Int = 0,
	  readTimeout: Int = 0,
	  followRedirects: Boolean = true,
	  delay: Long = 0L,
	  attempts: Int = 1,
	  duration: Long = 0L,
	  errorHandler: InetErrorHandler = null,
	  authenticator: InetAuthenticator = null,
	  url: String = null,
	  method: String = null,
	  headers: collection.Map[String, Any] = null
	  )(implicit converter: StreamConverter[T] = StreamConverter.stream2any,
	  channel: NetChannel = null): InetRequest[T] = {
		new InetRequest()(converter, channel)
		  .connectTimeout(connectTimeout)
		  .readTimeout(readTimeout)
		  .followRedirects(followRedirects)
		  .delay(delay)
		  .attempts(attempts)
		  .duration(duration)
		  .errorHandler(errorHandler)
		  .authenticator(authenticator)
		  .url(url)
		  .method(method)
		  .headers(headers)
	}
}


class InetRequest[T](implicit val converter: StreamConverter[T], var channel: NetChannel = null) {
	import InetQuery._
	private[this] var _connectTimeout: Int = 0
	private[this] var _readTimeout: Int = 0
	private[this] var _followRedirects: Boolean = true
	private[this] var _delay: Long = 0L
	private[this] var _attempts: Int = 1
	private[this] var _duration: Long = 0L
	private[this] var _errorHandler: InetErrorHandler = null
	private[this] var _authenticator: InetAuthenticator = null
	private[this] var _url: String = null
	private[this] var _method: String = null
	private[this] var _headers: mutable.Map[String, Any] = null
	// +
	private[this] var _urlArgs: mutable.Map[String, Any] = null
	private[this] var _bodyArgs: mutable.Map[String, Any] = null
	private[this] var _payload: Array[Byte] = null

	def apply()(implicit channel: NetChannel): InetRequest[T] = {
		copy(converter, channel)
	}
	def apply[V](implicit converter: StreamConverter[V], channel: NetChannel = null): InetRequest[V] = {
		copy(converter, channel)
	}
	private[this] def copy[V](converter: StreamConverter[V], channel: NetChannel): InetRequest[V] = {
		val p = new InetRequest[V]()(converter, channel)
		if (channel == null && this.channel != null) p.channel = this.channel
		p.connectTimeout(connectTimeout)
		  .readTimeout(readTimeout)
		  .followRedirects(followRedirects)
		  .delay(delay)
		  .attempts(attempts)
		  .duration(duration)
		  .errorHandler(errorHandler)
		  .authenticator(authenticator)
		  .url(url)
		  .method(method)
		  .headers(headers)
	}

	def connectTimeout: Int = _connectTimeout
	def readTimeout: Int = _readTimeout
	def followRedirects: Boolean = _followRedirects
	def delay: Long = _delay
	def attempts: Int = _attempts
	def duration: Long = _duration
	def errorHandler: InetErrorHandler = _errorHandler
	def authenticator: InetAuthenticator = _authenticator
	def url: String = _url
	def method: String = _method
	def headers: mutable.Map[String, Any] = _headers
	def urlArgs: mutable.Map[String, Any] = _urlArgs
	def bodyArgs: mutable.Map[String, Any] = _bodyArgs
	def payload: Array[Byte] = _payload

	def connectTimeout(v: Int): this.type = { _connectTimeout = v; this }
	def readTimeout(v: Int): this.type = { _readTimeout = v; this }
	def followRedirects(v: Boolean): this.type = { _followRedirects = v; this }
	def delay(v: Long): this.type = { _delay = v; this }
	def attempts(v: Int): this.type = { _attempts = v; this }
	def duration(v: Long): this.type = { _duration = v; this }
	def errorHandler(v: InetErrorHandler): this.type = { _errorHandler = v; this }
	def authenticator(v: InetAuthenticator): this.type = { _authenticator = v; this }
	def url(v: String): this.type = { _url = v; this }
	def method(v: String): this.type = { _method = v; this }
	def headers(v: collection.Map[String, Any]): this.type = {
		if (v != null) {
			if (_headers == null) _headers = mutable.Map.empty
			_headers ++= v
		}
		this
	}
	def urlArgs(v: collection.Map[String, Any]): this.type = {
		if (v != null) {
			if (_urlArgs == null) _urlArgs = mutable.Map.empty
			_urlArgs ++= v
		}
		this
	}
	def bodyArgs(v: collection.Map[String, Any]): this.type = {
		if (v != null) {
			if (_bodyArgs == null) _bodyArgs = mutable.Map.empty
			_bodyArgs ++= v
		}
		this
	}

	def hasHeader(v: String): Boolean = {
		if (_headers == null) false else _headers.contains(v)
	}
	def addHeader(name: String, v: Any): this.type = {
		if (_headers == null) _headers = mutable.Map.empty
		_headers += (name -> v)
		this
	}
	def addUrlArg(name: String, nonEncodedV: Any): this.type = {
		if (_urlArgs == null) _urlArgs = mutable.Map.empty
		_urlArgs += (name -> nonEncodedV)
		this
	}
	def addBodyArg(name: String, nonEncodedV: Any): this.type = {
		if (_bodyArgs == null) _bodyArgs = mutable.Map.empty
		_bodyArgs += (name -> nonEncodedV)
		this
	}
	def payload(v: Array[Byte]): this.type = { _payload = v; this }
	def payload(v: String): this.type = { _payload = v.getBytes("UTF-8"); this }
	def channel(v: NetChannel): this.type = { channel = v; this }
	def hContentType(v: String): this.type = {
		addHeader(HeaderContentType, v)
		this
	}
	def hAcceptCharset(v: String): this.type = {
		addHeader(HeaderCharset, v)
		this
	}
}





/* QUERY Object */
private[net] object InetQuery extends AppSingleton {
	private[this] var errorHandler: InetErrorHandler = null
	private[this] var idCounter = 0
	// methods OPTIONS, GET, HEAD, POST, PUT, DELETE, TRACE, PATCH
	val ContentTypeForm = "application/x-www-form-urlencoded"
	val ContentTypeJson = "application/json; charset=utf-8"
	//
	val HttpCodeOFFLINE = 1
	val HttpCodeCANCELED = 2
	// Headers
	val HeaderContentType = "Content-Type"
	val HeaderCharset = "Accept-Charset"

	private[net] def nextId: Int = synchronized {
		idCounter += 1
		idCounter
	}
	def defaultErrorHandler: InetErrorHandler = {
		if (errorHandler == null) errorHandler = new InetErrorHandler {}
		errorHandler
	}
	override protected[this] def onTrimMemory(e: MemoryState): Unit = e match {
		case LOW | CRITICAL | DROP  => errorHandler = null
		case _ =>
	}
}



/* QUERY Class */
class InetQuery[T](val request: InetRequest[T])(implicit module: InetModule) extends ModuleRequest[T] {
	import InetQuery._
	import request._
	id = nextId
	private[net] lazy val execTime = now + delay
	private[this] var startTime = 0L
	private[this] var attempt = 0
	private[this] var fullUrl: String = _
	private[this] var conn: HttpURLConnection = _
	private[this] var httpCode = 0
	private[this] var httpMessage: String = _

	override def execute(): Try[T] = {
		startTime = now
		var result: Try[T] = null
		do result = tryExec() while (result.isFailure && needRetry(result))
		result
	}
	private[this] def tryExec(): Try[T] = {
		try {
			prepare()
			logV(startMessage)
			val result = query()
			logV(resultMessage(result))
			Success(result)
		}
		catch {case ex: Throwable => onError(ex); logW(errorMessage(ex)); Failure(ex)}
		finally reset()
	}
	private[this] def prepare(): Unit = {
		attempt += 1
		//
		if (errorHandler == null) errorHandler(defaultErrorHandler)
		if (channel == null) channel(NetChannel.default)
		//
		if (isDone) throw new InetRequestCancelled(s"${classOf[InetQuery[_]].getSimpleName} object can be used only once.")
		if (channel.isOffline) throw new OfflineException
		// AUTH
		if (authenticator != null) authenticator.authenticate(request)
		// URL
		fullUrl = url
		if (nonEmpty(urlArgs)) fullUrl += "?" + argsToString(urlArgs)
		val urlObject: URL = new URL(fullUrl)
		// CONNECTION
		conn = channel.httpConnection(urlObject, checkOnline = false).get
		conn.setConnectTimeout(connectTimeout)
		conn.setReadTimeout(readTimeout)
		conn.setInstanceFollowRedirects(followRedirects)
		// HEADERS
		//		conn.setRequestProperty("Content-Type", contentType)
		//		conn.setRequestProperty("Accept-Charset", charset)
		if (headers != null) headers.foreach { case (k, v) => conn.addRequestProperty(k, v.toString) }
		// PAYLOAD
		if (nonEmpty(bodyArgs)) payload(argsToString(bodyArgs))
		if (payload != null) {
			conn.setDoOutput(true)
			conn.setFixedLengthStreamingMode(payload.length)
			conn.getOutputStream.write(payload)
		}
		// METHOD
		val method = if (request.method != null) request.method else if (conn.getDoOutput) "POST" else "GET"
		conn.setRequestMethod(method)
	}
	private[this] def argsToString(args: mutable.Map[String, Any]): String = {
		val body: StringBuilder = new StringBuilder
		args.foreach { case (k, v) =>
			body ++= s"${if (body.nonEmpty) "&" else ""}$k=${URLEncoder.encode(String.valueOf(v), "UTF-8")}"
		}
		body.toString()
	}
	private[this] def query(): T = {
		conn.connect()
		httpCode = conn.getResponseCode
		if (httpCode >= 200 && httpCode < 300) converter(conn.getInputStream)
		else throw new InetRequestException(httpCode, errorMessage)
	}
	private[this] def onError(ex: Throwable): Unit = ex match {
		case ex: InetRequestException => httpCode = ex.code; httpMessage = ex.reason
		case _ =>
	}
	private[this] def errorMessage: String = {
		try {
			val msg = conn.getResponseMessage
			val stream = conn.getErrorStream
			val info = if (stream != null) StreamConverter.streamToString(stream) else ""
			if (msg == null) info else s"$msg\n$info"
		} catch loggedE("?")
	}
	private[this] def needRetry(result: Try[_]): Boolean = {
		val Failure(ex) = result
		val retry = attempt < attempts && (duration == 0 || now - startTime < duration) && errorHandler.needRetry(request, ex, httpCode, httpMessage)
		if (retry) try Thread.sleep(500) catch logMutE
		retry
	}
	private[this] def wasteStream(in: InputStream): Unit = {
		val buf: Array[Byte] = new Array[Byte](1024)
		while (in.read(buf) != -1) {}
	}
	private[this] def reset(): Unit = {
		if (conn != null) {
			Try {wasteStream(conn.getInputStream)} // TODO ?
			Try {conn.getInputStream.close()}
			Try {conn.getOutputStream.close()}
			Try {conn.disconnect()}
			conn = null
		}
		httpCode = 0
		httpMessage = null
	}
	private[this] def startMessage = {
		s"STARTED...  attempt= $attempt;  method= $method;   url= $fullUrl;   payload len= ${if (payload == null) 0 else payload.length}"
	}
	private[this] def errorMessage(e: Throwable) = {
		s"FAILED  ::  httpCode= $httpCode;  duration= ${now - startTime};  err= ${e.getClass.getSimpleName}:: ${e.getMessage};   httpMessage= $httpMessage"
	}
	private[this] def resultMessage(result: Any) = {
		s"OK  ::  ${
			result match {
				case r1: String => r1.take(200) + (if (r1.length > 200) "..." else "")
				case r2: Array[_] => r2.length + " length"
				case r3 => r3.getClass.getSimpleName + ".class"
			}
		}"
	}
}







/* EXCEPTIONS */
class InetRequestException(val code: Int, val reason: String = "") extends Exception

class InetRequestCancelled(override val reason: String = "") extends InetRequestException(InetQuery.HttpCodeCANCELED, reason)

class OfflineException(override val reason: String = "") extends InetRequestException(InetQuery.HttpCodeOFFLINE, reason)





/* ERROR HANDLER */
trait InetErrorHandler {
	def needRetry(request: InetRequest[_], exception: Throwable, httpCode: Int, httpMessage: String): Boolean = exception match {
		case _: NoRouteToHostException | _: UnknownHostException |
		     _: SocketTimeoutException | _: SSLException | _: EOFException => true
		case _ => false
	}
}





/* INET AUTHENTICATOR */
trait InetAuthenticator {
	def authenticate(request: InetRequest[_]): Unit
}






/* INET CONVERTER */
object StreamConverter {
	implicit val stream2any = new StreamConverter[Any] {
		override def apply(stream: InputStream): Any = streamToBytes(stream)
	}
	private[net] def streamToString(in: InputStream): String = {
		new String(streamToBytes(in), "UTF-8")
	}
	private[net] def streamToBytes(in: InputStream): Array[Byte] = {
		val buffer = new ByteArrayOutputStream(1024)
		val bytes = new Array[Byte](1024)
		var n = in.read(bytes, 0, bytes.length)
		while (n != -1) {
			buffer.write(bytes, 0, n)
			n = in.read(bytes, 0, bytes.length)
		}
		buffer.flush()
		buffer.toByteArray
	}

}
trait StreamConverter[T] extends Function[InputStream, T] {
	//	override def apply(stream: InputStream): T
}
