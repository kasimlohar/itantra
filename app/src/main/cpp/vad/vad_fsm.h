#pragma once
#include <cstdint>
#include <cstddef>
#include <vector>
#include <deque>

namespace itantra {

enum class VadState {
    Idle,
    Speaking,
    Pause,
    Eou
};

/**
 * Pure FSM per PRD US-03 / FR-05, mirrors Kotlin com.itantra.data.vad.VadFsm.
 * Thresholds: onset p>=0.6 for 3 consecutive 30ms frames, sustain p>=0.35,
 * hangover 450 ms default (PTT) / 550 ms phone, pre-roll 200 ms.
 * Silero inference stubbed: caller supplies prob per 30ms frame.
 */
class VadFsm {
public:
    explicit VadFsm(int hangoverMs = 450, int frameMs = 30, int preRollMs = 200, int sampleRate = 16000);
    VadState onFrame(float prob, const int16_t* pcm = nullptr, size_t samples = 0);
    std::vector<int16_t> getUtterance() const;
    void reset();
    void setHangoverMs(int ms);
    VadState state() const { return state_; }

private:
    void handleIdle(float prob, const int16_t* pcm, size_t samples);
    void handleSpeaking(float prob, const int16_t* pcm, size_t samples);
    void handlePause(float prob, const int16_t* pcm, size_t samples);

    int hangoverMs_;
    int frameMs_;
    int frameSamples_;
    int hangoverFrames_;
    int preRollFrames_;

    VadState state_;
    int onsetCounter_;
    int pauseCounter_;
    std::deque<std::vector<int16_t>> preRoll_;
    std::vector<int16_t> utterance_;
    std::vector<std::vector<int16_t>> onsetBuffer_;
};

} // namespace itantra
