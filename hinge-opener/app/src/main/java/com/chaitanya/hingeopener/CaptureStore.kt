package com.chaitanya.hingeopener

import android.content.Context
import java.io.File

/** Screenshots live in app-private cache only, and are deleted after analysis or Clear. */
class CaptureStore(context: Context) {
    private val dir = File(context.cacheDir, "captures")
    private val files = mutableListOf<File>()

    init {
        // Never keep screenshots from a previous session.
        clear()
    }

    val count: Int get() = files.size
    val all: List<File> get() = files.toList()

    fun nextFile(): File = File(dir, "capture_${System.currentTimeMillis()}.jpg")

    fun add(file: File) {
        files += file
    }

    fun clear() {
        files.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val MAX_CAPTURES = 6
        const val MAX_WIDTH = 1080
    }
}
