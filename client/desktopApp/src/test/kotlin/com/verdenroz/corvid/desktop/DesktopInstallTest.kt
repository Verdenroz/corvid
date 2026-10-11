package com.verdenroz.corvid.desktop

import com.verdenroz.corvid.core.data.InstallResult
import com.verdenroz.corvid.core.model.AppPlatform
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopInstallTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root by lazy { File(tmp.root, "corvid").also { it.mkdirs() } }
    private val installation by lazy { DesktopInstall(root) }

    private fun tarball(
        name: String,
        executable: Boolean = true,
    ): File {
        val src = tmp.newFolder("src-$name")
        val launcher = File(src, "corvid/bin/corvid").also { it.parentFile.mkdirs() }
        launcher.writeText("#!/bin/sh\necho $name\n")
        launcher.setExecutable(executable)
        File(src, "corvid/lib").mkdirs()
        val out = File(tmp.root, "$name.tar.gz")
        assertEquals(0, ProcessBuilder("tar", "-czf", out.path, "-C", src.path, "corvid").inheritIO().start().waitFor())
        return out
    }

    private fun currentVersion() = File(root, "current").toPath().toRealPath().fileName.toString()

    @Test
    fun `installing unpacks the build with its launcher runnable and points current at it`() {
        installation.install(tarball("one"))

        assertTrue(installation.launcher.canExecute())
        assertTrue(File(root, "versions/1/corvid/lib").isDirectory)
        assertEquals("1", currentVersion())
    }

    @Test
    fun `a second install moves current and keeps the previous version`() {
        installation.install(tarball("one"))
        installation.install(tarball("two"))

        assertEquals(listOf("1", "2"), File(root, "versions").list()!!.sorted())
        assertEquals("2", currentVersion())
    }

    @Test
    fun `only the newest two versions are kept`() {
        listOf("a", "b", "c", "d").forEach { installation.install(tarball(it)) }

        assertEquals(listOf("3", "4"), File(root, "versions").list()!!.sorted())
    }

    @Test
    fun `a launcher that is not executable leaves current untouched`() {
        installation.install(tarball("one"))

        assertFailsWith<IOException> { installation.install(tarball("two", executable = false)) }

        assertEquals("1", currentVersion())
        assertEquals(listOf("1"), File(root, "versions").list()!!.sorted())
    }

    @Test
    fun `a file that is not a tarball fails and leaves current untouched`() {
        installation.install(tarball("one"))
        val junk = tmp.newFile("junk.tar.gz").also { it.writeText("nope") }

        assertFailsWith<IOException> { installation.install(junk) }

        assertEquals("1", currentVersion())
    }

    @Test
    fun `only a launcher under the managed versions counts as managed`() {
        installation.install(tarball("one"))

        assertTrue(installation.manages(File(root, "versions/1/corvid/bin/corvid").path))
        assertTrue(installation.manages(File(root, "current/corvid/bin/corvid").path))
        assertFalse(installation.manages(tmp.newFile("launcher").path))
        assertFalse(installation.manages(null))
    }

    @Test
    fun `an app outside the managed install is offered no update`() {
        installation.install(tarball("one"))

        assertNull(DesktopAppInstaller(installation, tmp.newFile("elsewhere").path) {}.platform)
        assertNull(DesktopAppInstaller(installation, null) {}.platform)
    }

    @Test
    fun `the managed install is offered the Linux build`() {
        installation.install(tarball("one"))

        val installer = DesktopAppInstaller(installation, File(root, "versions/1/corvid/bin/corvid").path) {}

        assertEquals(AppPlatform.Linux, installer.platform)
    }

    @Test
    fun `the installer refuses an unmanaged app without touching the install`() {
        var relaunched = false
        val installer = DesktopAppInstaller(installation, tmp.newFile("elsewhere").path) { relaunched = true }

        val result = runBlocking { installer.install(tarball("one")) }

        assertTrue(result is InstallResult.Failed)
        assertFalse(relaunched)
        assertFalse(File(root, "versions").exists())
    }

    @Test
    fun `the installer relaunches into current after a managed install`() {
        installation.install(tarball("one"))
        var launched: File? = null
        val installer = DesktopAppInstaller(installation, File(root, "versions/1/corvid/bin/corvid").path) { launched = it }

        val result = runBlocking { installer.install(tarball("two")) }

        assertEquals(InstallResult.Started, result)
        assertEquals(installation.launcher, launched)
        assertEquals("2", currentVersion())
    }
}
