package com.example.writersblock

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object Home : NavKey

@Serializable
data class FileBrowser(val folderPath: String? = null) : NavKey

@Serializable
data class FileEditor(val filePath: String? = null, val folderPath: String? = null) : NavKey

@Serializable
data object Dashboard : NavKey
