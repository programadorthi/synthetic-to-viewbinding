package dev.programadorthi.migration.helper

data class AndroidView(
    val isIncludeTag: Boolean,
    val layoutNameWithoutExtension: String,
    val rootTagName: String,
    val viewId: String
)
