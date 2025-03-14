package com.softwaremill.sttp

import java.io._
import java.net.{Authenticator, HttpURLConnection, PasswordAuthentication, URL, URLConnection}
import java.nio.channels.Channels
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.util.concurrent.ThreadLocalRandom
import java.util.zip.{GZIPInputStream, InflaterInputStream}

import com.github.ghik.silencer.silent
import com.softwaremill.sttp.internal._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.io.Source

class HttpURLConnectionBackend private (
    opts: SttpBackendOptions,
    customizeConnection: HttpURLConnection => Unit,
    createURL: String => URL,
    openConnection: (URL, Option[java.net.Proxy]) => URLConnection
) extends SttpBackend[Id, Nothing] {

  override def send[T](r: Request[T, Nothing]): Response[T] = {
    val c = openConnection(r.uri)
    c.setRequestMethod(r.method.m)
    r.headers.foreach { case (k, v) => c.setRequestProperty(k, v) }
    c.setDoInput(true)
    c.setReadTimeout(timeout(r.options.readTimeout))
    c.setConnectTimeout(timeout(opts.connectionTimeout))

    // redirects are handled by FollowRedirectsBackend
    c.setInstanceFollowRedirects(false)

    customizeConnection(c)

    if (r.body != NoBody) {
      c.setDoOutput(true)
      // we need to take care to:
      // (1) only call getOutputStream after the headers are set
      // (2) call it ony once
      writeBody(r.body, c).foreach { os =>
        os.flush()
        os.close()
      }
    }

    try {
      val is = c.getInputStream
      readResponse(c, is, r.response, r.options.parseResponseIf)
    } catch {
      case e: CharacterCodingException     => throw e
      case e: UnsupportedEncodingException => throw e
      case _: IOException if c.getResponseCode != -1 =>
        readResponse(c, c.getErrorStream, r.response, r.options.parseResponseIf)
    }
  }

  override val responseMonad: MonadError[Id] = IdMonad

  private def openConnection(uri: Uri): HttpURLConnection = {
    val url = createURL(uri.toString)
    val conn = opts.proxy match {
      case Some(p) if !p.ignoreProxy(uri.host) =>
        p.auth.foreach { proxyAuth =>
          Authenticator.setDefault(new Authenticator() {
            override def getPasswordAuthentication: PasswordAuthentication = {
              new PasswordAuthentication(proxyAuth.username, proxyAuth.password.toCharArray)
            }
          })
        }

        openConnection(url, Some(p.asJavaProxy))
      case _ => openConnection(url, None)
    }

    conn.asInstanceOf[HttpURLConnection]
  }

  private def writeBody(body: RequestBody[Nothing], c: HttpURLConnection): Option[OutputStream] = {
    body match {
      case NoBody =>
        // skip
        None

      case b: BasicRequestBody =>
        val os = c.getOutputStream
        writeBasicBody(b, os)
        Some(os)

      case StreamBody(_) =>
        // we have an instance of nothing - everything's possible!
        None

      case mp: MultipartBody =>
        setMultipartBody(mp, c)
    }
  }

  private def timeout(t: Duration): Int =
    if (t.isFinite) t.toMillis.toInt
    else 0

  @silent("discarded")
  private def writeBasicBody(body: BasicRequestBody, os: OutputStream): Unit = {
    body match {
      case StringBody(b, encoding, _) =>
        val writer = new OutputStreamWriter(os, encoding)
        writer.write(b)
        // don't close - as this will close the underlying OS and cause errors
        // with multi-part
        writer.flush()

      case ByteArrayBody(b, _) =>
        os.write(b)

      case ByteBufferBody(b, _) =>
        val channel = Channels.newChannel(os)
        channel.write(b)

      case InputStreamBody(b, _) =>
        transfer(b, os)

      case FileBody(f, _) =>
        Files.copy(f.toPath, os)
    }
  }

  private val BoundaryChars =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray

  private def setMultipartBody(mp: MultipartBody, c: HttpURLConnection): Option[OutputStream] = {
    val boundary = {
      val tlr = ThreadLocalRandom.current()
      List
        .fill(32)(BoundaryChars(tlr.nextInt(BoundaryChars.length)))
        .mkString
    }

    // inspired by: https://github.com/scalaj/scalaj-http/blob/master/src/main/scala/scalaj/http/Http.scala#L542
    val partsWithHeaders = mp.parts.map { p =>
      val contentDisposition =
        s"${HeaderNames.ContentDisposition}: ${p.contentDispositionHeaderValue}"
      val contentTypeHeader =
        p.contentType.map(ct => s"${HeaderNames.ContentType}: $ct")
      val otherHeaders = p.additionalHeaders.map(h => s"${h._1}: ${h._2}")
      val allHeaders = List(contentDisposition) ++ contentTypeHeader.toList ++ otherHeaders
      (allHeaders.mkString(CrLf), p)
    }

    val dashes = "--"

    val dashesLen = dashes.length.toLong
    val crLfLen = CrLf.length.toLong
    val boundaryLen = boundary.length.toLong
    val finalBoundaryLen = dashesLen + boundaryLen + dashesLen + crLfLen

    // https://stackoverflow.com/questions/31406022/how-is-an-http-multipart-content-length-header-value-calculated
    val contentLength = partsWithHeaders
      .map {
        case (headers, p) =>
          val bodyLen: Option[Long] = p.body match {
            case StringBody(b, encoding, _) =>
              Some(b.getBytes(encoding).length.toLong)
            case ByteArrayBody(b, _)   => Some(b.length.toLong)
            case ByteBufferBody(_, _)  => None
            case InputStreamBody(_, _) => None
            case FileBody(b, _)        => Some(b.toFile.length())
          }

          val headersLen = headers.getBytes(Iso88591).length

          bodyLen.map(bl => dashesLen + boundaryLen + crLfLen + headersLen + crLfLen + crLfLen + bl + crLfLen)
      }
      .foldLeft(Option(finalBoundaryLen)) {
        case (Some(acc), Some(l)) => Some(acc + l)
        case _                    => None
      }

    c.setRequestProperty(HeaderNames.ContentType, "multipart/form-data; boundary=" + boundary)

    contentLength.foreach { cl =>
      c.setFixedLengthStreamingMode(cl)
      c.setRequestProperty(HeaderNames.ContentLength, cl.toString)
    }

    var total = 0L

    val os = c.getOutputStream
    def writeMeta(s: String): Unit = {
      os.write(s.getBytes(Iso88591))
      total += s.getBytes(Iso88591).length.toLong
    }

    partsWithHeaders.foreach {
      case (headers, p) =>
        writeMeta(dashes)
        writeMeta(boundary)
        writeMeta(CrLf)
        writeMeta(headers)
        writeMeta(CrLf)
        writeMeta(CrLf)
        writeBasicBody(p.body, os)
        writeMeta(CrLf)
    }

    // final boundary
    writeMeta(dashes)
    writeMeta(boundary)
    writeMeta(dashes)
    writeMeta(CrLf)

    Some(os)
  }

  private def readResponse[T](
      c: HttpURLConnection,
      is: InputStream,
      responseAs: ResponseAs[T, Nothing],
      parseCondition: ResponseMetadata => Boolean
  ): Response[T] = {

    val headers = c.getHeaderFields.asScala.toVector
      .filter(_._1 != null)
      .flatMap { case (k, vv) => vv.asScala.map((k, _)) }
    val contentEncoding = Option(c.getHeaderField(HeaderNames.ContentEncoding))

    val charsetFromHeaders = Option(c.getHeaderField(HeaderNames.ContentType))
      .flatMap(encodingFromContentType)

    val code = c.getResponseCode
    val wrappedIs = if (c.getRequestMethod != "HEAD") {
      wrapInput(contentEncoding, handleNullInput(is))
    } else is
    val responseMetadata = ResponseMetadata(headers, code, c.getResponseMessage)
    val body = if (parseCondition(responseMetadata)) {
      Right(readResponseBody(wrappedIs, responseAs, charsetFromHeaders, responseMetadata))
    } else {
      Left(readResponseBody(wrappedIs, asByteArray, charsetFromHeaders, responseMetadata))
    }

    Response(body, code, c.getResponseMessage, headers, Nil)
  }

  private def readResponseBody[T](
      is: InputStream,
      responseAs: ResponseAs[T, Nothing],
      charset: Option[String],
      headers: ResponseMetadata
  ): T = {

    def asString(enc: String) =
      Source.fromInputStream(is, charset.getOrElse(enc)).mkString

    responseAs match {
      case MappedResponseAs(raw, g) => g(readResponseBody(is, raw, charset, headers), headers)

      case IgnoreResponse =>
        @tailrec def consume(): Unit = if (is.read() != -1) consume()
        consume()

      case ResponseAsString(enc) =>
        asString(enc)

      case ResponseAsByteArray =>
        toByteArray(is)

      case ResponseAsStream() =>
        // only possible when the user requests the response as a stream of
        // Nothing. Oh well ...
        throw new IllegalStateException()

      case ResponseAsFile(output, overwrite) =>
        val f = FileHelpers.saveFile(output.toFile, is, overwrite)
        SttpFile.fromFile(f)

    }
  }

  private def handleNullInput(is: InputStream): InputStream =
    if (is == null)
      new ByteArrayInputStream(Array.empty[Byte])
    else
      is

  private def wrapInput(contentEncoding: Option[String], is: InputStream): InputStream =
    contentEncoding.map(_.toLowerCase) match {
      case None            => is
      case Some("gzip")    => new GZIPInputStream(is)
      case Some("deflate") => new InflaterInputStream(is)
      case Some(ce) =>
        throw new UnsupportedEncodingException(s"Unsupported encoding: $ce")
    }

  override def close(): Unit = {}
}

object HttpURLConnectionBackend {

  def apply(
      options: SttpBackendOptions = SttpBackendOptions.Default,
      customizeConnection: HttpURLConnection => Unit = _ => (),
      createURL: String => URL = new URL(_),
      openConnection: (URL, Option[java.net.Proxy]) => URLConnection = {
        case (url, None)        => url.openConnection()
        case (url, Some(proxy)) => url.openConnection(proxy)
      }
  ): SttpBackend[Id, Nothing] =
    new FollowRedirectsBackend[Id, Nothing](
      new HttpURLConnectionBackend(options, customizeConnection, createURL, openConnection)
    )
}
