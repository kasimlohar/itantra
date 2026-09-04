#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>
#include <utility>
#include <cassert>

namespace itantra {
namespace audio {

/**
 * Lock-free SPSC ring buffer for 16 kHz PCM16.
 * FR-11: GC-free, lock-free, no underruns on 90 s run (Architecture pre-roll 200 ms).
 * Overwrite policy: when full, push overwrites oldest samples (drop oldest, keep newest).
 * This is appropriate for 200 ms pre-roll where newest audio matters; alternative would be drop-newest.
 * Capacity is rounded up to next power-of-two for mask-based wrap (efficient).
 * SPSC only: single producer (Oboe callback, SCHED_FIFO) + single consumer (VadFsm).
 * Uses std::atomic<size_t> head_ (write) / tail_ (read) with acquire/release.
 */
template <typename T>
class SpscRingBuffer {
public:
    explicit SpscRingBuffer(size_t capacity)
        : cap_(nextPow2(capacity)),
          mask_(cap_ - 1),
          buf_(cap_),
          head_(0),
          tail_(0) {
        assert(cap_ >= 2);
        assert((cap_ & mask_) == 0); // power-of-two
    }

    size_t capacity() const noexcept { return cap_; }

    size_t size() const noexcept {
        size_t h = head_.load(std::memory_order_acquire);
        size_t t = tail_.load(std::memory_order_acquire);
        return h - t;
    }

    bool empty() const noexcept { return size() == 0; }
    bool full() const noexcept { return size() == cap_; }

    void clear() noexcept {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }

    // Push count elements; always succeeds, overwrites oldest when full.
    bool push(const T* data, size_t count) {
        if (count == 0) return true;
        if (data == nullptr) return false;
        if (count > cap_) {
            // Keep only last cap elements
            data += count - cap_;
            count = cap_;
            clear();
        }
        size_t h = head_.load(std::memory_order_relaxed);
        size_t t = tail_.load(std::memory_order_acquire);
        size_t free = cap_ - (h - t);
        if (count > free) {
            size_t drop = count - free;
            tail_.store(t + drop, std::memory_order_release);
        }
        for (size_t i = 0; i < count; ++i) {
            buf_[(h + i) & mask_] = data[i];
        }
        head_.store(h + count, std::memory_order_release);
        return true;
    }

    bool pushOne(const T& v) { return push(&v, 1); }

    // Pop count elements; fails if not enough data (non-destructive on failure).
    bool pop(T* out, size_t count) {
        if (count == 0) return true;
        if (out == nullptr) return false;
        size_t t = tail_.load(std::memory_order_relaxed);
        size_t h = head_.load(std::memory_order_acquire);
        if (h - t < count) return false;
        for (size_t i = 0; i < count; ++i) {
            out[i] = buf_[(t + i) & mask_];
        }
        tail_.store(t + count, std::memory_order_release);
        return true;
    }

    bool popOne(T& out) { return pop(&out, 1); }

    // Zero-copy peek: returns pointer to contiguous readable region starting at tail.
    // Second element is count of contiguous elements available (may be < size() if wrap).
    std::pair<const T*, size_t> peekContiguous() const noexcept {
        size_t t = tail_.load(std::memory_order_acquire);
        size_t h = head_.load(std::memory_order_acquire);
        size_t sz = h - t;
        if (sz == 0) return {nullptr, 0};
        size_t idx = t & mask_;
        size_t contiguous = std::min(sz, cap_ - idx);
        return {buf_.data() + idx, contiguous};
    }

    void consume(size_t count) noexcept {
        // Advance tail by count (caller must ensure count <= size())
        size_t t = tail_.load(std::memory_order_relaxed);
        // Clamp to available to avoid underflow in race, but for SPSC caller ensures correctness
        size_t h = head_.load(std::memory_order_acquire);
        size_t sz = h - t;
        if (count > sz) count = sz;
        tail_.store(t + count, std::memory_order_release);
    }

private:
    static size_t nextPow2(size_t n) {
        if (n == 0) return 1;
        size_t p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    size_t cap_;
    size_t mask_;
    std::vector<T> buf_;
    std::atomic<size_t> head_;
    std::atomic<size_t> tail_;
};

using PcmRing = SpscRingBuffer<int16_t>;

} // namespace audio
} // namespace itantra
