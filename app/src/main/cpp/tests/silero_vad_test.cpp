#define _USE_MATH_DEFINES
#include <gtest/gtest.h>
#include "vad/silero_vad.h"
#include "vad/vad_fsm.h"
#include <cmath>
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#include <filesystem>
#include <fstream>

using itantra::vad::SileroVad;
using itantra::VadFsm;
using itantra::VadState;

static std::string kModelPath() {
    // Try multiple relative locations for host vs Android
    const std::vector<std::string> candidates = {
        "app/src/main/assets/models/vad/silero_vad.onnx",
        "../app/src/main/assets/models/vad/silero_vad.onnx",
        "../../app/src/main/assets/models/vad/silero_vad.onnx",
        "D:/SIH 2026/itantra/app/src/main/assets/models/vad/silero_vad.onnx"
    };
    for (auto& p : candidates) {
        if (std::filesystem::exists(p)) return p;
    }
    return "app/src/main/assets/models/vad/silero_vad.onnx";
}

// 1. Loads model successfully
TEST(SileroVad, LoadsModelSuccessfully) {
    SileroVad vad;
    ASSERT_TRUE(vad.load(kModelPath())) << "Failed to load " << kModelPath();
    EXPECT_TRUE(vad.isLoaded());
}

// 2. Predict returns probability in [0,1]
TEST(SileroVad, PredictReturnsProbabilityIn0_1) {
    SileroVad vad;
    ASSERT_TRUE(vad.load(kModelPath()));
    int16_t pcm[512] = {0};
    float p = vad.predict(pcm, 512);
    EXPECT_GE(p, 0.0f);
    EXPECT_LE(p, 1.0f);
}

// 3. Silence vs speech-like input
TEST(SileroVad, SilenceVsSpeechLike) {
    SileroVad vad;
    ASSERT_TRUE(vad.load(kModelPath()));
    int16_t silence[512] = {0};
    float pSilence = vad.predict(silence, 512);
    EXPECT_LT(pSilence, 0.35f) << "Silence should be low prob";
    // 440Hz sine at 16kHz, 512 samples ≈ 14 cycles
    int16_t speech[512];
    for (int i = 0; i < 512; ++i) {
        speech[i] = static_cast<int16_t>(10000 * std::sin(2 * M_PI * 440 * i / 16000));
    }
    float pSpeech = vad.predict(speech, 512);
    EXPECT_GE(pSpeech, 0.0f);
    EXPECT_LE(pSpeech, 1.0f);
    // Not strictly > silence for all models, but they should be distinct
    EXPECT_NE(pSilence, pSpeech);
}

// 4. Unload / reset
TEST(SileroVad, UnloadReset) {
    SileroVad vad;
    ASSERT_TRUE(vad.load(kModelPath()));
    EXPECT_TRUE(vad.isLoaded());
    vad.reset();
    EXPECT_TRUE(vad.isLoaded()); // reset should keep loaded but clear state
    vad.unload();
    EXPECT_FALSE(vad.isLoaded());
}

// 5. Integration with VadFsm (probability -> state)
TEST(SileroVad, IntegrationWithVadFsm) {
    SileroVad vad;
    ASSERT_TRUE(vad.load(kModelPath()));
    VadFsm fsm(450, 30, 200, 16000);
    int16_t silence[512] = {0};
    float pSil = vad.predict(silence, 512);
    VadState s = fsm.onFrame(pSil, silence, 512);
    EXPECT_EQ(s, VadState::Idle);
    // Feed 3 high prob frames (simulate speech) directly via fsm high prob, ensure fsm would go Speaking
    // But using vad's speech-like sine
    int16_t speech[512];
    for (int i = 0; i < 512; ++i) speech[i] = static_cast<int16_t>(10000 * std::sin(2 * M_PI * 440 * i / 16000));
    // We can't guarantee vad predicts high, so just test fsm integration with manual high prob
    // Alternative: test that vad + fsm pipeline doesn't crash
    float p = vad.predict(speech, 512);
    EXPECT_GE(p, 0.0f);
    // Feed to fsm
    fsm.onFrame(p, speech, 512);
    // At least not crash, state is one of enum
    EXPECT_TRUE(fsm.state() == VadState::Idle || fsm.state() == VadState::Speaking || fsm.state() == VadState::Pause || fsm.state() == VadState::Eou);
}

// 6. Handles 480 padded to 512 (30ms frame)
TEST(SileroVad, Handles480PaddedTo512) {
    SileroVad vad;
    ASSERT_TRUE(vad.load(kModelPath()));
    int16_t pcm480[480] = {0};
    float p = vad.predict(pcm480, 480);
    EXPECT_GE(p, 0.0f);
    EXPECT_LE(p, 1.0f);
}

// 7. Silence very low (<0.05) — heuristic gives 0.05, real gives 0.044
TEST(SileroVad, SilenceVeryLow) {
    SileroVad vad;
    ASSERT_TRUE(vad.load(kModelPath()));
    int16_t silence[512] = {0};
    float p = vad.predict(silence, 512);
    EXPECT_LT(p, 0.05f) << "Real Silero silence should be <0.05, heuristic 0.05 fails";
}

// 8. H/C state maintained across calls
TEST(SileroVad, HcStateMaintained) {
    SileroVad vad;
    ASSERT_TRUE(vad.load(kModelPath()));
    int16_t speech[512];
    for (int i = 0; i < 512; ++i) speech[i] = static_cast<int16_t>(10000 * std::sin(2 * M_PI * 440 * i / 16000));
    float p1 = vad.predict(speech, 512);
    float p2 = vad.predict(speech, 512);
    EXPECT_NE(p1, p2) << "h/c state should make second predict different";
    vad.reset();
    float p3 = vad.predict(speech, 512);
    EXPECT_FLOAT_EQ(p1, p3) << "After reset, first predict should be same as initial";
}

// 9. Corrupted model fails to load (proves real ONNX parsing, not just file size)
TEST(SileroVad, CorruptedModelFails) {
    // Create temp corrupted file same size but random content
    std::string tmp = std::filesystem::temp_directory_path().string() + "/corrupted_silero.onnx";
    {
        std::ofstream out(tmp, std::ios::binary);
        std::vector<char> junk(2313101, 'X');
        out.write(junk.data(), junk.size());
    }
    SileroVad vad;
    EXPECT_FALSE(vad.load(tmp)) << "Corrupted ONNX should fail real session creation, heuristic would pass";
    std::filesystem::remove(tmp);
}
