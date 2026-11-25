package com.personal.messenger
import com.personal.messenger.data.RetryPolicy
import org.junit.Assert.*
import org.junit.Test
class RetryPolicyTest {
    @Test fun transientErrorsAreRetried(){assertFalse(RetryPolicy.permanent(429));assertFalse(RetryPolicy.permanent(503));assertFalse(RetryPolicy.permanent(408));assertFalse(RetryPolicy.permanent(401))}
    @Test fun invalidInputFails(){assertTrue(RetryPolicy.permanent(400));assertTrue(RetryPolicy.permanent(403));assertTrue(RetryPolicy.permanent(409))}
    @Test fun adaptivePolling(){assertEquals(3000L,RetryPolicy.delayMs(0,0));assertEquals(15000L,RetryPolicy.delayMs(0,5));assertEquals(90000L,RetryPolicy.delayMs(9,0))}
}
