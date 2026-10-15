package com.verdenroz.corvid.app

import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.digest_banner
import com.verdenroz.corvid.appcommon.resources.enroll_title
import com.verdenroz.corvid.core.testing.composedResourceEnvironment
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resolve
import com.verdenroz.corvid.core.text.uiText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppTextTest {
    private val environment = composedResourceEnvironment()

    @Test
    fun `the digest banner counts the messages and says since when`() =
        runTest {
            fun line(count: Int) = UiText.Plural(Res.plurals.digest_banner, count, listOf(count, UiText.FromServer("Sun 4 Oct, 07:00")))

            assertEquals("Your digest is ready: 1 message since Sun 4 Oct, 07:00", line(1).resolve(environment))
            assertEquals("Your digest is ready: 12 messages since Sun 4 Oct, 07:00", line(12).resolve(environment))
        }

    @Test
    fun `the enrollment prompt names the device in quotes`() =
        runTest {
            assertEquals("Approve \"laptop-1\" from another device", uiText(Res.string.enroll_title, "laptop-1").resolve(environment))
        }
}
