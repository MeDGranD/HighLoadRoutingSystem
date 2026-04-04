package ru.ilyubarskiy.mai.routing_service.utils

import java.io.File
import java.util.zip.ZipInputStream

object ZipUtils {

    fun unzip(zip: File, target: File) {

        if (!target.exists()) {
            target.mkdirs()
        }

        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(target, entry.name)

                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()

                    file.outputStream().use { zis.copyTo(it) }
                }
                entry = zis.nextEntry
            }
        }
    }

}