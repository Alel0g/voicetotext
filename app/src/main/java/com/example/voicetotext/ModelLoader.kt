package com.example.voicetotext

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/** Распаковывает модель Vosk из assets во внутреннее хранилище. */
object ModelLoader {

    fun ensureModel(context: Context, assetZip: String, dirName: String): File {
        val dir = File(context.filesDir, dirName)
        val marker = File(dir, ".unpacked")

        // Уже распаковано — находим корень модели и возвращаем
        if (marker.exists()) return findRoot(dir)

        // Убираем возможные остатки от прошлых попыток
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw RuntimeException("Не удалось создать папку: ${dir.absolutePath}")

        var rootName: String? = null

        context.assets.open(assetZip).use { ins ->
            ZipInputStream(ins).use { zis ->
                val buf = ByteArray(65536)
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // Запоминаем имя корневой папки внутри zip (если есть)
                    if (rootName == null && name.contains('/')) {
                        rootName = name.substringBefore('/')
                    }
                    val outFile = File(dir, name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        marker.createNewFile()

        // Корень модели: вложенная папка из zip либо сама директория
        rootName?.let { rn ->
            val f = File(dir, rn)
            if (f.isDirectory) return f
        }
        return findRoot(dir)
    }

    // Ищем папку с подкаталогом conf — это структура модели Vosk
    private fun findRoot(dir: File): File {
        if (File(dir, "conf").isDirectory) return dir
        dir.listFiles()?.filter { it.isDirectory }?.forEach { child ->
            if (File(child, "conf").isDirectory) return child
        }
        return dir
    }
}
