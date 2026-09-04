#pragma once
#include <vector>
#include <cstdint>
#include <cstddef>
namespace itantra {
namespace asr {
std::vector<std::vector<float>> computeLogMel(const int16_t* pcm, size_t samples, int sampleRate = 16000);
int numFramesFor(size_t samples, int sampleRate = 16000);
} // namespace asr
} // namespace itantra
