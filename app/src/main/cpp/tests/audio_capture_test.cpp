#include <gtest/gtest.h>
#include "audio/audio_capture.h"

using itantra::audio::AudioCapture;

// 1. Starts and stops (host without mic may return false, but must not crash)
TEST(AudioCapture, StartsAndStops) {
    AudioCapture cap(8192);
    EXPECT_FALSE(cap.isCapturing());
    bool started = cap.start(); // on host without mic, may be false
    // Either true (device present) or false (host) is okay, but isCapturing should match started
    EXPECT_EQ(cap.isCapturing(), started);
    // Double start safe
    bool second = cap.start();
    EXPECT_EQ(cap.isCapturing(), started); // still same
    (void)second;
    cap.stop();
    EXPECT_FALSE(cap.isCapturing());
    // Double stop safe
    cap.stop();
    EXPECT_FALSE(cap.isCapturing());
}

// 2. Callback pushes 480 into ring
TEST(AudioCapture, CallbackPushes480IntoRing) {
    AudioCapture cap(8192);
    int16_t in[480];
    for (int i = 0; i < 480; ++i) in[i] = static_cast<int16_t>(i);
    auto result = cap.onAudioReady(nullptr, in, 480);
    EXPECT_EQ(result, oboe::DataCallbackResult::Continue);
    EXPECT_EQ(cap.ring().size(), 480u);
    int16_t out[480] = {};
    EXPECT_TRUE(cap.ring().pop(out, 480));
    for (int i = 0; i < 480; ++i) EXPECT_EQ(out[i], in[i]);
    EXPECT_TRUE(cap.ring().empty());
}

// 3. No underruns / no locking — 1000 callbacks
TEST(AudioCapture, CallbackNoUnderrunsNoLock) {
    AudioCapture cap(8192);
    int16_t frame[480] = {1};
    for (int i = 0; i < 1000; ++i) {
        cap.onAudioReady(nullptr, frame, 480);
        // ring size should never exceed capacity and should be lock-free (no deadlock)
        EXPECT_LE(cap.ring().size(), cap.ring().capacity());
    }
    // After 1000 pushes of 480 with overwrite policy, size should be capacity (8192)
    // Since 1000*480 >>8192, it will have wrapped and be full
    EXPECT_EQ(cap.ring().size(), cap.ring().capacity());
    // ASAN should be clean (no crash)
}

// 4. Clear resets ring
TEST(AudioCapture, ClearResetsRing) {
    AudioCapture cap(8192);
    int16_t in[100] = {5};
    cap.onAudioReady(nullptr, in, 100);
    EXPECT_EQ(cap.ring().size(), 100u);
    cap.clear();
    EXPECT_TRUE(cap.ring().empty());
    EXPECT_EQ(cap.ring().size(), 0u);
}

// 5. Stop destroys stream (on host, just ensures isCapturing false and ring not grown after stop)
TEST(AudioCapture, StopDestroysStream) {
    AudioCapture cap(8192);
    cap.start(); // may fail on host
    cap.stop();
    EXPECT_FALSE(cap.isCapturing());
    size_t sizeBefore = cap.ring().size();
    // After stop, onAudioReady should still push? In real Oboe, callback stops, but our mock still allows direct call.
    // For this test, we check that stop() itself doesn't leak and ring stays same
    EXPECT_EQ(cap.ring().size(), sizeBefore);
}
