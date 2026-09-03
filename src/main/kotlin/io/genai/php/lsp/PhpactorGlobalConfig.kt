package io.genai.php.lsp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Maintains Phpactor's GLOBAL config file (`~/.config/phpactor/phpactor.json`).
 *
 * Why a global file and not just LSP initializationOptions: Phpactor's diagnostics pipeline
 * runs in a BASE container that never sees initializationOptions — it reads config FILES.
 * Without this file, the base container uses defaults: a symlink-blind scanner, the default
 * (unshared) index path and no file-size headroom, which is exactly why non-Composer projects
 * (swoole_system-style) got flooded with false `Class "X" not found` diagnostics — the ones
 * that also painted tabs and the project tree red via WolfTheProblemSolver.
 *
 * The settings here point every Phpactor process (CLI, base container, language server) at
 * the plugin-managed STATIC index and make the scanner follow symlinks. The language server
 * session itself overrides `indexer.index_path` to a private LIVE copy (see
 * PhpactorConnectionProvider) because a running session rewrites index records and would
 * corrupt the static index.
 */
object PhpactorGlobalConfig {

    private val LOG = Logger.getInstance(PhpactorGlobalConfig::class.java)
    private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

    fun configPath(): Path =
        Path.of(System.getProperty("user.home"), ".config", "phpactor", "phpactor.json")

    /**
     * Ensures the global config contains our indexer settings, preserving any other keys the
     * user may have set. Rewrites only when something actually changed.
     */
    fun ensure(indexSchemaVersion: Int) {
        runCatching {
            val path = configPath()
            Files.createDirectories(path.parent)
            val existing = if (Files.isRegularFile(path)) runCatching {
                JsonParser.parseString(Files.readString(path)).asJsonObject
            }.getOrNull() ?: JsonObject() else JsonObject()

            val wanted = JsonObject().apply {
                addProperty("indexer.follow_symlinks", true)
                addProperty("indexer.index_path", "%cache%/index/%project_id%-static-v$indexSchemaVersion")
                addProperty("indexer.max_filesize_to_index", PhpactorConnectionProvider.MAX_FILESIZE_TO_INDEX)
            }

            var changed = false
            for ((key, value) in wanted.entrySet()) {
                if (existing.get(key) != value) {
                    existing.add(key, value)
                    changed = true
                }
            }
            if (changed) {
                Files.writeString(path, GSON.toJson(existing))
                LOG.info("php-portable: updated ${path}")
            }
        }.onFailure { LOG.warn("php-portable: could not update global Phpactor config", it) }
    }
}
