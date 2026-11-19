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
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

// Firma el APK sin firmar con apksig (Java puro, sin binarios externos).
//
// Si ProjectConfig.signingConfig == null, usa el debug.keystore empaquetado
// en resources/debug.keystore, extraido a cacheDir si no existe.
//
// Esquemas de firma:
//   v1 (JAR signing): habilitado solo si minSdk < 24
//   v2 (APK Signature Scheme v2): siempre
//   v3 (APK Signature Scheme v3): siempre
internal class SignStage : BuildStageExecutor<ResolvedClasspath, ResolvedClasspath> {

    override val stage: BuildStage = BuildStage.SIGN

    override suspend fun execute(input: ResolvedClasspath, ctx: BuildContext): ResolvedClasspath {
        ctx.checkCancelled()

        val unsignedApk = input.unsignedApk
            ?: throw SignExecutionException(
                "unsignedApk es null. PackageStage debe ejecutarse antes.",
                BuildDiagnostic(
                    severity = Severity.ERROR,
                    message = "SignStage sin APK sin firmar",
                    stage = BuildStage.SIGN
                )
            )

        if (!unsignedApk.exists()) {
            throw SignExecutionException(
                "APK sin firmar no existe en ${unsignedApk.absolutePath}",
                BuildDiagnostic(
                    severity = Severity.ERROR,
                    message = "APK sin firmar no encontrado",
                    stage = BuildStage.SIGN
                )
            )
        }

        val signedApk = File(ctx.workDir, "${ctx.config.projectName}-signed.apk")
        ctx.emit(BuildState.Signing(signedApk.absolutePath))

        val signingConfig = ctx.config.signingConfig
            ?: debugSigningConfig(ctx)

        ctx.log(
            LogLevel.INFO, "Sign",
            "Firmando ${unsignedApk.name} con keystore=${signingConfig.keystoreFile.name} " +
                "(v1=${ctx.config.minSdk < 24}, v2=true, v3=true)"
        )

        try {
            val keyStore = loadKeyStore(signingConfig)
            val alias = signingConfig.keyAlias
            val privateKey = keyStore.getKey(alias, signingConfig.keyPassword.toCharArray()) as? PrivateKey
                ?: throw SignExecutionException(
                    "No se encontro PrivateKey para alias '$alias'",
                    BuildDiagnostic(
                        severity = Severity.ERROR,
                        message = "PrivateKey no encontrada en keystore",
                        stage = BuildStage.SIGN
                    )
                )
            val cert = keyStore.getCertificate(alias) as? X509Certificate
                ?: throw SignExecutionException(
                    "No se encontro certificado para alias '$alias'",
                    BuildDiagnostic(
                        severity = Severity.ERROR,
                        message = "Certificado no encontrado en keystore",
                        stage = BuildStage.SIGN
                    )
                )

            val signerConfig = ApkSigner.SignerConfig.Builder(
                alias,
                privateKey,
                listOf(cert)
            ).build()

            val signer = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setMinSdkVersion(ctx.config.minSdk)
                .setV1SigningEnabled(ctx.config.minSdk < 24)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()

            signer.sign()
        } catch (t: SignExecutionException) {
            throw t
        } catch (t: Throwable) {
            val diag = BuildDiagnostic(
                severity = Severity.ERROR,
                message = "Fallo al firmar: ${t.message}",
                stage = BuildStage.SIGN,
                rawOutput = t.stackTraceToString()
            )
            ctx.diagnostic(diag)
            throw SignExecutionException("Fallo al firmar", diag)
        }

        ctx.log(LogLevel.INFO, "Sign", "APK firmado: ${signedApk.absolutePath} (${signedApk.length()} bytes)")
        ctx.emit(BuildState.Signing("(completado)"))

        return input.copy(signedApk = signedApk)
    }

    // Extrae debug.keystore de resources si no existe, y lo devuelve como SigningConfig.
    private fun debugSigningConfig(ctx: BuildContext): SigningConfig {
        val target = File(ctx.cacheDir, "debug.keystore")
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            val resource = javaClass.classLoader.getResourceAsStream("debug.keystore")
                ?: throw SignExecutionException(
                    "debug.keystore no encontrado en resources del engine",
                    BuildDiagnostic(
                        severity = Severity.ERROR,
                        message = "debug.keystore ausente en classpath del engine",
                        stage = BuildStage.SIGN
                    )
                )
            resource.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, true)
        }
        return SigningConfig(
            keystoreFile = target,
            keystorePassword = "android",
            keyAlias = "androiddebugkey",
            keyPassword = "android"
        )
    }

    private fun loadKeyStore(config: SigningConfig): KeyStore {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        config.keystoreFile.inputStream().use { input ->
            ks.load(input, config.keystorePassword.toCharArray())
        }
        return ks
    }
}

internal class SignExecutionException(
    message: String,
    val diagnostic: BuildDiagnostic
) : Exception(message)
