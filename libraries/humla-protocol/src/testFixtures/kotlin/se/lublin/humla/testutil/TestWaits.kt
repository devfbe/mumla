package se.lublin.humla.testutil

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Polls [condition] until true or fails after [timeoutMillis]. Real time, independent of
 * Robolectric's clock. The default is a ceiling for a loaded machine (the first TLS setup in a
 * JVM alone can take seconds); a passing wait returns as soon as the condition holds.
 */
public fun awaitUntil(timeoutMillis: Long = 20_000L, description: String = "condition", condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError("$description not met within $timeoutMillis ms")
        Thread.sleep(2)
    }
}
