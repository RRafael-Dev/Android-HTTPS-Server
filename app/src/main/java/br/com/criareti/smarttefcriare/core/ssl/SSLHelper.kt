package br.com.criareti.smarttefcriare.core.ssl

import br.com.criareti.smarttefcriare.app.SmartTEFCriare
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

@FunctionalInterface
fun interface IInputStreamGetter {
    fun getStream(): InputStream
}

@Throws(IOException::class)
fun getDefaultSSLContext(): SSLContext = getSSLContext(
    File(
        SmartTEFCriare.filesDir ?: throw IOException("KeyStore não disponível no momento"),
        "keystore.bks"
    ),
    "CriareTEF c315ff10-b8a0-4ace-83fe-8f97a357aefd",
    defaultKeyStoreGetter = { SmartTEFCriare.openAsset("keystore.bks") }
)

@Throws(IOException::class)
fun getSSLContext(
    keyStorePath: File,
    keyStorePassword: String,
    keyPassword: String = keyStorePassword,
    keyStoreType: String? = "BKS",
    sslProtocol: String? = "TLS",
    keyManagerFactoryAlgorithm: String? = KeyManagerFactory.getDefaultAlgorithm(),
    defaultKeyStoreGetter: IInputStreamGetter,
    tm: Array<out TrustManager>? = null,
    random: SecureRandom? = null,
): SSLContext {
    if (!keyStorePath.exists()) {
        val bytes = defaultKeyStoreGetter.getStream().use { it.readBytes() }
        keyStorePath.writeBytes(bytes)
    }

    val keyStoreBytes = keyStorePath.readBytes()
    val keyStorePasswordCharArray = keyStorePassword.toCharArray()
    val keyPasswordCharArray = keyPassword.toCharArray()

    try {
        val keyStore = KeyStore.getInstance(keyStoreType ?: "BKS")
        keyStore.load(ByteArrayInputStream(keyStoreBytes), keyStorePasswordCharArray)

        val keyManagerFactory =
            KeyManagerFactory.getInstance(
                keyManagerFactoryAlgorithm ?: KeyManagerFactory.getDefaultAlgorithm()
            )
        keyManagerFactory.init(keyStore, keyPasswordCharArray)

        val sslContext = SSLContext.getInstance(sslProtocol ?: "TLS")
        // Null means using default implementations for TrustManager and SecureRandom
        sslContext.init(keyManagerFactory.keyManagers, tm, random)
        return sslContext
    } finally {
        Arrays.fill(keyStorePasswordCharArray, '0')
        Arrays.fill(keyPasswordCharArray, '0')
    }
}