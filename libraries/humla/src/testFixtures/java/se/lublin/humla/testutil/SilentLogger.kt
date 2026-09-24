package se.lublin.humla.testutil

import se.lublin.humla.util.HumlaLogger

/** A [HumlaLogger] that drops everything. */
object SilentLogger : HumlaLogger {
    override fun logInfo(message: String?) = Unit
    override fun logWarning(message: String?) = Unit
    override fun logError(message: String?) = Unit
}
