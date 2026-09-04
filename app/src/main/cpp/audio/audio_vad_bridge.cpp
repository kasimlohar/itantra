#include "audio/audio_vad_bridge.h"
#include "audio/audio_capture.h"
#include "vad/silero_vad.h"
#include "vad/vad_fsm.h"
#include "vad/vad_pipeline.h"

namespace itantra {
namespace bridge {

AudioVadBridge::AudioVadBridge()
    : capture(std::make_unique<audio::AudioCapture>(8192)),
      vad(std::make_unique<vad::SileroVad>()),
      fsm(std::make_unique<VadFsm>()),
      pipeline(std::make_unique<vad::VadPipeline>(capture->ring(), *vad, *fsm)) {
    // Try to load silero model from assets path; ignore failure on host (no file)
    vad->load("app/src/main/assets/models/vad/silero_vad.onnx");
}

AudioVadBridge::~AudioVadBridge() = default;

} // namespace bridge
} // namespace itantra
