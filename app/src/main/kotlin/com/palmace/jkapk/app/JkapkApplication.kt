package com.palmace.jkapk.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.palmace.jkapk.app.build.BuildSessionController
import com.palmace.jkapk.app.data.ProjectConfigMapper
import com.palmace.jkapk.app.data.ProjectScanner
import com.palmace.jkapk.app.data.ProjectTemplateGenerator
import com.palmace.jkapk.app.domain.ToolchainPaths
import com.palmace.jkapk.engine.BuildEngine
import java.io.File

class JkapkApplication : Application() {

    lateinit var toolchainPaths: ToolchainPaths
        private set

    lateinit var configMapper: ProjectConfigMapper
        private set

    lateinit var templateGenerator: ProjectTemplateGenerator
        private set

    lateinit var projectScanner: ProjectScanner
        private set

    lateinit var buildEngine: BuildEngine
        private set

    lateinit var buildSessionController: BuildSessionController
        private set

    val projectsDirectory: File
        get() = toolchainPaths.projectsRootDir.apply { mkdirs() }

    override fun onCreate() {
        super.onCreate()
        android.widget.Toast.makeText(this, "JkapkApplication.onCreate ejecutado", android.widget.Toast.LENGTH_SHORT).show()
        // Copiar kotlin-stdlib.jar desde assets a cacheDir
        val stdlibAsset = "kotlin-stdlib.jar"
        val stdlibTarget = File(cacheDir, stdlibAsset)
        try {
            if (!stdlibTarget.exists()) {
                assets.open(stdlibAsset).use { input ->
                    stdlibTarget.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("JkapkApplication", "Error copiando kotlin-stdlib.jar: ${e.message}", e)
        }

        // Copiar kotlin-compiler-embeddable.jar desde assets a cacheDir (validando tamano y copia atomica)
        val compilerAsset = "kotlin-compiler-embeddable.jar"
        val compilerTarget = File(cacheDir, compilerAsset)
        val compilerTmp = File(cacheDir, "$compilerAsset.tmp")
        try {
            val expectedSize = try {
                assets.openFd(compilerAsset).use { it.length }
            } catch (_: Exception) {
                -1L
            }
            val needsCopy = !compilerTarget.exists() ||
                compilerTarget.length() <= 0L ||
                (expectedSize > 0L && compilerTarget.length() != expectedSize)
            if (needsCopy) {
                if (compilerTmp.exists()) compilerTmp.delete()
                assets.open(compilerAsset).use { input ->
                    compilerTmp.outputStream().buffered(65536).use { output ->
                        input.copyTo(output, bufferSize = 65536)
                        output.flush()
                    }
                }
                if (compilerTmp.length() <= 0L) {
                    throw IllegalStateException("Archivo copiado vacio para $compilerAsset")
                }
                if (compilerTarget.exists() && !compilerTarget.delete()) {
                    throw IllegalStateException("No se pudo reemplazar ${compilerTarget.absolutePath}")
                }
                if (!compilerTmp.renameTo(compilerTarget)) {
                    compilerTmp.copyTo(compilerTarget, overwrite = true)
                    compilerTmp.delete()
                }
            }
        } catch (e: Exception) {
            if (compilerTmp.exists()) compilerTmp.delete()
            android.util.Log.e("JkapkApplication", "Error copiando kotlin-compiler-embeddable.jar: ${e.message}", e)
            android.widget.Toast.makeText(this, "Error copiando compilador: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
        if (compilerTarget.exists()) {
            android.util.Log.i("JkapkApplication", "Compilador copiado OK: ${compilerTarget.absolutePath} (${compilerTarget.length()} bytes)")
        }

        instance = this

        toolchainPaths = ToolchainPaths.fromContext(this)
        configMapper = ProjectConfigMapper(toolchainPaths)
        templateGenerator = ProjectTemplateGenerator()
        projectScanner = ProjectScanner()

        buildEngine = createBuildEngine()
        buildSessionController = BuildSessionController(
            buildEngine = buildEngine,
            configMapper = configMapper
        )
    }

    private fun createBuildEngine(): BuildEngine {
        val engineClass = Class.forName("com.palmace.jkapk.engine.internal.DefaultBuildEngine")
        val ctor = engineClass.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance() as BuildEngine
    }

    fun prepareApkForInstall(sourceApk: File): File {
        require(sourceApk.exists()) { "El archivo APK no existe: ${sourceApk.absolutePath}" }
        projectsDirectory.mkdirs()

        val stagingDir = File(cacheDir, "apk-install").apply {
            if (!exists()) {
                mkdirs()
            }
        }
        val targetApk = File(stagingDir, sourceApk.name)
        targetApk.parentFile?.mkdirs()
        sourceApk.copyTo(targetApk, overwrite = true)
        return targetApk
    }

    fun createInstallApkIntent(context: Context, sourceApk: File): Intent {
        val stagedApk = prepareApkForInstall(sourceApk)
        val apkUri: Uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.fileprovider")
                .appendPath("staged")
                .appendPath(stagedApk.name)
                .build()
        } else {
            Uri.fromFile(stagedApk)
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        lateinit var instance: JkapkApplication
            private set
    }
}

class ApkStreamProvider : android.content.ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun resolveApkFile(uri: Uri): File {
        val name = uri.lastPathSegment?.takeIf { it.isNotBlank() && !it.contains("..") }
            ?: throw IllegalArgumentException("URI de APK inválida: $uri")
        val ctx = context ?: throw IllegalStateException("Context no disponible")
        val staged = File(File(ctx.cacheDir, "apk-install"), name)
        require(staged.exists() && staged.isFile) { "APK no encontrado en staging: ${staged.absolutePath}" }
        return staged
    }

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
        val file = resolveApkFile(uri)
        return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): android.database.Cursor? {
        val file = resolveApkFile(uri)
        val cols = projection ?: arrayOf(
            android.provider.OpenableColumns.DISPLAY_NAME,
            android.provider.OpenableColumns.SIZE
        )
        val cursor = android.database.MatrixCursor(cols, 1)
        val row = cols.map { col ->
            when (col) {
                android.provider.OpenableColumns.DISPLAY_NAME -> file.name
                android.provider.OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"
    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
