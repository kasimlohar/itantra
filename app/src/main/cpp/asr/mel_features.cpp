#include "asr/mel_features.h"
#include <cmath>
#include <algorithm>
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
namespace itantra {
namespace asr {
static constexpr int kWin = 400, kHop = 160, kFFT = 512, kMel = 80;
static float melScale(float f) { return 2595.0f * std::log10(1.0f + f / 700.0f); }
static float invMel(float m) { return 700.0f * (std::pow(10.0f, m / 2595.0f) - 1.0f); }
int numFramesFor(size_t samples, int sr) {
    if (sr != 16000) return 0;
    if (samples < static_cast<size_t>(kWin)) return 0;
    return 1 + static_cast<int>((samples - kWin) / kHop);
}
std::vector<std::vector<float>> computeLogMel(const int16_t* pcm, size_t samples, int sampleRate) {
    if (!pcm || samples == 0 || sampleRate != 16000) return {};
    int nFrames = numFramesFor(samples, sampleRate);
    if (nFrames <= 0) return {};
    static std::vector<std::vector<float>> fb;
    static bool init = false;
    if (!init) {
        fb.assign(kMel, std::vector<float>(kFFT / 2 + 1, 0.0f));
        float melLow = melScale(80.0f), melHigh = melScale(7600.0f);
        std::vector<float> melPts(kMel + 2);
        for (int i = 0; i < kMel + 2; ++i) melPts[i] = invMel(melLow + (melHigh - melLow) * i / (kMel + 1));
        std::vector<int> bin(kMel + 2);
        for (int i = 0; i < kMel + 2; ++i) bin[i] = std::clamp(int(std::floor((kFFT + 1) * melPts[i] / sampleRate)), 0, kFFT / 2);
        for (int m = 1; m <= kMel; ++m) {
            for (int k = bin[m - 1]; k < bin[m]; ++k) fb[m - 1][k] = (k - bin[m - 1]) / float(bin[m] - bin[m - 1]);
            for (int k = bin[m]; k < bin[m + 1]; ++k) fb[m - 1][k] = (bin[m + 1] - k) / float(bin[m + 1] - bin[m]);
        }
        init = true;
    }
    std::vector<std::vector<float>> out(nFrames, std::vector<float>(kMel, 0.0f));
    std::vector<float> win(kWin);
    for (int i = 0; i < kWin; ++i) win[i] = 0.54f - 0.46f * std::cos(2 * M_PI * i / (kWin - 1));
    for (int f = 0; f < nFrames; ++f) {
        float power[257] = {0};
        for (int k = 0; k <= kFFT / 2; ++k) {
            float re = 0, im = 0;
            for (int n = 0; n < kWin; ++n) {
                float s = pcm[f * kHop + n] / 32768.0f * win[n];
                float ang = 2 * M_PI * k * n / kFFT;
                re += s * std::cos(ang);
                im -= s * std::sin(ang);
            }
            power[k] = re * re + im * im;
        }
        for (int m = 0; m < kMel; ++m) {
            float e = 0;
            for (int k = 0; k <= kFFT / 2; ++k) e += power[k] * fb[m][k];
            out[f][m] = std::log10(std::max(e, 1e-10f));
        }
    }
    return out;
}
} // namespace asr
} // namespace itantra
