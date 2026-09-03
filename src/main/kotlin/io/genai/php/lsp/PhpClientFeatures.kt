package io.genai.php.lsp

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.client.features.LSPCodeActionFeature
import com.redhat.devtools.lsp4ij.client.features.LSPDiagnosticFeature
import com.redhat.devtools.lsp4ij.client.features.LSPDocumentLinkFeature
import io.genai.php.sdk.PhpSdkType
import io.genai.php.settings.PhpInterpreterSettings

/**
 * Gates when the PHP language server is active. LSP4IJ calls [isEnabled] before starting the
 * server for a file, so this is where the "Code intelligence" toggle and the prerequisites
 * (an interpreter is configured, the Phpactor PHAR is present) are enforced. Returning false
 * keeps the server dormant — no process, no indexing.
 *
 * Community-vs-PhpStorm is handled elsewhere for free: LSP4IJ maps this server to our
 * `PhpPortable` file type, which only owns `.php` on IDEs without the official PHP plugin.
 */
class PhpClientFeatures : LSPClientFeatures() {

    private companion object {
        val CLASS_NOT_FOUND = Regex("""Class "([^"]+)" not found""")
    }

    init {
        // Phpactor's document links render as noisy full-line underlines under Cmd+hover in
        // LSP4IJ. They only make include/require paths clickable, which isn't worth the noise —
        // completion, hover and go-to-definition are unaffected.
        setDocumentLinkFeature(object : LSPDocumentLinkFeature() {
            override fun isEnabled(file: PsiFile): Boolean = false
        })
        // Disable code actions. LSP4IJ's lightbulb continuously polls textDocument/codeAction,
        // which with Phpactor triggered a crash/retry storm (Amp ClosedException on a dead
        // process stream) — flooding errors and hanging on "Resolving code actions". Quick-fixes
        // are the least-essential feature here; dropping them keeps completion/nav/hover stable.
        setCodeActionFeature(object : LSPCodeActionFeature() {
            override fun isEnabled(file: PsiFile): Boolean = false
        })
        // Diagnostics tuning for machine-generated files and runtime alias classes:
        //  - protobuf `pb_proto_*.php` output is full of style findings ("missing docblock
        //    return type", …) nobody can act on — turn the whole file's diagnostics off;
        //  - Swoole's legacy aliases (swoole_table, Co\*, …) only exist via runtime
        //    class_alias(); Phpactor's diagnostics pipeline cannot resolve them even with the
        //    generated stubs (SwooleAliases), so drop exactly those "Class X not found"
        //    findings. Navigation/completion for them works via the stubs.
        // The suppression hooks BOTH rendering paths: `isInspectionApplicableFor` covers the
        // Problems view / local inspection, `createAnnotation` covers the in-editor red
        // underlines (LSPDiagnosticsCollector/Applier render highlights directly and never
        // consult the inspection hook).
        setDiagnosticFeature(object : LSPDiagnosticFeature() {
            override fun isSupported(file: PsiFile): Boolean =
                !file.name.startsWith("pb_proto_") && super.isSupported(file)

            override fun isInspectionApplicableFor(
                diagnostic: org.eclipse.lsp4j.Diagnostic,
                inspection: com.intellij.codeInspection.LocalInspectionTool,
            ): Boolean {
                if (isSuppressedClassNotFound(diagnostic)) return false
                return super.isInspectionApplicableFor(diagnostic, inspection)
            }

            override fun createAnnotation(
                diagnostic: org.eclipse.lsp4j.Diagnostic,
                document: com.intellij.openapi.editor.Document,
                fixes: MutableList<com.intellij.codeInsight.intention.IntentionAction>,
                holder: com.intellij.lang.annotation.AnnotationHolder,
            ) {
                if (isSuppressedClassNotFound(diagnostic)) return
                super.createAnnotation(diagnostic, document, fixes, holder)
            }
        })
    }

    /** True for false-positive `Class "X" not found` findings on known-declared classes. */
    private fun isSuppressedClassNotFound(diagnostic: org.eclipse.lsp4j.Diagnostic): Boolean {
        val cls = CLASS_NOT_FOUND.find(diagnostic.message)?.groupValues?.get(1) ?: return false
        // The class is declared in the project (per the generated class map) or is a Swoole
        // runtime alias — navigation/completion resolve it fine; the "not found" is a known
        // false positive of Phpactor's diagnostics pipeline on non-Composer layouts.
        return cls in SwooleAliases.knownAliases() || cls in ProjectClassmap.knownClasses()
    }

    override fun isEnabled(file: VirtualFile): Boolean {
        val settings = PhpInterpreterSettings.getInstance()
        if (!settings.codeIntelligenceEnabled) return false
        val hasInterpreter = settings.defaultSdk()?.homePath
            ?.let { PhpSdkType.findPhpExecutable(it) } != null
        return hasInterpreter && PhpactorManager.isInstalled()
    }
}
