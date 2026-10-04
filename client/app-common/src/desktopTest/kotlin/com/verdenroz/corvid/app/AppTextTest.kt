package com.verdenroz.corvid.app

import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.enroll_title
import com.verdenroz.corvid.core.text.resolve
import com.verdenroz.corvid.core.text.uiText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppTextTest {
    @Test
    fun `the enrollment prompt names the device in quotes`() =
        runTest {
            assertEquals("No key on the server for \"laptop-1\" yet.", uiText(Res.string.enroll_title, "laptop-1").resolve())
        }
}
