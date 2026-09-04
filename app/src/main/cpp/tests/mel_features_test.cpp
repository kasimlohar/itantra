#include "asr/mel_features.h"
#include <gtest/gtest.h>
#include <cmath>
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

TEST(Mel, NumFrames) {
    EXPECT_EQ(itantra::asr::numFramesFor(16000), 98); // 1 + (16000-400)/160 =98
    EXPECT_EQ(itantra::asr::numFramesFor(160), 0); // < win 400 => 0
    EXPECT_EQ(itantra::asr::numFramesFor(400), 1);
    EXPECT_EQ(itantra::asr::numFramesFor(560), 2); // 400 +160
    EXPECT_EQ(itantra::asr::numFramesFor(0), 0);
}

TEST(Mel, Shape80) {
    int16_t pcm[16000] = {0};
    auto m = itantra::asr::computeLogMel(pcm, 16000);
    ASSERT_EQ(m.size(), 98u);
    ASSERT_EQ(m[0].size(), 80u);
}

TEST(Mel, SilenceGivesLowEnergy) {
    int16_t pcm[16000] = {0};
    auto m = itantra::asr::computeLogMel(pcm, 16000);
    for (auto &f : m) for (float v : f) EXPECT_LT(v, -5.0f);
}

TEST(Mel, SineGivesHigherEnergy) {
    int16_t pcm[16000];
    for (int i = 0; i < 16000; i++) pcm[i] = (int16_t)(8000 * std::sin(2 * M_PI * 440 * i / 16000));
    auto m = itantra::asr::computeLogMel(pcm, 16000);
    float avg = 0;
    for (auto &f : m) for (float v : f) avg += v;
    avg /= m.size() * 80;
    EXPECT_GT(avg, -10.0f);
}

TEST(Mel, EmptyReturnsEmpty) {
    auto m = itantra::asr::computeLogMel(nullptr, 0);
    EXPECT_TRUE(m.empty());
}
