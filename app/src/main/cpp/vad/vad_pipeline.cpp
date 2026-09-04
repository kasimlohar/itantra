#include "vad/vad_pipeline.h"

namespace itantra {
namespace vad {

VadPipeline::VadPipeline(audio::SpscRingBuffer<int16_t>& ring, SileroVad& vad, VadFsm& fsm)
    : ring_(ring), vad_(vad), fsm_(fsm), running_(false) {}

bool VadPipeline::start() {
    running_ = true;
    return true;
}

void VadPipeline::stop() {
    running_ = false;
}

bool VadPipeline::isRunning() const {
    return running_;
}

bool VadPipeline::processOne() {
    if (!running_) return false;
    int16_t frame[480];
    if (!ring_.pop(frame, 480)) return false;
    float prob = 0.0f;
    if (vad_.isLoaded()) {
        prob = vad_.predict(frame, 480);
    }
    fsm_.onFrame(prob, frame, 480);
    return true;
}

size_t VadPipeline::processAll() {
    size_t n = 0;
    while (processOne()) ++n;
    return n;
}

VadState VadPipeline::state() const {
    return fsm_.state();
}

void VadPipeline::clear() {
    ring_.clear();
    fsm_.reset();
    vad_.reset();
}

} // namespace vad
} // namespace itantra
