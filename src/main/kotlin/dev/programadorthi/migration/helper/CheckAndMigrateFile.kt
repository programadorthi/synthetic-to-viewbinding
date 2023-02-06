package dev.programadorthi.migration.helper

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiParserFacade
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.kotlin.android.synthetic.AndroidConst
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath

object CheckAndMigrateFile {
    private const val ANDROID_ACTIVITY_CLASS = "android.app.Activity"
    private const val ANDROID_DIALOG_CLASS = "android.app.Dialog"
    private const val ANDROID_VIEW_CLASS = "android.view.View"
    private const val ANDROID_VIEW_GROUP_CLASS = "android.view.ViewGroup"
    private const val GROUPIE_CLASS = "com.xwray.groupie.kotlinandroidextensions.Item"

    fun migrate(file: PsiFile, packageName: String) {
        val ktFile = file as? KtFile ?: return
        val syntheticImports = file.importDirectives.filter(::shouldIMigrate)
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
            val parents = InheritanceUtil.getSuperClasses(psiClass)
            for (parent in parents) {
                if (parent.qualifiedName == ANDROID_ACTIVITY_CLASS || parent.qualifiedName == ANDROID_DIALOG_CLASS) {
                    val process = ActivityProcessCurrentClass(packageName = packageName, ktClass = currentClass)
                    process.collectAndMigrate()
                    bindingsToImport.addAll(process.bindingToImport)
                    break
                }
                if (parent.qualifiedName == ANDROID_VIEW_CLASS || parent.qualifiedName == ANDROID_VIEW_GROUP_CLASS) {
                    val process = ViewProcessCurrentClass(packageName = packageName, ktClass = currentClass)
                    process.collectAndMigrate()
                    bindingsToImport.addAll(process.bindingToImport)
                    break
                }
                if (parent.qualifiedName == GROUPIE_CLASS) {
                    val process = GroupieProcessCurrentClass(packageName = packageName, ktClass = currentClass)
                    process.collectAndMigrate()
                    bindingsToImport.addAll(process.bindingToImport)
                    break
                }
            }
        }
        // Avoiding remove imports from class not supported yet
        if (bindingsToImport.isEmpty()) return
        updateImports(ktFile, syntheticImports, bindingsToImport)
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

    private fun shouldIMigrate(importDirective: KtImportDirective): Boolean {
        val pathStr = importDirective.importPath?.pathStr ?: return false
        return pathStr.startsWith(AndroidConst.SYNTHETIC_PACKAGE) ||
                pathStr.startsWith("com.xwray.groupie.kotlinandroidextensions")
    }
}