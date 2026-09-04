#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include <optional>

namespace itantra {
namespace asr {

/**
 * Pure ASR seam per PRD FR-03 / §4.1.1 asr-engine.
 * Mock header only for this slice; real IndicConformer via sherpa-onnx deferred.
 * Mirrors Kotlin AsrEngine: load(lang), unload(), isReady(), transcribe(pcm, lang) -> text
 */
class AsrEngine {
public:
    virtual ~AsrEngine() = default;
    virtual bool load(const std::string& language) = 0;
    virtual void unload() = 0;
    virtual bool isReady() const = 0;
    virtual bool isLoaded(const std::string& language) const = 0;
    // Returns empty optional on error (notReady, empty audio, unsupported lang)
    virtual std::optional<std::string> transcribe(const int16_t* pcm, size_t samples, const std::string& language) = 0;
};

class MockAsrEngine : public AsrEngine {
public:
    bool load(const std::string& language) override;
    void unload() override;
    bool isReady() const override;
    bool isLoaded(const std::string& language) const override;
    std::optional<std::string> transcribe(const int16_t* pcm, size_t samples, const std::string& language) override;
private:
    // pure mock stores loaded set; no real model
    std::vector<std::string> loaded_;
};

} // namespace asr
} // namespace itantra
