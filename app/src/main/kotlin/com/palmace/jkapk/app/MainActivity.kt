package com.palmace.jkapk.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.palmace.jkapk.app.build.BuildService
import com.palmace.jkapk.app.domain.ProjectLanguage
import com.palmace.jkapk.app.domain.ProjectWorkspace
import com.palmace.jkapk.app.ui.editor.CodeEditorController
import com.palmace.jkapk.engine.BuildDiagnostic
import com.palmace.jkapk.engine.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var editorJob: Job? = null
    private var buildJob: Job? = null

    private val app: JkapkApplication
        get() = application as JkapkApplication

    private val editorController = CodeEditorController()
    private var activeWorkspace: ProjectWorkspace? = null
    private var boundService: BuildService? = null
    private var isBound: Boolean = false

    private lateinit var tvProjectsRoot: TextView
    private lateinit var etProjectName: EditText
    private lateinit var etPackageName: EditText
    private lateinit var btnCreateKotlin: Button
    private lateinit var btnCreateJava: Button
    private lateinit var tvActiveProject: TextView
    private lateinit var btnPickFile: Button
    private lateinit var btnSaveFile: Button
    private lateinit var tvActiveFile: TextView
    private lateinit var etCodeEditor: EditText
    private lateinit var btnBuild: Button
    private lateinit var btnCancel: Button
    private lateinit var btnInstallApk: Button
    private lateinit var tvBuildStatus: TextView
    private lateinit var progressBuild: ProgressBar
    private lateinit var containerDiagnostics: LinearLayout
    private lateinit var tvLogs: TextView

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        observeEditorState()
        observeBuildState()

        tvProjectsRoot.text = "Directorio: ${app.projectsDirectory.absolutePath}"
        val existing = app.projectScanner.discoverProjects(app.projectsDirectory).firstOrNull()
        if (existing != null) {
            selectProject(existing)
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, BuildService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        editorJob?.cancel()
        buildJob?.cancel()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        tvProjectsRoot = findViewById(R.id.tvProjectsRoot)
        etProjectName = findViewById(R.id.etProjectName)
        etPackageName = findViewById(R.id.etPackageName)
        btnCreateKotlin = findViewById(R.id.btnCreateKotlin)
        btnCreateJava = findViewById(R.id.btnCreateJava)
        tvActiveProject = findViewById(R.id.tvActiveProject)
        btnPickFile = findViewById(R.id.btnPickFile)
        btnSaveFile = findViewById(R.id.btnSaveFile)
        tvActiveFile = findViewById(R.id.tvActiveFile)
        etCodeEditor = findViewById(R.id.etCodeEditor)
        btnBuild = findViewById(R.id.btnBuild)
        btnCancel = findViewById(R.id.btnCancel)
        btnInstallApk = findViewById(R.id.btnInstallApk)
        tvBuildStatus = findViewById(R.id.tvBuildStatus)
        progressBuild = findViewById(R.id.progressBuild)
        containerDiagnostics = findViewById(R.id.containerDiagnostics)
        tvLogs = findViewById(R.id.tvLogs)
    }

    private fun setupListeners() {
        btnCreateKotlin.setOnClickListener { createNewProject(ProjectLanguage.KOTLIN) }
        btnCreateJava.setOnClickListener { createNewProject(ProjectLanguage.JAVA) }

        btnPickFile.setOnClickListener {
            val ws = activeWorkspace
            if (ws == null) {
                Toast.makeText(this, "Selecciona o crea un proyecto primero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showProjectFilesDialog(ws)
        }

        btnSaveFile.setOnClickListener {
            val ws = activeWorkspace ?: return@setOnClickListener
            val currentText = etCodeEditor.text?.toString().orEmpty()
            val cursorPos = etCodeEditor.selectionStart.coerceAtLeast(0)
            editorController.onTextChanged(currentText, cursorPos)
            editorController.saveActiveTab(ws)
            Toast.makeText(this, "Archivo guardado", Toast.LENGTH_SHORT).show()
        }

        btnBuild.setOnClickListener {
            val ws = activeWorkspace
            if (ws == null) {
                Toast.makeText(this, "Crea o selecciona un proyecto primero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val currentText = etCodeEditor.text?.toString().orEmpty()
            val cursorPos = etCodeEditor.selectionStart.coerceAtLeast(0)
            editorController.onTextChanged(currentText, cursorPos)
            editorController.saveActiveTab(ws)

            val serviceIntent = Intent(this, BuildService::class.java).apply {
                action = BuildService.ACTION_START_BUILD
                putExtra(BuildService.EXTRA_PROJECT_NAME, ws.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }

        btnCancel.setOnClickListener {
            app.buildSessionController.cancelBuild()
        }

        btnInstallApk.setOnClickListener {
            val apkFile = app.buildSessionController.uiState.value.outputApk
            if (apkFile == null || !apkFile.exists()) {
                Toast.makeText(this, "No hay un APK compilado disponible", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runCatching {
                val installIntent = app.createInstallApkIntent(this, apkFile)
                startActivity(installIntent)
            }.onFailure { err ->
                Toast.makeText(
                    this,
                    "Error al preparar instalación: ${err.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun createNewProject(language: ProjectLanguage) {
        val rawName = etProjectName.text?.toString()?.trim().orEmpty().ifEmpty { "DemoApp" }
        val rawPkg = etPackageName.text?.toString()?.trim().orEmpty().ifEmpty { "com.palmace.demo" }

        runCatching {
            val workspace = app.templateGenerator.createProject(
                parentDir = app.projectsDirectory,
                projectName = rawName,
                packageName = rawPkg,
                language = language
            )
            selectProject(workspace)
            Toast.makeText(
                this,
                "Proyecto '${workspace.name}' creado en ${app.projectsDirectory.name}",
                Toast.LENGTH_SHORT
            ).show()
        }.onFailure { err ->
            Toast.makeText(
                this,
                err.message ?: "No se pudo crear el proyecto",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun selectProject(workspace: ProjectWorkspace) {
        activeWorkspace = workspace
        tvActiveProject.text = "Proyecto activo: ${workspace.name} (${workspace.packageName})"
        val entries = app.projectScanner.listSourceAndResFiles(workspace)
        val defaultEntry = entries.firstOrNull { it.extension == "kt" || it.extension == "java" }
            ?: entries.firstOrNull()
        if (defaultEntry != null) {
            editorController.openFile(workspace, defaultEntry.file)
        }
    }

    private fun showProjectFilesDialog(workspace: ProjectWorkspace) {
        val entries = app.projectScanner.listSourceAndResFiles(workspace)
        if (entries.isEmpty()) {
            Toast.makeText(this, "No se encontraron archivos en el proyecto", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { it.relativePath }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Archivos del proyecto")
            .setItems(labels) { _, which ->
                val chosen = entries.getOrNull(which) ?: return@setItems
                editorController.openFile(workspace, chosen.file)
            }
            .show()
    }

    private fun observeEditorState() {
        editorJob?.cancel()
        editorJob = uiScope.launch {
            editorController.uiState.collect { state ->
                runOnUiThread {
                val activeTab = state.activeTab
                if (activeTab == null) {
                    tvActiveFile.text = "Archivo: ninguno"
                    btnSaveFile.isEnabled = false
                } else {
                    val dirtyMark = if (activeTab.isDirty) " *" else ""
                    tvActiveFile.text = "Archivo: ${activeTab.relativePath}$dirtyMark"
                    btnSaveFile.isEnabled = activeTab.isDirty
                    if (etCodeEditor.text?.toString() != activeTab.content) {
                        etCodeEditor.setText(activeTab.content)
                        val safePos = activeTab.cursorIndex.coerceIn(0, etCodeEditor.text?.length ?: 0)
                        etCodeEditor.setSelection(safePos)
                    }
                }

                val jump = state.pendingJump
                if (jump != null) {
                    val currentText = etCodeEditor.text?.toString().orEmpty()
                    val targetOffset = jump.charOffset.coerceIn(0, currentText.length)
                    etCodeEditor.requestFocus()
                    etCodeEditor.setSelection(targetOffset)
                    Toast.makeText(
                        this@MainActivity,
                        "Saltó a ${jump.relativePath}:${jump.line}:${jump.column}",
                        Toast.LENGTH_SHORT
                    ).show()
                    editorController.consumePendingJump()
                }
                }
            }
        }
    }

    private fun observeBuildState() {
        buildJob?.cancel()
        buildJob = uiScope.launch {
            app.buildSessionController.uiState.collect { state ->
                runOnUiThread {
                tvBuildStatus.text = "Estado: ${state.statusBanner}"
                progressBuild.max = state.totalStages.coerceAtLeast(1)
                progressBuild.progress = state.stageIndex.coerceAtLeast(0)

                btnBuild.isEnabled = !state.isBuilding
                btnCancel.isEnabled = state.isBuilding

                val apk = state.outputApk
                btnInstallApk.isEnabled = !state.isBuilding && apk != null && apk.exists()

                val ws = activeWorkspace
                if (ws != null) {
                    editorController.applyDiagnostics(ws, state.diagnostics)
                }

                renderDiagnosticsList(state.diagnostics)
                tvLogs.text = state.logs.takeLast(35).joinToString("\n")
                }
            }
        }
    }

    private fun renderDiagnosticsList(diagnostics: List<BuildDiagnostic>) {
        containerDiagnostics.removeAllViews()
        if (diagnostics.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "Sin diagnósticos"
            }
            containerDiagnostics.addView(emptyView)
            return
        }

        for (diag in diagnostics) {
            val sev = when (diag.severity) {
                Severity.ERROR -> "ERROR"
                Severity.WARNING -> "WARN"
                Severity.INFO -> "INFO"
            }
            val src = diag.source
            val loc = if (src != null) {
                "${src.relativePath}:${src.line ?: 1}:${src.column ?: 1}"
            } else {
                diag.stage.name
            }
            val itemView = TextView(this).apply {
                text = "[$sev] $loc — ${diag.message}"
                setPadding(0, 6, 0, 6)
                setOnClickListener {
                    val ws = activeWorkspace ?: return@setOnClickListener
                    editorController.jumpToDiagnostic(ws, diag)
                }
            }
            containerDiagnostics.addView(itemView)
        }
    }
}
