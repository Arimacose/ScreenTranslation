package com.screentranslation.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ModelStorageTest {
    @Test
    fun `counts and deletes only a child of the model root`() {
        val root = Files.createTempDirectory("model-storage-test").toFile()
        try {
            val edition = root.resolve("edition").apply { mkdirs() }
            edition.resolve("model.bin").writeBytes(ByteArray(32))
            edition.resolve("model.bin.part").writeBytes(ByteArray(11))

            assertEquals(43L, edition.recursiveSizeBytes())
            assertEquals(43L, deleteModelDirectory(root, edition))
            assertFalse(edition.exists())
            assertTrue(root.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects deletion outside the model root`() {
        val root = Files.createTempDirectory("model-storage-root").toFile()
        val outside = Files.createTempDirectory("model-storage-outside").toFile()
        try {
            deleteModelDirectory(root, outside)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
