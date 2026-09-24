/*
 * Handles that survive being released twice, and being used after release.
 *
 * Every JNI bridge hands out handles from a HandleTable instead of object pointers. A handle names
 * a slot of the table and the generation the slot had when the object was added:
 *
 *   - release() takes the object out of its slot and bumps the slot's generation. Exactly one
 *     caller gets the object and destroys it; every later release() of the same handle is a no-op.
 *   - get() returns the object only while the handle's generation is still the slot's. After
 *     release() it returns nullptr, so a late frame from the audio thread becomes an error return
 *     instead of a use-after-free, even once the slot has been reused for a new object.
 *   - Slot storage is allocated in fixed chunks that are never freed or moved, so get() may read
 *     any slot without a lock. Released slots are reused, so memory is bounded by the number of
 *     live objects, not by how many were ever created.
 *   - A value that does not name a slot of this table (0, an invented number) reads as nullptr.
 *
 * Caller contract: a handle may only be passed back to the table that issued it. A handle from
 * another table names a slot of this one, which get() may answer with an unrelated object of this
 * table's type. This is enforced Kotlin-side: one handle in one private field of one owner.
 *
 * release() is not safe against a call already inside the native object; the owner must stop
 * using the handle before releasing it.
 *
 * Threading: get() is lock-free and safe from the audio thread. add() and release() take a mutex
 * and may allocate; they belong to the owning thread and are never called per frame.
 */
#ifndef HUMLA_JNI_NATIVE_HANDLE_H
#define HUMLA_JNI_NATIVE_HANDLE_H

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <new>
#include <vector>

namespace humla {

class HandleTable {
  public:
    HandleTable() = default;
    HandleTable(const HandleTable&) = delete;
    HandleTable& operator=(const HandleTable&) = delete;
    /** Only for tables that provably have no handle in use; see handleTable(). */
    ~HandleTable() {
        for (auto& chunk : chunks_) delete[] chunk.load(std::memory_order_relaxed);
    }

    /** Stores object in a free slot. Returns 0 for a null object, or if no slot can be allocated,
     *  in which case the caller still owns object and must destroy it. */
    jlong add(void* object) noexcept {
        if (object == nullptr) return 0;
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            std::uint32_t index;
            if (!free_.empty()) {
                index = free_.back();
                free_.pop_back();
            } else {
                if (used_ == kMaxSlots) return 0;
                index = used_;
                std::uint32_t chunk = index / kChunkSize;
                if (chunks_[chunk].load(std::memory_order_relaxed) == nullptr) {
                    Slot* fresh = new Slot[kChunkSize];
                    // Release pairs with the acquire load in slot().
                    chunks_[chunk].store(fresh, std::memory_order_release);
                }
                used_++;
            }
            Slot* s = slotAt(index);
            // The generation was bumped by the release() that freed the slot, under this mutex.
            std::uint32_t generation = s->generation.load(std::memory_order_relaxed);
            // Release pairs with the acquire load in get(): whoever sees the new object also sees
            // the bumped generation, so a stale handle to this slot cannot read it.
            s->object.store(object, std::memory_order_release);
            return pack(index, generation);
        } catch (...) {  // bad_alloc from the chunk or the free list, system_error from the mutex
            return 0;
        }
    }

    /** The object behind handle, or nullptr if the handle is 0, not from this table, or has been
     *  released. Lock-free; safe to call from the audio thread. */
    void* get(jlong handle) const noexcept {
        const Slot* s = slot(handle);
        if (s == nullptr) return nullptr;
        void* object = s->object.load(std::memory_order_acquire);
        if (s->generation.load(std::memory_order_acquire) != generationOf(handle)) return nullptr;
        return object;
    }

    /** Takes the object out of its slot. Returns it to exactly one caller; every other call for the
     *  same handle, and any value this table did not hand out, returns nullptr. */
    void* release(jlong handle) noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            Slot* s = slot(handle);
            if (s == nullptr) return nullptr;
            if (s->generation.load(std::memory_order_relaxed) != generationOf(handle)) return nullptr;
            void* object = s->object.exchange(nullptr, std::memory_order_acq_rel);
            if (object == nullptr) return nullptr;
            // Reserve the free-list entry before invalidating the handle, so a failed push cannot
            // leave a slot that is neither live nor reusable.
            free_.reserve(free_.size() + 1);
            s->generation.store(s->generation.load(std::memory_order_relaxed) + 1,
                                std::memory_order_release);
            free_.push_back(indexOf(handle));
            return object;
        } catch (...) {
            return nullptr;
        }
    }

  private:
    struct Slot {
        std::atomic<void*> object{nullptr};
        std::atomic<std::uint32_t> generation{1};
    };

    static constexpr std::uint32_t kChunkSize = 256;
    static constexpr std::uint32_t kMaxChunks = 256;
    static constexpr std::uint32_t kMaxSlots = kChunkSize * kMaxChunks;

    /* Low 32 bits: slot index + 1, so that no handle is 0. High 32 bits: generation. */
    static jlong pack(std::uint32_t index, std::uint32_t generation) {
        return static_cast<jlong>((static_cast<std::uint64_t>(generation) << 32) | (index + 1u));
    }
    static std::uint32_t indexOf(jlong handle) {
        return static_cast<std::uint32_t>(static_cast<std::uint64_t>(handle)) - 1u;
    }
    static std::uint32_t generationOf(jlong handle) {
        return static_cast<std::uint32_t>(static_cast<std::uint64_t>(handle) >> 32);
    }

    Slot* slotAt(std::uint32_t index) const {
        return &chunks_[index / kChunkSize].load(std::memory_order_acquire)[index % kChunkSize];
    }

    /** The slot handle names, or nullptr if it names none (0 maps to index 0xFFFFFFFF). */
    Slot* slot(jlong handle) const noexcept {
        std::uint32_t index = indexOf(handle);
        if (index >= kMaxSlots) return nullptr;
        Slot* chunk = chunks_[index / kChunkSize].load(std::memory_order_acquire);
        return chunk == nullptr ? nullptr : &chunk[index % kChunkSize];
    }

    mutable std::mutex mutex_;
    std::atomic<Slot*> chunks_[kMaxChunks] = {};
    std::uint32_t used_ = 0;         // slots ever handed out; guarded by mutex_
    std::vector<std::uint32_t> free_;  // released slots; guarded by mutex_
};

/**
 * The table for one kind of native object. Intentionally never destroyed: it must outlive every
 * handle it issued (static destruction order could hand a late audio callback a destroyed mutex),
 * and staying reachable keeps LeakSanitizer quiet about the chunks.
 */
template <typename Tag>
HandleTable& handleTable() {
    static HandleTable* table = new HandleTable();
    return *table;
}

}  // namespace humla

#endif  // HUMLA_JNI_NATIVE_HANDLE_H
