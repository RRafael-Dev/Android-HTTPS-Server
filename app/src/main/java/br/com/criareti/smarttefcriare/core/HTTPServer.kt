package br.com.criareti.smarttefcriare.core

import androidx.core.util.ObjectsCompat
import br.com.criareti.smarttefcriare.util.HTTPProtocol
import br.com.criareti.smarttefcriare.util.HTTPRequest
import br.com.criareti.smarttefcriare.util.MutableHTTPResponse
import br.com.criareti.smarttefcriare.core.ssl.getDefaultSSLContext
import br.com.criareti.smarttefcriare.util.HTTPVerb
import br.com.criareti.smarttefcriare.util.readHTTPRequest
import br.com.criareti.smarttefcriare.util.toByteBuffer
import br.com.criareti.smarttefcriare.util.toHTTPResponse
import br.com.criareti.smarttefcriare.util.writeHTTPResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

enum class HTTPHandlerPriority { BEFORE, NORMAL, AFTER, ERROR }

typealias MutableHTTPRouterMap = MutableMap<String, MutableMap<HTTPVerb, IHTTPMethodHandler>>

@FunctionalInterface
interface IHTTPMethodHandler {
    suspend fun handle(req: HTTPRequest, res: MutableHTTPResponse)
}

interface IHTTPHandler : IHTTPMethodHandler {
    val priority: HTTPHandlerPriority
    val name: String
}

interface IHTTPRouter : IHTTPHandler {
    fun use(handler: IHTTPHandler)
    fun get(path: String, methodHandler: IHTTPMethodHandler)
    fun head(path: String, methodHandler: IHTTPMethodHandler)
    fun post(path: String, methodHandler: IHTTPMethodHandler)
    fun put(path: String, methodHandler: IHTTPMethodHandler)
    fun delete(path: String, methodHandler: IHTTPMethodHandler)
    fun connect(path: String, methodHandler: IHTTPMethodHandler)
    fun options(path: String, methodHandler: IHTTPMethodHandler)
    fun trace(path: String, methodHandler: IHTTPMethodHandler)
    fun patch(path: String, methodHandler: IHTTPMethodHandler)
}

class HTTPServer(
    val protocol: HTTPProtocol,
    val port: Int,
    private val router: IHTTPRouter = HTTPRouter()
) : AutoCloseable, IHTTPRouter by router {
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    var active: Boolean = false
        private set

    fun use(methodHandler: IHTTPMethodHandler): IHTTPHandler =
        use("Simple anonymous handler", HTTPHandlerPriority.NORMAL, methodHandler)

    fun use(
        name: String,
        priority: HTTPHandlerPriority,
        methodHandler: IHTTPMethodHandler
    ): IHTTPHandler =
        SimpleHTTPHandler(name, priority, methodHandler).also { use(it) }

    @Throws(IOException::class)
    fun start() {
        if (active) throw IOException("Servidor já iniciado")
        serverSocket = createServerSocket(protocol, InetSocketAddress(port))
        scope.launch {
            while (active) {
                try {
                    serverSocket?.accept()?.also {
                        launch {
                            try {
                                handleSocket(it)
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        active = true
    }

    @Throws(IOException::class)
    override fun close() {
        if (!active) throw IOException("Servidor já fechado")
        if (scope.isActive) scope.cancel()
        serverSocket?.close()
        serverSocket = null
        active = false
    }

    private suspend fun handleSocket(socket: Socket) = socket.use {
        val req = socket.getInputStream().readHTTPRequest()
        val res = req.createDefaultResponse()
        try {
            handleRequest(req, res)
            socket.getOutputStream().writeHTTPResponse(res.toHTTPResponse())
        } catch (e: Exception) {
            e.printStackTrace()
            socket.getOutputStream().writeHTTPResponse(e.toHTTPResponse(req))
        } finally {

        }
    }

    private suspend fun handleRequest(req: HTTPRequest, res: MutableHTTPResponse) {
        router.handle(req, res)
        if (!res.isComplete) {
            res.statusCode = 404
            res.body = JSONObject(mapOf("message" to "Essa página não existe XD")).toString()
                .toByteBuffer()
        }
    }
}

private class SimpleHTTPHandler(
    override val name: String,
    override val priority: HTTPHandlerPriority,
    val handler: IHTTPMethodHandler
) : IHTTPHandler {
    override suspend fun handle(req: HTTPRequest, res: MutableHTTPResponse) =
        handler.handle(req, res)

    override fun hashCode(): Int = ObjectsCompat.hash(name, priority, handler)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SimpleHTTPHandler

        if (name != other.name) return false
        if (priority != other.priority) return false
        if (handler != other.handler) return false

        return true
    }
}

private class HTTPRouter(val path: String = "/") : IHTTPRouter {
    override val name: String = "Router of $path"
    override val priority: HTTPHandlerPriority = HTTPHandlerPriority.NORMAL
    private val handlerList: MutableList<IHTTPHandler> = mutableListOf()
    private val routeMap: MutableHTTPRouterMap = mutableMapOf()
    private val params: List<Pair<Int, String>>
    private val pathRegex: Regex

    init {
        val splitPath = path.split("/")
        val pathRegexStr = splitPath.joinToString("/") { if (it.startsWith(":")) "[^/]+" else it }

        params = splitPath
            .mapIndexed { i, it -> if (it.startsWith(":")) i to it.substring(1) else null }
            .filterNotNull()
        pathRegex = Regex("/?$pathRegexStr")
    }

    fun matchUrl(currentPath: String, url: String) {
//        paramRegex.findAll()
    }

    override fun use(handler: IHTTPHandler) {
        handlerList.add(
            handlerList.indexOfLast { handler.priority <= it.priority }.coerceAtLeast(0),
            handler
        )
    }

    suspend fun internalHandle(req: HTTPRequest, res: MutableHTTPResponse) {

    }

    override suspend fun handle(req: HTTPRequest, res: MutableHTTPResponse) {
        if (res.isComplete) return

        // TODO: "check path"

        val prevPath = req.currentPath
        val prevPathSegmentIdx = req.currentSegmentIdx
        try {
            var didInternalHandle = false
            suspend fun maybeDoInternalHandle() {
                if (!didInternalHandle) {
                    internalHandle(req, res)
                    didInternalHandle = true
                }
            }

            val baseIdx = prevPathSegmentIdx as? Int ?: 0
            for (param in params) {
                val value = req.url.pathSegments[baseIdx + param.first]
                val key = param.second
                req.params[key] = value
            }

            for (handler in handlerList) {
                when (handler.priority) {
                    HTTPHandlerPriority.BEFORE -> handler.handle(req, res)
                    HTTPHandlerPriority.NORMAL -> {
                        maybeDoInternalHandle()
                        if (!res.isComplete)
                            handler.handle(req, res)
                    }

                    HTTPHandlerPriority.AFTER -> {
                        maybeDoInternalHandle()
                        handler.handle(req, res)
                    }

                    else -> { /*ignore*/
                    }
                }
            }

            maybeDoInternalHandle()
        } catch (e: Exception) {

            for (handler in handlerList) {
                if (handler.priority == HTTPHandlerPriority.ERROR)
                    handler.handle(req, res)
            }
        } finally {
            req.extra["currentPath"] = prevPath
            req.extra["currentSegmentIdx"] = prevPathSegmentIdx
        }
    }

    private fun add(method: HTTPVerb, path: String, methodHandler: IHTTPMethodHandler) {
        val map = routeMap[path] ?: mutableMapOf<HTTPVerb, IHTTPMethodHandler>().also {
            routeMap[path] = it
        }
        map[method] = methodHandler
    }

    override fun get(path: String, methodHandler: IHTTPMethodHandler) =
        add(HTTPVerb.GET, path, methodHandler)

    override fun head(path: String, methodHandler: IHTTPMethodHandler) =
        add(HTTPVerb.HEAD, path, methodHandler)

    override fun post(path: String, methodHandler: IHTTPMethodHandler) =
        add(HTTPVerb.POST, path, methodHandler)

    override fun put(path: String, methodHandler: IHTTPMethodHandler) =
        add(HTTPVerb.PUT, path, methodHandler)

    override fun delete(path: String, methodHandler: IHTTPMethodHandler) =
        add(HTTPVerb.DELETE, path, methodHandler)

    override fun connect(path: String, methodHandler: IHTTPMethodHandler) =
        add(HTTPVerb.CONNECT, path, methodHandler)

    override fun options(path: String, methodHandler: IHTTPMethodHandler) =
        add(HTTPVerb.OPTIONS, path, methodHandler)

    override fun trace(path: String, methodHandler: IHTTPMethodHandler) =
        add(HTTPVerb.TRACE, path, methodHandler)

    override fun patch(path: String, methodHandler: IHTTPMethodHandler) =
        add(HTTPVerb.PATCH, path, methodHandler)
}

private fun createServerSocket(protocol: HTTPProtocol, address: InetSocketAddress): ServerSocket {
    return when (protocol) {
        HTTPProtocol.HTTPS -> {
            val sslContext = getDefaultSSLContext()
            val socketFactory = sslContext.serverSocketFactory
            // Backlog is the maximum number of pending connections on the socket,
            // 0 means that an implementation-specific default is used
            val backlog = 0;
            // Bind the socket to the given port and address
            socketFactory.createServerSocket(address.port, backlog, address.address)
        }

        HTTPProtocol.HTTP -> ServerSocket(address.port, 0, address.address)
        else -> throw RuntimeException("Protocolo desconhecido ou não suportado")
    }
}