package dev.programadorthi.migration

import com.android.tools.idea.concurrency.addCallback
import com.android.tools.idea.databinding.module.LayoutBindingModuleCache
import com.android.tools.idea.databinding.project.LayoutBindingEnabledFacetsProvider
import com.android.tools.idea.databinding.psiclass.LightBindingClass
import com.android.tools.idea.gradle.project.GradleProjectInfo
import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker
import com.android.tools.idea.gradle.project.build.invoker.TestCompileType
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.gradle.project.sync.GradleSyncState
import com.google.common.util.concurrent.MoreExecutors
import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.actions.AbstractLayoutCodeProcessor
import com.intellij.codeInsight.actions.CodeCleanupCodeProcessor
import com.intellij.codeInsight.actions.DirectoryFormattingOptions
import com.intellij.codeInsight.actions.LayoutDirectoryDialog
import com.intellij.codeInsight.actions.LayoutProjectCodeDialog
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.RearrangeCodeProcessor
import com.intellij.codeInsight.actions.ReformatFilesOptions
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.ide.lightEdit.LightEditCompatible
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Conditions
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDirectoryContainer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.SearchScope
import com.intellij.util.ArrayUtil
import com.intellij.util.concurrency.Semaphore
import dev.programadorthi.migration.notification.MigrationNotification
import dev.programadorthi.migration.processor.MigrationProcessor
import org.jetbrains.android.util.AndroidUtils
import org.jetbrains.kotlin.idea.util.module
import java.util.regex.PatternSyntaxException

class MigrationAction : AnAction(), DumbAware, LightEditCompatible {
    private val log = Logger.getInstance(MigrationAction::class.java)

    init {
        isEnabledInModalContext = true
        setInjectedContext(true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = canPerform(e)
    }

    override fun actionPerformed(event: AnActionEvent) {
        if (!canPerform(event)) {
            return
        }

        val dataContext = event.dataContext
        val project = requireNotNull(CommonDataKeys.PROJECT.getData(dataContext)) {
            "No project found to do migration"
        }
        MigrationNotification.setProject(project)
        val psiElement = CommonDataKeys.PSI_ELEMENT.getData(dataContext)
        val moduleContext = LangDataKeys.MODULE_CONTEXT.getData(dataContext)
        val module = moduleContext ?: psiElement?.module
        if (psiElement == null || module == null) {
            MigrationNotification.showError("Selecting a module or file is required to do a migration")
            return
        }

        val model = GradleAndroidModel.get(module)
        if (model == null || model.androidProject.viewBindingOptions?.enabled != true) {
            MigrationNotification.showError("View Binding not enabled in the build.gradle(.kts)")
            return
        }

        if (moduleContext != null) {
            tryModuleMigration(project = project, module = module)
        } else {
            val dir = when (psiElement) {
                is PsiDirectoryContainer -> ArrayUtil.getFirstElement(psiElement.directories)
                is PsiDirectory -> psiElement
                else -> psiElement.containingFile?.containingDirectory
            }
            if (dir == null) {
                MigrationNotification.showError("No directory selected to do migration")
                return
            }
            tryDirectoryMigration(project = project, module = module, dir = dir)
        }
    }

    private fun tryModuleMigration(project: Project, module: Module) {
        val selectedFlags = getLayoutModuleOptions(project, module) ?: return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        registerAndRunProcessor(
            project = project,
            module = module,
            selectedFlags = selectedFlags,
            initialProcessor = MigrationProcessor(project, module),
        )
    }

    private fun tryDirectoryMigration(project: Project, module: Module, dir: PsiDirectory) {
        val selectedFlags = getDirectoryFormattingOptions(project, dir) ?: return
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        registerAndRunProcessor(
            project = project,
            module = module,
            selectedFlags = selectedFlags,
            initialProcessor = MigrationProcessor(
                project,
                dir,
                selectedFlags.isIncludeSubdirectories,
            ),
        )
    }

    private fun checkGradleBuild(project: Project, module: Module): List<LightBindingClass> {
        val result = Ref<List<LightBindingClass>>(emptyList())

        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            val indicator = ProgressManager.getInstance().progressIndicator
            indicator.isIndeterminate = true
            val targetDone = Semaphore()
            targetDone.down()
            GradleBuildInvoker.getInstance(project).compileJava(
                modules = arrayOf(module),
                testCompileType = TestCompileType.NONE,
            ).addCallback(
                MoreExecutors.directExecutor(),
                { taskResult ->
                    try {
                        ProgressManager.checkCanceled()
                        if (taskResult?.isBuildSuccessful == true) {
                            val enabledFacetsProvider = LayoutBindingEnabledFacetsProvider.getInstance(project)
                            val bindings = enabledFacetsProvider.getAllBindingEnabledFacets()
                                .flatMap { facet ->
                                    val bindingModuleCache = LayoutBindingModuleCache.getInstance(facet)
                                    bindingModuleCache.bindingLayoutGroups.flatMap { group ->
                                        bindingModuleCache.getLightBindingClasses(group)
                                    }
                                }
                            result.set(bindings)
                        } else {
                            MigrationNotification.showError("Gradle ViewBinding generation finished without success")
                        }
                    } finally {
                        targetDone.up()
                    }
                },
                {
                    try {
                        MigrationNotification.showError("Error when generating ViewBinding classes")
                        it?.printStackTrace()
                    } finally {
                        targetDone.up()
                    }
                },
            )
            targetDone.waitFor()
            indicator.isIndeterminate = false
        }, "Generating ViewBinding classes...", true, project)

        return result.get()
    }

    private fun registerAndRunProcessor(
        project: Project,
        module: Module,
        initialProcessor: MigrationProcessor,
        selectedFlags: ReformatFilesOptions,
    ) {
        val shouldOptimizeImports = selectedFlags.isOptimizeImports && !DumbService.getInstance(project).isDumb
        var processor: AbstractLayoutCodeProcessor = initialProcessor

        registerScopeFilter(processor, selectedFlags.searchScope)
        registerFileMaskFilter(processor, selectedFlags.fileTypeMask)

        if (shouldOptimizeImports) {
            processor = OptimizeImportsProcessor(processor)
        }

        if (selectedFlags.isRearrangeCode) {
            processor = RearrangeCodeProcessor(processor)
        }

        if (selectedFlags.isCodeCleanup) {
            processor = CodeCleanupCodeProcessor(processor)
        }

        val bindingClasses = checkGradleBuild(project, module)
        if (bindingClasses.isNotEmpty()) {
            initialProcessor.addBindingClasses(bindingClasses)
            processor.run()
        }
    }

    private fun registerFileMaskFilter(processor: AbstractLayoutCodeProcessor, fileTypeMask: String?) {
        if (fileTypeMask == null) return
        val patternCondition = try {
            FindInProjectUtil.createFileMaskCondition(fileTypeMask)
        } catch (ex: PatternSyntaxException) {
            log.error("Error while creating file mask condition: ", ex)
            Conditions.alwaysTrue()
        }
        processor.addFileFilter { file ->
            patternCondition.value(file.nameSequence)
        }
    }

    private fun registerScopeFilter(processor: AbstractLayoutCodeProcessor, scope: SearchScope?) {
        if (scope == null) return
        processor.addFileFilter(scope::contains)
    }

    private fun getLayoutModuleOptions(project: Project, moduleContext: Module?): ReformatFilesOptions? {
        if (ApplicationManager.getApplication().isUnitTestMode) {
            return null
        }
        val text = if (moduleContext != null) {
            CodeInsightBundle.message("process.scope.module", moduleContext.moduleFilePath)
        } else {
            CodeInsightBundle.message("process.scope.project", project.presentableUrl)
        }
        val dialog = LayoutProjectCodeDialog(
            project,
            MigrationProcessor.getCommandName(),
            text,
            false,
        )
        if (dialog.showAndGet()) {
            return dialog
        }
        return null
    }

    private fun getDirectoryFormattingOptions(project: Project, dir: PsiDirectory): DirectoryFormattingOptions? {
        val dialog = LayoutDirectoryDialog(
            project,
            MigrationProcessor.getCommandName(),
            CodeInsightBundle.message("process.scope.directory", dir.virtualFile.path),
            false,
        )
        val enableIncludeDirectoriesCb = dir.subdirectories.isNotEmpty()
        dialog.setEnabledIncludeSubdirsCb(enableIncludeDirectoriesCb)
        dialog.setSelectedIncludeSubdirsCb(enableIncludeDirectoriesCb)
        if (dialog.showAndGet()) {
            return dialog
        }
        return null
    }

    private fun canPerform(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return GradleProjectInfo.getInstance(project).isBuildWithGradle &&
                !GradleSyncState.getInstance(project).isSyncInProgress &&
                AndroidUtils.hasAndroidFacets(project)
    }
}