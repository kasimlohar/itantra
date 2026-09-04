#pragma once
#include "audio/ring_buffer.h"
#include "vad/silero_vad.h"
#include "vad/vad_fsm.h"
#include <cstdint>

namespace itantra {
namespace vad {

/**
 * Thin real-time pipeline: Ring Buffer → Silero VAD → VadFsm per PRD FR-11.
 * Consumer side (separate from Oboe producer). Keeps hot path lean: no allocs, no JVM.
 */
class VadPipeline {
public:
    VadPipeline(audio::SpscRingBuffer<int16_t>& ring, SileroVad& vad, VadFsm& fsm);
    bool start();
    void stop();
    bool isRunning() const;
    // Pop 480 from ring, run vad.predict, feed fsm.onFrame. Returns true if a frame was processed.
    bool processOne();
    // Drain ring, returns frames processed
    size_t processAll();
    VadState state() const;
    void clear();

private:
    audio::SpscRingBuffer<int16_t>& ring_;
    SileroVad& vad_;
    VadFsm& fsm_;
    bool running_ = false;
};

} // namespace vad
} // namespace itantra
