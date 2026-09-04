#include "audio/audio_capture.h"

namespace itantra {
namespace audio {

AudioCapture::AudioCapture(size_t ringCapacity)
    : ring_(ringCapacity), capturing_(false) {}

AudioCapture::~AudioCapture() {
    stop();
}

bool AudioCapture::start() {
    // For this slice, capture is simulated via onAudioReady direct call in tests.
    // Real Oboe stream (16kHz mono LowLatency Exclusive 480) will be enabled in next phase
    // where NDK oboe prebuilt is linked. Keep lean and not crashing on host.
    if (capturing_) return true;
    // On Android, would open Oboe stream here; for now just mark capturing true for pipeline tests
    capturing_ = true;
    return true;
}

void AudioCapture::stop() {
    capturing_ = false;
}

bool AudioCapture::isCapturing() const {
    return capturing_;
}

SpscRingBuffer<int16_t>& AudioCapture::ring() {
    return ring_;
}

const SpscRingBuffer<int16_t>& AudioCapture::ring() const {
    return ring_;
}

void AudioCapture::clear() {
    ring_.clear();
}

oboe::DataCallbackResult AudioCapture::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData, int32_t numFrames) {
    if (audioData != nullptr && numFrames > 0) {
        // Direct SPSC push, no allocation, no lock, no JVM, no log
        ring_.push(static_cast<int16_t*>(audioData), static_cast<size_t>(numFrames));
    }
    return oboe::DataCallbackResult::Continue;
}

} // namespace audio
} // namespace itantra
