#include "webview_host.h"
#include "bridge_script.h"

#include <objbase.h>
#include <DispatcherQueue.h>
#include <roapi.h>
#include <timeapi.h>
#include <wincodec.h>
#include <windows.ui.composition.interop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.System.h>
#include <winrt/Windows.UI.Composition.h>
#include <winrt/Windows.UI.Composition.Desktop.h>

#include <algorithm>
#include <chrono>
#include <cstring>

namespace {

const wchar_t* kWindowClass = L"KltytonUIWebViewOffscreen";
const UINT kHostWakeMessage = WM_APP + 1;
std::atomic<int> gActiveCaptures{0};
std::atomic<int> gCapturesInFlight{0};
std::mutex gCaptureBudgetMutex;
std::deque<WebViewHost*> gCaptureWaiters;

bool acquireCaptureBudget(WebViewHost* host) {
    std::lock_guard<std::mutex> lock(gCaptureBudgetMutex);
    if (std::find(gCaptureWaiters.begin(), gCaptureWaiters.end(), host) == gCaptureWaiters.end())
        gCaptureWaiters.push_back(host);
    if (gCapturesInFlight.load() >= 2 || gCaptureWaiters.front() != host) return false;
    gCaptureWaiters.pop_front();
    ++gCapturesInFlight;
    return true;
}

void cancelCaptureBudget(WebViewHost* host) {
    std::lock_guard<std::mutex> lock(gCaptureBudgetMutex);
    gCaptureWaiters.erase(std::remove(gCaptureWaiters.begin(), gCaptureWaiters.end(), host), gCaptureWaiters.end());
}

/**
 * Millisecond clock with sub-tick resolution.
 *
 * GetTickCount64 is quantised to the system timer tick (15.6 ms by default), which caps the
 * capture cadence at roughly two ticks per frame no matter how fast the codec is: a 16 ms
 * interval was really a ~31 ms one. steady_clock reads the performance counter instead.
 */
uint64_t nowMs() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count());
}

// Auto codec tuning: look at published frames over a short window; go fast once the page
// keeps changing, fall back to lossless after it has been quiet for a while.
const ULONGLONG AUTO_WINDOW_MS = 250;
const int AUTO_PUBLISHES_TO_GO_FAST = 2;
const ULONGLONG AUTO_QUIET_MS = 1500;

}  // namespace

struct WebViewHost::VisualComposition {
    bool runtimeInitialized = false;
    bool ready = false;
    winrt::Windows::System::DispatcherQueueController dispatcher{nullptr};
    winrt::Windows::UI::Composition::Compositor compositor{nullptr};
    winrt::Windows::UI::Composition::Desktop::DesktopWindowTarget target{nullptr};
    winrt::Windows::UI::Composition::ContainerVisual root{nullptr};
};

WebViewHost::WebViewHost(int width,
                         int height,
                         bool transparent,
                         bool autoCapture,
                         int frameIntervalMs,
                         int frameFormat,
                         const std::wstring& userDataDir)
        : width_(std::max(1, width)),
          height_(std::max(1, height)),
          transparent_(transparent),
          autoCapture_(autoCapture),
          frameIntervalMs_(std::max(8, frameIntervalMs)),
          frameFormat_(frameFormat < 0 || frameFormat > 3 ? 3 : frameFormat),
          userDataDir_(userDataDir) {
    // The update section is created up front so the reader can map it as soon as the view
    // exists; a view that never paints then simply leaves it empty.
    if (!channel_.open(width_, height_)) {
        setError(L"update channel could not be created");
    }
}

WebViewHost::~WebViewHost() {
    stop();
    // Reader mappings outlive the host thread by design; drop them only once nothing can be
    // writing any more.
    {
        std::lock_guard<std::mutex> lock(channelViewMutex_);
        for (void* view : channelViews_) {
            UnmapViewOfFile(view);
        }
        channelViews_.clear();
    }
    channel_.close();
}

void WebViewHost::addChannelView(void* view) {
    if (view == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(channelViewMutex_);
    channelViews_.push_back(view);
}

bool WebViewHost::start(unsigned long timeoutMs) {
    if (running_.exchange(true)) {
        return isReady();
    }
    readyEvent_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (readyEvent_ == nullptr) {
        running_ = false;
        return false;
    }
    thread_ = std::thread(&WebViewHost::threadMain, this);
    DWORD wait = WaitForSingleObject(readyEvent_, timeoutMs == 0 ? INFINITE : timeoutMs);
    if (wait != WAIT_OBJECT_0) {
        setError(L"timed out waiting for the WebView2 controller");
        stop();
        return false;
    }
    return isReady();
}

void WebViewHost::stop() {
    if (!running_.exchange(false)) {
        return;
    }
    if (threadId_ != 0) {
        PostThreadMessageW(threadId_, kHostWakeMessage, 0, 0);
    }
    if (thread_.joinable()) {
        thread_.join();
    }
    if (readyEvent_ != nullptr) {
        CloseHandle(readyEvent_);
        readyEvent_ = nullptr;
    }
}

void WebViewHost::post(std::function<void()> fn) {
    if (!running_.load()) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(commandMutex_);
        commands_.push_back(Command{std::move(fn), nowMs()});
    }
    if (threadId_ != 0) {
        PostThreadMessageW(threadId_, kHostWakeMessage, 0, 0);
    }
}

void WebViewHost::navigate(const std::wstring& url) {
    post([this, url] {
        pendingUrl_ = url;
        if (webview_) {
            webview_->Navigate(url.c_str());
        }
    });
}

void WebViewHost::setBoundsAndZoom(int width, int height, double zoom) {
    post([this, width, height, zoom] {
        width_ = std::max(1, width);
        height_ = std::max(1, height);
        requestedZoom_ = std::max(0.25, std::min(5.0, zoom));
        wakeCapture();
        updateCaptureBounds();
    });
}

void WebViewHost::wakeCapture(bool interactive) {
    const uint64_t now = nowMs();
    activeUntil_ = now + 750;
    if (interactive) interactiveUntil_ = now + 750;
    unchangedSince_ = 0;
    refreshPending_ = true;
}

void WebViewHost::updateCaptureBounds() {
    if (!controller_) return;
    const uint64_t now = nowMs();
    const bool reduce = frameFormat_ != 3 && requestedZoom_ >= 0.5 &&
            static_cast<int64_t>(width_) * height_ > 1920 * 1080 &&
            (now < interactiveUntil_ || (reducedRaster_ && now - reducedSince_ < 1000));
    const double scale = reduce ? 0.5 : 1.0;
    const int width = std::max(1, static_cast<int>(width_ * scale));
    const int height = std::max(1, static_cast<int>(height_ * scale));
    const double zoom = requestedZoom_ * static_cast<double>(width) / width_;
    if (reduce && !reducedRaster_) reducedSince_ = now;
    const bool restored = reducedRaster_ && !reduce;
    reducedRaster_ = reduce;
    if (windowWidth_ == width && windowHeight_ == height && appliedZoom_ == zoom) return;
    {
        std::lock_guard<std::mutex> lock(publishMutex_);
        ++frameGeneration_;
        streamReady_ = false;
    }
    windowWidth_ = width;
    windowHeight_ = height;
    appliedZoom_ = zoom;
    SetWindowPos(hwnd_, nullptr, originX_.load(), originY_.load(), width, height,
                 SWP_NOZORDER | SWP_NOACTIVATE);
    if (visualComposition_ && visualComposition_->ready)
        visualComposition_->root.Size({static_cast<float>(width), static_cast<float>(height)});
    if (streamActive_) {
        bool ok = stream_.resize(width, height, [this] {
            std::lock_guard<std::mutex> lock(publishMutex_);
            if (streamGeneration_) *streamGeneration_ = frameGeneration_.load();
        });
        if (!ok) {
            stream_.stop();
            streamActive_ = false;
            streamGeneration_.reset();
            streamUnavailable_ = true;
        }
    }
    RECT bounds{0, 0, width, height};
    if (FAILED(controller_->SetBoundsAndZoomFactor(bounds, zoom))) {
        controller_->put_Bounds(bounds);
        controller_->put_ZoomFactor(zoom);
    }
    if (restored && frameFormat_ == 2) {
        autoUsesFast_ = false;
        decisionWindowPublishes_ = 0;
        decisionWindowStart_ = now;
    }
    wakeCapture();
}

int WebViewHost::captureInterval(uint64_t now) const {
    int interval = std::max(33, frameIntervalMs_.load());
    interval = std::max(interval, 33 * std::max(1, gActiveCaptures.load()));
    interval = std::max(interval, static_cast<int>(std::min(500LL, lastRoundTripMs_.load())));
    interval = std::max(interval, static_cast<int>(std::min(500LL, lastDecodeMs_.load())));
    const uint64_t quiet = unchangedSince_.load();
    if (now >= activeUntil_ && quiet != 0) {
        if (now - quiet >= 1500) interval = std::max(interval, 500);
        else if (now - quiet >= 400) interval = std::max(interval, 100);
    }
    return interval;
}

void WebViewHost::setFrameInterval(int ms) {
    frameIntervalMs_ = std::max(8, ms);
}

void WebViewHost::setAutoCapture(bool enabled) {
    post([this, enabled] {
        const bool changed = autoCapture_.exchange(enabled) != enabled;
        if (changed && enabled) wakeCapture();
        if (!enabled && streamActive_) {
            {
                std::lock_guard<std::mutex> lock(publishMutex_);
                ++frameGeneration_;
                streamReady_ = false;
            }
            stream_.stop();
            streamActive_ = false;
            streamGeneration_.reset();
        }
    });
}

void WebViewHost::setFrameFormat(int format) {
    post([this, format] {
        const int next = format < 0 || format > 3 ? 3 : format;
        {
            std::lock_guard<std::mutex> lock(publishMutex_);
            if (frameFormat_ == next) return;
            frameFormat_ = next;
            ++frameGeneration_;
            streamReady_ = false;
        }
        wakeCapture();
        if (next != 3 && streamActive_) {
            stream_.stop();
            streamActive_ = false;
            streamGeneration_.reset();
        }
    });
}

void WebViewHost::focus(bool focused) {
    post([this, focused] {
        if (!controller_) {
            return;
        }
        if (focused) {
            controller_->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
        }
    });
}

void WebViewHost::mouse(int kind, int virtualKeys, int mouseData, int x, int y) {
    if (kind == KUI_WEBVIEW_MOUSE_MOVE) {
        if (!running_.load()) return;
        auto move = [this, virtualKeys, x, y] {
            if (!compositionController_) return;
            wakeCapture((virtualKeys & (KUI_WEBVIEW_VK_LEFT | KUI_WEBVIEW_VK_RIGHT | KUI_WEBVIEW_VK_MIDDLE)) != 0);
            POINT point{MulDiv(x, windowWidth_, width_), MulDiv(y, windowHeight_, height_)};
            compositionController_->SendMouseInput(
                    static_cast<COREWEBVIEW2_MOUSE_EVENT_KIND>(KUI_WEBVIEW_MOUSE_MOVE),
                    static_cast<COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS>(virtualKeys), 0, point);
        };
        {
            std::lock_guard<std::mutex> lock(commandMutex_);
            if (!commands_.empty() && commands_.back().mouseMove) {
                commands_.back().fn = std::move(move);
                ++coalescedMouseMoves_;
                return;
            }
            commands_.push_back(Command{std::move(move), nowMs(), true});
        }
        if (threadId_ != 0) {
            PostThreadMessageW(threadId_, kHostWakeMessage, 0, 0);
        }
        return;
    }
    post([this, kind, virtualKeys, mouseData, x, y] {
        if (!compositionController_) {
            return;
        }
        wakeCapture(kind != KUI_WEBVIEW_MOUSE_LEAVE);
        POINT point{MulDiv(x, windowWidth_, width_), MulDiv(y, windowHeight_, height_)};
        compositionController_->SendMouseInput(
                static_cast<COREWEBVIEW2_MOUSE_EVENT_KIND>(kind),
                static_cast<COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS>(virtualKeys),
                static_cast<UINT32>(mouseData),
                point);
    });
}

void WebViewHost::eval(const std::wstring& script) {
    post([this, script] {
        if (webview_) {
            wakeCapture(true);
            webview_->ExecuteScript(script.c_str(), nullptr);
        }
    });
}

void WebViewHost::enableBridge(bool enabled) {
    std::lock_guard<std::mutex> lock(bridgeMutex_);
    bridgeEnabled_ = enabled;
    bridgeMessages_.clear();
}

std::vector<std::wstring> WebViewHost::drainMessages() {
    std::lock_guard<std::mutex> lock(bridgeMutex_);
    std::vector<std::wstring> result;
    // Bound work per Java tick. Unread messages remain queued.
    for (int i = 0; i < 32 && !bridgeMessages_.empty(); ++i) {
        auto message = std::move(bridgeMessages_.front());
        bridgeMessages_.pop_front();
        result.insert(result.end(), message.begin(), message.end());
    }
    return result;
}

bool WebViewHost::postMessage(uint64_t generation, const std::wstring& json) {
    if (!running_ || json.size() > 65536) return false;
    if (bridgePosts_.fetch_add(1) >= 256) { --bridgePosts_; return false; }
    post([this, generation, json] {
        --bridgePosts_;
        // A response queued before navigation must never reach the next document.
        if (webview_ && generation == bridgeGeneration_.load()) {
            wakeCapture();
            webview_->PostWebMessageAsJson(json.c_str());
        }
    });
    return true;
}

void WebViewHost::requestCapture() {
    post([this] { forceCapture_ = true; wakeCapture(); });
}

bool WebViewHost::publishCanvas(const uint8_t* pixels, int width, int height, bool bgraSource,
                                uint64_t sourceGeneration) {
    std::lock_guard<std::mutex> lock(publishMutex_);
    if (sourceGeneration != frameGeneration_.load() || pixels == nullptr || width <= 0 || height <= 0 || !channel_.isOpen()
            || (bgraSource && streamReady_)) {
        return false;
    }
    if (!bgraSource) streamReady_ = true;
    const bool published = channel_.publish(pixels, width, height, bgraSource);
    if (published) {
        lastPublishTick_ = static_cast<long long>(nowMs());
        ++decisionWindowPublishes_;
        unchangedSince_ = 0;
        refreshPending_ = false;
    } else if (!channel_.hasDeferredRects() && !channel_.fullRefreshRequested()) {
        uint64_t expected = 0;
        unchangedSince_.compare_exchange_strong(expected, nowMs());
    }
    return published;
}

std::wstring WebViewHost::lastError() {
    std::lock_guard<std::mutex> lock(errorMutex_);
    return lastError_;
}

void WebViewHost::setError(const std::wstring& message) {
    std::lock_guard<std::mutex> lock(errorMutex_);
    lastError_ = message;
}

void WebViewHost::noteStatus(const std::wstring& message) {
    std::lock_guard<std::mutex> lock(errorMutex_);
    status_ = message;
}

std::wstring WebViewHost::statusText() {
    std::lock_guard<std::mutex> lock(errorMutex_);
    wchar_t buffer[1024];
    swprintf_s(buffer,
               L"ready=%d nav=%d capture=%d done=%d rejected=%d stale=%d decodeFail=%d bytes=%lld "
               L"roundTrip=%lldms decode=%lldms period=%lldms loop=%dHz fps=%d "
               L"cmd=%lld/%lldms pending=%d dropped=%d coalesced=%d "
               L"stream=%lld/%lld/%lld/%lld@%dx%d raster=%dx%d format=%s "
               L"window=%dx%d | %s | %s",
               ready_.load() ? 1 : 0, navigationCompleted_.load(),
               captureAttempts_.load(), captureCompleted_.load(), captureRejected_.load(),
               staleCaptures_.load(), decodeFailures_.load(), lastPngBytes_.load(),
               lastRoundTripMs_.load(), lastDecodeMs_.load(), lastCapturePeriodMs_.load(),
               loopHz_.load(), capturedPerSecond_.load(),
               lastCommandLatencyMs_.load(), maxCommandLatencyMs_.load(),
               pendingDecodes_.load(), droppedDecodes_.load(), coalescedMouseMoves_.load(),
               stream_.callbackCount(), stream_.frameCount(), stream_.emptyCallbackCount(),
               stream_.blankFrameCount(), stream_.lastFrameWidth(), stream_.lastFrameHeight(),
               windowWidth_.load(), windowHeight_.load(),
               frameFormat_ == 2 ? (streamActive_ ? L"auto-stream"
                                                  : (autoUsesFast_ ? L"auto-jpeg" : L"auto-png"))
                                 : (frameFormat_ == 3 ? L"stream"
                                                      : (frameFormat_ == 1 ? L"jpeg" : L"png")),
               windowWidth_.load(), windowHeight_.load(),
               channel_.statusText().c_str(), status_.c_str());
    return std::wstring(buffer);
}

LRESULT CALLBACK WebViewHost::windowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    return DefWindowProcW(hwnd, message, wParam, lParam);
}

bool WebViewHost::createWindow(HINSTANCE instance) {
    static std::mutex registrationMutex;
    static bool registered = false;
    {
        std::lock_guard<std::mutex> lock(registrationMutex);
        if (!registered) {
            WNDCLASSEXW wc{};
            wc.cbSize = sizeof(wc);
            wc.lpfnWndProc = &WebViewHost::windowProc;
            wc.hInstance = instance;
            wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
            wc.lpszClassName = kWindowClass;
            if (RegisterClassExW(&wc) == 0 && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
                setError(L"RegisterClassExW failed");
                return false;
            }
            registered = true;
        }
    }
    // Parked off the desktop: the window stays "visible" to the compositor (so the
    // renderer keeps painting and CapturePreview returns real pixels) while the user
    // never sees it. KUI_WEBVIEW_X/KUI_WEBVIEW_Y override the parking spot for
    // diagnosing compositor throttling.
    int originX = -32000;
    int originY = -32000;
    wchar_t envBuffer[32];
    if (GetEnvironmentVariableW(L"KUI_WEBVIEW_X", envBuffer, 32) > 0) {
        originX = _wtoi(envBuffer);
    }
    if (GetEnvironmentVariableW(L"KUI_WEBVIEW_Y", envBuffer, 32) > 0) {
        originY = _wtoi(envBuffer);
    }
    originX_ = originX;
    originY_ = originY;
    windowWidth_ = width_;
    windowHeight_ = height_;
    // KUI_WEBVIEW_TOPMOST is a diagnostic knob: the composition stream cannot see a window
    // that something else covers, so this is how to tell "occluded" apart from "capturable
    // content is not exposed at all".
    DWORD exStyle = WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW;
    if (GetEnvironmentVariableW(L"KUI_WEBVIEW_TOPMOST", envBuffer, 32) > 0 && _wtoi(envBuffer) != 0) {
        exStyle |= WS_EX_TOPMOST;
    }
    hwnd_ = CreateWindowExW(exStyle, kWindowClass, L"",
                            WS_POPUP, originX_, originY_, width_, height_,
                            nullptr, nullptr, instance, nullptr);
    if (hwnd_ == nullptr) {
        setError(L"CreateWindowExW failed");
        return false;
    }
    ShowWindow(hwnd_, SW_SHOWNOACTIVATE);
    UpdateWindow(hwnd_);
    return true;
}

bool WebViewHost::createEnvironment() {
    auto options = Microsoft::WRL::Make<CoreWebView2EnvironmentOptions>();
    // The host window is parked off the desktop, which Windows reports as occluded;
    // Chromium then stops compositing and CapturePreview keeps returning the last
    // presented frame. These are the standard switches for offscreen capture.
    //
    // The device scale factor is pinned to 1 so the raster size is exactly the bounds in
    // pixels; the host Java side then drives ZoomFactor itself, which is what makes the
    // page's CSS viewport equal the element's content box (bounds / zoom) with a matching
    // devicePixelRatio. Leaving the scale factor automatic would make both depend on the
    // monitor and break that.
    options->put_AdditionalBrowserArguments(
            L"--disable-features=CalculateNativeWinOcclusion,msEdgeAutofill"
            L" --disable-background-timer-throttling"
            L" --disable-backgrounding-occluded-windows"
            L" --disable-renderer-backgrounding"
            L" --force-device-scale-factor=1");
    const wchar_t* userData = userDataDir_.empty() ? nullptr : userDataDir_.c_str();

    HRESULT hr = CreateCoreWebView2EnvironmentWithOptions(
            nullptr, userData, options.Get(),
            Microsoft::WRL::Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
                    [this](HRESULT result, ICoreWebView2Environment* environment) -> HRESULT {
                        if (FAILED(result) || environment == nullptr) {
                            setError(L"CreateCoreWebView2EnvironmentWithOptions failed");
                            initFailed_ = true;
                            SetEvent(readyEvent_);
                            return S_OK;
                        }
                        environment_ = environment;
                        Microsoft::WRL::ComPtr<ICoreWebView2Environment3> environment3;
                        if (FAILED(environment_->QueryInterface(IID_PPV_ARGS(&environment3)))) {
                            setError(L"WebView2 runtime is too old for offscreen hosting");
                            initFailed_ = true;
                            SetEvent(readyEvent_);
                            return S_OK;
                        }
                        HRESULT attach = environment3->CreateCoreWebView2CompositionController(
                                hwnd_,
                                Microsoft::WRL::Callback<ICoreWebView2CreateCoreWebView2CompositionControllerCompletedHandler>(
                                        [this](HRESULT controllerResult,
                                               ICoreWebView2CompositionController* controller) -> HRESULT {
                                            if (FAILED(controllerResult) || controller == nullptr) {
                                                setError(L"CreateCoreWebView2CompositionController failed");
                                                initFailed_ = true;
                                            } else {
                                                HRESULT attached = attachController(controller);
                                                if (FAILED(attached)) {
                                                    initFailed_ = true;
                                                }
                                            }
                                            if (initFailed_) SetEvent(readyEvent_);
                                            return S_OK;
                                        })
                                        .Get());
                        if (FAILED(attach)) {
                            setError(L"CreateCoreWebView2CompositionController call failed");
                            initFailed_ = true;
                            SetEvent(readyEvent_);
                        }
                        return S_OK;
                    })
                    .Get());

    if (FAILED(hr)) {
        setError(L"WebView2 runtime is not installed");
        return false;
    }
    return true;
}

bool WebViewHost::enableVisualCapture() {
    if (visualComposition_) return visualComposition_->ready;
    visualComposition_ = std::make_unique<VisualComposition>();
    try {
        winrt::check_hresult(RoInitialize(RO_INIT_SINGLETHREADED));
        visualComposition_->runtimeInitialized = true;
        DispatcherQueueOptions options{sizeof(DispatcherQueueOptions), DQTYPE_THREAD_CURRENT, DQTAT_COM_NONE};
        winrt::check_hresult(CreateDispatcherQueueController(options,
                reinterpret_cast<PDISPATCHERQUEUECONTROLLER*>(winrt::put_abi(visualComposition_->dispatcher))));
        visualComposition_->compositor = winrt::Windows::UI::Composition::Compositor();
        auto interop = visualComposition_->compositor.as<ABI::Windows::UI::Composition::Desktop::ICompositorDesktopInterop>();
        // The existing DirectComposition target occupies the topmost layer during a switch.
        winrt::check_hresult(interop->CreateDesktopWindowTarget(hwnd_, dcompTarget_ ? FALSE : TRUE,
                reinterpret_cast<ABI::Windows::UI::Composition::Desktop::IDesktopWindowTarget**>(
                        winrt::put_abi(visualComposition_->target))));
        visualComposition_->root = visualComposition_->compositor.CreateContainerVisual();
        visualComposition_->root.Size({static_cast<float>(width_), static_cast<float>(height_)});
        visualComposition_->target.Root(visualComposition_->root);
        winrt::check_hresult(compositionController_->put_RootVisualTarget(winrt::get_unknown(visualComposition_->root)));
        if (dcompTarget_) {
            winrt::check_hresult(dcompTarget_->SetRoot(nullptr));
            winrt::check_hresult(dcompDevice_->Commit());
            dcompVisual_.Reset();
            dcompTarget_.Reset();
            dcompDevice_.Reset();
        }
        visualComposition_->ready = true;
        return true;
    } catch (const winrt::hresult_error& failure) {
        noteStatus(L"visual capture setup failed: " + std::wstring(failure.message().c_str()));
        if (dcompTarget_ && dcompVisual_) {
            const HRESULT rootResult = dcompTarget_->SetRoot(dcompVisual_.Get());
            const HRESULT targetResult = compositionController_->put_RootVisualTarget(dcompVisual_.Get());
            const HRESULT commitResult = dcompDevice_->Commit();
            if (FAILED(rootResult) || FAILED(targetResult) || FAILED(commitResult)) {
                setError(L"could not restore the previous WebView composition target");
            }
        }
        try {
            if (visualComposition_->target) {
                visualComposition_->target.Root(nullptr);
                visualComposition_->target.Close();
                visualComposition_->target = nullptr;
            }
            if (visualComposition_->root) {
                visualComposition_->root.Close();
                visualComposition_->root = nullptr;
            }
            if (visualComposition_->compositor) {
                visualComposition_->compositor.Close();
                visualComposition_->compositor = nullptr;
            }
        } catch (const winrt::hresult_error& closeFailure) {
            noteStatus(L"visual capture cleanup failed: " + std::wstring(closeFailure.message().c_str()));
        }
        return false;
    }
}

HRESULT WebViewHost::attachController(ICoreWebView2CompositionController* compositionController) {
    compositionController_ = compositionController;
    // The composition controller implements ICoreWebView2Controller as well; the
    // controller half owns bounds/visibility/focus while the composition half adds
    // the visual target and mouse injection.
    HRESULT hr = compositionController_.As(&controller_);
    if (FAILED(hr) || controller_ == nullptr) {
        setError(L"composition controller has no ICoreWebView2Controller");
        return FAILED(hr) ? hr : E_NOINTERFACE;
    }

    bool visualCapture = frameFormat_ == 3 && !transparent_ && enableVisualCapture();
    if (!visualCapture) {
        hr = DCompositionCreateDevice(nullptr, IID_PPV_ARGS(&dcompDevice_));
        if (FAILED(hr)) {
            setError(L"DCompositionCreateDevice failed");
            return hr;
        }
        hr = dcompDevice_->CreateTargetForHwnd(hwnd_, TRUE, &dcompTarget_);
        if (FAILED(hr)) {
            setError(L"CreateTargetForHwnd failed");
            return hr;
        }
        hr = dcompDevice_->CreateVisual(&dcompVisual_);
        if (FAILED(hr)) {
            setError(L"CreateVisual failed");
            return hr;
        }
        hr = dcompTarget_->SetRoot(dcompVisual_.Get());
        if (FAILED(hr)) {
            setError(L"DirectComposition SetRoot failed");
            return hr;
        }
        hr = compositionController_->put_RootVisualTarget(dcompVisual_.Get());
        if (FAILED(hr)) {
            setError(L"put_RootVisualTarget failed");
            return hr;
        }
        hr = dcompDevice_->Commit();
        if (FAILED(hr)) {
            setError(L"DirectComposition commit failed");
            return hr;
        }
    }

    RECT bounds{0, 0, width_, height_};
    controller_->put_Bounds(bounds);
    controller_->put_IsVisible(TRUE);

    if (transparent_) {
        Microsoft::WRL::ComPtr<ICoreWebView2Controller2> controller2;
        if (SUCCEEDED(controller_.As(&controller2))) {
            COREWEBVIEW2_COLOR transparentColor{0, 0, 0, 0};
            controller2->put_DefaultBackgroundColor(transparentColor);
        }
    }

    hr = controller_->get_CoreWebView2(&webview_);
    if (FAILED(hr)) {
        setError(L"get_CoreWebView2 failed");
        return hr;
    }

    Microsoft::WRL::ComPtr<ICoreWebView2Settings> settings;
    if (SUCCEEDED(webview_->get_Settings(&settings))) {
        settings->put_AreDefaultContextMenusEnabled(FALSE);
        settings->put_IsStatusBarEnabled(FALSE);
        settings->put_AreDevToolsEnabled(FALSE);
        settings->put_IsZoomControlEnabled(TRUE);
    }

    EventRegistrationToken navigationToken{};
    webview_->add_NavigationCompleted(
            Microsoft::WRL::Callback<ICoreWebView2NavigationCompletedEventHandler>(
                    [this](ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs* args) -> HRESULT {
                        BOOL success = FALSE;
                        if (args != nullptr) {
                            args->get_IsSuccess(&success);
                        }
                        wakeCapture();
                        ++navigationCompleted_;
                        navigationSucceeded_ = success == TRUE;
                        wakeCapture();
                        return S_OK;
                    })
                    .Get(),
            &navigationToken);

    // A page that opens a link in a new window would otherwise get a real popup window on the
    // user's desktop, from a browser instance they cannot see or move. The element is a frame
    // in a game UI, so the navigation belongs in this view: take the request over and go
    // there ourselves. (window.close() is deliberately not handled — there is no window of
    // ours to close, and blanking the page would be a surprise.)
    EventRegistrationToken newWindowToken{};
    webview_->add_NewWindowRequested(
            Microsoft::WRL::Callback<ICoreWebView2NewWindowRequestedEventHandler>(
                    [this](ICoreWebView2*, ICoreWebView2NewWindowRequestedEventArgs* args) -> HRESULT {
                        if (args == nullptr) {
                            return S_OK;
                        }
                        args->put_Handled(TRUE);
                        LPWSTR uri = nullptr;
                        if (SUCCEEDED(args->get_Uri(&uri)) && uri != nullptr && *uri != L'\0') {
                            noteStatus(L"popup navigation kept in this view");
                            webview_->Navigate(uri);
                        }
                        if (uri != nullptr) {
                            CoTaskMemFree(uri);
                        }
                        return S_OK;
                    })
                    .Get(),
            &newWindowToken);

    EventRegistrationToken startingToken{}, messageToken{};
    webview_->add_NavigationStarting(
        Microsoft::WRL::Callback<ICoreWebView2NavigationStartingEventHandler>(
            [this](ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs*) -> HRESULT {
                manualCapture_ = false;
                captureMode_ = 0;
                {
                    std::lock_guard<std::mutex> publishLock(publishMutex_);
                    ++frameGeneration_;
                    streamReady_ = false;
                }
                wakeCapture();
                std::lock_guard<std::mutex> lock(bridgeMutex_);
                ++bridgeGeneration_;
                bridgeMessages_.clear();
                if (bridgeEnabled_) bridgeMessages_.push_back({std::to_wstring(bridgeGeneration_.load()), L"", L""});
                return S_OK;
            }).Get(), &startingToken);
    webview_->add_WebMessageReceived(
        Microsoft::WRL::Callback<ICoreWebView2WebMessageReceivedEventHandler>(
            [this](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                LPWSTR control = nullptr;
                if (SUCCEEDED(args->TryGetWebMessageAsString(&control)) && control) {
                    const std::wstring message(control);
                    CoTaskMemFree(control);
                    if (message == L"kui:capture:manual" || message == L"kui:capture:auto") {
                        manualCapture_ = message == L"kui:capture:manual";
                        captureMode_ = manualCapture_ ? 1 : 0;
                        wakeCapture();
                        return S_OK;
                    }
                    if (message == L"kui:capture:invalidate") {
                        wakeCapture();
                        return S_OK;
                    }
                }
                if (!bridgeEnabled_) return S_OK;
                LPWSTR source = nullptr, json = nullptr;
                args->get_Source(&source);
                args->get_WebMessageAsJson(&json);
                if (source && json && wcslen(json) <= 65536) {
                    std::lock_guard<std::mutex> lock(bridgeMutex_);
                    if (bridgeMessages_.size() < 256) {
                        bridgeMessages_.push_back({std::to_wstring(bridgeGeneration_.load()), source, json});
                    } else {
                        webview_->PostWebMessageAsJson(L"{\"v\":1,\"type\":\"overflow\"}");
                    }
                }
                CoTaskMemFree(source);
                CoTaskMemFree(json);
                return S_OK;
            }).Get(), &messageToken);
    // Registration must finish before the first navigation, or early page calls are lost.
    return webview_->AddScriptToExecuteOnDocumentCreated(kBridgeScript,
        Microsoft::WRL::Callback<ICoreWebView2AddScriptToExecuteOnDocumentCreatedCompletedHandler>(
            [this](HRESULT result, LPCWSTR) -> HRESULT {
                if (FAILED(result)) {
                    setError(L"bridge script registration failed");
                    initFailed_ = true;
                } else {
                    controller_->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
                    ready_ = true;
                    lastCaptureTick_ = nowMs();
                }
                SetEvent(readyEvent_);
                return S_OK;
            }).Get());
}

void WebViewHost::drainCommands() {
    std::deque<Command> pending;
    {
        std::lock_guard<std::mutex> lock(commandMutex_);
        pending.swap(commands_);
    }
    for (auto& command : pending) {
        // This is the number that matters for feel: how long pointer input sat in the queue
        // before the host thread got to it. It is large exactly while the thread is busy
        // decoding, which is why decoding no longer happens here.
        const long long latency = static_cast<long long>(nowMs() - command.queuedAt);
        lastCommandLatencyMs_ = latency;
        if (latency > maxCommandLatencyMs_.load()) {
            maxCommandLatencyMs_ = latency;
        }
        command.fn();
    }
}

void WebViewHost::tickCapture() {
    if (!ready_ || webview_ == nullptr || capturesInFlight_ >= kMaxCapturesInFlight) {
        return;
    }
    // A manual capture while paused uses the existing one-shot preview path; starting a
    // continuous graphics session here would keep capturing after that request completed.
    if (autoCapture_.load() && !manualCapture_ && resolveFrameFormat() == 3) {
        if (!ensureStream()) {
            if (frameFormat_ == 3) {
                ++captureRejected_;
            } else {
                frameFormat_ = 2;  // auto: fall back to the codec path on the next tick
            }
        } else if (streamReady_) {
            bool channelNeedsRefresh;
            {
                std::lock_guard<std::mutex> lock(publishMutex_);
                channelNeedsRefresh = channel_.hasDeferredRects() || channel_.fullRefreshRequested();
            }
            if (channelNeedsRefresh) {
                const uint64_t sourceGeneration = frameGeneration_.load();
                stream_.replayLatest([this, sourceGeneration](int width, int height, const uint8_t* rgba, size_t, bool) {
                    publishCanvas(rgba, width, height, false, sourceGeneration);
                });
            }
            return;
        }
        // Keep the existing lossless capture until the stream delivers its first frame.
    }
    if (!acquireCaptureBudget(this)) {
        ++budgetWaits_;
        return;
    }
    Microsoft::WRL::ComPtr<IStream> stream;
    if (FAILED(CreateStreamOnHGlobal(nullptr, TRUE, &stream))) {
        --gCapturesInFlight;
        return;
    }
    ++capturesInFlight_;
    const uint64_t captureId = ++captureSequence_;
    const uint64_t sourceGeneration = frameGeneration_.load();
    uint64_t startTick = 0;
    ++captureAttempts_;
    {
        const uint64_t now = nowMs();
        if (lastCaptureStartTick_ != 0) {
            lastCapturePeriodMs_ = static_cast<long long>(now - lastCaptureStartTick_);
        }
        lastCaptureStartTick_ = now;
        startTick = now;
    }
    const COREWEBVIEW2_CAPTURE_PREVIEW_IMAGE_FORMAT format =
            resolveFrameFormat() == 1 ? COREWEBVIEW2_CAPTURE_PREVIEW_IMAGE_FORMAT_JPEG
                                      : COREWEBVIEW2_CAPTURE_PREVIEW_IMAGE_FORMAT_PNG;
    HRESULT hr = webview_->CapturePreview(
            format, stream.Get(),
            Microsoft::WRL::Callback<ICoreWebView2CapturePreviewCompletedHandler>(
                    [this, stream, captureId, startTick, sourceGeneration](HRESULT result) -> HRESULT {
                        if (!running_) {
                            if (capturesInFlight_ > 0) {
                                --capturesInFlight_;
                                --gCapturesInFlight;
                            }
                            return S_OK;
                        }
                        lastCaptureHr_ = static_cast<long long>(result);
                        ++captureCompleted_;
                        lastRoundTripMs_ = static_cast<long long>(nowMs() - startTick);
                        ++rateWindowFrames_;
                        if (SUCCEEDED(result)) {
                            if (sourceGeneration == frameGeneration_.load() && captureId > publishedCaptureSequence_) {
                                // Overlapping captures can complete out of order; an older
                                // frame published after a newer one would leave stale
                                // rectangles on the canvas, so only the newest one lands. The
                                // decision is made here, on the host thread, and the decoder
                                // then applies the survivors in that order.
                                publishedCaptureSequence_ = captureId;
                                forceCapture_ = false;
                                enqueueDecode(stream, sourceGeneration);
                            } else {
                                // A newer capture already published; this one is stale, not
                                // failed, and dropping it is what keeps the canvas monotonic.
                                ++staleCaptures_;
                            }
                        } else {
                            ++captureRejected_;
                            retryCapture_ = true;
                        }
                        --capturesInFlight_;
                        --gCapturesInFlight;
                        return S_OK;
                    })
                    .Get());
    if (FAILED(hr)) {
        lastCaptureHr_ = static_cast<long long>(hr);
        ++captureRejected_;
        --capturesInFlight_;
        --gCapturesInFlight;
        retryCapture_ = true;
    }
}

bool WebViewHost::ensureStream() {
    if (streamActive_) {
        return true;
    }
    if (streamUnavailable_ || hwnd_ == nullptr) {
        return false;
    }
    if (!FrameStream::isSupported()) {
        streamUnavailable_ = true;
        noteStatus(L"composition stream not supported on this system");
        return false;
    }
    if (!transparent_ && !enableVisualCapture()) {
        streamUnavailable_ = true;
        return false;
    }
    auto sourceGeneration = std::make_shared<std::atomic<uint64_t>>(frameGeneration_.load());
    IUnknown* visual = visualComposition_ && visualComposition_->root
            ? winrt::get_unknown(visualComposition_->root) : nullptr;
    const bool visualCapture = visual != nullptr;
    const bool started = stream_.start(
            hwnd_, visual, windowWidth_.load(), windowHeight_.load(),
            [this, sourceGeneration, visualCapture](int frameWidth, int frameHeight, const uint8_t* rgba, size_t bytes, bool blank) {
                publishRaw(frameWidth, frameHeight, rgba, bytes, sourceGeneration->load(), visualCapture, blank);
            });
    if (!started) {
        {
            std::lock_guard<std::mutex> lock(publishMutex_);
            streamReady_ = false;
            ++frameGeneration_;
        }
        streamUnavailable_ = true;
        const std::string error = stream_.lastError();
        noteStatus(L"composition stream failed: " + std::wstring(error.begin(), error.end()));
        return false;
    }
    streamGeneration_ = std::move(sourceGeneration);
    streamActive_ = true;
    noteStatus(L"composition stream running");
    return true;
}

/**
 * Publishes a frame produced by the composition stream. Called from the stream's own
 * callback thread, so it only touches the channel (guarded by the publish mutex) and the
 * rate counters.
 */
void WebViewHost::publishRaw(int width, int height, const uint8_t* rgba, size_t bytes,
                             uint64_t sourceGeneration, bool visualCapture, bool blank) {
    if (sourceGeneration != frameGeneration_.load() || width <= 0 || height <= 0 || rgba == nullptr || bytes == 0) {
        return;
    }
    if (blank && !visualCapture) {
        // Every frame the composition stream hands back is a single flat colour, which
        // means it cannot see this window's content at all: the WebView2 visual tree is
        // attached through DirectComposition and neither Windows.Graphics.Capture nor
        // PrintWindow reports it. Drop the frame (never publish it — it would show as a
        // blank rectangle) and ask the host thread to fall back to the capture codecs.
        {
            std::lock_guard<std::mutex> lock(publishMutex_);
            if (sourceGeneration == frameGeneration_.load()) streamAbandon_ = true;
        }
        return;
    }
    if (blank && !streamReady_) return;
    if (publishCanvas(rgba, width, height, false, sourceGeneration)) {
        lastPngBytes_ = static_cast<long long>(bytes);
        ++captureCompleted_;
    }
    ++rateWindowFrames_;
}

/**
 * Picks the codec for the next capture.
 *
 * Auto stays lossless until frames keep changing, then uses the fast codec; the switching
 * has hysteresis so a page hovering around the threshold does not flip codecs (each switch
 * invalidates the payload baseline and costs one extra published frame).
 */
int WebViewHost::resolveFrameFormat() {
    if (frameFormat_ == 3 && streamUnavailable_) {
        return 0;  // nothing but the codecs left; the caller treats this as "no stream"
    }
    // Auto deliberately does not try mode 3. Measured on Windows 11 with this hosting mode,
    // a window capture of the WebView2 HWND returns a single flat-colour frame and then no
    // further callbacks — both while the window was parked off the desktop and while it was
    // on screen and topmost — so the DirectComposition visual is simply not exposed to
    // window capture. Mode 3 stays available as an explicit opt-in in case a future
    // Windows/WebView2 build changes that, but nothing selects it automatically.
    if (frameFormat_ != 2) {
        return frameFormat_;
    }
    const ULONGLONG now = nowMs();
    if (decisionWindowStart_ == 0) {
        decisionWindowStart_ = now;
    } else if (now - decisionWindowStart_ >= AUTO_WINDOW_MS) {
        if (decisionWindowPublishes_ >= AUTO_PUBLISHES_TO_GO_FAST) {
            autoUsesFast_ = true;
        } else if (autoUsesFast_ && now - lastPublishTick_ >= AUTO_QUIET_MS) {
            autoUsesFast_ = false;
        }
        decisionWindowStart_ = now;
        decisionWindowPublishes_ = 0;
    }
    return autoUsesFast_ ? 1 : 0;
}

void WebViewHost::decodeAndPublish(IStream* stream, uint64_t sourceGeneration) {
    if (sourceGeneration != frameGeneration_.load()) return;
    LARGE_INTEGER origin{};
    stream->Seek(origin, STREAM_SEEK_SET, nullptr);

    STATSTG stats{};
    size_t payloadBytes = 0;
    if (SUCCEEDED(stream->Stat(&stats, STATFLAG_NONAME))) {
        payloadBytes = static_cast<size_t>(stats.cbSize.QuadPart);
        lastPngBytes_ = static_cast<long long>(payloadBytes);
    }
    if (payloadBytes == 0) {
        ++decodeFailures_;
        retryCapture_ = true;
        noteStatus(L"capture produced no payload");
        return;
    }

    // Pull the encoded payload out of the stream once and compare it against the
    // previous one before doing any work. The encoder is deterministic for identical
    // content, so an unchanged page produces byte-identical payloads — which lets a
    // static page skip the decode, the per-pixel swap and the Java-side copy entirely.
    // This matters most with JPEG, where decoded pixels are never bit-identical.
    if (payload_.size() != payloadBytes) {
        payload_.resize(payloadBytes);
    }
    ULONG read = 0;
    if (FAILED(stream->Read(payload_.data(), static_cast<ULONG>(payloadBytes), &read)) ||
        read != payloadBytes) {
        ++decodeFailures_;
        retryCapture_ = true;
        noteStatus(L"capture stream read failed");
        return;
    }
    // An unchanged page re-encodes to identical bytes, which is where the whole per-frame
    // cost of a static view disappears. The shortcut is skipped while the reader is still
    // owed rectangles or has asked for a full refresh — a static page must still be able to
    // finish its own picture.
    bool channelNeedsRefresh;
    {
        std::lock_guard<std::mutex> lock(publishMutex_);
        channelNeedsRefresh = channel_.hasDeferredRects() || channel_.fullRefreshRequested();
    }
    if (lastPayloadGeneration_ == sourceGeneration && lastPayload_.size() == payloadBytes &&
        std::memcmp(lastPayload_.data(), payload_.data(), payloadBytes) == 0 &&
        !channelNeedsRefresh) {
        uint64_t expected = 0;
        unchangedSince_.compare_exchange_strong(expected, nowMs());
        return;
    }

    Microsoft::WRL::ComPtr<IWICImagingFactory> factory;
    if (FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                                IID_PPV_ARGS(&factory)))) {
        ++decodeFailures_;
        retryCapture_ = true;
        noteStatus(L"WIC factory unavailable");
        return;
    }
    Microsoft::WRL::ComPtr<IWICStream> wicStream;
    if (FAILED(factory->CreateStream(&wicStream)) ||
        FAILED(wicStream->InitializeFromMemory(payload_.data(), static_cast<DWORD>(payloadBytes)))) {
        ++decodeFailures_;
        retryCapture_ = true;
        noteStatus(L"WIC stream init failed");
        return;
    }
    Microsoft::WRL::ComPtr<IWICBitmapDecoder> decoder;
    if (FAILED(factory->CreateDecoderFromStream(wicStream.Get(), nullptr,
                                                WICDecodeMetadataCacheOnDemand, &decoder))) {
        ++decodeFailures_;
        retryCapture_ = true;
        noteStatus(L"WIC decoder creation failed");
        return;
    }
    Microsoft::WRL::ComPtr<IWICBitmapFrameDecode> decoded;
    if (FAILED(decoder->GetFrame(0, &decoded))) {
        ++decodeFailures_;
        retryCapture_ = true;
        noteStatus(L"WIC GetFrame failed");
        return;
    }
    UINT width = 0;
    UINT height = 0;
    if (FAILED(decoded->GetSize(&width, &height)) || width == 0 || height == 0) {
        ++decodeFailures_;
        retryCapture_ = true;
        noteStatus(L"WIC frame has no size");
        return;
    }
    Microsoft::WRL::ComPtr<IWICFormatConverter> converter;
    if (FAILED(factory->CreateFormatConverter(&converter)) ||
        FAILED(converter->Initialize(decoded.Get(), GUID_WICPixelFormat32bppBGRA,
                                     WICBitmapDitherTypeNone, nullptr, 0.0,
                                     WICBitmapPaletteTypeCustom))) {
        ++decodeFailures_;
        retryCapture_ = true;
        noteStatus(L"WIC format conversion failed");
        return;
    }

    std::vector<uint8_t>& pixels = decodedPixels_;
    const size_t pixelBytes = static_cast<size_t>(width) * height * 4;
    if (pixels.size() != pixelBytes) {
        pixels.resize(pixelBytes);
    }
    rasterBytes_ = static_cast<int>(pixelBytes);
    const UINT stride = width * 4;
    if (FAILED(converter->CopyPixels(nullptr, stride, static_cast<UINT>(pixelBytes),
                                     pixels.data()))) {
        ++decodeFailures_;
        retryCapture_ = true;
        noteStatus(L"WIC CopyPixels failed");
        return;
    }
    // Remember the encoded payload as the baseline: an unchanged page re-encodes to the
    // same bytes, which is what lets the next capture skip all of this work. The skip is
    // held back while the channel still owes the reader rectangles, otherwise a static page
    // would never get the rest of its update.
    lastPayload_.swap(payload_);
    lastPayloadGeneration_ = sourceGeneration;
    if (!publishCanvas(pixels.data(), static_cast<int>(width), static_cast<int>(height), true, sourceGeneration))
        retryCapture_ = true;
}

void WebViewHost::startDecodeThread() {
    decodeRunning_ = true;
    decodeThread_ = std::thread(&WebViewHost::decodeThreadMain, this);
}

void WebViewHost::stopDecodeThread() {
    {
        std::lock_guard<std::mutex> lock(decodeMutex_);
        decodeRunning_ = false;
    }
    decodeSignal_.notify_all();
    if (decodeThread_.joinable()) {
        decodeThread_.join();
    }
    {
        std::lock_guard<std::mutex> lock(decodeMutex_);
        decodeQueue_.clear();
    }
    pendingDecodes_ = 0;
}

void WebViewHost::enqueueDecode(Microsoft::WRL::ComPtr<IStream> stream, uint64_t sourceGeneration) {
    {
        std::lock_guard<std::mutex> lock(decodeMutex_);
        if (decodeQueue_.size() >= static_cast<size_t>(kMaxPendingDecodes)) {
            // The decoder is still behind: this frame would be applied far too late to be
            // worth the work, and the capture loop will be told to slow down anyway.
            ++droppedDecodes_;
            return;
        }
        decodeQueue_.emplace_back(std::move(stream), sourceGeneration);
        ++pendingDecodes_;
    }
    decodeSignal_.notify_one();
}

/**
 * Decodes and publishes off the UI thread.
 *
 * <p>WIC decoding, the channel byte-order swap, the tile diff and the shared-memory publish
 * all used to run inside the capture completion handler, which is the UI thread — the same
 * thread that pumps WebView2's messages and forwards pointer input. On a 1200x900 raster
 * that was 15-25 ms of message pump per frame, which is exactly what made dragging and
 * scrolling feel laggy. Nothing here touches COM objects owned by the UI thread.</p>
 */
void WebViewHost::decodeThreadMain() {
    // Below normal: the game's render thread must win any CPU contention with an iframe.
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_BELOW_NORMAL);
    // WIC needs an apartment on whichever thread creates its factory.
    const HRESULT com = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    while (true) {
        std::pair<Microsoft::WRL::ComPtr<IStream>, uint64_t> item;
        {
            std::unique_lock<std::mutex> lock(decodeMutex_);
            decodeSignal_.wait_for(lock, std::chrono::milliseconds(50),
                                   [this] { return !decodeQueue_.empty() || !decodeRunning_; });
            if (decodeQueue_.empty()) {
                if (!decodeRunning_) {
                    break;
                }
                continue;
            }
            item = std::move(decodeQueue_.front());
            decodeQueue_.pop_front();
        }
        const uint64_t start = nowMs();
        decodeAndPublish(item.first.Get(), item.second);
        lastDecodeMs_ = static_cast<long long>(nowMs() - start);
        --pendingDecodes_;
    }
    if (SUCCEEDED(com)) {
        CoUninitialize();
    }
}

void WebViewHost::releaseAll() {
    cancelCaptureBudget(this);
    if (budgetRegistered_) {
        --gActiveCaptures;
        budgetRegistered_ = false;
    }
    if (capturesInFlight_ != 0) {
        gCapturesInFlight -= capturesInFlight_;
        capturesInFlight_ = 0;
    }
    if (streamActive_) {
        stream_.stop();
        streamActive_ = false;
        streamGeneration_.reset();
    }
    webview_.Reset();
    if (controller_) {
        controller_->Close();
        controller_.Reset();
    }
    compositionController_.Reset();
    if (visualComposition_) {
        const bool runtimeInitialized = visualComposition_->runtimeInitialized;
        try {
            if (visualComposition_->target) {
                visualComposition_->target.Root(nullptr);
                visualComposition_->target.Close();
                visualComposition_->target = nullptr;
            }
            if (visualComposition_->root) {
                visualComposition_->root.Close();
                visualComposition_->root = nullptr;
            }
            if (visualComposition_->compositor) {
                visualComposition_->compositor.Close();
                visualComposition_->compositor = nullptr;
            }
        } catch (const winrt::hresult_error& failure) {
            noteStatus(L"visual composition close failed: " + std::wstring(failure.message().c_str()));
        }
        try {
            if (visualComposition_->dispatcher) {
                auto shutdown = visualComposition_->dispatcher.ShutdownQueueAsync();
                MSG message{};
                while (shutdown.Status() == winrt::Windows::Foundation::AsyncStatus::Started) {
                    while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
                        TranslateMessage(&message);
                        DispatchMessageW(&message);
                    }
                    MsgWaitForMultipleObjectsEx(0, nullptr, 10, QS_ALLINPUT, 0);
                }
                shutdown.GetResults();
            }
        } catch (const winrt::hresult_error& failure) {
            noteStatus(L"visual dispatcher shutdown failed: " + std::wstring(failure.message().c_str()));
        }
        visualComposition_.reset();
        if (runtimeInitialized) RoUninitialize();
    }
    dcompVisual_.Reset();
    if (dcompTarget_) {
        dcompTarget_->SetRoot(nullptr);
        dcompTarget_.Reset();
    }
    if (dcompDevice_) {
        dcompDevice_->Commit();
        dcompDevice_.Reset();
    }
    environment_.Reset();
    // A view that is gone must not keep a canvas-sized scratch buffer in the process.
    decodedPixels_.clear();
    decodedPixels_.shrink_to_fit();
}

void WebViewHost::threadMain() {
    threadId_ = GetCurrentThreadId();
    // Raise the timer resolution for this thread only: every wait in the capture loop below
    // is rounded up to the system tick otherwise, which is the difference between a 32 fps
    // ceiling and whatever the codec can actually do. Paired with timeEndPeriod on the way
    // out so the process does not leave the machine with a raised timer.
    timeBeginPeriod(1);
    // Thread-scoped per-monitor DPI awareness: makes "N requested pixels" mean "N
    // captured pixels" while leaving the host process's own DPI awareness (set by
    // Minecraft) untouched.
    SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    startDecodeThread();

    HINSTANCE instance = GetModuleHandleW(nullptr);
    if (!createWindow(instance)) {
        initFailed_ = true;
        SetEvent(readyEvent_);
    } else if (!createEnvironment()) {
        initFailed_ = true;
        SetEvent(readyEvent_);
    }

    MSG message{};
    while (running_.load()) {
        ++loopIterations_;
        while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        drainCommands();
        if (streamAbandon_.exchange(false)) {
            {
                std::lock_guard<std::mutex> lock(publishMutex_);
                streamReady_ = false;
                ++frameGeneration_;
            }
            stream_.stop();
            streamActive_ = false;
            streamGeneration_.reset();
            streamUnavailable_ = true;
            noteStatus(L"composition stream saw no content; fell back to the capture codecs");
        }
        {
            const ULONGLONG now = nowMs();
            if (rateWindowStart_ == 0) {
                rateWindowStart_ = now;
            } else if (now - rateWindowStart_ >= 1000) {
                capturedPerSecond_ = rateWindowFrames_.exchange(0);
                // Worst queue wait in the last second is the number that describes how the
                // view feels; a lifetime maximum would only ever grow.
                maxCommandLatencyMs_ = lastCommandLatencyMs_.load();
                loopHz_ = static_cast<int>(loopIterations_ - loopIterationsAtWindowStart_);
                loopIterationsAtWindowStart_ = loopIterations_;
                rateWindowStart_ = now;
            }
        }
        // The interval is a ceiling on the *request* rate, so it may only be consumed when a
        // capture can actually start: counting the ticks that land while one is in flight
        // added a whole interval to every frame (the callback already arrives tens of
        // milliseconds later, and waiting 8 ms past it was pure loss).
        // Only keep the pipeline as deep as the browser is serving cheaply. When a capture
        // already takes tens of milliseconds the extra requests just queue readbacks the GPU
        // has to work through — and in game that GPU is busy drawing the world.
        if (ready_.load()) updateCaptureBounds();
        const uint64_t now = nowMs();
        bool readerNeedsRefresh = false;
        {
            std::unique_lock<std::mutex> lock(publishMutex_, std::try_to_lock);
            if (lock.owns_lock())
                readerNeedsRefresh = channel_.hasDeferredRects() || channel_.fullRefreshRequested();
        }
        if (retryCapture_.exchange(false)) {
            forceCapture_ = true;
            wakeCapture();
        }
        const bool enabled = ready_.load() && (autoCapture_.load() || forceCapture_);
        const bool demand = forceCapture_ || !manualCapture_ || refreshPending_
                || now < activeUntil_ || readerNeedsRefresh;
        const uint64_t quiet = unchangedSince_.load();
        const bool active = enabled && demand && (now < activeUntil_ || quiet == 0 || now - quiet < 1500);
        if (active != budgetRegistered_) {
            gActiveCaptures += active ? 1 : -1;
            budgetRegistered_ = active;
        }
        const int interval = captureInterval(now);
        effectiveIntervalMs_ = enabled && demand ? interval : 0;
        if (enabled && demand && capturesInFlight_ < kMaxCapturesInFlight
                && pendingDecodes_ < kMaxPendingDecodes
                && now - lastCaptureTick_ >= static_cast<uint64_t>(interval)) {
            lastCaptureTick_ = now;
            tickCapture();
        } else {
            cancelCaptureBudget(this);
        }
        const DWORD wait = !enabled || !demand ? 50 : (interval >= 100 ? 25 : 4);
        MsgWaitForMultipleObjectsEx(0, nullptr, wait, QS_ALLINPUT, MWMO_INPUTAVAILABLE);
    }

    stopDecodeThread();
    releaseAll();
    if (hwnd_ != nullptr) {
        DestroyWindow(hwnd_);
        hwnd_ = nullptr;
    }
    // Give WebView2 a moment to finish tearing down before COM goes away.
    for (int i = 0; i < 10; ++i) {
        while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        Sleep(5);
    }
    CoUninitialize();
    timeEndPeriod(1);
    threadId_ = 0;
}
