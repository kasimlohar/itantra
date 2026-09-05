#pragma once
#include "audio/ring_buffer.h"
#include <cstdint>
#include <cstddef>

#if defined(__ANDROID__) && __has_include(<oboe/Oboe.h>)
#include <oboe/Oboe.h>
#else
// Host stub for oboe types - keeps host GTest runnable without NDK/Oboe.
// On Android without Oboe prebuilt, also falls back to stub to keep build green offline.
namespace oboe {
enum class DataCallbackResult { Continue, Stop };
enum class Result { OK, ErrorTimeout };
class AudioStream {};
class AudioStreamCallback {
public:
    virtual ~AudioStreamCallback() = default;
    virtual DataCallbackResult onAudioReady(AudioStream*, void*, int32_t) { return DataCallbackResult::Continue; }
};
} // namespace oboe
#endif

namespace itantra {
namespace audio {

/**
 * Oboe capture → SPSC ring buffer per PRD FR-11.
 * Single producer (Oboe DataCallback, SCHED_FIFO) → ring → consumer (VadFsm).
 * GC-free, lock-free, no allocations in callback, no JVM calls.
 */
class AudioCapture : public oboe::AudioStreamCallback {
public:
    explicit AudioCapture(size_t ringCapacity = 8192);
    ~AudioCapture() override;

    // Opens 16kHz mono low-latency input, Exclusive share, 480 frames/callback
    bool start();
    void stop();
    bool isCapturing() const;
    SpscRingBuffer<int16_t>& ring();
    const SpscRingBuffer<int16_t>& ring() const;
    void clear();

    // oboe::AudioStreamCallback — single producer, must be lock-free
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;

private:
    SpscRingBuffer<int16_t> ring_;
    bool capturing_ = false;
#if defined(__ANDROID__) && __has_include(<oboe/Oboe.h>)
    oboe::AudioStream* stream_ = nullptr;
#endif
};

} // namespace audio
} // namespace itantra
