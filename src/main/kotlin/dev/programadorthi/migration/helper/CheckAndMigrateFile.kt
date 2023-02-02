package dev.programadorthi.migration.helper

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiParserFacade
import org.jetbrains.kotlin.android.synthetic.AndroidConst
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath

object CheckAndMigrateFile {

    fun migrate(file: PsiFile, packageName: String) {
        val ktFile = file as? KtFile ?: return
        val syntheticImports = file.importDirectives
            .filter { it.importPath?.pathStr?.startsWith(AndroidConst.SYNTHETIC_PACKAGE) == true }
        if (syntheticImports.isEmpty()) return
        lookupForReferences(ktFile, syntheticImports, packageName)
    }

    private fun lookupForReferences(ktFile: KtFile, syntheticImports: List<KtImportDirective>, packageName: String) {
        val bindingsToImport = mutableSetOf<String>()
        for (psiClass in ktFile.classes) {
            val currentClass = when (psiClass) {
                is KtLightElement<*, *> -> psiClass.kotlinOrigin as? KtClass ?: continue
                is KtClass -> psiClass
                else -> error("Not supported class type to migrate --> ${psiClass.name}")
            }
            val process = ProcessCurrentClass.getInstance(psiClass, currentClass, packageName)
            if (process != null) {
                process.collectAndMigrate()
                bindingsToImport.addAll(process.bindingToImport)
            }
        }
        // Avoiding remove imports from class not supported yet
        if (bindingsToImport.isNotEmpty()) {
            updateImports(ktFile, syntheticImports, bindingsToImport)
        }
    }

    private fun updateImports(ktFile: KtFile, imports: List<KtImportDirective>, bindingToImport: Set<String>) {
        val importList = ktFile.importList ?: return
        val psiFactory = KtPsiFactory(ktFile.project)
        for (imp in bindingToImport) {
            val importPath = ImportPath.fromString(imp)
            val importDirective = psiFactory.createImportDirective(importPath)
            val parserFacade = PsiParserFacade.SERVICE.getInstance(ktFile.project)
            val newLine = parserFacade.createWhiteSpaceFromText("\n")
            importList.add(newLine)
            importList.add(importDirective)
        }
        for (imp in imports) {
            imp.delete()
        }
    }
}