package com.mrboomdev.kotnya

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey
import com.mrboomdev.kotnya.compiler.AndroidSdk
import com.mrboomdev.kotnya.compiler.CompileRequest
import com.mrboomdev.kotnya.compiler.CompilerEvent
import com.mrboomdev.kotnya.compiler.CompilerModule
import com.mrboomdev.kotnya.compiler.DEFAULT_TOOLCHAIN_VERSION
import com.mrboomdev.kotnya.compiler.KeystoreRequest
import com.mrboomdev.kotnya.compiler.KotlinToolchain
import com.mrboomdev.kotnya.compiler.NewProject
import com.mrboomdev.kotnya.compiler.PackageEvent
import com.mrboomdev.kotnya.compiler.ProjectHistory
import com.mrboomdev.kotnya.compiler.ProjectTemplate
import com.mrboomdev.kotnya.compiler.SdkCatalog
import com.mrboomdev.kotnya.compiler.SdkEvent
import com.mrboomdev.kotnya.compiler.SdkPackage
import com.mrboomdev.kotnya.compiler.SdkPackages
import com.mrboomdev.kotnya.compiler.SigningConfig
import com.mrboomdev.kotnya.compiler.TermuxBootstrapRelease
import com.mrboomdev.kotnya.compiler.ToolchainEvent
import com.mrboomdev.kotnya.terminal.BootstrapSource
import com.mrboomdev.kotnya.terminal.ShellEvent
import com.mrboomdev.kotnya.terminal.TermuxBootstrap
import com.mrboomdev.kotnya.terminal.TermuxSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The screens of the first-run setup. The first three components are required by the IDE. */
enum class OnboardingStep { Welcome, Termux, Java, Toolchain, Ready }

data class ProjectInfo(
    val name: String,
    val directory: File,
    val lastOpenedAt: Long? = null,
)

data class EditorTab(val file: File, val text: String, val savedText: String) {
    val name: String get() = file.name
    val isDirty: Boolean get() = text != savedText
}

enum class BuildStatus { Idle, Running, Succeeded, Failed }

enum class BuildVariant(val title: String, val description: String) {
    Debug("Debug", "Build an APK and install it on this device."),
    Release("Release", "Build an APK signed with your own release keystore."),
}

/**
 * Holds the IDE state shared by all screens: projects on disk, open editor tabs, the terminal
 * session, build output, SDK setup and settings. State lives in Compose snapshot state, so screens
 * only read from it.
 */
class IdeController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val compilerModule = CompilerModule(appContext)
    val workspace = File(compilerModule.terminal.bootstrap.homeDir, "projects")

    private val history = ProjectHistory(workspace)

    /** The Navigation 3 back stack. The root is always the project list. */
    val backStack = mutableStateListOf<NavKey>(ProjectsRoute)

    val currentRoute: NavKey? get() = backStack.lastOrNull()

    var projects by mutableStateOf<List<ProjectInfo>>(emptyList())
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    // Setup gate.
    var setupChecked by mutableStateOf(false)
        private set

    var environmentReady by mutableStateOf(false)
        private set

    // New project form.
    var projectName by mutableStateOf("")
    var packageName by mutableStateOf("com.example.hello")
    var template by mutableStateOf(ProjectTemplate.JvmConsole)

    // Editor.
    var tabs by mutableStateOf<List<EditorTab>>(emptyList())
        private set

    var activeTabIndex by mutableStateOf(0)
        private set

    /** Bumped after file-system changes so the file tree reloads. */
    var filesRevision by mutableStateOf(0)
        private set

    var buildStatus by mutableStateOf(BuildStatus.Idle)
        private set

    var buildLog by mutableStateOf<List<String>>(emptyList())
        private set

    var buildVariant by mutableStateOf(BuildVariant.Debug)

    // Release signing.
    var signingConfig by mutableStateOf<SigningConfig?>(null)
        private set

    var signedApkPath by mutableStateOf<String?>(null)
        private set

    var showKeystoreDialog by mutableStateOf(false)
        private set

    var keystoreName by mutableStateOf("release.keystore")
    var keystoreAlias by mutableStateOf("release")
    var keystorePassword by mutableStateOf("")
    var keystorePasswordConfirmation by mutableStateOf("")
    var keystoreValidityYears by mutableStateOf("30")

    var keystoreBusy by mutableStateOf(false)
        private set

    // Terminal.
    var terminalSession by mutableStateOf<TermuxSession?>(null)
        private set

    var terminalError by mutableStateOf<String?>(null)
        private set

    // SDK setup.
    var androidSdk by mutableStateOf<AndroidSdk?>(null)
        private set

    // First-run setup.
    var onboardingStep by mutableStateOf(OnboardingStep.Welcome.ordinal)
        private set

    var onboardingDismissed by mutableStateOf(false)
        private set

    // Version catalogue, fetched from the vendors' own indexes so nothing has to be typed in.
    var bootstrapReleases by mutableStateOf<List<TermuxBootstrapRelease>>(emptyList())
        private set

    var sdkPackages by mutableStateOf<SdkPackages?>(null)
        private set

    var catalogLoading by mutableStateOf(false)
        private set

    var catalogError by mutableStateOf<String?>(null)
        private set

    var toolchainVersion by mutableStateOf(DEFAULT_TOOLCHAIN_VERSION)

    var busyMessage by mutableStateOf<String?>(null)
        private set

    var installedToolchains by mutableStateOf<List<KotlinToolchain>>(emptyList())
        private set

    var javaRuntimeAvailable by mutableStateOf(false)
        private set

    /**
     * Snapshot of [TermuxBootstrap.isInstalled]. The bootstrap reads the file system directly, so it
     * is not observable; without this, setup screens never re-enable their buttons after installing.
     */
    var bootstrapInstalled by mutableStateOf(false)
        private set

    // Editor settings.
    var editorFontSize by mutableStateOf(13f)

    private var buildJob: Job? = null
    private var terminalProject: File? = null

    val bootstrap: TermuxBootstrap get() = compilerModule.terminal.bootstrap
    val isBuilding: Boolean get() = buildStatus == BuildStatus.Running

    var currentProject by mutableStateOf<ProjectInfo?>(null)
        private set

    val deviceAbi: String get() = SdkCatalog.deviceAbi

    val installedBootstrapVersion: String? get() = bootstrap.installedVersion

    val currentOnboardingStep: OnboardingStep
        get() = OnboardingStep.entries.getOrElse(onboardingStep) { OnboardingStep.Welcome }

    /** The newest bootstrap archive published for this device's ABI. */
    val recommendedBootstrap: TermuxBootstrapRelease?
        get() = bootstrapReleases.firstOrNull { it.abi == deviceAbi } ?: bootstrapReleases.firstOrNull()

    /** The command-line tools only need the bootstrap and a Java runtime. */
    val canInstallCommandLineTools: Boolean
        get() = bootstrapInstalled && javaRuntimeAvailable && busyMessage == null

    /** sdkmanager can only be driven once the bootstrap, Java and the command-line tools exist. */
    val canInstallSdkPackages: Boolean
        get() = canInstallCommandLineTools && androidSdk?.commandLineTools != null

    init {
        refreshProjects()
        refreshSetup()
        loadCatalog()

        scope.launch {
            InstallStatusBus.messages.collect(::appendLog)
        }
    }

    // ---------------------------------------------------------------- navigation

    fun startNewProject() {
        projectName = ""
        packageName = "com.example.hello"
        template = ProjectTemplate.JvmConsole
        navigate(NewProjectRoute)
    }

    fun openSettings() {
        navigate(SettingsRoute)
    }

    fun openSdkDashboard() {
        navigate(SdkDashboardRoute)
        loadCatalog()
        refreshSetup()
    }

    fun nextOnboardingStep() {
        if (onboardingStep < OnboardingStep.entries.lastIndex) onboardingStep++
    }

    fun previousOnboardingStep() {
        if (onboardingStep > 0) onboardingStep--
    }

    /** Leaves the first-run setup once the required components are installed. */
    fun dismissOnboarding() {
        onboardingDismissed = true
        backStack.clear()
        backStack.add(ProjectsRoute)
    }

    /** Pops the current route. Returns false when the back stack is already at its root. */
    fun back(): Boolean {
        if (backStack.size <= 1) return false

        val removed = backStack.removeLastOrNull()
        if (removed is EditorRoute) closeEditorState()
        return true
    }

    private fun navigate(route: NavKey) {
        if (backStack.lastOrNull() == route) return
        backStack.add(route)
    }

    fun clearError() {
        errorMessage = null
    }

    // ---------------------------------------------------------------- projects

    fun refreshProjects() {
        workspace.mkdirs()

        projects = history.entries().map {
            ProjectInfo(name = it.name, directory = it.directory, lastOpenedAt = it.lastOpenedAt)
        }
    }

    fun createProject() {
        val name = projectName.trim()
        val packageName = packageName.trim()

        if (name.isEmpty()) {
            errorMessage = "Enter a project name."
            return
        }

        if (File(workspace, name).exists()) {
            errorMessage = "A project called '$name' already exists."
            return
        }

        scope.launch {
            try {
                val directory = withContext(Dispatchers.IO) {
                    compilerModule.projects.generate(
                        NewProject(
                            directory = File(workspace, name),
                            name = name,
                            packageName = packageName,
                            template = template,
                        )
                    )
                }

                // The editor replaces the creation form instead of stacking on top of it.
                if (backStack.lastOrNull() is NewProjectRoute) backStack.removeLastOrNull()
                refreshProjects()
                openProject(ProjectInfo(name, directory))
            } catch (e: Exception) {
                errorMessage = e.message ?: "Could not create the project."
            }
        }
    }

    /** Renames the project directory and returns whether the rename succeeded. */
    fun renameProject(project: ProjectInfo, newName: String): Boolean {
        val name = newName.trim()

        if (!PROJECT_NAME.matches(name)) {
            errorMessage = "Use letters, digits, '-' and '_' for the project name, starting with a letter."
            return false
        }

        if (name == project.name) return true

        val target = File(workspace, name)
        if (target.exists()) {
            errorMessage = "A project called '$name' already exists."
            return false
        }

        if (!project.directory.renameTo(target)) {
            errorMessage = "Could not rename '${project.name}'."
            return false
        }

        history.rename(project.directory, target)
        refreshProjects()
        return true
    }

    fun deleteProject(project: ProjectInfo) {
        if (!project.directory.deleteRecursively()) {
            errorMessage = "Could not delete '${project.name}'."
            return
        }

        history.forget(project.directory)
        refreshProjects()
    }

    fun openProject(project: ProjectInfo) {
        history.markOpened(project.directory)
        signingConfig = SigningConfig.load(project.directory)
        signedApkPath = null
        tabs = emptyList()
        activeTabIndex = 0
        buildLog = emptyList()
        buildStatus = BuildStatus.Idle
        currentProject = project
        navigate(EditorRoute(project.directory.absolutePath))
        openTerminal(project.directory)
        refreshProjects()
    }

    fun closeEditor() {
        closeEditorState()
        if (backStack.lastOrNull() is EditorRoute) backStack.removeLastOrNull()
        refreshProjects()
    }

    private fun closeEditorState() {
        buildJob?.cancel()
        buildJob = null
        closeTerminal()
        tabs = emptyList()
        activeTabIndex = 0
        buildLog = emptyList()
        buildStatus = BuildStatus.Idle
        signingConfig = null
        signedApkPath = null
        currentProject = null
    }

    /** The project behind an editor route, or null when it no longer exists in the workspace. */
    fun projectFor(route: EditorRoute): ProjectInfo? =
        projects.firstOrNull { it.directory.absolutePath == route.projectPath }

    // ---------------------------------------------------------------- editor

    fun openFile(file: File) {
        val existing = tabs.indexOfFirst { it.file == file }
        if (existing >= 0) {
            activeTabIndex = existing
            return
        }

        if (file.length() > MAX_EDITABLE_FILE_SIZE) {
            errorMessage = "${file.name} is too large to open in the editor."
            return
        }

        val text = try {
            file.readText()
        } catch (e: Exception) {
            errorMessage = "Could not open ${file.name}: ${e.message}"
            return
        }

        tabs = tabs + EditorTab(file = file, text = text, savedText = text)
        activeTabIndex = tabs.lastIndex
    }

    fun selectTab(index: Int) {
        if (index in tabs.indices) activeTabIndex = index
    }

    /** Applies an edit to the tab showing [file], even if another tab became active meanwhile. */
    fun updateTabText(file: File, text: String) {
        val index = tabs.indexOfFirst { it.file == file }
        if (index < 0) return

        val tab = tabs[index]
        if (tab.text == text) return

        tabs = tabs.toMutableList().apply { set(index, tab.copy(text = text)) }
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return

        val wasActive = index == activeTabIndex
        val remaining = tabs.toMutableList().apply { removeAt(index) }

        activeTabIndex = when {
            remaining.isEmpty() -> 0
            wasActive -> index.coerceAtMost(remaining.lastIndex)
            index < activeTabIndex -> activeTabIndex - 1
            else -> activeTabIndex
        }

        tabs = remaining
    }

    /** Moves a tab to another position, keeping the selection on the same file. */
    fun moveTab(from: Int, to: Int) {
        if (from !in tabs.indices || to !in tabs.indices || from == to) return

        val activeFile = tabs.getOrNull(activeTabIndex)?.file
        val reordered = tabs.toMutableList().apply { add(to, removeAt(from)) }

        tabs = reordered
        activeTabIndex = reordered.indexOfFirst { it.file == activeFile }.coerceAtLeast(0)
    }

    // ---------------------------------------------------------------- project files

    fun refreshFiles() {
        filesRevision++
    }

    /** Creates an empty file at [relativePath] inside the project and opens it. */
    fun createFile(project: ProjectInfo, relativePath: String): Boolean {
        val path = relativePath.trim().replace('\\', '/')
        if (path.isEmpty()) {
            errorMessage = "Enter a file path."
            return false
        }

        val target = resolveInside(project.directory, path)
        if (target == null) {
            errorMessage = "The file has to stay inside the project."
            return false
        }

        if (target.exists()) {
            errorMessage = "${target.name} already exists."
            return false
        }

        try {
            target.parentFile?.mkdirs()
            if (!target.createNewFile()) {
                errorMessage = "Could not create ${target.name}."
                return false
            }
        } catch (e: Exception) {
            errorMessage = "Could not create ${target.name}: ${e.message}"
            return false
        }

        refreshFiles()
        openFile(target)
        return true
    }

    /** Renames a file or directory and keeps its open tabs pointing at the new location. */
    fun renameFile(file: File, newName: String): Boolean {
        val name = newName.trim()

        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name == "." || name == "..") {
            errorMessage = "Enter a file name without directory separators."
            return false
        }

        if (name == file.name) return true

        val target = File(file.parentFile, name)
        if (target.exists()) {
            errorMessage = "${target.name} already exists."
            return false
        }

        val wasDirectory = file.isDirectory
        if (!file.renameTo(target)) {
            errorMessage = "Could not rename ${file.name}."
            return false
        }

        tabs = tabs.map { tab ->
            when {
                tab.file == file -> tab.copy(file = target)
                wasDirectory && tab.file.path.startsWith(file.path + File.separator) ->
                    tab.copy(file = File(target, tab.file.relativeTo(file).path))
                else -> tab
            }
        }

        refreshFiles()
        return true
    }

    fun deleteFile(file: File) {
        val activeFile = tabs.getOrNull(activeTabIndex)?.file
        val remaining = tabs.filterNot { tab ->
            tab.file == file || tab.file.path.startsWith(file.path + File.separator)
        }

        if (!file.deleteRecursively()) {
            errorMessage = "Could not delete ${file.name}."
            return
        }

        tabs = remaining
        activeTabIndex = remaining.indexOfFirst { it.file == activeFile }
            .takeIf { it >= 0 }
            ?: remaining.lastIndex.coerceAtLeast(0)

        refreshFiles()
    }

    private fun resolveInside(root: File, relativePath: String): File? {
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath).canonicalFile

        return target.takeIf { it.path.startsWith(canonicalRoot.path + File.separator) }
    }

    /** Writes dirty tabs back to disk. Returns false when a write failed. */
    fun saveAll(): Boolean {
        var saved = true

        tabs = tabs.map { tab ->
            if (!tab.isDirty) return@map tab

            try {
                tab.file.writeText(tab.text)
                tab.copy(savedText = tab.text)
            } catch (e: Exception) {
                errorMessage = "Could not save ${tab.file.name}: ${e.message}"
                saved = false
                tab
            }
        }

        return saved
    }

    // ---------------------------------------------------------------- build

    fun build() = runTask(task = "build")

    fun install() = runTask(task = "build", installApkAfterwards = true)

    /** Builds the project and signs the produced APK with the project's release keystore. */
    fun signApk() {
        if (signingConfig == null) {
            openKeystoreDialog()
            errorMessage = "Generate a keystore before signing a release APK."
            return
        }

        runTask(task = "build", signApkAfterwards = true)
    }

    private fun runTask(
        task: String,
        installApkAfterwards: Boolean = false,
        signApkAfterwards: Boolean = false,
    ) {
        val project = currentProject ?: return
        if (isBuilding) return
        if (!saveAll()) return

        if (!bootstrap.isInstalled) {
            errorMessage = "Install the Termux bootstrap in the SDK manager first."
            return
        }

        val toolchain = compilerModule.toolchains.find(toolchainVersion)
        if (!toolchain.isInstalled) {
            errorMessage = "Kotlin Toolchain $toolchainVersion is not installed. Install it in the SDK manager."
            return
        }

        buildLog = emptyList()
        buildStatus = BuildStatus.Running

        buildJob = scope.launch {
            try {
                compilerModule.compiler.compile(
                    CompileRequest(
                        projectDirectory = project.directory,
                        toolchain = toolchain,
                        tasks = listOf(task),
                    )
                ).collect { event ->
                    when (event) {
                        is CompilerEvent.Started -> appendLog("$ ${event.command}")

                        is CompilerEvent.Output -> appendLog(event.line)

                        is CompilerEvent.Finished -> {
                            buildStatus = if (event.isSuccess) BuildStatus.Succeeded else BuildStatus.Failed
                            appendLog(
                                if (event.isSuccess) "Finished in ${event.durationMillis} ms"
                                else "Exited with code ${event.exitCode}"
                            )
                        }

                        is CompilerEvent.Failed -> {
                            buildStatus = BuildStatus.Failed
                            appendLog(event.message)
                            event.cause?.message?.let(::appendLog)
                        }
                    }
                }

                if (installApkAfterwards && buildStatus == BuildStatus.Succeeded) {
                    installApk(project.directory)
                }

                if (signApkAfterwards && buildStatus == BuildStatus.Succeeded) {
                    signBuiltApk(project.directory)
                }
            } catch (e: Exception) {
                buildStatus = BuildStatus.Failed
                appendLog("Build failed: ${e.message}")
            }
        }
    }

    private fun installApk(projectDirectory: File) {
        val apk = compilerModule.signer.findApk(projectDirectory)

        if (apk == null) {
            buildStatus = BuildStatus.Failed
            appendLog("No APK was produced. Installing is only available for Android app projects.")
            return
        }

        appendLog("Installing ${apk.name}…")

        try {
            ApkInstaller(appContext).install(apk)
        } catch (e: Exception) {
            buildStatus = BuildStatus.Failed
            appendLog("Install failed: ${e.message}")
        }
    }

    private suspend fun signBuiltApk(projectDirectory: File) {
        val config = signingConfig ?: return
        val apk = compilerModule.signer.findApk(projectDirectory)

        if (apk == null) {
            buildStatus = BuildStatus.Failed
            appendLog("No APK was produced. Signing is only available for Android app projects.")
            return
        }

        appendLog("Signing ${apk.name} with alias '${config.alias}'…")

        try {
            val signed = withContext(Dispatchers.IO) {
                compilerModule.signer.sign(projectDirectory, apk, config)
            }

            signedApkPath = signed.absolutePath
            appendLog("Signed APK: ${signed.absolutePath}")
        } catch (e: Exception) {
            buildStatus = BuildStatus.Failed
            appendLog("Signing failed: ${e.message}")
        }
    }

    private fun appendLog(line: String) {
        buildLog = (buildLog + line).takeLast(MAX_LOG_LINES)
    }

    // ---------------------------------------------------------------- signing

    fun openKeystoreDialog() {
        keystoreName = if (signingConfig == null) "release.keystore" else "release-new.keystore"
        keystoreAlias = signingConfig?.alias ?: "release"
        keystorePassword = ""
        keystorePasswordConfirmation = ""
        keystoreValidityYears = "30"
        showKeystoreDialog = true
    }

    fun dismissKeystoreDialog() {
        if (!keystoreBusy) showKeystoreDialog = false
    }

    fun generateKeystore() {
        val project = currentProject ?: return
        if (keystoreBusy) return

        val password = keystorePassword
        if (password.length < 6) {
            errorMessage = "Use a keystore password of at least 6 characters."
            return
        }

        if (password != keystorePasswordConfirmation) {
            errorMessage = "The passwords do not match."
            return
        }

        val fileName = keystoreName.trim().let { name ->
            when {
                name.isEmpty() -> "release.keystore"
                name.endsWith(".keystore") || name.endsWith(".jks") -> name
                else -> "$name.keystore"
            }
        }

        val alias = keystoreAlias.trim()
        if (alias.isEmpty()) {
            errorMessage = "Enter a key alias."
            return
        }

        val validity = keystoreValidityYears.trim().toIntOrNull()
        if (validity == null || validity !in 1..1000) {
            errorMessage = "Enter the validity in years, between 1 and 1000."
            return
        }

        keystoreBusy = true

        scope.launch {
            try {
                val config = withContext(Dispatchers.IO) {
                    compilerModule.signer.generateKeystore(
                        projectDirectory = project.directory,
                        request = KeystoreRequest(
                            fileName = fileName,
                            alias = alias,
                            password = password,
                            validityYears = validity,
                            commonName = project.name,
                        ),
                    )
                }

                signingConfig = config
                showKeystoreDialog = false
                keystorePassword = ""
                keystorePasswordConfirmation = ""
                appendLog("Generated keystore ${config.keystore.name} with alias '${config.alias}'")
            } catch (e: Exception) {
                errorMessage = e.message ?: "Could not generate the keystore."
            } finally {
                keystoreBusy = false
            }
        }
    }

    // ---------------------------------------------------------------- terminal

    private fun openTerminal(directory: File) {
        closeTerminal()
        terminalProject = directory

        if (!bootstrap.isInstalled) {
            terminalError = "The Termux bootstrap is not installed. Install it in the SDK manager to use the terminal."
            return
        }

        try {
            terminalSession = compilerModule.terminal.openSession(workingDirectory = directory)
        } catch (e: Exception) {
            terminalError = e.message ?: "Could not start the terminal."
        }
    }

    fun retryTerminal() {
        terminalProject?.let(::openTerminal)
    }

    private fun closeTerminal() {
        terminalSession?.close()
        terminalSession = null
        terminalError = null
        terminalProject = null
    }

    // ---------------------------------------------------------------- SDK setup

    /** Re-reads the on-device environment and decides whether the IDE may be entered. */
    fun refreshSetup() {
        scope.launch {
            val sdk = withContext(Dispatchers.IO) { compilerModule.androidSdk.status() }
            val toolchains = withContext(Dispatchers.IO) { compilerModule.toolchains.installed() }
            val javaAvailable = compilerModule.toolchains.hasJavaRuntime()

            androidSdk = sdk
            installedToolchains = toolchains
            javaRuntimeAvailable = javaAvailable
            bootstrapInstalled = bootstrap.isInstalled

            // The Android SDK is optional, so only these three components gate the IDE.
            environmentReady = bootstrapInstalled && javaAvailable && toolchains.isNotEmpty()

            if (!setupChecked) {
                onboardingStep = when {
                    environmentReady -> OnboardingStep.Ready.ordinal
                    !bootstrapInstalled && !javaAvailable && toolchains.isEmpty() ->
                        OnboardingStep.Welcome.ordinal

                    !bootstrapInstalled -> OnboardingStep.Termux.ordinal
                    !javaAvailable -> OnboardingStep.Java.ordinal
                    else -> OnboardingStep.Toolchain.ordinal
                }

                onboardingDismissed = environmentReady
            }

            setupChecked = true
        }
    }

    /** Loads the browseable version lists; nothing here has to be pasted in by the user. */
    fun loadCatalog(force: Boolean = false) {
        if (catalogLoading) return
        if (!force && bootstrapReleases.isNotEmpty() && sdkPackages != null) return

        catalogLoading = true
        catalogError = null

        scope.launch {
            val bootstraps = withContext(Dispatchers.IO) {
                runCatching { compilerModule.catalog.bootstraps() }
            }
            val packages = withContext(Dispatchers.IO) {
                runCatching { compilerModule.catalog.packages() }
            }

            bootstraps.onSuccess { bootstrapReleases = it }
            packages.onSuccess { sdkPackages = it }
            catalogError = (bootstraps.exceptionOrNull() ?: packages.exceptionOrNull())
                ?.message
                ?.takeIf(String::isNotBlank)
            catalogLoading = false
        }
    }

    /** Installs the newest Termux bootstrap published for this device. */
    fun installBootstrap() {
        val release = recommendedBootstrap
        if (release == null) {
            loadCatalog()
            errorMessage = "Still fetching the list of Termux bootstraps. Try again in a moment."
            return
        }

        installBootstrap(release)
    }

    fun installBootstrap(release: TermuxBootstrapRelease) {
        if (busyMessage != null) return

        busyMessage = "Downloading Termux bootstrap ${release.version}…"

        scope.launch {
            try {
                compilerModule.terminal.install(
                    source = BootstrapSource.Url(release.downloadUrl, release.sha256),
                    version = release.version,
                    onProgress = { progress ->
                        busyMessage = "Extracting ${progress.currentEntry ?: "bootstrap"}…"
                    },
                    onDownloadProgress = { downloaded, total ->
                        busyMessage = "Downloading Termux bootstrap: ${formatSize(downloaded, total)}"
                    },
                )
            } catch (e: Exception) {
                errorMessage = "Bootstrap installation failed: ${e.message}"
            } finally {
                // Publish the new environment before clearing the progress message so the setup
                // screen re-enables its Continue button in the same frame.
                bootstrapInstalled = bootstrap.isInstalled
                busyMessage = null
                refreshSetup()
            }
        }
    }

    fun installToolchain() {
        if (busyMessage != null) return

        busyMessage = "Installing Kotlin Toolchain $toolchainVersion…"

        scope.launch {
            compilerModule.toolchains.install(toolchainVersion).collect { event ->
                when (event) {
                    is ToolchainEvent.Downloading -> {
                        busyMessage = "Downloading Kotlin Toolchain: ${formatSize(event.bytesDownloaded, event.totalBytes)}"
                    }

                    is ToolchainEvent.Extracting -> busyMessage = "Extracting Kotlin Toolchain…"

                    is ToolchainEvent.Installed -> busyMessage = null

                    is ToolchainEvent.Failed -> {
                        busyMessage = null
                        errorMessage = event.message + (event.cause?.message?.let { ": $it" } ?: "")
                    }
                }
            }

            refreshSetup()
        }
    }

    fun installJavaRuntime() {
        if (busyMessage != null) return

        busyMessage = "Preparing the Java runtime…"

        scope.launch {
            compilerModule.toolchains.installJavaRuntime().collect { event ->
                when (event) {
                    is PackageEvent.Downloading -> {
                        busyMessage = "Downloading ${event.packageName}: ${formatSize(event.bytes, event.total)}"
                    }

                    is PackageEvent.Extracting -> busyMessage = "Extracting ${event.packageName}…"

                    is PackageEvent.Installed -> Unit

                    is PackageEvent.Failed -> {
                        busyMessage = null
                        errorMessage = "${event.packageName}: ${event.message}" +
                            (event.cause?.message?.let { " ($it)" } ?: "")
                    }
                }
            }

            busyMessage = null
            refreshSetup()
        }
    }

    fun installCommandLineTools(release: SdkPackage) {
        if (busyMessage != null) return

        val url = release.downloadUrl
        if (url == null) {
            errorMessage = "This command-line tools release has no Linux archive."
            return
        }

        busyMessage = "Downloading Android command-line tools…"

        scope.launch {
            compilerModule.androidSdk.installCommandLineTools(url).collect { event ->
                when (event) {
                    is SdkEvent.Downloading -> {
                        busyMessage = "Downloading command-line tools: ${formatSize(event.bytesDownloaded, event.totalBytes)}"
                    }

                    is SdkEvent.Extracting -> busyMessage = "Extracting Android command-line tools…"

                    is SdkEvent.Installed -> {
                        androidSdk = event.sdk
                        busyMessage = null
                    }

                    is SdkEvent.Failed -> {
                        busyMessage = null
                        errorMessage = event.message + (event.cause?.message?.let { ": $it" } ?: "")
                    }
                }
            }

            refreshSetup()
        }
    }

    /** Installs one SDK package chosen in the dashboard, e.g. `platforms;android-36`. */
    fun installSdkPackage(packageId: String, label: String = packageId) {
        if (busyMessage != null) return

        if (!javaRuntimeAvailable) {
            errorMessage = "Install the Java runtime before installing SDK packages."
            return
        }

        if (androidSdk?.commandLineTools == null) {
            errorMessage = "Install the Android command-line tools first."
            return
        }

        busyMessage = "Installing $label…"

        scope.launch {
            compilerModule.androidSdk.installPackage(packageId).collect { event ->
                when (event) {
                    is ShellEvent.Started -> Unit
                    is ShellEvent.Output -> busyMessage = "Installing $label: ${event.line}"
                    is ShellEvent.Finished -> busyMessage = null
                    is ShellEvent.Failed -> {
                        busyMessage = null
                        errorMessage = "Could not install $label: ${event.message}"
                    }
                }
            }

            refreshSetup()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    fun dispose() {
        buildJob?.cancel()
        closeTerminal()
        scope.cancel()
    }

    private fun formatSize(bytes: Long, total: Long?): String {
        val downloaded = "%.1f MB".format(bytes / 1024.0 / 1024.0)
        return if (total != null && total > 0) {
            "$downloaded / ${"%.1f MB".format(total / 1024.0 / 1024.0)}"
        } else {
            downloaded
        }
    }

    private companion object {
        const val MAX_LOG_LINES = 2_000
        const val MAX_EDITABLE_FILE_SIZE = 512L * 1024L
        val PROJECT_NAME = Regex("[A-Za-z][A-Za-z0-9_-]*")
    }
}
