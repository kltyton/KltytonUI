/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.builtins.ArrayBufferBuiltins;
import dev.mooner.neonjs.builtins.ElementType;
import dev.mooner.neonjs.builtins.JSArrayBuffer;
import dev.mooner.neonjs.builtins.JSDataView;
import dev.mooner.neonjs.builtins.JSONBuiltins;
import dev.mooner.neonjs.builtins.JSTypedArray;
import dev.mooner.neonjs.builtins.TypedArrayBuiltins;
import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.ErrorKind;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.vm.JSPromise;
import dev.mooner.neonjs.vm.Promises;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static dev.mooner.neonjs.ext.Idl.typeErr;
import static dev.mooner.neonjs.runtime.Values.arg;

/** {@code Request}, {@code Response} and {@code fetch} (Fetch Standard), outside a browser. */
final class Fetch {
    private Fetch() {
    }

    abstract static class JSBody extends JSObject {
        byte[] body;
        boolean bodyUsed;
        Headers.JSHeaders headers;

        JSBody(JSObject prototype) {
            super(prototype);
        }
    }

    static final class JSRequest extends JSBody {
        String method = "GET";
        Url.Record url = new Url.Record();
        String redirect = "follow";
        String mode = "cors";
        String credentials = "same-origin";
        String cache = "default";
        String referrer = "about:client";
        String referrerPolicy = "";
        String integrity = "";
        boolean keepalive;
        Abort.JSAbortSignal signal;

        JSRequest(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "Request";
        }
    }

    static final class JSResponse extends JSBody {
        String type = "default";
        String url;
        boolean redirected;
        int status = 200;
        String statusText = "";

        JSResponse(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "Response";
        }
    }

    private static final Set<Integer> NULL_BODY_STATUSES = Set.of(101, 103, 204, 205, 304);
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> FORBIDDEN_METHODS = Set.of("CONNECT", "TRACE", "TRACK");
    private static final Set<String> NORMALIZED_METHODS = Set.of(
            "DELETE", "GET", "HEAD", "OPTIONS", "POST", "PUT");
    private static final Set<String> REQUEST_BODY_HEADERS = Set.of(
            "content-encoding", "content-language", "content-location", "content-type");

    private static String enumValue(Object value, Set<String> allowed, String what) {
        if (value == Undefined.INSTANCE) return null;
        String text = Idl.domString(value);
        if (!allowed.contains(text)) throw typeErr(what + ": '" + text + "' is not a valid value");
        return text;
    }

    record BodyData(byte[] bytes, String type) {
    }

    /** "extract a body" of a BodyInit. */
    static BodyData extract(Realm realm, Object value) {
        if (value instanceof Blob.JSBlob blob) {
            return new BodyData(blob.bytes(), blob.type.isEmpty() ? null : blob.type);
        }
        if (value instanceof JSArrayBuffer || value instanceof JSTypedArray
                || value instanceof JSDataView) {
            return new BodyData(Idl.bytes(value, "body", false), null);
        }
        if (value instanceof FormData.JSFormData formData) {
            FormData.Multipart multipart = FormData.multipart(realm, formData);
            return new BodyData(multipart.body(), multipart.contentType());
        }
        if (value instanceof Url.JSURLSearchParams params) {
            return new BodyData(WebGlobals.utf8(Url.serializeForm(params.list)),
                    "application/x-www-form-urlencoded;charset=UTF-8");
        }
        return new BodyData(WebGlobals.utf8(Idl.usvString(value)), "text/plain;charset=UTF-8");
    }

    private static Headers.JSHeaders newHeaders(Realm realm) {
        return new Headers.JSHeaders(realm.intrinsic("%Headers.prototype%"));
    }

    // ------------------------------------------------------------------ Request

    static JSRequest newRequest(Realm realm, JSObject prototype, Object input, Object initArgument) {
        JSObject init = Idl.dictionary(initArgument, "RequestInit");
        JSRequest request = new JSRequest(prototype);
        JSRequest from = input instanceof JSRequest value ? value : null;
        if (from == null) {
            String text = Idl.usvString(input);
            Url.Record url = Url.parse(text, null);
            if (url == null) throw typeErr("Failed to parse URL from " + text);
            if (url.includesCredentials()) {
                throw typeErr("Request cannot be constructed from a URL that includes credentials: " + text);
            }
            request.url = url;
        } else {
            request.url = from.url.copy();
            request.method = from.method;
            request.redirect = from.redirect;
            request.mode = from.mode;
            request.credentials = from.credentials;
            request.cache = from.cache;
            request.referrer = from.referrer;
            request.referrerPolicy = from.referrerPolicy;
            request.integrity = from.integrity;
            request.keepalive = from.keepalive;
        }
        Object bodyInit = Idl.member(init, "body");
        String cache = enumValue(Idl.member(init, "cache"),
                Set.of("default", "no-store", "reload", "no-cache", "force-cache", "only-if-cached"),
                "RequestInit cache");
        if (cache != null) request.cache = cache;
        String credentials = enumValue(Idl.member(init, "credentials"),
                Set.of("omit", "same-origin", "include"), "RequestInit credentials");
        if (credentials != null) request.credentials = credentials;
        enumValue(Idl.member(init, "duplex"), Set.of("half"), "RequestInit duplex");
        Object headersInit = Idl.member(init, "headers");
        Object integrity = Idl.member(init, "integrity");
        if (integrity != Undefined.INSTANCE) request.integrity = Idl.domString(integrity);
        Object keepalive = Idl.member(init, "keepalive");
        if (keepalive != Undefined.INSTANCE) request.keepalive = Ops.toBoolean(keepalive);
        Object methodValue = Idl.member(init, "method");
        String methodInit = methodValue == Undefined.INSTANCE ? null
                : Idl.byteString(methodValue, "RequestInit method");
        String mode = enumValue(Idl.member(init, "mode"),
                Set.of("navigate", "same-origin", "no-cors", "cors"), "RequestInit mode");
        enumValue(Idl.member(init, "priority"), Set.of("high", "low", "auto"), "RequestInit priority");
        String redirect = enumValue(Idl.member(init, "redirect"),
                Set.of("follow", "error", "manual"), "RequestInit redirect");
        if (redirect != null) request.redirect = redirect;
        Object referrerValue = Idl.member(init, "referrer");
        String referrer = referrerValue == Undefined.INSTANCE ? null : Idl.usvString(referrerValue);
        String referrerPolicy = enumValue(Idl.member(init, "referrerPolicy"),
                Set.of("", "no-referrer", "no-referrer-when-downgrade", "same-origin", "origin",
                        "strict-origin", "origin-when-cross-origin", "strict-origin-when-cross-origin",
                        "unsafe-url"), "RequestInit referrerPolicy");
        if (referrerPolicy != null) request.referrerPolicy = referrerPolicy;
        Object signalInit = Idl.member(init, "signal");
        Abort.JSAbortSignal signal = signalInit == Undefined.INSTANCE || signalInit == Null.INSTANCE
                ? null : Abort.signalOf(signalInit);
        Object window = Idl.member(init, "window");
        if (window != Undefined.INSTANCE && window != Null.INSTANCE) {
            throw typeErr("RequestInit window can only be null");
        }
        if ("navigate".equals(mode)) throw typeErr("Request mode 'navigate' is not allowed");
        if (mode != null) request.mode = mode;
        if (request.cache.equals("only-if-cached") && !request.mode.equals("same-origin")) {
            throw typeErr("Request cache 'only-if-cached' needs mode 'same-origin'");
        }
        if (referrer != null) {
            if (referrer.isEmpty()) request.referrer = "";
            else {
                Url.Record url = Url.parse(referrer, null);
                if (url == null) throw typeErr("Failed to parse referrer URL from " + referrer);
                request.referrer = url.scheme.equals("about") && url.pathname().equals("client")
                        ? "about:client" : url.serialize();
            }
        }
        if (methodInit != null) {
            if (!Headers.isToken(methodInit)) {
                throw typeErr("'" + methodInit + "' is not a valid HTTP method");
            }
            String upper = methodInit.toUpperCase(Locale.ROOT);
            if (FORBIDDEN_METHODS.contains(upper)) {
                throw typeErr("'" + methodInit + "' HTTP method is unsupported");
            }
            request.method = NORMALIZED_METHODS.contains(upper) ? upper : methodInit;
        }

        Abort.JSAbortSignal newSignal = Abort.newSignal(realm);
        Abort.JSAbortSignal sourceSignal = signal != null ? signal : from == null ? null : from.signal;
        if (sourceSignal != null) {
            if (sourceSignal.aborted) {
                newSignal.aborted = true;
                newSignal.reason = sourceSignal.reason;
            } else {
                sourceSignal.addAlgorithm(() -> newSignal.abort(realm, sourceSignal.reason));
            }
        }
        request.signal = newSignal;

        Headers.JSHeaders headers = newHeaders(realm);
        headers.requestGuard = true;
        request.headers = headers;
        if (headersInit != Undefined.INSTANCE) {
            Headers.fill(realm, headers, headersInit, "Request headers");
        } else if (from != null && from.headers != null) {
            for (Map.Entry<String, String> entry : from.headers.list) headers.append(entry.getKey(), entry.getValue());
        }
        if (request.mode.equals("no-cors") && !request.method.equals("GET")
                && !request.method.equals("HEAD") && !request.method.equals("POST")) {
            throw typeErr("'" + request.method + "' is unsupported in no-cors mode");
        }

        byte[] inputBody = from == null ? null : from.body;
        boolean hasInitBody = bodyInit != Undefined.INSTANCE && bodyInit != Null.INSTANCE;
        if ((hasInitBody || inputBody != null) && (request.method.equals("GET") || request.method.equals("HEAD"))) {
            throw typeErr("Request with GET/HEAD method cannot have body.");
        }
        if (hasInitBody) {
            BodyData body = extract(realm, bodyInit);
            request.body = body.bytes();
            if (body.type() != null && !headers.contains("Content-Type")) {
                headers.append("Content-Type", body.type());
            }
        } else if (inputBody != null && from != null) {
            if (from.bodyUsed) {
                throw typeErr("Cannot construct a Request with a Request object that has already been used.");
            }
            request.body = inputBody;
            from.bodyUsed = true;
        }
        return request;
    }

    private static JSRequest cloneRequest(Realm realm, JSRequest request) {
        if (request.bodyUsed) throw typeErr("Request.clone: the body has already been used");
        JSRequest clone = new JSRequest(realm.intrinsic("%Request.prototype%"));
        clone.method = request.method;
        clone.url = request.url.copy();
        clone.redirect = request.redirect;
        clone.mode = request.mode;
        clone.credentials = request.credentials;
        clone.cache = request.cache;
        clone.referrer = request.referrer;
        clone.referrerPolicy = request.referrerPolicy;
        clone.integrity = request.integrity;
        clone.keepalive = request.keepalive;
        clone.body = request.body;
        Headers.JSHeaders headers = newHeaders(realm);
        headers.requestGuard = true;
        headers.list.addAll(request.headers.list);
        clone.headers = headers;
        Abort.JSAbortSignal signal = Abort.newSignal(realm);
        if (request.signal != null) {
            Abort.JSAbortSignal source = request.signal;
            if (source.aborted) {
                signal.aborted = true;
                signal.reason = source.reason;
            } else {
                source.addAlgorithm(() -> signal.abort(realm, source.reason));
            }
        }
        clone.signal = signal;
        return clone;
    }

    // ------------------------------------------------------------------ Response

    static JSResponse newResponse(Realm realm, JSObject prototype) {
        JSResponse response = new JSResponse(prototype != null
                ? prototype : realm.intrinsic("%Response.prototype%"));
        response.headers = newHeaders(realm);
        return response;
    }

    private static boolean isReasonPhrase(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c == '\t' || c >= 0x20 && c <= 0x7E || c >= 0x80 && c <= 0xFF)) return false;
        }
        return true;
    }

    private static void initialize(Realm realm, JSResponse response, JSObject init, BodyData body) {
        Object headersInit = Idl.member(init, "headers");
        Object statusValue = Idl.member(init, "status");
        int status = statusValue == Undefined.INSTANCE ? 200
                : (int) Idl.integer(statusValue, 16, false, "ResponseInit status");
        Object statusTextValue = Idl.member(init, "statusText");
        String statusText = statusTextValue == Undefined.INSTANCE ? ""
                : Idl.byteString(statusTextValue, "ResponseInit statusText");
        if (status < 200 || status > 599) {
            throw JSException.rangeError("The status provided (" + status
                    + ") is outside the range [200, 599].");
        }
        if (!isReasonPhrase(statusText)) throw typeErr("Invalid statusText");
        response.status = status;
        response.statusText = statusText;
        if (headersInit != Undefined.INSTANCE) {
            Headers.fill(realm, response.headers, headersInit, "Response headers");
        }
        if (body != null) {
            if (NULL_BODY_STATUSES.contains(status)) {
                throw typeErr("Response constructor: a " + status + " response cannot have a body");
            }
            response.body = body.bytes();
            if (body.type() != null && !response.headers.contains("Content-Type")) {
                response.headers.append("Content-Type", body.type());
            }
        }
    }

    private static JSResponse cloneResponse(Realm realm, JSResponse response) {
        if (response.bodyUsed) throw typeErr("Response.clone: the body has already been used");
        JSResponse clone = newResponse(realm, null);
        clone.type = response.type;
        clone.url = response.url;
        clone.redirected = response.redirected;
        clone.status = response.status;
        clone.statusText = response.statusText;
        clone.body = response.body;
        clone.headers.list.addAll(response.headers.list);
        clone.headers.immutable = response.headers.immutable;
        return clone;
    }

    // ------------------------------------------------------------------ Body mixin

    private static byte[] consume(JSBody body, String what) {
        byte[] bytes = body.body;
        if (bytes == null) return new byte[0];
        if (body.bodyUsed) throw typeErr(what + ": the body has already been used");
        body.bodyUsed = true;
        return bytes;
    }

    private static String mimeEssence(JSBody body) {
        String contentType = body.headers == null ? null : body.headers.get("Content-Type");
        if (contentType == null) return null;
        int semicolon = contentType.indexOf(';');
        String essence = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType)
                .strip().toLowerCase(Locale.ROOT);
        return essence.isEmpty() ? null : essence;
    }

    private static void installBody(WebInterface iface, String name, Class<? extends JSBody> cls) {
        iface.attribute("bodyUsed", (function, receiver, arguments, newTarget) -> {
            if (!cls.isInstance(receiver)) {
                throw typeErr(name + ".bodyUsed called on " + Ops.describe(receiver)
                        + ", which is not a " + name);
            }
            JSBody body = (JSBody) receiver;
            return body.body != null && body.bodyUsed;
        });
        iface.promiseOperation("arrayBuffer", 0, (function, receiver, arguments, newTarget) -> {
            if (!cls.isInstance(receiver)) {
                throw typeErr(name + ".arrayBuffer called on " + Ops.describe(receiver)
                        + ", which is not a " + name);
            }
            byte[] bytes = consume((JSBody) receiver, name + ".arrayBuffer");
            JSArrayBuffer buffer = ArrayBufferBuiltins.allocate(function.realm, null, bytes.length, -1);
            System.arraycopy(bytes, 0, buffer.data, 0, bytes.length);
            return buffer;
        });
        iface.promiseOperation("blob", 0, (function, receiver, arguments, newTarget) -> {
            if (!cls.isInstance(receiver)) {
                throw typeErr(name + ".blob called on " + Ops.describe(receiver)
                        + ", which is not a " + name);
            }
            JSBody body = (JSBody) receiver;
            String contentType = body.headers == null ? null : body.headers.get("Content-Type");
            String type = "";
            if (contentType != null) {
                boolean printable = true;
                for (int i = 0; i < contentType.length(); i++) {
                    char c = contentType.charAt(i);
                    if (c < 0x20 || c > 0x7E) {
                        printable = false;
                        break;
                    }
                }
                type = printable ? contentType.toLowerCase(Locale.ROOT) : "";
            }
            return Blob.newBlob(function.realm, consume(body, name + ".blob"), type);
        });
        iface.promiseOperation("bytes", 0, (function, receiver, arguments, newTarget) -> {
            if (!cls.isInstance(receiver)) {
                throw typeErr(name + ".bytes called on " + Ops.describe(receiver)
                        + ", which is not a " + name);
            }
            byte[] bytes = consume((JSBody) receiver, name + ".bytes");
            JSTypedArray array = TypedArrayBuiltins.allocate(function.realm, ElementType.UINT8,
                    TypedArrayBuiltins.protoOf(function.realm, ElementType.UINT8), bytes.length);
            System.arraycopy(bytes, 0, array.buffer.data, 0, bytes.length);
            return array;
        });
        iface.promiseOperation("formData", 0, (function, receiver, arguments, newTarget) -> {
            if (!cls.isInstance(receiver)) {
                throw typeErr(name + ".formData called on " + Ops.describe(receiver)
                        + ", which is not a " + name);
            }
            JSBody body = (JSBody) receiver;
            String essence = mimeEssence(body);
            String contentType = body.headers == null ? "" : body.headers.get("Content-Type");
            byte[] bytes = consume(body, name + ".formData");
            switch (essence == null ? "" : essence) {
                case "multipart/form-data": {
                    String boundary = FormData.headerParam(contentType, "boundary");
                    if (boundary == null) {
                        throw typeErr(name + ".formData: the multipart/form-data body has no boundary");
                    }
                    return FormData.parseMultipart(function.realm, bytes, boundary);
                }
                case "application/x-www-form-urlencoded": {
                    FormData.JSFormData formData = FormData.newFormData(function.realm);
                    function.realm.agent.checkStringLength(bytes.length);
                    for (Map.Entry<String, String> entry : Url.parseForm(WhatwgUtf8.decode(bytes))) {
                        formData.entries.add(new AbstractMap.SimpleImmutableEntry<>(
                                entry.getKey(), entry.getValue()));
                    }
                    return formData;
                }
                default:
                    throw typeErr(name + ".formData: the body is neither multipart/form-data "
                            + "nor application/x-www-form-urlencoded");
            }
        });
        iface.promiseOperation("json", 0, (function, receiver, arguments, newTarget) -> {
            if (!cls.isInstance(receiver)) {
                throw typeErr(name + ".json called on " + Ops.describe(receiver)
                        + ", which is not a " + name);
            }
            String text = WebGlobals.utf8Decode(function.realm,
                    consume((JSBody) receiver, name + ".json"));
            return new JSONBuiltins.JsonParser(function.realm, text).parseRoot().value;
        });
        iface.promiseOperation("text", 0, (function, receiver, arguments, newTarget) -> {
            if (!cls.isInstance(receiver)) {
                throw typeErr(name + ".text called on " + Ops.describe(receiver)
                        + ", which is not a " + name);
            }
            return WebGlobals.utf8Decode(function.realm,
                    consume((JSBody) receiver, name + ".text"));
        });
    }

    static void install(Realm realm) {
        WebInterface requestInterface = WebInterface.define(realm, "Request", 1, null, null,
                (arguments, prototype) -> {
                    Idl.required(arguments, 1, "Request constructor");
                    return newRequest(realm, prototype, arguments[0], arg(arguments, 1));
                });
        requestInterface.attribute("method", (function, receiver, arguments, newTarget) ->
                Idl.self(JSRequest.class, receiver, "Request", "method").method);
        requestInterface.attribute("url", (function, receiver, arguments, newTarget) ->
                Idl.self(JSRequest.class, receiver, "Request", "url").url.serialize());
        requestInterface.attribute("headers", (function, receiver, arguments, newTarget) ->
                Idl.self(JSRequest.class, receiver, "Request", "headers").headers);
        requestInterface.attribute("destination", (function, receiver, arguments, newTarget) -> {
            Idl.self(JSRequest.class, receiver, "Request", "destination");
            return "";
        });
        requestInterface.attribute("referrer", (function, receiver, arguments, newTarget) ->
                Idl.self(JSRequest.class, receiver, "Request", "referrer").referrer);
        requestInterface.attribute("referrerPolicy", (function, receiver, arguments, newTarget) ->
                Idl.self(JSRequest.class, receiver, "Request", "referrerPolicy").referrerPolicy);
        requestInterface.attribute("mode", (function, receiver, arguments, newTarget) ->
                Idl.self(JSRequest.class, receiver, "Request", "mode").mode);
        requestInterface.attribute("credentials", (function, receiver, arguments, newTarget) ->
                Idl.self(JSRequest.class, receiver, "Request", "credentials").credentials);
        requestInterface.attribute("cache", (function, receiver, arguments, newTarget) ->
                Idl.self(JSRequest.class, receiver, "Request", "cache").cache);
        requestInterface.attribute("redirect", (function, receiver, arguments, newTarget) ->
                Idl.self(JSRequest.class, receiver, "Request", "redirect").redirect);
        requestInterface.attribute("integrity", (function, receiver, arguments, newTarget) ->
                Idl.self(JSRequest.class, receiver, "Request", "integrity").integrity);
        requestInterface.attribute("keepalive", (function, receiver, arguments, newTarget) ->
                Idl.self(JSRequest.class, receiver, "Request", "keepalive").keepalive);
        requestInterface.attribute("isReloadNavigation", (function, receiver, arguments, newTarget) -> {
            Idl.self(JSRequest.class, receiver, "Request", "isReloadNavigation");
            return false;
        });
        requestInterface.attribute("isHistoryNavigation", (function, receiver, arguments, newTarget) -> {
            Idl.self(JSRequest.class, receiver, "Request", "isHistoryNavigation");
            return false;
        });
        requestInterface.attribute("signal", (function, receiver, arguments, newTarget) ->
                Idl.self(JSRequest.class, receiver, "Request", "signal").signal);
        requestInterface.attribute("duplex", (function, receiver, arguments, newTarget) -> {
            Idl.self(JSRequest.class, receiver, "Request", "duplex");
            return "half";
        });
        requestInterface.operation("clone", 0, (function, receiver, arguments, newTarget) ->
                cloneRequest(function.realm, Idl.self(JSRequest.class, receiver, "Request", "clone")));
        installBody(requestInterface, "Request", JSRequest.class);

        WebInterface responseInterface = WebInterface.define(realm, "Response", 0, null, null,
                (arguments, prototype) -> {
                    Object bodyValue = arg(arguments, 0);
                    BodyData body = bodyValue == Undefined.INSTANCE || bodyValue == Null.INSTANCE
                            ? null : extract(realm, bodyValue);
                    JSObject init = Idl.dictionary(arg(arguments, 1), "ResponseInit");
                    JSResponse response = newResponse(realm, prototype);
                    initialize(realm, response, init, body);
                    return response;
                });
        responseInterface.staticOperation("error", 0, (function, receiver, arguments, newTarget) -> {
            JSResponse response = newResponse(function.realm, null);
            response.type = "error";
            response.status = 0;
            response.headers.immutable = true;
            return response;
        });
        responseInterface.staticOperation("redirect", 1, (function, receiver, arguments, newTarget) -> {
            Idl.required(arguments, 1, "Response.redirect");
            String text = Idl.usvString(arguments[0]);
            Url.Record url = Url.parse(text, null);
            if (url == null) throw typeErr("Failed to parse URL from " + text);
            Object statusValue = arg(arguments, 1);
            int status = statusValue == Undefined.INSTANCE ? 302
                    : (int) Idl.integer(statusValue, 16, false, "Response.redirect status");
            if (!REDIRECT_STATUSES.contains(status)) throw JSException.rangeError("Invalid status code " + status);
            JSResponse response = newResponse(function.realm, null);
            response.status = status;
            response.headers.append("Location", url.serialize());
            response.headers.immutable = true;
            return response;
        });
        responseInterface.staticOperation("json", 1, (function, receiver, arguments, newTarget) -> {
            Object text = JSONBuiltins.stringify(function.realm, arg(arguments, 0),
                    Undefined.INSTANCE, Undefined.INSTANCE);
            if (text == Undefined.INSTANCE) throw typeErr("Response.json: the data is not JSON-serializable");
            JSObject init = Idl.dictionary(arg(arguments, 1), "ResponseInit");
            JSResponse response = newResponse(function.realm, null);
            initialize(function.realm, response, init,
                    new BodyData(WebGlobals.utf8(Ops.toString(text)), "application/json"));
            return response;
        });
        responseInterface.attribute("type", (function, receiver, arguments, newTarget) ->
                Idl.self(JSResponse.class, receiver, "Response", "type").type);
        responseInterface.attribute("url", (function, receiver, arguments, newTarget) -> {
            String url = Idl.self(JSResponse.class, receiver, "Response", "url").url;
            return url == null ? "" : url;
        });
        responseInterface.attribute("redirected", (function, receiver, arguments, newTarget) ->
                Idl.self(JSResponse.class, receiver, "Response", "redirected").redirected);
        responseInterface.attribute("status", (function, receiver, arguments, newTarget) ->
                (double) Idl.self(JSResponse.class, receiver, "Response", "status").status);
        responseInterface.attribute("ok", (function, receiver, arguments, newTarget) -> {
            int status = Idl.self(JSResponse.class, receiver, "Response", "ok").status;
            return status >= 200 && status <= 299;
        });
        responseInterface.attribute("statusText", (function, receiver, arguments, newTarget) ->
                Idl.self(JSResponse.class, receiver, "Response", "statusText").statusText);
        responseInterface.attribute("headers", (function, receiver, arguments, newTarget) ->
                Idl.self(JSResponse.class, receiver, "Response", "headers").headers);
        responseInterface.operation("clone", 0, (function, receiver, arguments, newTarget) ->
                cloneResponse(function.realm, Idl.self(JSResponse.class, receiver, "Response", "clone")));
        installBody(responseInterface, "Response", JSResponse.class);
    }

    // ------------------------------------------------------------------ fetch

    static void installFetch(Realm realm, WebNetwork.FetchTransport transport) {
        WebInterface.globalOperation(realm, "fetch", 1, (function, receiver, arguments, newTarget) -> {
            JSPromise promise = Promises.newPromise(function.realm);
            JSRequest request;
            try {
                Idl.required(arguments, 1, "fetch");
                request = newRequest(function.realm,
                        function.realm.intrinsic("%Request.prototype%"), arguments[0], arg(arguments, 1));
            } catch (JSException error) {
                Promises.rejectPromise(function.realm, promise, error.value);
                return promise;
            }
            new FetchRun(function.realm, transport, request, promise).start();
            return promise;
        });
    }

    private static final class FetchRun implements Agent.ExternalSource {
        final Realm realm;
        final WebNetwork.FetchTransport transport;
        final JSRequest request;
        final JSPromise promise;
        volatile WebNetwork.Cancellable current;
        volatile boolean cancelled;
        boolean settled;
        String method;
        Url.Record url;
        ArrayList<Map.Entry<String, String>> headers;
        byte[] body;
        int redirects;

        FetchRun(Realm realm, WebNetwork.FetchTransport transport, JSRequest request, JSPromise promise) {
            this.realm = realm;
            this.transport = transport;
            this.request = request;
            this.promise = promise;
            this.method = request.method;
            this.url = request.url;
            this.headers = new ArrayList<>(request.headers.list);
            this.body = request.body;
        }

        @Override
        public void cancel() {
            cancelled = true;
            WebNetwork.Cancellable exchange = current;
            if (exchange != null) exchange.cancel();
        }

        void start() {
            Abort.JSAbortSignal signal = request.signal;
            if (signal.aborted) {
                Promises.rejectPromise(realm, promise, signal.reason);
                return;
            }
            if (!realm.agent.addExternalSource(this)) {
                fail("the context is closing");
                return;
            }
            signal.addAlgorithm(() -> abort(signal.reason));
            boolean hasAccept = false;
            for (Map.Entry<String, String> header : headers) {
                if (header.getKey().equalsIgnoreCase("Accept")) {
                    hasAccept = true;
                    break;
                }
            }
            if (!hasAccept) headers.add(new AbstractMap.SimpleImmutableEntry<>("Accept", "*/*"));
            send();
        }

        void send() {
            if (!url.scheme.equals("http") && !url.scheme.equals("https")) {
                fail("fetch supports http: and https: URLs only, not " + url.scheme + ":");
                return;
            }
            Agent agent = realm.agent;
            WebNetwork.Request networkRequest = new WebNetwork.Request(method,
                    url.serialize(true), List.copyOf(headers), body);
            WebNetwork.FetchCallback callback = new WebNetwork.FetchCallback() {
                @Override
                public void onResponse(WebNetwork.Response response) {
                    agent.postExternalJob(() -> {
                        if (!settled) received(response);
                    });
                }

                @Override
                public void onFailure(String message) {
                    agent.postExternalJob(() -> {
                        if (!settled) fail(message);
                    });
                }
            };
            try {
                current = transport.send(networkRequest, callback);
                if (cancelled && current != null) current.cancel();
            } catch (RuntimeException error) {
                fail(error.getMessage() == null ? error.toString() : error.getMessage());
            }
        }

        void received(WebNetwork.Response response) {
            current = null;
            String location = null;
            for (Map.Entry<String, String> header : response.headers()) {
                if (header.getKey().equalsIgnoreCase("Location")) {
                    location = header.getValue();
                    break;
                }
            }
            if (REDIRECT_STATUSES.contains(response.status()) && location != null
                    && !request.redirect.equals("manual")) {
                if (request.redirect.equals("error")) {
                    fail("unexpected redirect");
                    return;
                }
                redirect(response.status(), location);
                return;
            }
            settled = true;
            realm.agent.removeExternalSource(this);
            byte[] bytes = response.body();
            if (bytes != null) realm.agent.reserveAllocation(bytes.length);
            JSResponse result = newResponse(realm, null);
            result.type = "basic";
            result.url = url.serialize(true);
            result.redirected = redirects > 0;
            result.status = response.status();
            result.statusText = response.statusText();
            result.headers.list.addAll(response.headers());
            result.headers.immutable = true;
            result.body = method.equals("HEAD") || NULL_BODY_STATUSES.contains(response.status())
                    ? null : bytes == null ? new byte[0] : bytes;
            Promises.resolvePromise(realm, promise, result);
        }

        void redirect(int status, String location) {
            Url.Record next = Url.parse(location, url);
            if (next == null) {
                fail("invalid redirect location: " + location);
                return;
            }
            if (!next.scheme.equals("http") && !next.scheme.equals("https")) {
                fail("redirect to a " + next.scheme + ": URL");
                return;
            }
            if (redirects == 20) {
                fail("redirect count exceeded");
                return;
            }
            redirects++;
            if ((status == 301 || status == 302) && method.equals("POST")
                    || status == 303 && !method.equals("GET") && !method.equals("HEAD")) {
                method = "GET";
                body = null;
                headers.removeIf(header -> REQUEST_BODY_HEADERS.contains(
                        header.getKey().toLowerCase(Locale.ROOT)));
            }
            if (!next.origin().equals(url.origin())) {
                headers.removeIf(header -> header.getKey().equalsIgnoreCase("Authorization")
                        || header.getKey().equalsIgnoreCase("Cookie")
                        || header.getKey().equalsIgnoreCase("Proxy-Authorization"));
            }
            if (next.fragment == null) next.fragment = url.fragment;
            url = next;
            send();
        }

        void abort(Object reason) {
            if (settled) return;
            settled = true;
            realm.agent.removeExternalSource(this);
            if (current != null) current.cancel();
            Promises.rejectPromise(realm, promise, reason);
        }

        void fail(String message) {
            if (settled) return;
            settled = true;
            realm.agent.removeExternalSource(this);
            if (current != null) current.cancel();
            var error = realm.newError(ErrorKind.TYPE, "fetch failed");
            error.defineOwn("cause", realm.newError(ErrorKind.ERROR, message), Attr.WC);
            Promises.rejectPromise(realm, promise, error);
        }
    }
}
