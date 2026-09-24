package se.lublin.mumla.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "PortProbe"

/** Whether a TCP connection to [host]:[port] succeeds within [timeoutMs]. Runs on the IO dispatcher. */
suspend fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
    try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (e: IOException) {
        Log.d(TAG, "isPortOpen($host, $port): $e")
        false
    }
}
