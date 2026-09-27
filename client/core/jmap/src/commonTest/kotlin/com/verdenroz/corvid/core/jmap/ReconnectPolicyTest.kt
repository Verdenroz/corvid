package com.verdenroz.corvid.core.jmap

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ReconnectPolicyTest {
    @Test
    fun `delays never exceed the five minute cap`() {
        val policy = ReconnectPolicy()

        repeat(30) {
            assertTrue(policy.nextDelay() <= 5.minutes)
        }
    }

    @Test
    fun `reset starts the backoff over from the first delay's range`() {
        val policy = ReconnectPolicy()
        repeat(10) { policy.nextDelay() }

        policy.reset()

        assertTrue(policy.nextDelay() <= 1.minutes)
    }
}
