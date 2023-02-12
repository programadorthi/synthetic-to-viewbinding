package dev.programadorthi.migration.processor

import com.intellij.codeInsight.actions.AbstractLayoutCodeProcessor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ex.util.EditorScrollingPositionKeeper
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.IncorrectOperationException
import com.intellij.util.SlowOperations
import dev.programadorthi.migration.migration.BuildGradleMigration
import dev.programadorthi.migration.migration.BuildGradleStatusProvider
import dev.programadorthi.migration.migration.FileMigration
import dev.programadorthi.migration.migration.ParcelizeStatusProvider
import dev.programadorthi.migration.model.MigrationStatus
import dev.programadorthi.migration.notification.MigrationNotification
import org.jetbrains.kotlin.android.model.AndroidModuleInfoProvider
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

internal class MigrationProcessor : AbstractLayoutCodeProcessor, BuildGradleStatusProvider, ParcelizeStatusProvider {
    private val log = Logger.getInstance(MigrationProcessor::class.java)

    private val buildGradleStatus = ConcurrentHashMap<String, MigrationStatus>()
    private val parcelizeStatus = ConcurrentHashMap<String, MigrationStatus>()

    constructor(project: Project) : super(
        project,
        getCommandName(),
        getProgressText(),
        false,
    )

    constructor(project: Project, module: Module) : super(
        project,
        module,
        getCommandName(),
        getProgressText(),
        false,
    )

    constructor(project: Project, directory: PsiDirectory, includeSubdirs: Boolean) : super(
        project,
        directory,
        includeSubdirs,
        getProgressText(),
        getCommandName(),
        false,
    )

    override fun prepareTask(file: PsiFile, processChangedTextOnly: Boolean): FutureTask<Boolean> {
        val fileToProcess = ReadAction.compute<PsiFile?, IncorrectOperationException> {
            ensureValid(file)
        } ?: return FutureTask { false }
        return FutureTask {
            doMigration(fileToProcess)
        }
    }

    override fun currentBuildGradleStatus(path: String): MigrationStatus {
        return buildGradleStatus[path] ?: MigrationStatus.NOT_STARTED
    }

    override fun currentParcelizeStatus(path: String): MigrationStatus {
        return parcelizeStatus[path] ?: MigrationStatus.NOT_STARTED
    }

    override fun updateBuildGradleStatus(path: String, status: MigrationStatus) {
        buildGradleStatus[path] = status
    }

    override fun updateParcelizeStatus(path: String, status: MigrationStatus) {
        parcelizeStatus[path] = status
    }

    private fun doMigration(file: PsiFile): Boolean {
        try {
            val document = PsiDocumentManager.getInstance(myProject).getDocument(file)
            log.assertTrue(infoCollector == null || document != null)
            val before = document?.immutableCharSequence
            try {
                EditorScrollingPositionKeeper.perform(document, true) {
                    SlowOperations.allowSlowOperations<Nothing> {
                        if (document != null) {
                            // In languages that are supported by a non-commit typing assistant (such as C++ and Kotlin),
                            // the `document` here can be in an uncommitted state. In the case of an external formatter,
                            // this may be the cause of formatting artifacts
                            PsiDocumentManager.getInstance(myProject).commitDocument(document)
                        }
                        if (isGroovyBuildGradle(file) || isKtsBuildGradle(file)) {
                            BuildGradleMigration.migrateScript(file, this@MigrationProcessor)
                        } else if (file is KtFile && !file.isScript()) {
                            val provider = AndroidModuleInfoProvider.getInstance(file)
                            val applicationPackage = provider?.getApplicationPackage()
                            if (provider?.isAndroidModule() != true || applicationPackage == null) {
                                log.warn("${file.name} is in a module not supported. Migration supports android modules only")
                            } else {
                                FileMigration.migrate(
                                    ktFile = file,
                                    applicationPackage = applicationPackage,
                                    moduleInfoProvider = provider,
                                    parcelizeStatusProvider = this@MigrationProcessor,
                                )
                            }
                        }
                    }
                }
            } catch (pce: ProcessCanceledException) {
                log.error(pce)
                if (before != null) {
                    document.setText(before)
                }
                MigrationNotification.showError("Process cancelled")
                return false
            }
            return true
        } catch (ioe: IncorrectOperationException) {
            log.error(ioe)
            MigrationNotification.showError("Operation not supported")
        }
        return false
    }

    private fun isGroovyBuildGradle(psiFile: PsiFile): Boolean =
        psiFile is GroovyFile && psiFile.name == BuildGradleMigration.BUILD_GRADLE_FILE_NAME

    private fun isKtsBuildGradle(psiFile: PsiFile): Boolean =
        psiFile is KtFile && psiFile.isScript() && psiFile.name == BuildGradleMigration.BUILD_GRADLE_KTS_FILE_NAME

    private fun ensureValid(file: PsiFile): PsiFile? {
        if (file.isValid) {
            return file
        }
        val virtualFile = file.virtualFile
        if (virtualFile.isValid) {
            return null
        }
        val provider = file.manager.findViewProvider(virtualFile) ?: return null
        val language = file.language
        return if (provider.hasLanguage(language)) {
            provider.getPsi(language)
        } else {
            provider.getPsi(provider.baseLanguage)
        }
    }

    companion object {
        private fun getProgressText(): String = "Migrating files..."
        fun getCommandName(): String = "Synthetic to ViewBinding"
    }
}