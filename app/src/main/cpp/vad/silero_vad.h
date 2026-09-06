#pragma once
#include <string>
#include <cstdint>
#include <cstddef>
#include <memory>

namespace itantra {
namespace vad {

/**
 * Silero VAD wrapper per PRD §3.1 and FR-05.
 * Executes neural ONNX Runtime inference using assets/models/vad/silero_vad.onnx.
 * Maintains 64-sample internal recurrent hidden states across 512-sample (32 ms @ 16 kHz) frame chunks.
 */
class SileroVad {
public:
    SileroVad();
    ~SileroVad();

    // Load model from path (assets/models/vad/silero_vad.onnx)
    bool load(const std::string& path);
    bool isLoaded() const;

    // Predict speech probability in [0,1] for 30ms/32ms frame (480/512 samples @ 16kHz)
    float predict(const int16_t* pcm, size_t samples);
    void reset(); // clear recurrent states h/c
    void unload();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;

    bool loaded_ = false;
    std::string modelPath_;

    // Recurrent states for Silero: 2 layers x 1 batch x 64 hidden = 128 floats each
    float h_[128] = {0};
    float c_[128] = {0};
};

} // namespace vad
} // namespace itantra
