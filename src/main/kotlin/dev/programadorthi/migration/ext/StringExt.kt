package dev.programadorthi.migration.ext

import android.databinding.tool.ext.capitalizeUS
import android.databinding.tool.ext.stripNonJava

fun String.layoutToBindingName(): String =
    stripNonJava().capitalizeUS() + "Binding"