#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include <optional>

namespace itantra {
namespace tts {

struct SpeechBuffer {
    std::vector<int16_t> pcm;
    int sampleRate = 22050;
};

/**
 * Pure TTS seam per PRD FR-04 / §4.1.1 tts-engine.
 * Mock header only for this slice; real Piper VITS deferred.
 * Mirrors Kotlin TtsEngine: loadVoice(lang), unload(), isReady(), synthesize(text, lang) -> SpeechBuffer
 */
class TtsEngine {
public:
    virtual ~TtsEngine() = default;
    virtual bool loadVoice(const std::string& language) = 0;
    virtual void unload() = 0;
    virtual bool isReady() const = 0;
    virtual bool isLoaded(const std::string& language) const = 0;
    virtual std::optional<SpeechBuffer> synthesize(const std::string& text, const std::string& language) = 0;
};

class MockTtsEngine : public TtsEngine {
public:
    bool loadVoice(const std::string& language) override;
    void unload() override;
    bool isReady() const override;
    bool isLoaded(const std::string& language) const override;
    std::optional<SpeechBuffer> synthesize(const std::string& text, const std::string& language) override;
private:
    std::vector<std::string> loaded_;
};

} // namespace tts
} // namespace itantra
