#include "audio/audio_capture.h"
#if defined(__ANDROID__) && __has_include(<oboe/Oboe.h>)
#include <oboe/Oboe.h>
#endif

namespace itantra {
namespace audio {

AudioCapture::AudioCapture(size_t ringCapacity)
    : ring_(ringCapacity), capturing_(false)
#if defined(__ANDROID__) && __has_include(<oboe/Oboe.h>)
    , stream_(nullptr)
#endif
{}

AudioCapture::~AudioCapture() {
    stop();
}

bool AudioCapture::start() {
    if (capturing_) return true;
#if defined(__ANDROID__) && __has_include(<oboe/Oboe.h>)
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input);
    builder.setSampleRate(16000);
    builder.setChannelCount(1);
    builder.setFormat(oboe::AudioFormat::I16);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setCallback(this);
    builder.setFramesPerCallback(480);
    oboe::AudioStream* stream = nullptr;
    auto res = builder.openStream(stream);
    if (res != oboe::Result::OK || !stream) return false;
    stream_ = stream;
    res = stream_->requestStart();
    if (res != oboe::Result::OK) {
        stream_->close();
        stream_ = nullptr;
        return false;
    }
#endif
    capturing_ = true;
    return true;
}

void AudioCapture::stop() {
#if defined(__ANDROID__) && __has_include(<oboe/Oboe.h>)
    if (stream_) {
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
