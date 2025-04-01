package br.com.criareti.smarttefcriare.app

import android.app.Application
import android.content.Context
import android.os.Build
import br.com.criareti.smarttefcriare.util.HTTPProtocol
import br.com.criareti.smarttefcriare.core.HTTPServer
import br.com.criareti.smarttefcriare.core.Settings
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference

class SmartTEFCriare : Application() {
    companion object {
        private var ref: WeakReference<SmartTEFCriare>? = null
        var filesDir: File? = null
            private set
        var dataDir: File? = null
            private set
        var cacheDir: File? = null
            private set
        var codeCacheDir: File? = null
            private set
        var obbDir: File? = null
            private set
        var externalCacheDir: File? = null
            private set

        private fun setRef(ref: SmartTEFCriare) {
            this.ref = WeakReference(ref)
            filesDir = ref.filesDir
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) this.dataDir = ref.dataDir
            this.cacheDir = ref.cacheDir
            this.codeCacheDir = ref.codeCacheDir
            this.obbDir = ref.obbDir
            this.externalCacheDir = ref.externalCacheDir
        }

        @Throws(IOException::class)
        fun openAsset(path: String): InputStream {
            val ctx = ref?.get() ?: throw IOException("Asset não disponível!")
            return ctx.assets.open(path)
        }
    }

    override fun onCreate() {
        super.onCreate()
        setRef(this)
        Settings.initInstance(getSharedPreferences("hadouken", Context.MODE_PRIVATE))
        openServer()
    }

    private fun openServer() {
        val server = HTTPServer(HTTPProtocol.HTTPS, 8899)
        server.start()
    }
}