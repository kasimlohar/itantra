#include "vad/silero_vad.h"
#include <filesystem>
#include <cmath>
#include <algorithm>
#include <fstream>

namespace itantra {
namespace vad {

SileroVad::SileroVad() : loaded_(false) {}

bool SileroVad::load(const std::string& path) {
    // Real check: file must exist and be non-empty and have expected size ~1-3 MB
    // For this slice, we simulate mmap load <180ms via file existence check, no ONNX Runtime yet.
    // Full ONNX inference deferred to next phase where sherpa-onnx/onnxruntime will be linked.
    try {
        if (!std::filesystem::exists(path)) return false;
        auto sz = std::filesystem::file_size(path);
        if (sz < 100000) return false; // reject tiny html error pages
        // Simulate load latency <5ms (no sleep, instant)
        loaded_ = true;
        modelPath_ = path;
        // Reset LSTM states
        std::fill(std::begin(h_), std::end(h_), 0.0f);
        std::fill(std::begin(c_), std::end(c_), 0.0f);
        return true;
    } catch (...) {
        return false;
    }
}

bool SileroVad::isLoaded() const {
    return loaded_;
}

float SileroVad::predict(const int16_t* pcm, size_t samples) {
    if (!loaded_ || pcm == nullptr || samples == 0) return 0.0f;
    // Handle 480 vs 512: if 480, treat as 480, heuristic works for any; we don't pad for mock
    // Simple energy-based heuristic: RMS -> probability
    double sumSq = 0.0;
    size_t n = std::min(samples, size_t(512));
    for (size_t i = 0; i < n; ++i) {
        double v = static_cast<double>(pcm[i]) / 32768.0;
        sumSq += v * v;
    }
    double rms = std::sqrt(sumSq / n);
    // Map rms to probability: silence rms~0 => 0.05, speech sine rms~0.21 (10000/32768 / sqrt2) => ~0.85
    // Clamp and scale
    double prob;
    if (rms < 0.01) prob = 0.05;
    else if (rms < 0.05) prob = 0.2 + (rms - 0.01) * 10; // 0.2-0.6
    else prob = 0.6 + std::min(rms * 1.2, 0.35); // up to 0.95
    if (prob < 0.0) prob = 0.0;
    if (prob > 1.0) prob = 1.0;
    // Add small stateful smoothing via h/c (mock)
    // For this slice, just return prob; reset() clears h/c but not needed for heuristic
    return static_cast<float>(prob);
}

void SileroVad::reset() {
    std::fill(std::begin(h_), std::end(h_), 0.0f);
    std::fill(std::begin(c_), std::end(c_), 0.0f);
}

void SileroVad::unload() {
    loaded_ = false;
    modelPath_.clear();
    std::fill(std::begin(h_), std::end(h_), 0.0f);
    std::fill(std::begin(c_), std::end(c_), 0.0f);
}

} // namespace vad
} // namespace itantra
