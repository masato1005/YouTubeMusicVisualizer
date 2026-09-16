package dev.musicvisualizer.browser

import dev.musicvisualizer.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class ChromeLaunchMode { CONTROLLED, LOGIN }

internal fun buildChromeArguments(
    executable: Path,
    profileDirectory: Path,
    minimized: Boolean,
    mode: ChromeLaunchMode,
): List<String> = buildList {
    add(executable.toString())
    add("--user-data-dir=${profileDirectory.toAbsolutePath()}")
    when (mode) {
        ChromeLaunchMode.CONTROLLED -> {
            add("--app=https://music.youtube.com/")
            add("--remote-debugging-port=0")
            add("--disable-features=Translate")
        }
        ChromeLaunchMode.LOGIN -> {
            add("--new-window")
            add("https://music.youtube.com/")
        }
    }
    if (minimized) add("--start-minimized")
}

class ChromeController(
    private val profileDirectory: Path = SettingsRepository.defaultDataDirectory().resolve("chrome-profile"),
) {
    @Volatile
    private var launchedProcess: Process? = null

    suspend fun ensureRunning(minimized: Boolean): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            findManagedProcess()?.let { existing ->
                if (devToolsPort() != null) return@runCatching existing.pid()
                closeManagedProcess()
            }
            launch(minimized, ChromeLaunchMode.CONTROLLED)
        }
    }

    suspend fun openForLogin(minimized: Boolean): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            closeManagedProcess()
            launch(minimized, ChromeLaunchMode.LOGIN)
        }
    }

    suspend fun restartControlled(minimized: Boolean): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            closeManagedProcess()
            launch(minimized, ChromeLaunchMode.CONTROLLED)
        }
    }

    fun isLoginSetupComplete(): Boolean = Files.isRegularFile(loginMarkerPath())

    fun markLoginSetupComplete() {
        Files.createDirectories(profileDirectory)
        Files.writeString(loginMarkerPath(), "Login completed by the user.\n")
    }

    fun findManagedProcess(): ProcessHandle? {
        val profile = profileDirectory.toAbsolutePath().normalize().toString().lowercase()
        return ProcessHandle.allProcesses()
            .filter { process ->
                val commandLine = process.info().commandLine().orElse("").lowercase()
                commandLine.contains("chrome.exe") &&
                    commandLine.contains("--user-data-dir=") &&
                    commandLine.contains(profile)
            }
            .min(Comparator.comparingLong { it.info().startInstant().orElse(java.time.Instant.MAX).toEpochMilli() })
            .orElse(null)
    }

    suspend fun closeManagedProcess() = withContext(Dispatchers.IO) {
        val root = findManagedProcess() ?: return@withContext
        root.descendants().forEach { it.destroy() }
        root.destroy()
        root.onExit().get(2, TimeUnit.SECONDS)
    }

    fun profilePath(): Path = profileDirectory

    fun devToolsPort(): Int? {
        val file = profileDirectory.resolve("DevToolsActivePort")
        if (!Files.isRegularFile(file)) return null
        return runCatching { Files.readAllLines(file).firstOrNull()?.trim()?.toInt() }.getOrNull()
    }

    private fun findChromeExecutable(): Path? {
        val candidates = buildList {
            System.getenv("PROGRAMFILES")?.let { add(Path.of(it, "Google", "Chrome", "Application", "chrome.exe")) }
            System.getenv("PROGRAMFILES(X86)")?.let { add(Path.of(it, "Google", "Chrome", "Application", "chrome.exe")) }
            System.getenv("LOCALAPPDATA")?.let { add(Path.of(it, "Google", "Chrome", "Application", "chrome.exe")) }
        }
        return candidates.firstOrNull(Files::isRegularFile)
    }

    private fun launch(minimized: Boolean, mode: ChromeLaunchMode): Long {
        val chrome = findChromeExecutable()
            ?: error("Google Chrome が見つかりません。設定でインストール先を確認してください。")
        Files.createDirectories(profileDirectory)
        return ProcessBuilder(buildChromeArguments(chrome, profileDirectory, minimized, mode))
            .start()
            .also { launchedProcess = it }
            .pid()
    }

    private fun loginMarkerPath(): Path = profileDirectory.resolve(".login-setup-complete")
}
