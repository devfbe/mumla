/* Host test for humla::HandleTable (../jni_native_handle.h), the handle convention of every JNI
 * bridge: exactly-once release, stale handles that read as nullptr even after their slot has been
 * reused, and memory bounded by the number of live objects. */
#include "jni_native_handle.h"

#include <cstdint>
#include <cstdio>

static int failures = 0;
#define CHECK(cond, msg)                              \
    do {                                              \
        if (!(cond)) {                                \
            std::fprintf(stderr, "FAIL: %s\n", (msg));\
            failures++;                               \
        }                                             \
    } while (0)

static int a, b, c;

static void test_lifecycle() {
    humla::HandleTable table;
    CHECK(table.add(nullptr) == 0, "a null object gets no handle");

    jlong ha = table.add(&a);
    CHECK(ha != 0, "add hands out a non-zero handle");
    CHECK(table.get(ha) == &a, "get returns the object");

    CHECK(table.release(ha) == &a, "the first release returns the object");
    CHECK(table.release(ha) == nullptr, "a second release returns nothing");
    CHECK(table.get(ha) == nullptr, "a released handle reads as nullptr");

    /* The slot is reused for the next object; the old handle must not see it. */
    jlong hb = table.add(&b);
    CHECK(hb != 0 && hb != ha, "a reused slot gets a new handle");
    CHECK(table.get(hb) == &b, "the new handle reads the new object");
    CHECK(table.get(ha) == nullptr, "the stale handle to the reused slot still reads as nullptr");
    CHECK(table.release(ha) == nullptr, "the stale handle cannot release the new object");
    CHECK(table.get(hb) == &b, "the new object survives the stale release");
    CHECK(table.release(hb) == &b, "and is released by its own handle");
}

static void test_foreign_values() {
    humla::HandleTable table;
    jlong h = table.add(&a);

    CHECK(table.get(0) == nullptr, "0 reads as nullptr");
    CHECK(table.release(0) == nullptr, "0 releases nothing");
    CHECK(table.get(0xdeadbeef) == nullptr, "an invented handle reads as nullptr");
    CHECK(table.release(0xdeadbeef) == nullptr, "an invented handle releases nothing");
    CHECK(table.get(-1) == nullptr, "-1 reads as nullptr");
    CHECK(table.get(h + 1) == nullptr, "a handle to a slot never handed out reads as nullptr");
    CHECK(table.get(h ^ (jlong(1) << 40)) == nullptr, "a handle with a wrong generation reads as nullptr");
    CHECK(table.release(h ^ (jlong(1) << 40)) == nullptr, "and releases nothing");
    CHECK(table.get(h) == &a, "the real handle is unaffected");
    table.release(h);
}

/* Released slots are reused, so a long session creating and destroying objects does not grow the
 * table: after many cycles a new handle still names one of the first slots. */
static void test_slots_are_reused() {
    humla::HandleTable table;
    jlong live = table.add(&c);
    for (int i = 0; i < 200000; i++) {
        jlong h = table.add(&a);
        if (h == 0 || table.release(h) != &a) {
            CHECK(false, "an add/release cycle fails");
            return;
        }
    }
    jlong h = table.add(&b);
    CHECK((static_cast<std::uint64_t>(h) & 0xffffffffu) <= 2u, "a slot freed 200000 times is reused");
    CHECK(table.get(live) == &c, "a long-lived handle is untouched by the churn around it");
    table.release(h);
    table.release(live);
}

/* Many live objects at once span several chunks; each handle keeps its own object. */
static void test_many_live_objects() {
    humla::HandleTable table;
    static int objects[2000];
    static jlong handles[2000];
    for (int i = 0; i < 2000; i++) handles[i] = table.add(&objects[i]);
    bool all = true;
    for (int i = 0; i < 2000; i++) all = all && handles[i] != 0 && table.get(handles[i]) == &objects[i];
    CHECK(all, "2000 live handles each read their own object");
    for (int i = 0; i < 2000; i += 2) table.release(handles[i]);
    all = true;
    for (int i = 0; i < 2000; i++) {
        void* expected = (i % 2 == 0) ? nullptr : &objects[i];
        all = all && table.get(handles[i]) == expected;
    }
    CHECK(all, "releasing every other handle leaves the rest intact");
    for (int i = 1; i < 2000; i += 2) table.release(handles[i]);
}

int main() {
    test_lifecycle();
    test_foreign_values();
    test_slots_are_reused();
    test_many_live_objects();
    if (failures != 0) {
        std::fprintf(stderr, "%d failure(s)\n", failures);
        return 1;
    }
    std::puts("handle_table: all checks passed");
    return 0;
}
