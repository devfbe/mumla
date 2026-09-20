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
 * A [CapturePreprocessor] that owns one native handle, behind **one lock covering both audio
 * streams and the release**.
 *
 * Every stage in this package that holds native state extends this, and none of them writes its
 * own locking. That is the point: two stages with two correct locks are still two locks, and the
 * thing that has to hold is a property of the pair.
 *
 * ### Why one lock, and why it has to cover the playback thread too
 *
 * The WebRTC APM has two audio threads: `processRender` on the playback thread and
 * `processCapture` on the capture thread. The JNI handle table (`jni_native_handle.h`) makes a
 * use-after-release an error code rather than heap corruption, but it does not make `destroy()`
 * safe against a `process()` that is already running -- its contract is "stop feeding, then
 * release". The window is not a few instructions either: it spans `GetArrayLength` *and*
 * `GetShortArrayElements` (`jni_webrtc_apm.cpp:55-58`), i.e. an array copy that can take a
 * garbage collection pause.
 *
 * `AudioHandler.java:220-225,467-482` serialises `encode()` and `destroy()` through
 * `mEncoderLock`, which is why the window is not reachable today -- but `mEncoderLock` knows only
 * the capture path. The moment the far-end feed is wired up (task 10), the playback thread can be
 * inside `processRender` while the control thread frees the handle, and `mEncoderLock` will not
 * have been taken by either of them. Hence spec §4.1: one lock, not two adapters.
 *
 * What it costs: the playback thread can wait for one capture frame's native call and the other
 * way round -- a few hundred microseconds at 48 kHz, once per 10 ms tick, and only when the two
 * ticks actually collide. Taking the lock allocates nothing (measured in
 * `CaptureThreadAllocationTest`). The alternative is a free of the state another audio thread is
 * reading from.
 *
 * ### Why the handle lives here and nowhere else
 *
 * Spec §4.1 again: `HandleTable::get()` dereferences without validating, so handing an RNNoise
 * handle to the APM bridge is a segfault with no Java stack trace, and the checkable place for
 * that is Kotlin-side. So the handle is one private field of one owner, it is never returned by
 * any member, and a subclass only ever sees it as an argument it is expected to pass straight
 * back to its own bridge. `SingleHandleStageTest` pins that as a property of the class rather
 * than of today's members.
 *
 * ### Life cycle
 *
 * [release] clears the handle under the lock before freeing it, so a stage that has been released
 * is a stage that does nothing: [process] returns null without a native call and the far-end path
 * drops the frame. That is what makes a mode switch in the middle of a sentence survivable -- the
 * old chain can still have a frame in it when it is released.
 *
 * @param handle the handle the bridge issued, or 0 if it could not create one.
 * @param what what failed, for the exception message; user-facing text does not belong here.
 */
abstract class SingleHandleStage protected constructor(handle: Long, what: String) : CapturePreprocessor {
    /**
     * The one lock. It covers the capture path, the far-end path and [release]. Do not add a
     * second one, and do not narrow this one to the capture path -- see the class KDoc.
     */
    private val lock = Any()

    /** The one field that holds this stage's handle. 0 once released, and never seen by anyone else. */
    private var handle: Long = handle

    init {
        check(handle != 0L) { "$what could not be created" }
    }

    final override fun process(frame: ShortArray): Float? = synchronized(lock) {
        val handle = this.handle
        if (handle == 0L) null else onCaptureFrame(handle, frame)
    }

    final override fun release() = synchronized(lock) {
        val handle = this.handle
        if (handle != 0L) {
            this.handle = 0L
            onReleaseHandle(handle)
        }
    }

    /**
     * The far-end entry point, under the same lock as [process] and [release]. A stage that
     * consumes the reverse stream implements [FarEndSink.analyzeReverseStream] by calling this and
     * overrides [onFarEndFrame]; a stage that does not implements neither.
     */
    protected fun farEnd(frame: ShortArray) = synchronized(lock) {
        val handle = this.handle
        if (handle != 0L) onFarEndFrame(handle, frame)
    }

    /**
     * Processes one near-end frame in place, with the lock held.
     *
     * @param handle this stage's own handle -- pass it to this stage's own bridge and to nothing
     *   else.
     */
    protected abstract fun onCaptureFrame(handle: Long, frame: ShortArray): Float?

    /**
     * Consumes one far-end frame, with the lock held. Only a stage that calls [farEnd] needs this.
     *
     * The default throws rather than doing nothing on purpose: a silently dropped reference frame
     * costs about 21 dB of echo cancellation and every test above this layer still passes (task 2
     * measured exactly that, `src/main/cpp/tests/test_apm.c`).
     */
    protected open fun onFarEndFrame(handle: Long, frame: ShortArray): Unit =
        throw UnsupportedOperationException("${javaClass.simpleName} has no far-end path")

    /** Frees [handle]. Called at most once, with the lock held and the field already cleared. */
    protected abstract fun onReleaseHandle(handle: Long)
}
