#pragma once
#include <string>
#include <cstdint>
#include <cstddef>

namespace itantra {
namespace vad {

/**
 * Minimal Silero VAD wrapper per PRD §3.1.
 * Stub for RED phase — real ONNX inference deferred to GREEN.
 * Interface kept clean so VadFsm can consume probabilities.
 */
class SileroVad {
public:
    SileroVad();
    // Load model from path (assets/models/vad/silero_vad.onnx)
    bool load(const std::string& path);
    bool isLoaded() const;
    // Predict speech probability in [0,1] for 30ms frame (480 samples @16kHz, padded to 512)
    float predict(const int16_t* pcm, size_t samples);
    void reset(); // clear LSTM states h/c
    void unload();
private:
    bool loaded_ = false;
    std::string modelPath_;
    // LSTM states h/c for Silero: [2,1,64] =128 floats each
    float h_[128] = {0};
    float c_[128] = {0};
};

} // namespace vad
} // namespace itantra
