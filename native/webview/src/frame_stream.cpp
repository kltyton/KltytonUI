#include "frame_stream.h"

#include <d3d11.h>
#include <dxgi.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>

#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>
#include <winrt/Windows.UI.Composition.h>

#include <condition_variable>
#include <cstdio>
#include <iterator>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>

namespace {

using namespace winrt::Windows::Graphics;
using namespace winrt::Windows::Graphics::Capture;
using namespace winrt::Windows::Graphics::DirectX;
using namespace winrt::Windows::Graphics::DirectX::Direct3D11;

constexpr int kPoolBuffers = 2;

std::string hrToText(HRESULT hr) {
    char buffer[32];
    sprintf_s(buffer, "0x%08lX", static_cast<unsigned long>(hr));
    return buffer;
}

}  // namespace

struct FrameStream::Impl : std::enable_shared_from_this<FrameStream::Impl> {
    HWND window = nullptr;
    bool visualCapture = false;
    int width = 0;
    int height = 0;
    Sink sink;

    winrt::com_ptr<ID3D11Device> device;
    winrt::com_ptr<ID3D11DeviceContext> context;
    IDirect3DDevice direct3DDevice{nullptr};
    GraphicsCaptureItem item{nullptr};
    Direct3D11CaptureFramePool pool{nullptr};
    GraphicsCaptureSession session{nullptr};
    winrt::com_ptr<ID3D11Texture2D> staging;
    int stagingWidth = 0;
    int stagingHeight = 0;

    std::vector<uint8_t> buffer;
    std::vector<uint8_t> latestBuffer;
    bool hasLatestFrame = false;
    int latestWidth = 0;
    int latestHeight = 0;
    uint64_t latestGeneration = 0;

    std::mutex apiMutex;
    std::mutex mutex;
    std::condition_variable idle;
    bool running = false;
    bool paused = false;
    uint64_t generation = 0;
    int inFlight = 0;
    std::mutex frameMutex;

    winrt::event_token token{};
    bool hasToken = false;

    std::mutex errorMutex;
    std::string error;

    std::atomic<long long> frames{0};
    std::atomic<long long> blankFrames{0};
    std::atomic<long long> callbacks{0};
    std::atomic<long long> emptyCallbacks{0};
    std::atomic<int> lastWidth{0};
    std::atomic<int> lastHeight{0};

    void setError(const std::string& text) {
        std::lock_guard<std::mutex> lock(errorMutex);
        error = text;
    }

    /** Confirms the staging texture matches the frame size and is CPU readable. */
    bool ensureStaging(int width, int height) {
        if (device == nullptr) {
            return false;
        }
        if (staging && stagingWidth == width && stagingHeight == height) {
            return true;
        }
        D3D11_TEXTURE2D_DESC desc{};
        desc.Width = static_cast<UINT>(width);
        desc.Height = static_cast<UINT>(height);
        desc.MipLevels = 1;
        desc.ArraySize = 1;
        desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        desc.SampleDesc.Count = 1;
        desc.Usage = D3D11_USAGE_STAGING;
        desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        staging = nullptr;
        if (FAILED(device->CreateTexture2D(&desc, nullptr, staging.put()))) {
            return false;
        }
        stagingWidth = width;
        stagingHeight = height;
        return true;
    }

    void invalidateLatestFrame() {
        hasLatestFrame = false;
        latestWidth = 0;
        latestHeight = 0;
        latestGeneration = 0;
        std::vector<uint8_t>().swap(latestBuffer);
    }

    bool beginCallback(uint64_t expectedGeneration) {
        std::lock_guard<std::mutex> lock(mutex);
        if (!running || paused || generation != expectedGeneration) {
            return false;
        }
        ++inFlight;
        return true;
    }

    void endCallback() {
        std::lock_guard<std::mutex> lock(mutex);
        if (inFlight > 0) {
            --inFlight;
        }
        if (inFlight == 0) {
            idle.notify_all();
        }
    }

    void createPool(int width, int height, uint64_t generation) {
        pool = Direct3D11CaptureFramePool::CreateFreeThreaded(
                direct3DDevice, DirectXPixelFormat::B8G8R8A8UIntNormalized, kPoolBuffers,
                {width, height});

        std::weak_ptr<Impl> weak = weak_from_this();
        token = pool.FrameArrived(
                [weak, generation](const Direct3D11CaptureFramePool& sender,
                                   const winrt::Windows::Foundation::IInspectable&) {
                    if (auto self = weak.lock()) {
                        self->onFrame(sender, generation);
                    }
                });
        hasToken = true;

        session = pool.CreateCaptureSession(item);
        session.IsCursorCaptureEnabled(false);
        try {
            session.IsBorderRequired(false);
        } catch (const winrt::hresult_error&) {
            // Needs Windows 11 / the borderless capability; the border is invisible for
            // a window nobody can see anyway.
        }
    }

    void onFrame(const Direct3D11CaptureFramePool& sender, uint64_t expectedGeneration) {
        if (!beginCallback(expectedGeneration)) {
            return;
        }

        struct CallbackScope {
            Impl* self;
            ~CallbackScope() { self->endCallback(); }
        } scope{this};

        std::unique_lock<std::mutex> frameLock(frameMutex);

        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!running || paused || generation != expectedGeneration) {
                return;
            }
        }

        ++callbacks;

        Direct3D11CaptureFrame frame{nullptr};
        try {
            frame = sender.TryGetNextFrame();
        } catch (const winrt::hresult_error& exception) {
            setError("TryGetNextFrame: " + hrToText(exception.code().value));
            return;
        }
        if (frame == nullptr) {
            ++emptyCallbacks;
            return;
        }

        const auto contentSize = frame.ContentSize();
        lastWidth = contentSize.Width;
        lastHeight = contentSize.Height;
        const int width = contentSize.Width;
        const int height = contentSize.Height;
        if (width <= 0 || height <= 0) {
            return;
        }

        winrt::com_ptr<ID3D11Texture2D> source;
        try {
            auto access = frame.Surface().as<
                    ::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
            if (FAILED(access->GetInterface(__uuidof(ID3D11Texture2D), source.put_void()))) {
                setError("frame surface has no D3D11 texture");
                return;
            }
        } catch (const winrt::hresult_error& exception) {
            setError("frame surface: " + hrToText(exception.code().value));
            return;
        }

        if (!ensureStaging(width, height)) {
            setError("staging texture creation failed");
            return;
        }

        if (context == nullptr) {
            setError("D3D11 device context is gone");
            return;
        }

        // Copy the GPU texture into a CPU-readable one and map it. This is the only
        // per-frame GPU sync in the whole path.
        context->CopyResource(staging.get(), source.get());
        D3D11_MAPPED_SUBRESOURCE mapped{};
        if (FAILED(context->Map(staging.get(), 0, D3D11_MAP_READ, 0, &mapped))) {
            setError("staging map failed");
            return;
        }

        const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height);
        buffer.resize(count * 4);
        const uint8_t* rows = static_cast<const uint8_t*>(mapped.pData);
        bool blank = true;
        uint8_t first[4] = {0, 0, 0, 0};
        for (int y = 0; y < height; ++y) {
            const uint8_t* src = rows + static_cast<size_t>(y) * mapped.RowPitch;
            uint8_t* dst = buffer.data() + static_cast<size_t>(y) * width * 4;
            for (int x = 0; x < width; ++x) {
                // B,G,R,A -> R,G,B,A, which is what NativeImage expects on little-endian.
                const uint8_t b = src[x * 4 + 0];
                const uint8_t g = src[x * 4 + 1];
                const uint8_t r = src[x * 4 + 2];
                dst[x * 4 + 0] = r;
                dst[x * 4 + 1] = g;
                dst[x * 4 + 2] = b;
                dst[x * 4 + 3] = 0xFF;
                if (blank) {
                    if (y == 0 && x == 0) {
                        first[0] = r;
                        first[1] = g;
                        first[2] = b;
                    } else if (r != first[0] || g != first[1] || b != first[2]) {
                        blank = false;
                    }
                }
            }
        }
        context->Unmap(staging.get(), 0);

        if (blank) {
            ++blankFrames;
            if (!visualCapture) setError("frame is a single flat colour (capture cannot see the window content)");
        }
        buffer.swap(latestBuffer);
        latestWidth = width;
        latestHeight = height;
        latestGeneration = expectedGeneration;
        hasLatestFrame = true;
        ++frames;

        try {
            sink(width, height, latestBuffer.data(), latestBuffer.size(), blank);
        } catch (const winrt::hresult_error& exception) {
            setError("frame sink: " + hrToText(exception.code().value));
        }
    }

    void resetCapture() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            running = false;
            paused = false;
            ++generation;
        }

        {
            std::unique_lock<std::mutex> lock(mutex);
            idle.wait(lock, [this] { return inFlight == 0; });
        }

        {
            std::unique_lock<std::mutex> frameLock(frameMutex);
            invalidateLatestFrame();
        }

        if (session != nullptr) {
            try {
                session.Close();
            } catch (const winrt::hresult_error& exception) {
                setError("session close: " + hrToText(exception.code().value));
            }
            session = nullptr;
        }
        if (pool != nullptr) {
            if (hasToken) {
                try {
                    pool.FrameArrived(token);
                } catch (const winrt::hresult_error& exception) {
                    setError("frame pool revoke: " + hrToText(exception.code().value));
                }
                hasToken = false;
                token = {};
            }
            try {
                pool.Close();
            } catch (const winrt::hresult_error& exception) {
                setError("frame pool close: " + hrToText(exception.code().value));
            }
            pool = nullptr;
        }

        item = nullptr;
        staging = nullptr;
        stagingWidth = 0;
        stagingHeight = 0;
        direct3DDevice = nullptr;
        context = nullptr;
        device = nullptr;
        sink = nullptr;
        width = 0;
        height = 0;
    }
};

FrameStream::FrameStream() : impl_(std::make_shared<Impl>()) {}

FrameStream::~FrameStream() {
    stop();
    impl_.reset();
}

bool FrameStream::isSupported() {
    try {
        return GraphicsCaptureSession::IsSupported();
    } catch (...) {
        return false;
    }
}

bool FrameStream::start(HWND window, IUnknown* visual, int width, int height, Sink sink) {
    const auto impl = impl_;
    if (impl == nullptr) {
        return false;
    }
    std::lock_guard<std::mutex> apiLock(impl->apiMutex);
    {
        std::lock_guard<std::mutex> lock(impl->mutex);
        if (impl->running) {
            running_ = true;
            return true;
        }
    }

    running_ = false;
    impl->resetCapture();

    impl->window = window;
    impl->visualCapture = visual != nullptr;
    impl->sink = std::move(sink);
    impl->setError("");

    const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                                   D3D11_CREATE_DEVICE_BGRA_SUPPORT, levels,
                                   static_cast<UINT>(std::size(levels)), D3D11_SDK_VERSION,
                                   impl->device.put(), nullptr, impl->context.put());
    if (FAILED(hr)) {
        impl->setError("D3D11CreateDevice: " + hrToText(hr));
        impl->resetCapture();
        return false;
    }

    auto dxgiDevice = impl->device.as<IDXGIDevice>();
    hr = CreateDirect3D11DeviceFromDXGIDevice(
            dxgiDevice.get(),
            reinterpret_cast<IInspectable**>(winrt::put_abi(impl->direct3DDevice)));
    if (FAILED(hr) || impl->direct3DDevice == nullptr) {
        impl->setError("CreateDirect3D11DeviceFromDXGIDevice: " + hrToText(hr));
        impl->resetCapture();
        return false;
    }

    try {
        if (visual != nullptr) {
            winrt::Windows::Foundation::IUnknown identity{nullptr};
            winrt::copy_from_abi(identity, visual);
            impl->item = GraphicsCaptureItem::CreateFromVisual(identity.as<winrt::Windows::UI::Composition::Visual>());
        } else {
            auto factory = winrt::get_activation_factory<GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
            hr = factory->CreateForWindow(
                    window, winrt::guid_of<GraphicsCaptureItem>(), winrt::put_abi(impl->item));
            if (FAILED(hr) || impl->item == nullptr) {
                impl->setError("IGraphicsCaptureItemInterop::CreateForWindow: " + hrToText(hr));
                impl->resetCapture();
                return false;
            }
        }
    } catch (const winrt::hresult_error& exception) {
        impl->setError("capture item: " + hrToText(exception.code().value));
        impl->resetCapture();
        return false;
    }

    const int safeWidth = width < 1 ? 1 : width;
    const int safeHeight = height < 1 ? 1 : height;
    uint64_t generation = 0;
    {
        std::lock_guard<std::mutex> lock(impl->mutex);
        impl->width = safeWidth;
        impl->height = safeHeight;
        impl->paused = false;
        generation = impl->generation;
    }

    struct StartRollback {
        Impl* self;
        std::atomic<bool>* runningFlag;
        bool committed = false;
        ~StartRollback() {
            if (committed) {
                return;
            }
            *runningFlag = false;
            std::lock_guard<std::mutex> lock(self->mutex);
            self->running = false;
        }
    } rollback{impl.get(), &running_};

    try {
        impl->createPool(safeWidth, safeHeight, generation);
        {
            std::lock_guard<std::mutex> lock(impl->mutex);
            impl->running = true;
        }
        running_ = true;
        impl->session.StartCapture();
        rollback.committed = true;
    } catch (const winrt::hresult_error& exception) {
        running_ = false;
        impl->setError("frame pool: " + hrToText(exception.code().value));
        impl->resetCapture();
        return false;
    }

    return true;
}

bool FrameStream::resize(int width, int height, const std::function<void()>& beforeResume) {
    const auto impl = impl_;
    if (impl == nullptr) {
        return false;
    }
    std::lock_guard<std::mutex> apiLock(impl->apiMutex);

    const int safeWidth = width < 1 ? 1 : width;
    const int safeHeight = height < 1 ? 1 : height;
    {
        std::unique_lock<std::mutex> lock(impl->mutex);
        if (!impl->running || impl->pool == nullptr) {
            return false;
        }
        if (impl->width == safeWidth && impl->height == safeHeight) {
            return true;
        }

        impl->paused = true;
        impl->idle.wait(lock, [&impl] { return impl->inFlight == 0; });
    }

    struct ResizeCompletion {
        Impl* self;
        int width;
        int height;
        bool resized = false;
        ~ResizeCompletion() {
            std::lock_guard<std::mutex> lock(self->mutex);
            if (resized) {
                self->width = width;
                self->height = height;
            }
            self->paused = false;
        }
    } completion{impl.get(), safeWidth, safeHeight};

    {
        std::unique_lock<std::mutex> frameLock(impl->frameMutex);
        impl->invalidateLatestFrame();
    }

    bool ok = true;
    try {
        impl->pool.Recreate(impl->direct3DDevice, DirectXPixelFormat::B8G8R8A8UIntNormalized,
                            kPoolBuffers, {safeWidth, safeHeight});
        impl->staging = nullptr;
        impl->stagingWidth = 0;
        impl->stagingHeight = 0;
    } catch (const winrt::hresult_error& exception) {
        impl->setError("frame pool: " + hrToText(exception.code().value));
        ok = false;
    }

    if (ok) beforeResume();
    completion.resized = ok;
    return ok;
}

bool FrameStream::replayLatest(const Sink& sink) {
    const auto impl = impl_;
    if (impl == nullptr) {
        return false;
    }

    uint64_t generation = 0;
    {
        std::lock_guard<std::mutex> lock(impl->mutex);
        if (!impl->running || impl->paused) {
            return false;
        }
        generation = impl->generation;
    }
    if (!impl->beginCallback(generation)) {
        return false;
    }

    struct ReplayScope {
        Impl* self;
        ~ReplayScope() { self->endCallback(); }
    } scope{impl.get()};

    std::unique_lock<std::mutex> frameLock(impl->frameMutex);
    {
        std::lock_guard<std::mutex> lock(impl->mutex);
        if (!impl->running || impl->paused || impl->generation != generation) {
            return false;
        }
    }

    if (!impl->hasLatestFrame || impl->latestGeneration != generation ||
        impl->latestWidth <= 0 || impl->latestHeight <= 0 ||
        impl->latestBuffer.size() != static_cast<size_t>(impl->latestWidth) *
                                            static_cast<size_t>(impl->latestHeight) * 4) {
        return false;
    }

    try {
        sink(impl->latestWidth, impl->latestHeight,
             impl->latestBuffer.data(), impl->latestBuffer.size(), false);
    } catch (const winrt::hresult_error& exception) {
        impl->setError("frame replay sink: " + hrToText(exception.code().value));
        return false;
    }
    return true;
}

void FrameStream::stop() {
    const auto impl = impl_;
    if (impl == nullptr) {
        running_ = false;
        return;
    }
    std::lock_guard<std::mutex> apiLock(impl->apiMutex);
    running_ = false;
    impl->resetCapture();
}

long long FrameStream::frameCount() const {
    const auto impl = impl_;
    return impl ? impl->frames.load() : 0;
}

long long FrameStream::callbackCount() const {
    const auto impl = impl_;
    return impl ? impl->callbacks.load() : 0;
}

long long FrameStream::emptyCallbackCount() const {
    const auto impl = impl_;
    return impl ? impl->emptyCallbacks.load() : 0;
}

int FrameStream::lastFrameWidth() const {
    const auto impl = impl_;
    return impl ? impl->lastWidth.load() : 0;
}

int FrameStream::lastFrameHeight() const {
    const auto impl = impl_;
    return impl ? impl->lastHeight.load() : 0;
}

long long FrameStream::blankFrameCount() const {
    const auto impl = impl_;
    return impl ? impl->blankFrames.load() : 0;
}

std::string FrameStream::lastError() {
    const auto impl = impl_;
    if (impl == nullptr) {
        return {};
    }
    std::lock_guard<std::mutex> lock(impl->errorMutex);
    return impl->error;
}
