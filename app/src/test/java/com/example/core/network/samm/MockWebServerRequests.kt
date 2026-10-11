package com.example.core.network.samm

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit

/**
 * Bounded replacement for [MockWebServer.takeRequest].
 *
 * The no-arg overload blocks FOREVER when the code under test issues fewer requests than the test
 * consumes. That does not fail the test, it hangs the whole Gradle build with no output, which is
 * indistinguishable from a wedged machine. Every request read in this suite must use this instead.
 */
fun MockWebServer.takeRequestOrFail(
    what: String = "request",
    timeoutSeconds: Long = 5L
): RecordedRequest =
    takeRequest(timeoutSeconds, TimeUnit.SECONDS)
        ?: throw AssertionError(
            "No $what reached MockWebServer within ${timeoutSeconds}s. " +
                "Either the implementation stopped early or the test enqueued more expectations than it consumes."
        )