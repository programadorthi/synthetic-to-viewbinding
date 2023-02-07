package dev.programadorthi.migration.migration

import com.intellij.psi.PsiFile
import com.intellij.psi.util.InheritanceUtil
import dev.programadorthi.migration.notification.MigrationNotification
import org.jetbrains.kotlin.android.synthetic.AndroidConst
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath

internal object FileMigration {
    private const val GROUPIE_PACKAGE_PREFIX = "com.xwray.groupie.kotlinandroidextensions"
    private const val GROUPIE_ITEM_CLASS = "$GROUPIE_PACKAGE_PREFIX.Item"
    private const val GROUPIE_VIEW_HOLDER_CLASS = "$GROUPIE_PACKAGE_PREFIX.GroupieViewHolder"
    private const val PARCELIZE_PACKAGE_PREFIX = "kotlinx.android.parcel"

    private val parcelizeImports = setOf("kotlinx.parcelize.Parcelize")

    fun migrate(
        file: PsiFile,
        packageName: String,
        buildGradleStatusProvider: BuildGradleStatusProvider,
        parcelizeStatusProvider: ParcelizeStatusProvider,
    ) {
        BuildGradleMigration.migrateScript(file, buildGradleStatusProvider)

        val ktFile = file as? KtFile ?: return
        val syntheticImports = file.importDirectives.filter(::shouldIMigrate)
        if (syntheticImports.isNotEmpty()) {
            if (syntheticImports.all(::isParcelize)) {
                ParcelizeMigration.migrate(ktFile, parcelizeStatusProvider)
                updateImports(ktFile, syntheticImports, parcelizeImports)
            } else {
                lookupForReferences(ktFile, syntheticImports, packageName)
            }
            MigrationNotification.showInfo("${ktFile.name} migration successfully!")
        }
    }

    private fun lookupForReferences(
        ktFile: KtFile,
        syntheticImports: List<KtImportDirective>,
        packageName: String,
    ) {
        val bindingsToImport = mutableSetOf<String>()
        for (psiClass in ktFile.classes) {
            val currentClass = when (psiClass) {
                is KtLightElement<*, *> -> psiClass.kotlinOrigin as? KtClass ?: continue
                is KtClass -> psiClass
                else -> error("Not supported class type to migrate --> ${psiClass.name}")
            }
            val parents = InheritanceUtil.getSuperClasses(psiClass)
            for (parent in parents) {
                val process: CommonMigration = when (parent.qualifiedName) {
                    AndroidConst.ACTIVITY_FQNAME,
                    AndroidConst.DIALOG_FQNAME -> ClassWithSetContentViewMigration(
                        packageName = packageName,
                        ktClass = currentClass,
                    )

                    AndroidConst.VIEW_FQNAME -> ViewMigration(
                        packageName = packageName,
                        ktClass = currentClass,
                    )

                    GROUPIE_ITEM_CLASS,
                    GROUPIE_VIEW_HOLDER_CLASS -> GroupieMigration(
                        packageName = packageName,
                        ktClass = currentClass,
                    )

                    else -> continue
                }
                process.doMigration()
                bindingsToImport.addAll(process.bindingToImport)
            }
        }

        if (syntheticImports.any(::isParcelize)) {
            bindingsToImport.addAll(parcelizeImports)
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
            val newLine = psiFactory.createWhiteSpace("\n")
            importList.add(newLine)
            importList.add(importDirective)
        }
        for (imp in imports) {
            imp.delete()
        }
    }

    private fun isParcelize(importDirective: KtImportDirective): Boolean {
        val pathStr = importDirective.importPath?.pathStr ?: return false
        return pathStr.startsWith(PARCELIZE_PACKAGE_PREFIX)
    }

    private fun shouldIMigrate(importDirective: KtImportDirective): Boolean {
        val pathStr = importDirective.importPath?.pathStr ?: return false
        return pathStr.startsWith(AndroidConst.SYNTHETIC_PACKAGE) ||
                pathStr.startsWith(GROUPIE_PACKAGE_PREFIX) ||
                isParcelize(importDirective)
    }
}