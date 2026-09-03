package io.genai.php.lsp

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import com.redhat.devtools.lsp4ij.client.indexing.ProjectIndexingManager
import com.redhat.devtools.lsp4ij.features.LSPPsiElement

/**
 * Exposes the identifier under the caret as a target PSI element, so the platform's
 * target-element machinery can route through LSP4IJ.
 *
 * Why we need our own: LSP4IJ registers its identical `LSPTargetElementEvaluator` with
 * `language=""`, but `LanguageExtensionPoint` uses the raw `language` attribute as the lookup
 * key and `LanguageExtension.forLanguage()` matches keys by exact language ID — so the empty
 * key matches nothing and that evaluator NEVER runs, for any language (upstream LSP4IJ bug).
 * Our flat PSI has no `PsiReference`s, therefore without an evaluator
 * `TargetElementUtil.findTargetElement()` returns null for `.php` files. That breaks:
 *  - Cmd+Click / Cmd+B on a method *declaration*: `GotoDeclarationAction` resolves it to the
 *    element itself and is supposed to fall back to `findElementToShowUsagesOf()` →
 *    `ShowUsagesAction` ("Method 'x' has N usages" popup) — but that helper is
 *    `TargetElementUtil`-based and returns null, so the popup never appears and users can't
 *    jump to callers;
 *  - plain Find Usages (Option+F7), whose target resolution goes through the same machinery.
 *
 * With the evaluator in place, `findTargetElement` returns an [LSPPsiElement] for the word at
 * the caret; LSP4IJ's usage pipeline (`LSPFindUsagesHandlerFactory` → `textDocument/references`)
 * accepts it because the containing file is served by a references-capable server.
 *
 * Registered via `<targetElementEvaluator language="PhpPortable">` in META-INF/lsp.xml (so it
 * only exists when LSP4IJ is installed, and only applies to our language).
 */
class PhpTargetElementEvaluator : TargetElementEvaluatorEx2() {

    override fun adjustReferenceOrReferencedElement(
        file: PsiFile,
        editor: Editor,
        offset: Int,
        flags: Int,
        refElement: PsiElement?,
    ): PsiElement? {
        if (!LanguageServersRegistry.getInstance().isFileSupported(file)) return null
        if (ProjectIndexingManager.isIndexingAll()) return null
        val range: TextRange = LSPIJUtils.getWordRangeAt(editor.document, file, offset) ?: return null
        return LSPPsiElement(file, range)
    }
}
