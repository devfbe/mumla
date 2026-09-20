package se.lublin.humla.testutil

/** Polls [condition] until true or fails after [timeoutMillis]. Real time, independent of Robolectric's clock. */
fun awaitUntil(timeoutMillis: Long = 5_000L, description: String = "condition", condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError("$description not met within $timeoutMillis ms")
        Thread.sleep(5)
    }
}
