#include "audio/audio_capture.h"

#ifdef __ANDROID__
#include <oboe/Oboe.h>
#endif

namespace itantra {
namespace audio {

AudioCapture::AudioCapture(size_t ringCapacity)
    : ring_(ringCapacity)
#ifdef __ANDROID__
    , stream_(nullptr)
#endif
    , capturing_(false) {}

AudioCapture::~AudioCapture() {
    stop();
}

bool AudioCapture::start() {
#ifdef __ANDROID__
    if (capturing_) return true;
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setSampleRate(16000)
        ->setChannelCount(1)
        ->setFormat(oboe::AudioFormat::I16)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setCallback(this)
        ->setFramesPerCallback(480);
    // Note: setFramesPerCallback may be ignored on some devices, but we request 480 (30ms)
    oboe::AudioStream* stream = nullptr;
    oboe::Result result = builder.openStream(&stream);
    if (result != oboe::Result::OK || stream == nullptr) {
        return false;
    }
    result = stream->requestStart();
    if (result != oboe::Result::OK) {
        stream->close();
        return false;
    }
    stream_ = stream;
    capturing_ = true;
    return true;
#else
    // Host unit-test path: no mic, no Oboe device. Return false but not crash.
    // Tests will call onAudioReady directly to simulate callback.
    capturing_ = false;
    return false;
#endif
}

void AudioCapture::stop() {
#ifdef __ANDROID__
    if (stream_ != nullptr) {
        stream_->requestStop();
        stream_->close();
        stream_ = nullptr;
    }
#endif
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
