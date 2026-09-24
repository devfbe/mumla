/*
 * Copyright (C) 2026 The Mumla Authors
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

package se.lublin.humla.audio.capture

/**
 * A [CapturePreprocessor] that owns one native handle behind one lock covering the capture path,
 * the far-end (playback thread) path and [release]. All native-state stages extend this and add no
 * locking of their own.
 *
 * The native `destroy()` is not safe against a `process()` already running on another audio thread,
 * so all three entry points must share one lock. The handle table dereferences handles without
 * type-checking, so the handle stays a private field here and subclasses only see it as a callback
 * argument to pass back to their own bridge. [process], [release] and [analyzeReverseStream] are
 * final, so there is no unlocked route to the native layer.
 *
 * After [release], [process] returns null without a native call and far-end frames are dropped, so
 * releasing a chain that still has a frame in flight (mode switch mid-sentence) is safe.
 *
 * @param handle the handle the bridge issued, or 0 if it could not create one.
 * @param what what failed, for the exception message; user-facing text does not belong here.
 */
abstract class SingleHandleStage protected constructor(handle: Long, what: String) : CapturePreprocessor {
    /** Covers the capture path, the far-end path and [release]; see the class KDoc. */
    private val lock = Any()

    /** 0 once released. */
    private var handle: Long = handle

    init {
        check(handle != 0L) { "$what could not be created" }
    }

    final override fun process(frame: ShortArray): Float? = synchronized(lock) {
        val handle = this.handle
        if (handle == 0L) null else onCaptureFrame(handle, frame)
    }

    /**
     * Clears the field before freeing, so a throwing [onReleaseHandle] leaks one native object instead
     * of leaving a freed handle for later [process] calls.
     */
    final override fun release() = synchronized(lock) {
        val handle = this.handle
        if (handle != 0L) {
            this.handle = 0L
            onReleaseHandle(handle)
        }
    }

    /**
     * Far-end entry point, under the same lock as [process] and [release]. Satisfies [FarEndSink] for
     * subclasses that declare it; they override [onFarEndFrame].
     */
    fun analyzeReverseStream(frame: ShortArray) = synchronized(lock) {
        val handle = this.handle
        if (handle != 0L) onFarEndFrame(handle, frame)
    }

    /**
     * Processes one near-end frame in place, with the lock held.
     *
     * @param handle this stage's own handle; pass it only to this stage's own bridge.
     */
    protected abstract fun onCaptureFrame(handle: Long, frame: ShortArray): Float?

    /**
     * Consumes one far-end frame, with the lock held. Only [FarEndSink] stages override this. The
     * default throws rather than silently dropping, since a lost reference frame degrades AEC unnoticed.
     */
    protected open fun onFarEndFrame(handle: Long, frame: ShortArray): Unit =
        throw UnsupportedOperationException("${javaClass.simpleName} has no far-end path")

    /** Frees [handle]. Called at most once, with the lock held and the field already cleared. */
    protected abstract fun onReleaseHandle(handle: Long)
}
