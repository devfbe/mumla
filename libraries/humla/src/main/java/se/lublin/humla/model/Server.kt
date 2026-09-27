/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package se.lublin.humla.model

import android.net.InetAddresses
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.VisibleForTesting
import org.minidns.hla.ResolverApi
import org.minidns.util.SrvUtil
import se.lublin.humla.util.Constants
import se.lublin.humla.util.HumlaLog
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * A server the user can connect to. [port] 0 means "look up the `_mumble._tcp` SRV record".
 *
 * The mutable fields are volatile: the UI thread edits them while the connecting thread resolves
 * the endpoint.
 */
open class Server(
    id: Long,
    name: String?,
    val host: String,
    val port: Int,
    username: String?,
    password: String?,
) : Parcelable {
    /** The database id, or -1 for a server that is not stored. */
    @Volatile var id: Long = id
    @Volatile var username: String? = username
    @Volatile var password: String? = password

    private val customName: String? = name

    /** The user-defined name, or the host when none is set. */
    val name: String
        get() = customName?.takeIf { it.isNotEmpty() } ?: host

    /** True if the server is stored in the database. */
    val isSaved: Boolean
        get() = id != -1L

    // Written together, but read one at a time.
    @Volatile private var resolvedHost: String? = null
    @Volatile private var resolvedPort = 0

    /** The host to connect to, after the SRV lookup when [port] is 0. May block on the lookup. */
    val srvHost: String
        get() = resolve().first

    /** The port to connect to, after the SRV lookup when [port] is 0. May block on the lookup. */
    val srvPort: Int
        get() = resolve().second

    private constructor(parcel: Parcel) : this(
        id = parcel.readLong(),
        name = parcel.readString(),
        host = parcel.readString().orEmpty(),
        port = parcel.readInt(),
        username = parcel.readString(),
        password = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeString(customName)
        parcel.writeString(host)
        parcel.writeInt(port)
        parcel.writeString(username)
        parcel.writeString(password)
    }

    override fun describeContents(): Int = 0

    /**
     * Pins the endpoint to the entered host and port (or the default port) without an SRV
     * lookup, so a proxy can resolve the host itself and no DNS query leaves the device.
     */
    @Synchronized
    fun resolveWithoutSrv() {
        resolvedHost = host
        resolvedPort = if (port != 0) port else Constants.DEFAULT_PORT
    }

    @Synchronized
    private fun resolve(): Pair<String, Int> {
        resolvedHost?.let { return it to resolvedPort }
        val (resolved, resolvedTo) = when {
            // A given port needs no SRV lookup, and neither do IP addresses and onion services.
            port != 0 -> host to port
            InetAddresses.isNumericAddress(host) || host.endsWith(".onion") -> host to Constants.DEFAULT_PORT
            else -> lookupOffThread(host) ?: (host to Constants.DEFAULT_PORT)
        }
        resolvedHost = resolved
        resolvedPort = resolvedTo
        return resolved to resolvedTo
    }

    /** Resolves `_mumble._tcp.<host>` into a host and port, or null when there is no record. */
    fun interface SrvLookup {
        fun lookup(host: String): InetSocketAddress?
    }

    companion object {
        private val TAG = Server::class.java.name

        @VisibleForTesting
        @Volatile
        var srvLookup: SrvLookup = SrvLookup(::lookupSrv)

        @JvmField
        val CREATOR: Parcelable.Creator<Server> = object : Parcelable.Creator<Server> {
            override fun createFromParcel(parcel: Parcel): Server = Server(parcel)
            override fun newArray(size: Int): Array<Server?> = arrayOfNulls(size)
        }

        /** Runs [srvLookup] on a thread of its own, which may be joined from the main thread. */
        private fun lookupOffThread(host: String): Pair<String, Int>? {
            val target = AtomicReference<InetSocketAddress?>()
            val thread = Thread { target.set(srvLookup.lookup(host)) }
            try {
                thread.start()
                thread.join()
            } catch (e: InterruptedException) {
                HumlaLog.d(TAG, "resolveSRV() $e")
            }
            return target.get()?.let { it.hostString to it.port }
        }

        private fun lookupSrv(host: String): InetSocketAddress? {
            val lookup = "_mumble._tcp.$host"
            return try {
                val res = ResolverApi.INSTANCE.resolveSrv(lookup)
                val answers = if (res.wasSuccessful()) res.answersOrEmptySet else null
                when {
                    answers == null -> null.also { HumlaLog.d(TAG, "resolveSrv $lookup: ${res.responseCode}") }
                    answers.isEmpty() -> null.also { HumlaLog.d(TAG, "resolveSrv $lookup: empty answer") }
                    else -> {
                        // TODO SRV just picking the first record.
                        val srv = SrvUtil.sortSrvRecords(answers)[0]
                        HumlaLog.d(TAG, "resolved $lookup SRV: $srv")
                        InetSocketAddress.createUnresolved(srv.target.toString(), srv.port)
                    }
                }
            } catch (e: IOException) {
                HumlaLog.d(TAG, "exception in srvResolve: $e")
                null
            } catch (e: IllegalArgumentException) {
                // java.net.IDN.toASCII inside resolveSrv() throws IAE (MiniDNS issue 104).
                HumlaLog.d(TAG, "exception in srvResolve: $e")
                null
            }
        }
    }
}
