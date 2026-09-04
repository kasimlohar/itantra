#include <gtest/gtest.h>
#include "audio/ring_buffer.h"
#include <atomic>
#include <thread>
#include <vector>

using itantra::audio::SpscRingBuffer;

// 1. Empty / full behaviour
TEST(RingBuffer, EmptyFullBehaviour) {
    SpscRingBuffer<int16_t> rb(4096);
    EXPECT_TRUE(rb.empty());
    EXPECT_FALSE(rb.full());
    EXPECT_EQ(rb.size(), 0u);
    EXPECT_EQ(rb.capacity(), 4096u);
    int16_t v = 1;
    for (size_t i = 0; i < 4096; ++i) ASSERT_TRUE(rb.pushOne(v));
    EXPECT_TRUE(rb.full());
    EXPECT_EQ(rb.size(), 4096u);
    EXPECT_FALSE(rb.empty());
}

// 2. Push / pop of single 30 ms frame (480 samples @ 16 kHz)
TEST(RingBuffer, PushPopSingle30msFrame) {
    SpscRingBuffer<int16_t> rb(4096);
    int16_t in[480];
    for (int i = 0; i < 480; ++i) in[i] = static_cast<int16_t>(i);
    EXPECT_TRUE(rb.push(in, 480));
    EXPECT_EQ(rb.size(), 480u);
    int16_t out[480] = {};
    EXPECT_TRUE(rb.pop(out, 480));
    EXPECT_EQ(rb.size(), 0u);
    for (int i = 0; i < 480; ++i) EXPECT_EQ(out[i], in[i]);
    EXPECT_TRUE(rb.empty());
}

// 3. Capacity for ≥200 ms pre-roll (≈3200 samples)
TEST(RingBuffer, CapacityAtLeast200msPreRoll) {
    SpscRingBuffer<int16_t> rb(4096);
    EXPECT_GE(rb.capacity(), 3200u);
    int16_t frame[480] = {1};
    for (int i = 0; i < 7; ++i) ASSERT_TRUE(rb.push(frame, 480));
    EXPECT_EQ(rb.size(), 7u * 480);
    EXPECT_GE(rb.size(), 3200u);
    // ensure 8th frame still fits (3360+480=3840 <4096)
    EXPECT_TRUE(rb.push(frame, 480));
    EXPECT_EQ(rb.size(), 8u * 480);
}

// 4. Overwrite / drop policy when full (document: overwrite oldest, keep newest)
TEST(RingBuffer, OverwritePolicyWhenFull) {
    SpscRingBuffer<int16_t> rb(8);
    for (int i = 0; i < 8; ++i) ASSERT_TRUE(rb.pushOne(static_cast<int16_t>(i)));
    EXPECT_TRUE(rb.full());
    // Next push should succeed and overwrite oldest (0)
    EXPECT_TRUE(rb.pushOne(99));
    EXPECT_EQ(rb.size(), 8u);
    EXPECT_TRUE(rb.full());
    int16_t v;
    EXPECT_TRUE(rb.popOne(v));
    EXPECT_EQ(v, 1); // 0 was dropped, next is 1
    // Remaining should be 2,3,4,5,6,7,99 in order
    std::vector<int16_t> rest;
    while (rb.popOne(v)) rest.push_back(v);
    ASSERT_EQ(rest.size(), 7u);
    EXPECT_EQ(rest[0], 2);
    EXPECT_EQ(rest[6], 99);
}

// 5. Clear / reset
TEST(RingBuffer, ClearReset) {
    SpscRingBuffer<int16_t> rb(1024);
    int16_t f[100] = {5};
    rb.push(f, 100);
    EXPECT_EQ(rb.size(), 100u);
    rb.clear();
    EXPECT_TRUE(rb.empty());
    EXPECT_EQ(rb.size(), 0u);
    EXPECT_FALSE(rb.full());
    // after clear, push again works
    EXPECT_TRUE(rb.push(f, 100));
    EXPECT_EQ(rb.size(), 100u);
}

// 6. FIFO order preserved
TEST(RingBuffer, FifoOrderPreserved) {
    SpscRingBuffer<int16_t> rb(16);
    for (int i = 0; i < 5; ++i) rb.pushOne(static_cast<int16_t>(i));
    for (int i = 0; i < 5; ++i) {
        int16_t v; ASSERT_TRUE(rb.popOne(v));
        EXPECT_EQ(v, i);
    }
    EXPECT_TRUE(rb.empty());
}

// 7. Wrap around
TEST(RingBuffer, WrapAround) {
    SpscRingBuffer<int16_t> rb(8);
    // Fill 6, pop 4, push 6 more to force wrap
    for (int i = 0; i < 6; ++i) rb.pushOne(static_cast<int16_t>(i));
    for (int i = 0; i < 4; ++i) { int16_t v; rb.popOne(v); EXPECT_EQ(v, i); }
    EXPECT_EQ(rb.size(), 2u);
    for (int i = 6; i < 12; ++i) rb.pushOne(static_cast<int16_t>(i));
    EXPECT_EQ(rb.size(), 8u);
    // Now pop all and verify order: should be 4,5,6,7,8,9,10,11
    for (int expected = 4; expected < 12; ++expected) {
        int16_t v; ASSERT_TRUE(rb.popOne(v));
        EXPECT_EQ(v, expected);
    }
}

// 8. Thread-safety SPSC stress (basic concurrent push+pop, ASAN clean)
TEST(RingBuffer, ThreadSafetySPSCStress) {
    SpscRingBuffer<int16_t> rb(4096);
    const int frames = 8; // 8*480=3840 <4096, fits without overwrite for deterministic pop
    int16_t frame[480];
    for (int i = 0; i < 480; ++i) frame[i] = 1;
    std::atomic<int> popped{0};
    std::thread prod([&]() {
        for (int i = 0; i < frames; ++i) {
            // retry until push succeeds (should always succeed with enough capacity and proper interleaving)
            while (!rb.push(frame, 480)) { std::this_thread::yield(); }
        }
    });
    std::thread cons([&]() {
        int16_t out[480];
        int local = 0;
        while (local < frames) {
            if (rb.pop(out, 480)) ++local;
            else std::this_thread::yield();
        }
        popped.store(local);
    });
    prod.join();
    cons.join();
    EXPECT_EQ(popped.load(), frames);
    EXPECT_LE(rb.size(), rb.capacity());
}

// 9. Zero-copy peekContiguous
TEST(RingBuffer, ZeroCopyPeekContiguous) {
    SpscRingBuffer<int16_t> rb(8);
    for (int i = 0; i < 4; ++i) rb.pushOne(static_cast<int16_t>(i));
    auto peek = rb.peekContiguous();
    EXPECT_GE(peek.second, 1u);
    EXPECT_LE(peek.second, 4u);
    EXPECT_EQ(peek.first[0], 0);
    rb.consume(2);
    EXPECT_EQ(rb.size(), 2u);
    int16_t v;
    rb.popOne(v); EXPECT_EQ(v, 2);
}
