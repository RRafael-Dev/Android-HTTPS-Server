package br.com.criareti.smarttefcriare.util

import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.Calendar

enum class HTTPProtocol { HTTP, HTTPS }

/**
 * @see <a href="https://developer.mozilla.org/pt-BR/docs/Web/HTTP/Methods">methods</a>
 */
enum class HTTPVerb(val requestHasBody: Boolean?, val responseHasBody: Boolean?) {
    GET(requestHasBody = false, responseHasBody = true),
    HEAD(requestHasBody = false, responseHasBody = false),
    POST(requestHasBody = true, responseHasBody = true),
    PUT(requestHasBody = true, responseHasBody = false),
    DELETE(requestHasBody = null, responseHasBody = null),
    CONNECT(requestHasBody = true, responseHasBody = true),
    OPTIONS(requestHasBody = false, responseHasBody = false),
    TRACE(requestHasBody = false, responseHasBody = false),
    PATCH(requestHasBody = true, responseHasBody = true),
}

enum class HTTPBodyKind { NONE, ANY, EXPECTED, MANDATORY }

enum class HTTPStatusCodes(val statusCode: Int, val message: String) {
    HTTP_100(100, "Continue"),
    HTTP_101(101, "Switching Protocols"),
    HTTP_200(200, "OK"),
    HTTP_201(201, "Created"),
    HTTP_202(202, "Accepted"),
    HTTP_203(203, "Non-Authoritative Information"),
    HTTP_204(204, "No Content"),
    HTTP_205(205, "Reset Content"),
    HTTP_206(206, "Partial Content"),
    HTTP_300(300, "Multiple Choices"),
    HTTP_301(301, "Moved Permanently"),
    HTTP_302(302, "Found"),
    HTTP_303(303, "See Other"),
    HTTP_304(304, "Not Modified"),
    HTTP_305(305, "Use Proxy"),
    HTTP_307(307, "Temporary Redirect"),
    HTTP_400(400, "Bad Request"),
    HTTP_401(401, "Unauthorized"),
    HTTP_402(402, "Payment Required"),
    HTTP_403(403, "Forbidden"),
    HTTP_404(404, "Not Found"),
    HTTP_405(405, "Method Not Allowed"),
    HTTP_406(406, "Not Acceptable"),
    HTTP_407(407, "Proxy Authentication Required"),
    HTTP_408(408, "Request Timeout"),
    HTTP_409(409, "Conflict"),
    HTTP_410(410, "Gone"),
    HTTP_411(411, "Length Required"),
    HTTP_412(412, "Precondition Failed"),
    HTTP_413(413, "Payload Too Large"),
    HTTP_414(414, "URI Too Long"),
    HTTP_415(415, "Unsupported Media Type"),
    HTTP_416(416, "Range Not Satisfiable"),
    HTTP_417(417, "Expectation Failed"),
    HTTP_426(426, "Upgrade Required"),
    HTTP_500(500, "Internal Server Error"),
    HTTP_501(501, "Not Implemented"),
    HTTP_502(502, "Bad Gateway"),
    HTTP_503(503, "Service Unavailable"),
    HTTP_504(504, "Gateway Timeout"),
    HTTP_505(505, "HTTP Version Not Supported")
}

class CustomHTTPException(message: String, val statusCode: Int = 500) : RuntimeException(message)

data class HTTPRequest(
    val method: HTTPVerb,
    val url: Uri,
    val protocol: String,
    val headers: Map<String, String>,
    val body: ByteBuffer?,
    val extra: MutableMap<String, Any?> = mutableMapOf(),
) {
    var currentPath: String? by extra
    val currentSegmentIdx: Int? by extra
    val params: MutableMap<String, String>
        get() = extra["params"] as? MutableMap<String, String> ?:
            mutableMapOf<String, String>().also { extra["params"] = it }

    fun createDefaultResponse() = MutableHTTPResponse(this, "HTTP/1.1")
}

data class HTTPResponse(
    val request: HTTPRequest,
    val protocol: String,
    val statusCode: Int,
    val message: String = getMessageForStatusCode(statusCode) ?: "Unknown",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteBuffer? = null,
    val extra: Map<String, Any?> = emptyMap(),
)

data class MutableHTTPResponse(
    val request: HTTPRequest,
    var protocol: String,
    var statusCode: Int? = null,
    var customMessage: String = "",
    var headers: MutableMap<String, String> = mutableMapOf(),
    var body: ByteBuffer? = null,
    var extra: MutableMap<String, Any?> = mutableMapOf(),
) {
    val isComplete: Boolean
        get() = statusCode != null

    fun toHTTPResponse() = HTTPResponse(
        request,
        protocol,
        statusCode ?: throw RuntimeException("Resposta não completamente formada"),
        getMessageForStatusCode(statusCode!!) ?: customMessage,
        headers
            .also {
                if (request.method.responseHasBody ?: (body != null))
                    it["Content-Length"] = body?.array()?.size?.toString() ?: "0"
            }
            .toMap(),
        if (request.method.responseHasBody != false) body else null,
        extra.toMap()
    )
}

@Throws(IOException::class, CustomHTTPException::class)
fun parseHTTPRequest(inputStream: InputStream, timeout: Int?): HTTPRequest {
    val start = Calendar.getInstance().timeInMillis
    val bufSize = 1024
    val lineEndPattern = Regex("\r?\n")
    val requestEndPattern = Regex(".*\r?\n\r?\n$")

    var reading = true
    val buf = ByteArray(bufSize)
    var read = 0
    var totalRead = 0
    var totalBodyRead = 0
    val builder = StringBuilder()
    var validatedFirstLine = false
    var method = HTTPVerb.GET
    var url = ""
    var protocol = ""
    val headers = mutableMapOf<String, String>()
    var body: ByteBuffer? = null
    while (reading) {
        read = inputStream.read(buf)
        totalRead += read

        val line = String(buf, 0, read)
        builder.append(line)
        reading = !requestEndPattern.matches(builder.substring(Math.max(0, builder.length - 10)))

        if (!validatedFirstLine && totalRead >= 8000) throw CustomHTTPException(
            "Headers muito extensos",
            400
        )
        if (!validatedFirstLine && line.contains("\n")) {
            //validate first line
            val firstLine = builder.substring(0, builder.indexOf("\n")).trim()
            method = HTTPVerb.valueOf(firstLine.substring(0, firstLine.indexOf(" ")).trim())
            protocol = firstLine.substring(firstLine.lastIndexOf(" ")).trim()
            Regex("HTTP/1(.[01])?").matchEntire(protocol)
                ?: throw CustomHTTPException("Protocolo não suportado", 400)
            url =
                firstLine.substring(method.name.length + 1, firstLine.length - protocol.length - 1)
                    .trim()
            validatedFirstLine = true
        }
    }

    val lines = builder.split(lineEndPattern)
    for (i in 1..lines.size) {
        val line = lines[i]
        if (line.isBlank())
            break
        val idx = line.indexOf(':')
        headers.put(line.substring(0, idx), line.substring(idx + 2))
    }

    val contentLength = headers["Content-Length"]?.toIntOrNull()
    if (contentLength != null) {
        body = ByteBuffer.allocate(contentLength)
        val bytes = lines.last().toByteArray()
        body!!.put(bytes)
        totalBodyRead = bytes.size
        while (totalBodyRead < contentLength) {
            read = inputStream.read(buf)
            if (read != -1) throw CustomHTTPException("Conexão fechada inexperadamente")
            body.position(totalBodyRead)
            body.put(buf, 0, read)
            totalRead += read
            totalBodyRead += read
        }
    }

    return HTTPRequest(method, Uri.parse(url), protocol, headers, body, mutableMapOf())
}

fun formatHTTPResponse(
    response: HTTPResponse,
    outputStream: OutputStream,
    charset: Charset? = null
) {
    val writer = OutputStreamWriter(outputStream, charset ?: Charset.forName("UTF8"))
    writer.apply {
        write("${response.protocol} ${response.statusCode} ${response.message}\n")
        for (entry in response.headers.entries) {
            write("${entry.key}: ${entry.value}\n")
        }
        if (response.body != null) {
            write("\n")
            flush()
            outputStream.write(response.body.array())
        }
        flush()
    }
}

@Throws(IOException::class, CustomHTTPException::class)
fun InputStream.readHTTPRequest(timeout: Int? = null): HTTPRequest = parseHTTPRequest(this, timeout)

fun OutputStream.writeHTTPResponse(response: HTTPResponse, charset: Charset? = null) =
    formatHTTPResponse(response, this, charset)

fun String.toByteBuffer(): ByteBuffer = ByteBuffer.wrap(toByteArray())

fun Exception.toHTTPResponse(request: HTTPRequest) = HTTPResponse(
    request,
    "HTTP/1.1",
    (this as CustomHTTPException?)?.statusCode ?: 500,
    message ?: HTTPStatusCodes.HTTP_500.message,
    body = JSONObject(
        mapOf(
            "message" to message,
            "trace" to stackTraceToString()
        )
    ).toString().toByteBuffer()
)

fun getMessageForStatusCode(statusCode: Int): String? = statusCodeMap[statusCode]

private val statusCodeMap = HTTPStatusCodes.entries.associate { it.statusCode to it.message }