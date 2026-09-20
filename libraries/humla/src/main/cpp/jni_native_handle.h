/*
 * Handles that survive being released twice, and being used after release.
 *
 * jni_handle.h carries a native pointer to Kotlin as a raw jlong. That is the cheapest thing
 * that works and it is what the codec bridges do, but it puts the whole lifetime contract on the
 * Kotlin side: release() must run exactly once, and no other thread may be inside the object
 * when it does. Both are ordinary mistakes -- an adapter closed explicitly and then finalised, a
 * capture thread that delivers one more 10 ms frame after the session was torn down -- and both
 * are heap corruption, in native code, with no Java stack trace and usually no crash at the site
 * of the bug. A double-free guard has already gone missing once in this project.
 *
 * So the handles the RNNoise and WebRTC APM bridges hand out are not the objects themselves.
 * They are cells: one word each, holding the object pointer.
 *
 *   - release() atomically takes the pointer out of the cell. Exactly one caller gets it and
 *     destroys the object; every later release() of the same handle gets nullptr and does
 *     nothing. Releasing twice is a no-op, not a double free.
 *   - get() reads the cell. After release() it reads nullptr, so a late frame from the audio
 *     thread turns into an error return instead of a use-after-free.
 *   - Cells are never freed and never reused. That is the point, not an oversight: a stale
 *     handle has to stay safe to dereference, which it cannot be if the cell it points at can
 *     come back as a different object. The cost is one heap cell per create() -- tens of bytes
 *     over an app's lifetime, since these objects are created per audio session, not per frame.
 *     Every cell stays reachable from the table, so LeakSanitizer does not report them. That
 *     last part cuts both ways: nothing here bounds or reports the number of cells, and because
 *     they stay reachable LSan will not either. A create() that ran away -- once per codec
 *     switch, or worse per frame, instead of once per session -- would be invisible to every
 *     check this repository has. The number to watch is calls to add(), not bytes.
 *
 * What the table does NOT validate is the handle get() is given. release() checks membership in
 * cells_ under the mutex before dereferencing, so a stale, mangled or foreign jlong is refused.
 * get() runs on the audio thread, cannot take that mutex, and therefore dereferences whatever it
 * is handed. The caller contract is consequently stricter than release() alone suggests:
 *
 *     A handle may only be passed back to the bridge that issued it.
 *
 * Each bridge has its own HandleTable in its own .so, so the RNNoise handle given to the APM
 * bridge is a valid pointer to the wrong kind of cell -- a type-confused dereference that returns
 * plausible nonsense -- and an invented or uninitialised jlong is a segmentation fault with no
 * Java stack trace. The reachable ways to get there are a swapped argument in an adapter and a
 * field read before it is assigned, both Kotlin-side mistakes.
 *
 * Note what the reason for that is, because it is easy to write down the wrong one. It is NOT
 * that a check would be incomplete. The complete check is already in this file: release()'s
 * cells_.find(cell) compares pointer VALUES and never dereferences the handle, so it catches both
 * the invented jlong and the other bridge's -- which is more than a marker word inside the cell
 * could do, since reading a marker means dereferencing the very pointer in question. The reason
 * get() does not do it is the mutex: get() runs on the audio thread, which must not block. Cost,
 * not coverage. Anything that closed it here -- a lock, a lock-free side table, a generation
 * counter read under one -- would be paid per frame.
 *
 * Which means the place where this contract can actually be enforced is Kotlin: one handle in one
 * private field of one owner, with the adapter's own tests holding that down. Repeating the
 * sentence in three files (here, RnnoiseNative.kt, WebRtcApmNative.kt) documents a rule that
 * nothing checks; it is worth it only until the adapters exist.
 *
 * What this does NOT do either is make release() safe to call *concurrently* with a process()
 * call that is already inside the native object. Closing that would mean either a lock on the
 * audio path or reference counting on every frame, and the audio thread must not block. The
 * contract therefore remains: whoever owns the handle stops feeding frames before releasing it.
 * The difference is that getting it slightly wrong -- releasing while a frame is queued, or
 * releasing twice -- is now an error code rather than heap corruption.
 *
 * Threading: get() is lock-free and safe from the audio thread. add() and release() take a
 * mutex and are for the owning thread; they are never called per frame.
 */
#ifndef HUMLA_JNI_NATIVE_HANDLE_H
#define HUMLA_JNI_NATIVE_HANDLE_H

#include <jni.h>

#include <atomic>
#include <mutex>
#include <new>
#include <unordered_set>

namespace humla {

class HandleTable {
  public:
    /** Wraps object in a fresh cell. Returns 0 for a null object or if the cell cannot be
     *  allocated, in which case the caller still owns object and must destroy it. */
    jlong add(void* object) noexcept {
        if (object == nullptr) return 0;
        Cell* cell = new (std::nothrow) Cell();
        if (cell == nullptr) return 0;
        // Release, not relaxed: get() loads with acquire, and an acquire has nothing to
        // synchronise with unless the store that publishes the pointer is a release. On arm64
        // that is one STLR instead of one STR, off the audio path -- add() runs per session.
        //
        // Nothing in this repository can pin this line, and nothing here should claim to. The
        // host tests run on x86_64, whose TSO model compiles a relaxed store and a release store
        // to the same MOV, so weakening this to relaxed keeps every ctest green -- on the host,
        // and only on the host. The argument for it is the memory model, not a measurement;
        // a device test on arm64 with two threads would be the measurement.
        cell->object.store(object, std::memory_order_release);
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            cells_.insert(cell);
        } catch (...) {  // bad_alloc from the set, system_error from the mutex
            delete cell;
            return 0;
        }
        return reinterpret_cast<jlong>(cell);
    }

    /** The object behind handle, or nullptr if the handle is 0 or has been released.
     *  Lock-free; safe to call from the audio thread.
     *
     *  handle MUST be 0 or a handle THIS table handed out. Unlike release() below, this does not
     *  check membership -- it dereferences the value as a Cell*. See the caller contract in the
     *  file comment. */
    void* get(jlong handle) const noexcept {
        if (handle == 0) return nullptr;
        return reinterpret_cast<const Cell*>(handle)->object.load(std::memory_order_acquire);
    }

    /** Takes the object out of the cell. Returns it to exactly one caller; every other call for
     *  the same handle, and any handle this table did not hand out, returns nullptr.
     *
     *  This is the only entry point that validates its handle, because it is the only one that
     *  can afford the mutex; get() cannot. */
    void* release(jlong handle) noexcept {
        if (handle == 0) return nullptr;
        Cell* cell = reinterpret_cast<Cell*>(handle);
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            // Membership is checked before the cell is dereferenced, so a handle that never came
            // from THIS table -- a stale jlong, a value mangled on the Kotlin side, the other
            // bridge's handle -- is rejected here instead of being treated as a pointer. This
            // guard protects release() only: get() runs on the audio thread and cannot take the
            // mutex, so it dereferences whatever it is given. See the file comment.
            if (cells_.find(cell) == cells_.end()) return nullptr;
        } catch (...) {
            return nullptr;
        }
        // The cell itself is deliberately kept, and kept in cells_: see the file comment.
        return cell->object.exchange(nullptr, std::memory_order_acq_rel);
    }

  private:
    struct Cell {
        std::atomic<void*> object{nullptr};
    };

    mutable std::mutex mutex_;
    std::unordered_set<Cell*> cells_;
};

}  // namespace humla

#endif  // HUMLA_JNI_NATIVE_HANDLE_H
