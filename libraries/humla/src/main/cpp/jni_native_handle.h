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
 *     Every cell stays reachable from the table, so LeakSanitizer does not report them.
 *
 * What this does NOT do is make release() safe to call *concurrently* with a process() call that
 * is already inside the native object. Closing that would mean either a lock on the audio path
 * or reference counting on every frame, and the audio thread in this app must not block. The
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
     *  Lock-free; safe to call from the audio thread. */
    void* get(jlong handle) const noexcept {
        if (handle == 0) return nullptr;
        return reinterpret_cast<const Cell*>(handle)->object.load(std::memory_order_acquire);
    }

    /** Takes the object out of the cell. Returns it to exactly one caller; every other call for
     *  the same handle, and any handle this table did not hand out, returns nullptr. */
    void* release(jlong handle) noexcept {
        if (handle == 0) return nullptr;
        Cell* cell = reinterpret_cast<Cell*>(handle);
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            // Membership is checked before the cell is dereferenced, so a handle that never came
            // from add() -- a stale jlong, a value mangled on the Kotlin side -- is rejected
            // instead of being treated as a pointer.
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
