package io.github.bakeron.tapedo.security

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RetryTemporaryTest {

    @Test
    fun `a failure for a moment is retried and the key is read`() = runTest {
        var calls = 0
        val key = retryTemporary {
            calls++
            if (calls < 3) throw IOException("keystore busy")
            "secret"
        }
        assertEquals("secret", key)
        assertEquals(3, calls)
    }

    @Test
    fun `a key lost for good is not retried`() = runTest {
        var calls = 0
        try {
            retryTemporary<String> {
                calls++
                throw KeyLostException("gone")
            }
            fail("expected KeyLostException")
        } catch (e: KeyLostException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun `after the last try the failure reaches the caller`() = runTest {
        var calls = 0
        try {
            retryTemporary<String> {
                calls++
                throw IOException("keystore busy")
            }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals(3, calls)
        }
    }
}
