package dev.programadorthi.migration.model

enum class BindingFunction(val value: String) {
    AS_CHILD("viewBindingAsChild"),
    AS_MERGE("viewBindingMergeTag"),
    DEFAULT("viewBinding"),
}