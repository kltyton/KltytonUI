/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.builtins.ArrayBufferBuiltins;
import dev.mooner.neonjs.builtins.JSArrayBuffer;
import dev.mooner.neonjs.builtins.JSDataView;
import dev.mooner.neonjs.builtins.JSTypedArray;
import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.ErrorKind;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static dev.mooner.neonjs.runtime.Values.arg;

/** {@code WebSocket} (WebSockets Standard) over a transport. */
final class WebSockets {
    static final int CONNECTING = 0;
    static final int OPEN = 1;
    static final int CLOSING = 2;
    static final int CLOSED = 3;

    private WebSockets() {
    }

    static final class JSWebSocket extends Events.JSEventTarget implements Agent.ExternalSource {
        String url = "";
        String origin = "";
        String protocol = "";
        String extensions = "";
        int readyState = CONNECTING;
        String binaryType = "blob";
        volatile WebNetwork.WebSocketConnection connection;
        volatile boolean cancelled;
        long bufferedAfterClose;
        boolean ended;

        JSWebSocket(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "WebSocket";
        }

        @Override
        public void cancel() {
            cancelled = true;
            WebNetwork.WebSocketConnection current = connection;
            if (current != null) current.cancel();
        }
    }

    private static JSException syntaxError(Realm realm, String message) {
        return WebGlobals.domException(realm, message, "SyntaxError");
    }

    static void install(Realm realm, WebNetwork.WebSocketTransport transport) {
        WebInterface iface = WebInterface.define(realm, "WebSocket", 1, "EventTarget",
                (arguments, prototype) -> {
                    Idl.required(arguments, 1, "WebSocket constructor");
                    String urlString = Idl.usvString(arguments[0]);
                    Object protocolArgument = arg(arguments, 1);
                    List<String> protocols;
                    if (protocolArgument == Undefined.INSTANCE) {
                        protocols = List.of();
                    } else if (protocolArgument instanceof JSObject object
                            && Ops.getMethod(object, JSSymbol.iterator) != Undefined.INSTANCE) {
                        protocols = Idl.sequence(realm, object, "WebSocket protocols", Idl::domString);
                    } else {
                        protocols = List.of(Idl.domString(protocolArgument));
                    }
                    Url.Record url = Url.parse(urlString, null);
                    if (url == null) {
                        throw syntaxError(realm, "Failed to construct 'WebSocket': the URL '"
                                + urlString + "' is invalid");
                    }
                    switch (url.scheme) {
                        case "http":
                            url.scheme = "ws";
                            break;
                        case "https":
                            url.scheme = "wss";
                            break;
                        case "ws":
                        case "wss":
                            break;
                        default:
                            throw syntaxError(realm, "Failed to construct 'WebSocket': the URL's scheme must be "
                                    + "'ws' or 'wss', not '" + url.scheme + "'");
                    }
                    if (url.fragment != null) {
                        throw syntaxError(realm,
                                "Failed to construct 'WebSocket': the URL contains a fragment identifier");
                    }
                    Set<String> seen = new HashSet<>();
                    for (String protocol : protocols) {
                        if (!Headers.isToken(protocol)) {
                            throw syntaxError(realm, "Failed to construct 'WebSocket': '"
                                    + protocol + "' is not a valid subprotocol");
                        }
                        if (!seen.add(protocol)) {
                            throw syntaxError(realm, "Failed to construct 'WebSocket': the subprotocol '"
                                    + protocol + "' is given twice");
                        }
                    }
                    JSWebSocket socket = new JSWebSocket(prototype);
                    socket.url = url.serialize();
                    socket.origin = url.origin();
                    connect(realm, socket, transport, protocols);
                    return socket;
                });
        iface.constant("CONNECTING", CONNECTING);
        iface.constant("OPEN", OPEN);
        iface.constant("CLOSING", CLOSING);
        iface.constant("CLOSED", CLOSED);
        iface.attribute("url", (function, receiver, arguments, newTarget) ->
                Idl.self(JSWebSocket.class, receiver, "WebSocket", "url").url);
        iface.attribute("readyState", (function, receiver, arguments, newTarget) ->
                (double) Idl.self(JSWebSocket.class, receiver, "WebSocket", "readyState").readyState);
        iface.attribute("bufferedAmount", (function, receiver, arguments, newTarget) -> {
            JSWebSocket socket = Idl.self(JSWebSocket.class, receiver, "WebSocket", "bufferedAmount");
            WebNetwork.WebSocketConnection current = socket.connection;
            long amount = socket.readyState == OPEN && current != null ? current.bufferedAmount() : 0L;
            return (double) (amount + socket.bufferedAfterClose);
        });
        Events.eventHandler(iface, "onopen", "open");
        Events.eventHandler(iface, "onerror", "error");
        Events.eventHandler(iface, "onclose", "close");
        iface.attribute("extensions", (function, receiver, arguments, newTarget) ->
                Idl.self(JSWebSocket.class, receiver, "WebSocket", "extensions").extensions);
        iface.attribute("protocol", (function, receiver, arguments, newTarget) ->
                Idl.self(JSWebSocket.class, receiver, "WebSocket", "protocol").protocol);
        iface.operation("close", 0, (function, receiver, arguments, newTarget) -> {
            JSWebSocket socket = Idl.self(JSWebSocket.class, receiver, "WebSocket", "close");
            int code = arg(arguments, 0) == Undefined.INSTANCE ? -1
                    : (int) Idl.integer(arg(arguments, 0), 16, false, "WebSocket.close code",
                    false, true);
            if (code != -1 && code != 1000 && (code < 3000 || code > 4999)) {
                throw WebGlobals.domException(function.realm,
                        "WebSocket.close: the code must be 1000 or between 3000 and 4999, not " + code,
                        "InvalidAccessError");
            }
            String reason = arg(arguments, 1) == Undefined.INSTANCE ? ""
                    : Idl.usvString(arg(arguments, 1));
            if (WebGlobals.utf8(reason).length > 123) {
                throw syntaxError(function.realm, "WebSocket.close: the reason is longer than 123 bytes");
            }
            switch (socket.readyState) {
                case CLOSING:
                case CLOSED:
                    break;
                case CONNECTING:
                    socket.readyState = CLOSING;
                    if (socket.connection != null) socket.connection.cancel();
                    failLocally(function.realm, socket,
                            "WebSocket was closed before the connection was established");
                    break;
                default:
                    socket.readyState = CLOSING;
                    if (socket.connection != null) socket.connection.close(code, reason);
                    break;
            }
            return Undefined.INSTANCE;
        });
        Events.eventHandler(iface, "onmessage", "message");
        iface.attribute("binaryType", (function, receiver, arguments, newTarget) ->
                        Idl.self(JSWebSocket.class, receiver, "WebSocket", "binaryType").binaryType,
                (function, receiver, arguments, newTarget) -> {
                    JSWebSocket socket = Idl.self(JSWebSocket.class, receiver, "WebSocket", "binaryType");
                    String value = Idl.domString(arg(arguments, 0));
                    if (value.equals("blob") || value.equals("arraybuffer")) socket.binaryType = value;
                    return Undefined.INSTANCE;
                });
        iface.operation("send", 1, (function, receiver, arguments, newTarget) -> {
            JSWebSocket socket = Idl.self(JSWebSocket.class, receiver, "WebSocket", "send");
            Idl.required(arguments, 1, "WebSocket.send");
            if (socket.readyState == CONNECTING) {
                throw WebGlobals.domException(function.realm,
                        "WebSocket.send: the connection is not open yet", "InvalidStateError");
            }
            Object data = arguments[0];
            if (data instanceof JSArrayBuffer || data instanceof JSTypedArray || data instanceof JSDataView) {
                sendBinary(socket, Idl.bytes(data, "WebSocket.send data", true));
            } else if (data instanceof Blob.JSBlob blob) {
                sendBinary(socket, blob.bytes());
            } else {
                String text = Idl.usvString(data);
                if (socket.readyState == OPEN && socket.connection != null) {
                    socket.connection.sendText(text);
                } else {
                    socket.bufferedAfterClose += WebGlobals.utf8(text).length;
                }
            }
            return Undefined.INSTANCE;
        });
    }

    private static void sendBinary(JSWebSocket socket, byte[] bytes) {
        if (socket.readyState == OPEN && socket.connection != null) socket.connection.sendBinary(bytes);
        else socket.bufferedAfterClose += bytes.length;
    }

    private static void connect(Realm realm, JSWebSocket socket, WebNetwork.WebSocketTransport transport,
                                List<String> protocols) {
        Agent agent = realm.agent;
        if (!agent.addExternalSource(socket)) {
            socket.readyState = CLOSED;
            socket.ended = true;
            return;
        }
        WebNetwork.WebSocketEvents events = new WebNetwork.WebSocketEvents() {
            @Override
            public void onOpen(String protocol, String extensions) {
                agent.postExternalJob(() -> {
                    if (socket.ended || socket.readyState != CONNECTING) return;
                    socket.readyState = OPEN;
                    socket.protocol = protocol;
                    socket.extensions = extensions;
                    Events.dispatch(realm, socket, Events.engineEvent(realm, "open"));
                });
            }

            @Override
            public void onText(String text) {
                agent.postExternalJob(() -> {
                    if (!socket.ended && socket.readyState == OPEN) message(realm, socket, text);
                });
            }

            @Override
            public void onBinary(byte[] bytes) {
                agent.postExternalJob(() -> {
                    if (socket.ended || socket.readyState != OPEN) return;
                    agent.reserveAllocation(bytes.length);
                    Object data;
                    if (socket.binaryType.equals("arraybuffer")) {
                        JSArrayBuffer buffer = ArrayBufferBuiltins.allocate(realm, null, bytes.length, -1);
                        System.arraycopy(bytes, 0, buffer.data, 0, bytes.length);
                        data = buffer;
                    } else {
                        data = Blob.newBlob(realm, bytes, "");
                    }
                    message(realm, socket, data);
                });
            }

            @Override
            public void onError(String message) {
                agent.postExternalJob(() -> {
                    if (!socket.ended) fireError(realm, socket, message);
                });
            }

            @Override
            public void onClose(int code, String reason, boolean wasClean) {
                agent.postExternalJob(() -> {
                    if (!socket.ended) closed(realm, socket, code, reason, wasClean);
                });
            }
        };
        try {
            socket.connection = transport.connect(socket.url, protocols, events);
            if (socket.cancelled && socket.connection != null) socket.connection.cancel();
        } catch (RuntimeException error) {
            socket.readyState = CLOSING;
            failLocally(realm, socket, error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private static void message(Realm realm, JSWebSocket socket, Object data) {
        Events.JSMessageEvent event = Events.engineEvent(realm,
                new Events.JSMessageEvent(realm.intrinsic("%MessageEvent.prototype%")), "message");
        event.data = data;
        event.origin = socket.origin;
        Events.dispatch(realm, socket, event);
    }

    private static void fireError(Realm realm, JSWebSocket socket, String message) {
        Events.JSErrorEvent event = Events.engineEvent(realm,
                new Events.JSErrorEvent(realm.intrinsic("%ErrorEvent.prototype%")), "error");
        event.message = message;
        event.error = realm.newError(ErrorKind.TYPE, message);
        Events.dispatch(realm, socket, event);
    }

    private static void closed(Realm realm, JSWebSocket socket, int code, String reason, boolean wasClean) {
        socket.ended = true;
        socket.readyState = CLOSED;
        realm.agent.removeExternalSource(socket);
        Events.JSCloseEvent event = Events.engineEvent(realm,
                new Events.JSCloseEvent(realm.intrinsic("%CloseEvent.prototype%")), "close");
        event.code = code;
        event.reason = reason;
        event.wasClean = wasClean;
        Events.dispatch(realm, socket, event);
    }

    private static void failLocally(Realm realm, JSWebSocket socket, String message) {
        socket.ended = true;
        realm.agent.postExternalJob(() -> {
            fireError(realm, socket, message);
            socket.readyState = CLOSED;
            realm.agent.removeExternalSource(socket);
            Events.JSCloseEvent event = Events.engineEvent(realm,
                    new Events.JSCloseEvent(realm.intrinsic("%CloseEvent.prototype%")), "close");
            event.code = 1006;
            Events.dispatch(realm, socket, event);
        });
    }
}
