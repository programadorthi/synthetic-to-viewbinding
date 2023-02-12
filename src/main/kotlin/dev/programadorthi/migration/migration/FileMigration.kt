package dev.programadorthi.migration.migration

import android.databinding.tool.store.LayoutFileParser
import android.databinding.tool.store.ResourceBundle
import android.databinding.tool.util.RelativizableFile
import android.databinding.tool.writer.BaseLayoutModel
import android.databinding.tool.writer.toViewBinder
import com.android.tools.idea.util.toIoFile
import dev.programadorthi.migration.model.BindingData
import org.jetbrains.kotlin.android.model.AndroidModuleInfoProvider
import org.jetbrains.kotlin.android.synthetic.AndroidConst
import org.jetbrains.kotlin.android.synthetic.res.AndroidLayoutGroupData
import org.jetbrains.kotlin.android.synthetic.res.AndroidResource
import org.jetbrains.kotlin.android.synthetic.res.AndroidVariant
import org.jetbrains.kotlin.android.synthetic.res.CliAndroidLayoutXmlFileManager
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.idea.caches.project.toDescriptor
import org.jetbrains.kotlin.idea.util.module
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath
import java.io.File
import java.nio.file.Files

internal object FileMigration {
    private const val GROUPIE_PACKAGE_PREFIX = "com.xwray.groupie.kotlinandroidextensions"
    private const val PARCELIZE_PACKAGE_PREFIX = "kotlinx.android.parcel"
    const val GROUPIE_ITEM_CLASS = "$GROUPIE_PACKAGE_PREFIX.Item"
    const val GROUPIE_VIEW_HOLDER_CLASS = "$GROUPIE_PACKAGE_PREFIX.GroupieViewHolder"

    private val parcelizeImports = setOf("kotlinx.parcelize.Parcelize")

    fun migrate(
        ktFile: KtFile,
        applicationPackage: String,
        moduleInfoProvider: AndroidModuleInfoProvider,
        parcelizeStatusProvider: ParcelizeStatusProvider,
    ) {
        val syntheticImports = ktFile.importDirectives.filter(::shouldIMigrate)
        if (syntheticImports.isEmpty()) return

        if (syntheticImports.all(::isParcelize)) {
            ParcelizeMigration.migrate(ktFile, parcelizeStatusProvider)
            updateImports(ktFile, syntheticImports, parcelizeImports)
        } else {
            lookupForReferences(
                ktFile = ktFile,
                applicationPackage = applicationPackage,
                syntheticImports = syntheticImports,
                moduleInfoProvider = moduleInfoProvider,
            )
        }
    }

    private fun lookupForReferences(
        ktFile: KtFile,
        applicationPackage: String,
        syntheticImports: List<KtImportDirective>,
        moduleInfoProvider: AndroidModuleInfoProvider,
    ) {
        val lookupLayouts = syntheticImports
            .filterNot(::isParcelize)
            .mapNotNull {
                it.importPath?.pathStr
                    ?.substringBeforeLast(".")
                    ?.removeSuffix(".view")
            }
            .toSet()

        val variants = moduleInfoProvider.getActiveSourceProviders().map { active ->
            AndroidVariant(
                name = active.name,
                resDirectories = active.resDirectories.mapNotNull { it.canonicalPath },
            )
        }
        val layoutXmlFileManager = CliAndroidLayoutXmlFileManager(
            project = ktFile.project,
            applicationPackage = applicationPackage,
            variants = variants,
        )
        val moduleData = layoutXmlFileManager.getModuleData()
        val moduleDescriptor = ktFile.module?.toDescriptor() ?: error("Module descriptor not found for ${ktFile.name}")
        val layoutWithResources = mutableMapOf<String, List<AndroidResource>>()
        val resourceBundle = ResourceBundle(applicationPackage, true)
        for (variantData in moduleData.variants) {
            for ((layoutName, layouts) in variantData.layouts) {
                val packageFqName = AndroidConst.SYNTHETIC_PACKAGE + '.' + variantData.variant.name + '.' + layoutName
                if (lookupLayouts.contains(packageFqName)) {
                    layoutWithResources[layoutName] = layoutXmlFileManager.extractResources(
                        layoutGroupFiles = AndroidLayoutGroupData(layoutName, layouts),
                        module = moduleDescriptor,
                    )
                    val tempDirPath = Files.createTempDirectory("res-stripped")
                    for (layout in layouts) {
                        val bundle = LayoutFileParser.parseXml(
                            RelativizableFile.fromAbsoluteFile(layout.virtualFile.toIoFile()),
                            File(tempDirPath.toFile(), layout.name),
                            applicationPackage,
                            { it },
                            true,
                            false,
                        )
                        resourceBundle.addLayoutBundle(bundle, true)
                    }
                    if (lookupLayouts.size == layoutWithResources.size) {
                        break
                    }
                }
            }
        }
        resourceBundle.validateAndRegisterErrors()

        // Sort the layout bindings to ensure deterministic order
        val layoutBindings = resourceBundle.allLayoutFileBundlesInSource
            .groupBy(ResourceBundle.LayoutFileBundle::getFileName).toSortedMap()

        val bindingData = mutableListOf<BindingData>()
        for ((layoutName, variations) in layoutBindings) {
            val baseLayoutModel = BaseLayoutModel(variations, null)
            bindingData += BindingData(
                layoutName = layoutName,
                resources = layoutWithResources[layoutName] ?: emptyList(),
                baseLayoutModel = baseLayoutModel,
                rootNode = baseLayoutModel.toViewBinder().rootNode,
            )
        }

        val bindingsToImport = mutableSetOf<String>()
        bindingsToImport.addAll(
            bindingData.map {
                val model = it.baseLayoutModel
                "${model.bindingClassPackage}.${model.bindingClassName}"
            }
        )
        bindingsToImport.addAll(processEachClass(ktFile, bindingData))

        if (syntheticImports.any(::isParcelize)) {
            bindingsToImport.addAll(parcelizeImports)
        }

        // Avoiding remove imports from class not supported yet
        if (bindingsToImport.isNotEmpty()) {
            updateImports(ktFile, syntheticImports, bindingsToImport)
        }
    }

    private fun processEachClass(ktFile: KtFile, bindingData: List<BindingData>): Set<String> {
        val bindingsToImport = mutableSetOf<String>()
        for (psiClass in ktFile.classes) {
            val currentClass = when (psiClass) {
                is KtLightElement<*, *> -> psiClass.kotlinOrigin as? KtClass ?: continue
                is KtClass -> psiClass
                else -> error("Not supported class type to migrate --> ${psiClass.name}")
            }
            bindingsToImport.addAll(MigrationHelper.tryMigrate(psiClass, currentClass, bindingData))
        }

        for (func in ktFile.children.filterIsInstance<KtNamedFunction>()) {
            TODO("lookup for top-level functions using synthetic refenreces")
        }

        return bindingsToImport
    }

    private fun updateImports(
        ktFile: KtFile,
        syntheticImports: List<KtImportDirective>,
        bindingsToImport: Set<String>,
    ) {
        val importList = ktFile.importList ?: return
        val psiFactory = KtPsiFactory(ktFile.project)
        for (import in bindingsToImport) {
            val importPath = ImportPath.fromString(import)
            val importDirective = psiFactory.createImportDirective(importPath)
            val newLine = psiFactory.createWhiteSpace("\n")
            importList.add(newLine)
            importList.add(importDirective)
        }
        for (importDirective in syntheticImports) {
            importDirective.delete()
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