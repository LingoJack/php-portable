package io.genai.php.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
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
            // Also one-time: generate Swoole legacy-alias stubs (swoole_table, Co\*, …) into
            // the extracted stubs, so swoole_system-style projects can navigate/complete them.
            SwooleAliases.ensureGenerated(PhpactorManager.stubsDir())
            setCommands(listOf(php.absolutePath, phar.toString(), "language-server"))
            project.basePath?.let { base ->
                setWorkingDirectory(base)
                warmUpIndex(php, phar, base)
            }
        }
    }

    /**
     * Phpactor's language-server indexer only processes files that change while it runs (file
     * watcher events + opened documents) — on macOS no watcher is available, so a freshly
     * created index would stay mostly empty for a long time and half the project's classes
     * would report "Class not found". The offline `index:build` command does the full scan in
     * seconds and is incremental, so run it once per server start on a background thread.
     */
    private fun warmUpIndex(php: java.io.File, phar: java.nio.file.Path, base: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val cmd = com.intellij.execution.configurations.GeneralCommandLine(
                    php.absolutePath,
                    phar.toString(),
                    "--config-extra=" + indexConfigJson(),
                    "index:build",
                ).withWorkDirectory(java.io.File(base))
                val output = com.intellij.execution.util.ExecUtil.execAndGetOutput(cmd, 120_000)
                LOG.info("php-portable: index:build finished (${output.exitCode}) ${output.stdoutLines.lastOrNull() ?: ""}")
            }.onFailure { LOG.warn("php-portable: index:build failed", it) }
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
     * `indexer.follow_symlinks` on fixes both. `max_filesize_to_index` is raised above the
     * 1 MB default so large generated files (protobuf `pb_proto_*.php` in this stack can be
     * big) are indexed too.
     *
     * The on-disk index is keyed by project root only (`~/.cache/phpactor/index/<root>-<hash>`),
     * so an index poisoned by a previous run (built without the options above) would be reused
     * forever — `isFresh` skips every unchanged file and the missing class records never come
     * back. Versioning the path forces one clean rebuild whenever [INDEX_SCHEMA_VERSION] is
     * bumped; `%cache%` / `%project_id%` are expanded by Phpactor's path resolver.
     */
    override fun getInitializationOptions(rootUri: VirtualFile?): Any? {
        val options = linkedMapOf<String, Any>()
        for ((key, value) in INDEXER_OPTIONS) {
            options["indexer.$key"] = value
        }
        val stubs = PhpactorManager.stubsDir()
        if (Files.isDirectory(stubs)) {
            options["worse_reflection.stub_dir"] = stubs.toString()
            options["indexer.stub_paths"] = listOf(stubs.toString())
        }
        return options
    }

    companion object {
        private val LOG = Logger.getInstance(PhpactorConnectionProvider::class.java)

        /** Bump when the injected Phpactor config changes in a way that invalidates the index. */
        const val INDEX_SCHEMA_VERSION = 4

        /** Above the 1 MB default: keep big generated files (protobuf, config dumps) indexed. */
        const val MAX_FILESIZE_TO_INDEX = 2_000_000

        /** Indexer settings shared by the LSP handshake and the offline warm-up. */
        val INDEXER_OPTIONS: Map<String, Any> = mapOf(
            "follow_symlinks" to true,
            "index_path" to "%cache%/index/%project_id%-$INDEX_SCHEMA_VERSION",
            "max_filesize_to_index" to MAX_FILESIZE_TO_INDEX,
        )

        /** The same options as JSON for Phpactor CLI's `--config-extra`. */
        fun indexConfigJson(): String {
            val inner = INDEXER_OPTIONS.entries.joinToString(",") { (key, value) ->
                val encoded = when (value) {
                    is String -> "\"" + StringUtil.escapeQuotes(value) + "\""
                    else -> value.toString()
                }
                "\"$key\":$encoded"
            }
            return "{\"indexer\":{$inner}}"
        }
    }
}
