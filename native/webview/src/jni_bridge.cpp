// JNI surface for the offscreen WebView host.
//
// Java side: io.github.kltyton.kltytonui.webview.WebViewNative
#include "webview_host.h"

#include <jni.h>

#include <map>
#include <memory>
#include <mutex>
#include <string>

namespace {

std::mutex g_registryMutex;
std::map<jlong, std::shared_ptr<WebViewHost>> g_liveHandles;
jlong g_nextHandle = 1;
std::mutex g_errorMutex;
std::wstring g_lastCreateError;

void setCreateError(const std::wstring& message) {
    std::lock_guard<std::mutex> lock(g_errorMutex);
    g_lastCreateError = message;
}

std::wstring utf8ToWide(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return std::wstring();
    }
    const jclass stringClass = env->FindClass("java/lang/String");
    const jmethodID getBytes = env->GetMethodID(stringClass, "getBytes", "(Ljava/lang/String;)[B");
    const jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, getBytes, charset));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(charset);
        env->DeleteLocalRef(stringClass);
        return std::wstring();
    }
    const jsize length = env->GetArrayLength(bytes);
    std::string utf8(static_cast<size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(&utf8[0]));
    }
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(stringClass);

    if (utf8.empty()) {
        return std::wstring();
    }
    const int wideLength = MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), static_cast<int>(utf8.size()),
                                               nullptr, 0);
    std::wstring wide(static_cast<size_t>(wideLength), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), static_cast<int>(utf8.size()), &wide[0], wideLength);
    return wide;
}

jstring wideToJstring(JNIEnv* env, const std::wstring& value) {
    return env->NewString(reinterpret_cast<const jchar*>(value.data()), static_cast<jsize>(value.size()));
}

std::shared_ptr<WebViewHost> resolve(jlong handle) {
    std::lock_guard<std::mutex> lock(g_registryMutex);
    const auto it = g_liveHandles.find(handle);
    return it == g_liveHandles.end() ? nullptr : it->second;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nAvailable(JNIEnv*, jclass) {
    LPWSTR version = nullptr;
    const HRESULT hr = GetAvailableCoreWebView2BrowserVersionString(nullptr, &version);
    if (version != nullptr) {
        CoTaskMemFree(version);
    }
    return SUCCEEDED(hr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nBrowserVersion(JNIEnv* env, jclass) {
    LPWSTR version = nullptr;
    const HRESULT hr = GetAvailableCoreWebView2BrowserVersionString(nullptr, &version);
    if (FAILED(hr) || version == nullptr) {
        return env->NewStringUTF("");
    }
    jstring result = wideToJstring(env, std::wstring(version));
    CoTaskMemFree(version);
    return result;
}

JNIEXPORT jlong JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nCreate(
        JNIEnv* env, jclass, jstring url, jstring userDataDir, jint width, jint height,
        jboolean transparent, jboolean autoCapture, jint frameIntervalMs, jint frameFormat) {
    setCreateError(L"");
    auto host = std::make_shared<WebViewHost>(width, height, transparent == JNI_TRUE, autoCapture == JNI_TRUE,
                                             frameIntervalMs, frameFormat, utf8ToWide(env, userDataDir));
    // Start the host thread first: navigate() is delivered through the host's command
    // queue, which only accepts work once the thread is running.
    if (!host->start(20000)) {
        const std::wstring error = host->lastError();
        setCreateError(error.empty() ? L"WebView2 host failed to start" : error);
        return 0;
    }
    if (url != nullptr) {
        host->navigate(utf8ToWide(env, url));
    }
    jlong handle;
    {
        std::lock_guard<std::mutex> lock(g_registryMutex);
        handle = g_nextHandle++;
        g_liveHandles.emplace(handle, host);
    }
    return handle;
}

JNIEXPORT jstring JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nLastCreateError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_errorMutex);
    return wideToJstring(env, g_lastCreateError);
}

JNIEXPORT void JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nDestroy(JNIEnv*, jclass, jlong handle) {
    std::shared_ptr<WebViewHost> host;
    {
        std::lock_guard<std::mutex> lock(g_registryMutex);
        const auto it = g_liveHandles.find(handle);
        if (it == g_liveHandles.end()) return;
        host = std::move(it->second);
        g_liveHandles.erase(it);
    }
    host->stop();
}

JNIEXPORT jboolean JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nIsAlive(JNIEnv*, jclass, jlong handle) {
    return resolve(handle) != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nStatus(JNIEnv* env, jclass, jlong handle) {
    auto host = resolve(handle);
    return wideToJstring(env, host == nullptr ? std::wstring(L"no such handle") : host->statusText());
}

JNIEXPORT jstring JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nLastError(JNIEnv* env, jclass, jlong handle) {
    auto host = resolve(handle);
    return wideToJstring(env, host == nullptr ? std::wstring() : host->lastError());
}

JNIEXPORT void JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nNavigate(
        JNIEnv* env, jclass, jlong handle, jstring url) {
    if (auto host = resolve(handle)) {
        host->navigate(utf8ToWide(env, url));
    }
}

JNIEXPORT void JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nSetBoundsAndZoom(
        JNIEnv*, jclass, jlong handle, jint width, jint height, jdouble zoom) {
    if (auto host = resolve(handle)) {
        host->setBoundsAndZoom(width, height, zoom);
    }
}

JNIEXPORT void JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nSetFrameFormat(
        JNIEnv*, jclass, jlong handle, jint format) {
    if (auto host = resolve(handle)) {
        host->setFrameFormat(format);
    }
}

JNIEXPORT void JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nSetFrameInterval(
        JNIEnv*, jclass, jlong handle, jint milliseconds) {
    if (auto host = resolve(handle)) {
        host->setFrameInterval(milliseconds);
    }
}

JNIEXPORT void JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nSetAutoCapture(
        JNIEnv*, jclass, jlong handle, jboolean enabled) {
    if (auto host = resolve(handle)) {
        host->setAutoCapture(enabled == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nRequestCapture(
        JNIEnv*, jclass, jlong handle) {
    if (auto host = resolve(handle)) {
        host->requestCapture();
    }
}

JNIEXPORT void JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nFocus(
        JNIEnv*, jclass, jlong handle, jboolean focused) {
    if (auto host = resolve(handle)) {
        host->focus(focused == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nMouse(
        JNIEnv*, jclass, jlong handle, jint kind, jint virtualKeys, jint mouseData, jint x, jint y) {
    if (auto host = resolve(handle)) {
        host->mouse(kind, virtualKeys, mouseData, x, y);
    }
}

JNIEXPORT void JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nEval(
        JNIEnv* env, jclass, jlong handle, jstring script) {
    if (auto host = resolve(handle)) {
        host->eval(utf8ToWide(env, script));
    }
}

JNIEXPORT jobject JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nMapChannel(
        JNIEnv* env, jclass, jlong handle) {
    auto host = resolve(handle);
    if (host == nullptr) {
        return nullptr;
    }
    const HANDLE section = host->channelSection();
    const size_t bytes = host->channelBytes();
    if (section == nullptr || bytes == 0) {
        return nullptr;
    }
    void* view = MapViewOfFile(section, FILE_MAP_ALL_ACCESS, 0, 0, 0);
    if (view == nullptr) {
        return nullptr;
    }
    // The host keeps the mapping so it can be dropped at teardown, after the host thread is
    // gone: the JVM never learns about the section, only about this view of it.
    host->addChannelView(view);
    return env->NewDirectByteBuffer(view, static_cast<jlong>(bytes));
}

JNIEXPORT void JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nEnableBridge(
        JNIEnv*, jclass, jlong handle, jboolean enabled) {
    if (auto host = resolve(handle)) host->enableBridge(enabled == JNI_TRUE);
}

JNIEXPORT jobjectArray JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nDrainMessages(
        JNIEnv* env, jclass, jlong handle) {
    auto host = resolve(handle);
    const auto messages = host ? host->drainMessages() : std::vector<std::wstring>();
    jclass type = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(messages.size()), type, nullptr);
    for (size_t i = 0; i < messages.size(); ++i) {
        // Windows wchar_t and Java jchar are UTF-16, including surrogate pairs.
        jstring value = env->NewString(reinterpret_cast<const jchar*>(messages[i].data()),
                                      static_cast<jsize>(messages[i].size()));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(type);
    return result;
}

JNIEXPORT jboolean JNICALL Java_io_github_kltyton_kltytonui_webview_WebViewNative_nPostMessage(
        JNIEnv* env, jclass, jlong handle, jlong generation, jstring json) {
    auto host = resolve(handle);
    if (host == nullptr || json == nullptr) return JNI_FALSE;
    const jchar* characters = env->GetStringChars(json, nullptr);
    if (characters == nullptr) return JNI_FALSE;
    std::wstring message(reinterpret_cast<const wchar_t*>(characters),
                         static_cast<size_t>(env->GetStringLength(json)));
    env->ReleaseStringChars(json, characters);
    return host->postMessage(static_cast<uint64_t>(generation), message) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
