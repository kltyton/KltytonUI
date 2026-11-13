// Raw frame streaming out of a window's DWM composition.
//
// This is the alternative to WebView2's CapturePreview, which can only hand back an
// encoded image (PNG/JPEG) that then has to be decoded again — an encode/decode round trip
// of tens of milliseconds per frame. Windows.Graphics.Capture hands us the window's
// composited output directly as a GPU texture and tells us when a new frame exists, so the
// only cost left is a staging copy plus a byte swap.
#pragma once

#include <windows.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

/**
 * Pushes window frames as raw RGBA.
 *
 * Frame delivery is callback-driven: Windows calls back whenever the window's composition
 * changes, so a static window costs nothing at all. The callback runs on a Windows thread
 * pool thread, never on the caller's thread.
 */
class FrameStream {
public:
    /** Receives one frame: RGBA byte order, row major, exactly width * height * 4 bytes. */
    using Sink = std::function<void(int width, int height, const uint8_t* rgba, size_t bytes, bool blank)>;

    FrameStream();
    ~FrameStream();

    FrameStream(const FrameStream&) = delete;
    FrameStream& operator=(const FrameStream&) = delete;

    /** True when this OS build offers window capture at all. */
    static bool isSupported();

    /** Streams the supplied composition visual, or the window when no visual is supplied. */
    bool start(HWND window, IUnknown* visual, int width, int height, Sink sink);

    /** Changes the captured size; beforeResume runs while frame callbacks are paused. */
    bool resize(int width, int height, const std::function<void()>& beforeResume);

    bool replayLatest(const Sink& sink);

    void stop();
    bool isRunning() const { return running_; }

    /** Frames handed to the sink so far. */
    long long frameCount() const;

    /** Times the capture callback fired. */
    long long callbackCount() const;

    /** Callbacks that produced no frame at all. */
    long long emptyCallbackCount() const;

    /** Size of the most recent frame the pool reported. */
    int lastFrameWidth() const;

    int lastFrameHeight() const;

    /** Frames whose pixels were entirely one colour — the signature of a capture that can
     *  see nothing, which is what an off-screen window can produce. */
    long long blankFrameCount() const;

    std::string lastError();

private:
    struct Impl;
    std::shared_ptr<Impl> impl_;
    std::atomic<bool> running_{false};
};
