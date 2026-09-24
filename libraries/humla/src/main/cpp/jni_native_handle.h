/*
 * Handles that survive being released twice, and being used after release.
 *
 * The RNNoise and WebRTC APM bridges hand out cells, not object pointers: one word each, holding
 * the object pointer.
 *
 *   - release() atomically takes the pointer out of the cell. Exactly one caller gets it and
 *     destroys the object; every later release() of the same handle is a no-op.
 *   - get() reads the cell. After release() it reads nullptr, so a late frame from the audio
 *     thread becomes an error return instead of a use-after-free.
 *   - Cells are never freed or reused, so a stale handle stays safe to dereference. The cost is
 *     one small heap cell per create() (per audio session, not per frame). Cells stay reachable
 *     from the table, so LeakSanitizer will not flag a runaway create(); watch calls to add().
 *
 * Caller contract: a handle may only be passed back to the bridge (table) that issued it.
 * release() checks membership under the mutex and refuses foreign or stale values, but get()
 * runs on the audio thread, must not block, and therefore dereferences whatever it is given.
 * A handle from another bridge is a type-confused read; an invented jlong is a segfault. This is
 * enforced Kotlin-side: one handle in one private field of one owner.
 *
 * release() is not safe against a process() call already inside the native object; the owner
 * must stop feeding frames before releasing.
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
        // Release pairs with the acquire load in get(). Host tests (x86_64, TSO) cannot catch a
        // weakening to relaxed; this matters on arm64.
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
     *  handle MUST be 0 or a handle THIS table handed out: membership is not checked. */
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
            // Check membership before dereferencing, so foreign or mangled handles are refused.
            if (cells_.find(cell) == cells_.end()) return nullptr;
        } catch (...) {
            return nullptr;
        }
        // The cell is deliberately kept (see file comment).
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
