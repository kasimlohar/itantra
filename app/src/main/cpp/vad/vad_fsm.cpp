#include "vad/vad_fsm.h"
#include <algorithm>

namespace itantra {

VadFsm::VadFsm(int hangoverMs, int frameMs, int preRollMs, int sampleRate)
    : hangoverMs_(hangoverMs),
      frameMs_(frameMs),
      frameSamples_(sampleRate * frameMs / 1000),
      hangoverFrames_(hangoverMs / frameMs),
      preRollFrames_((preRollMs + frameMs - 1) / frameMs),
      state_(VadState::Idle),
      onsetCounter_(0),
      pauseCounter_(0) {}

void VadFsm::setHangoverMs(int ms) {
    hangoverMs_ = ms;
    hangoverFrames_ = hangoverMs_ / frameMs_;
}

void VadFsm::reset() {
    state_ = VadState::Idle;
    onsetCounter_ = 0;
    pauseCounter_ = 0;
    preRoll_.clear();
    utterance_.clear();
    onsetBuffer_.clear();
}

std::vector<int16_t> VadFsm::getUtterance() const {
    return utterance_;
}

VadState VadFsm::onFrame(float prob, const int16_t* pcm, size_t samples) {
    // Normalize frame to frameSamples_ (pad with zeros or truncate)
    std::vector<int16_t> frame(frameSamples_, 0);
    if (pcm != nullptr && samples > 0) {
        size_t n = std::min(samples, static_cast<size_t>(frameSamples_));
        for (size_t i = 0; i < n; ++i) frame[i] = pcm[i];
    }

    switch (state_) {
        case VadState::Idle:
            handleIdle(prob, frame.data(), frame.size());
            break;
        case VadState::Speaking:
            handleSpeaking(prob, frame.data(), frame.size());
            break;
        case VadState::Pause:
            handlePause(prob, frame.data(), frame.size());
            break;
        case VadState::Eou:
            // stay in Eou until reset
            break;
    }
    return state_;
}

void VadFsm::handleIdle(float prob, const int16_t* pcm, size_t samples) {
    if (prob >= 0.6f) {
        onsetCounter_++;
        std::vector<int16_t> f(pcm, pcm + samples);
        onsetBuffer_.push_back(std::move(f));
        if (onsetCounter_ >= 3) {
            // flush preRoll
            for (auto& fr : preRoll_) {
                utterance_.insert(utterance_.end(), fr.begin(), fr.end());
            }
            for (auto& fr : onsetBuffer_) {
                utterance_.insert(utterance_.end(), fr.begin(), fr.end());
            }
            preRoll_.clear();
            onsetBuffer_.clear();
            state_ = VadState::Speaking;
            pauseCounter_ = 0;
        }
    } else {
        onsetCounter_ = 0;
        onsetBuffer_.clear();
        std::vector<int16_t> f(pcm, pcm + samples);
        preRoll_.push_back(std::move(f));
        if (static_cast<int>(preRoll_.size()) > preRollFrames_) {
            preRoll_.pop_front();
        }
    }
}

void VadFsm::handleSpeaking(float prob, const int16_t* pcm, size_t samples) {
    if (prob >= 0.35f) {
        utterance_.insert(utterance_.end(), pcm, pcm + samples);
    } else {
        state_ = VadState::Pause;
        pauseCounter_ = 1;
    }
}

void VadFsm::handlePause(float prob, const int16_t* pcm, size_t samples) {
    if (prob >= 0.35f) {
        utterance_.insert(utterance_.end(), pcm, pcm + samples);
        state_ = VadState::Speaking;
        pauseCounter_ = 0;
    } else {
        pauseCounter_++;
        if (pauseCounter_ >= hangoverFrames_) {
            state_ = VadState::Eou;
        } else {
            state_ = VadState::Pause;
        }
    }
}

} // namespace itantra
