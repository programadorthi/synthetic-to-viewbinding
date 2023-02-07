package dev.programadorthi.migration.migration

internal interface BuildGradleProvider {
    fun hasMigratedAlready(path: String): Boolean

    fun registerModuleMigration(path: String)
}