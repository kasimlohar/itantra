#pragma once
#include <memory>

namespace itantra {
namespace audio { class AudioCapture; }
namespace vad { class SileroVad; }
class VadFsm;
namespace vad { class VadPipeline; }

namespace bridge {

struct AudioVadBridge {
    std::unique_ptr<audio::AudioCapture> capture;
    std::unique_ptr<vad::SileroVad> vad;
    std::unique_ptr<VadFsm> fsm;
    std::unique_ptr<vad::VadPipeline> pipeline;
    AudioVadBridge();
    ~AudioVadBridge();
};

} // namespace bridge
} // namespace itantra
