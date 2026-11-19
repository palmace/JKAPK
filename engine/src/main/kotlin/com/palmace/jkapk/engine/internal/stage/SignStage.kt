package com.palmace.jkapk.engine.internal.stage

import com.android.apksig.ApkSigner
import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildDiagnostic
import com.palmace.jkapk.engine.BuildStage
import com.palmace.jkapk.engine.BuildStageExecutor
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel
import com.palmace.jkapk.engine.Severity
import com.palmace.jkapk.engine.SigningConfig
import com.palmace.jkapk.engine.internal.resolve.ResolvedClasspath
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

// Etapa de firma: firma el APK sin firmar usando apksig (Java puro).
// Soporta SigningConfig personalizado o debug.keystore empaquetado por defecto.
// Esquemas: v1 solo si minSdk < 24; v2 y v3 habilitados siempre.
internal class SignStage : BuildStageExecutor<ResolvedClasspath, ResolvedClasspath> {

    override val stage: BuildStage = BuildStage.SIGN

    override suspend fun execute(input: ResolvedClasspath, ctx: BuildContext): ResolvedClasspath {
        ctx.checkCancelled()

        val unsignedApk = input.unsignedApk
        if (unsignedApk == null || !unsignedApk.exists()) {
            val diag = BuildDiagnostic(
                severity = Severity.ERROR,
                message = "No existe APK sin firmar para la etapa de firma",
                stage = BuildStage.SIGN
            )
            ctx.diagnostic(diag)
            throw SignExecutionException("Falta unsignedApk", diag)
        }

        ctx.emit(BuildState.Signing(unsignedApk.absolutePath))
        ctx.log(LogLevel.INFO, "SignStage", "Firmando APK: ${unsignedApk.name}")

        val signedDir = File(ctx.workDir, "signed").apply { mkdirs() }
        val signedApk = File(signedDir, "${ctx.config.projectName.lowercase().replace("\\s+".toRegex(), "-")}-signed.apk")

        try {
            val (privateKey, certChain) = resolveSigningMaterial(ctx)

            val v1Enabled = ctx.config.minSdk < 24
            val signerConfig = ApkSigner.SignerConfig.Builder(
                ctx.config.signingConfig?.keyAlias ?: DEFAULT_DEBUG_ALIAS,
                privateKey,
                certChain
            ).build()

            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setMinSdkVersion(ctx.config.minSdk)
                .setV1SigningEnabled(v1Enabled)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign()

            ctx.log(
                LogLevel.INFO,
                "SignStage",
                "APK firmado en ${signedApk.absolutePath} (v1=$v1Enabled, v2=true, v3=true)"
            )
            return input.copy(signedApk = signedApk)
        } catch (t: SignExecutionException) {
            throw t
        } catch (t: Throwable) {
            val diag = BuildDiagnostic(
                severity = Severity.ERROR,
                message = "Error al firmar APK con apksig: ${t.message}",
                stage = BuildStage.SIGN,
                rawOutput = t.stackTraceToString()
            )
            ctx.diagnostic(diag)
            throw SignExecutionException("Fallo en SignStage", diag)
        }
    }

    private fun resolveSigningMaterial(ctx: BuildContext): Pair<PrivateKey, List<X509Certificate>> {
        val custom: SigningConfig? = ctx.config.signingConfig
        val keyStore: KeyStore
        val keyAlias: String
        val keyPassword: CharArray

        if (custom != null) {
            if (!custom.keystoreFile.exists()) {
                val diag = BuildDiagnostic(
                    severity = Severity.ERROR,
                    message = "Keystore personalizado no encontrado: ${custom.keystoreFile.absolutePath}",
                    stage = BuildStage.SIGN
                )
                ctx.diagnostic(diag)
                throw SignExecutionException("Keystore inexistente", diag)
            }
            keyStore = loadKeyStoreFromFile(custom.keystoreFile, custom.keystorePassword.toCharArray(), custom.storeType)
            keyAlias = custom.keyAlias
            keyPassword = custom.keyPassword.toCharArray()
        } else {
            val debugKsFile = ensureDebugKeystore(ctx.cacheDir)
            keyStore = loadKeyStoreFromFile(debugKsFile, DEFAULT_DEBUG_PASS.toCharArray(), storeType = null)
            keyAlias = DEFAULT_DEBUG_ALIAS
            keyPassword = DEFAULT_DEBUG_PASS.toCharArray()
        }

        val entryKey = keyStore.getKey(keyAlias, keyPassword) as? PrivateKey
            ?: throw IllegalStateException("No se pudo obtener PrivateKey para alias '$keyAlias'")

        val chain = keyStore.getCertificateChain(keyAlias)
            ?.mapNotNull { it as? X509Certificate }
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("No se encontro cadena de certificados X509 para alias '$keyAlias'")

        return entryKey to chain
    }

    private fun loadKeyStoreFromFile(file: File, storePass: CharArray, storeType: String?): KeyStore {
        val candidateTypes = if (storeType != null) {
            listOf(storeType)
        } else {
            listOf("PKCS12", "JKS", KeyStore.getDefaultType())
        }

        var lastError: Throwable? = null
        for (type in candidateTypes.distinct()) {
            try {
                val ks = KeyStore.getInstance(type)
                FileInputStream(file).use { fis -> ks.load(fis, storePass) }
                return ks
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw IllegalStateException("No se pudo abrir keystore ${file.name}: ${lastError?.message}", lastError)
    }

    private fun ensureDebugKeystore(cacheDir: File): File {
        val outFile = File(cacheDir, "debug.keystore")
        if (outFile.exists() && outFile.length() > 0) return outFile

        val resourceStream = javaClass.getResourceAsStream("/debug.keystore")
            ?: throw IllegalStateException("Recurso /debug.keystore no encontrado en el classpath")

        cacheDir.mkdirs()
        resourceStream.use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    companion object {
        private const val DEFAULT_DEBUG_ALIAS = "androiddebugkey"
        private const val DEFAULT_DEBUG_PASS = "android"
    }
}

internal class SignExecutionException(
    message: String,
    val diagnostic: BuildDiagnostic
) : Exception(message)
