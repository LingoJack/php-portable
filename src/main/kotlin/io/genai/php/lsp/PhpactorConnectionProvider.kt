package io.genai.php.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import io.genai.php.sdk.PhpSdkType
import io.genai.php.settings.PhpInterpreterSettings
import java.nio.file.Files

/**
 * Launches Phpactor as `<portable-php> phpactor.phar language-server`, talking LSP over
 * stdio. The interpreter is the plugin's current default portable PHP, so code intelligence
 * reuses the exact runtime the user already runs scripts with — no extra dependency.
 *
 * If no interpreter or PHAR is available the command list is left empty and the server won't
 * start; [PhpClientFeatures.isEnabled] gates this up front so we don't get here in that case.
 */
class PhpactorConnectionProvider(project: Project) : ProcessStreamConnectionProvider() {
    init {
        val php = PhpInterpreterSettings.getInstance().defaultSdk()?.homePath
            ?.let { PhpSdkType.findPhpExecutable(it) }
        val phar = PhpactorManager.pharPath()
        if (php != null && PhpactorManager.isInstalled()) {
            // One-time (~0.7s): unpack phpstorm-stubs to disk so go-to-definition on built-ins
            // opens real files (see getInitializationOptions). No-op once extracted.
            PhpactorManager.ensureStubsExtracted(php)
            setCommands(listOf(php.absolutePath, phar.toString(), "language-server"))
            project.basePath?.let { setWorkingDirectory(it) }
        }
    }

    /**
     * Point Phpactor's stub config at our on-disk phpstorm-stubs (extracted from the phar),
     * replacing the default `phar://…` path. Phpactor merges initializationOptions into its
     * config, so go-to-definition on built-ins (\DateTime, json_encode, …) resolves to real,
     * openable files instead of a sealed phar path.
     *
     * The indexer options are load-bearing for real-world projects: Phpactor's file scanner
     * does NOT follow symlinks by default, so a workspace whose root is a container of
     * symlinked checkouts (very common) indexes ZERO project files — member completion
     * returns nothing and every cross-file class shows as "Class not found". Turning
     * `indexer.follow_symlinks` on fixes both.
     *
     * The on-disk index is keyed by project root only (`~/.cache/phpactor/index/<root>-<hash>`),
     * so an index poisoned by a previous run (built without the options above) would be reused
     * forever — `isFresh` skips every unchanged file and the missing class records never come
     * back. Versioning the path forces one clean rebuild whenever [INDEX_SCHEMA_VERSION] is
     * bumped; `%cache%` / `%project_id%` are expanded by Phpactor's path resolver.
     */
    override fun getInitializationOptions(rootUri: VirtualFile?): Any? {
        val options = linkedMapOf<String, Any>(
            "indexer.follow_symlinks" to true,
            "indexer.index_path" to "%cache%/index/%project_id%-$INDEX_SCHEMA_VERSION",
        )
        val stubs = PhpactorManager.stubsDir()
        if (Files.isDirectory(stubs)) {
            options["worse_reflection.stub_dir"] = stubs.toString()
            options["indexer.stub_paths"] = listOf(stubs.toString())
        }
        return options
    }

    companion object {
        /** Bump when the injected Phpactor config changes in a way that invalidates the index. */
        const val INDEX_SCHEMA_VERSION = 2
    }
}
