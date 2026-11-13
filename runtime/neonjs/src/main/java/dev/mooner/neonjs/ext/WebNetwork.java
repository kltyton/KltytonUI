/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.runtime.Realm;
import java.util.List;
import java.util.Map;

/**
 * The network under {@code fetch} and {@code WebSocket}, for engine modules: a transport moves bytes, decides what
 * may be reached, and reports from any thread. The web globals do the standards' part on the context's thread.
 * Engine extensions install transports explicitly; a realm without them has neither network global.
 */
public final class WebNetwork {
    private WebNetwork() {
    }

    /** One HTTP exchange of a fetch; the transport makes exactly this request and follows no redirect. */
    public record Request(String method, String url, List<Map.Entry<String, String>> headers, byte[] body) {
    }

    public record Response(int status, String statusText, List<Map.Entry<String, String>> headers, byte[] body) {
    }

    /** An exchange or connection a transport started. */
    @FunctionalInterface
    public interface Cancellable {
        void cancel();
    }

    public interface FetchTransport {
        Cancellable send(Request request, FetchCallback callback);
    }

    public interface FetchCallback {
        void onResponse(Response response);

        void onFailure(String message);
    }

    public interface WebSocketTransport {
        WebSocketConnection connect(String url, List<String> protocols, WebSocketEvents events);
    }

    /** An open (or opening) connection; called on the context's thread only. */
    public interface WebSocketConnection extends Cancellable {
        void sendText(String text);

        void sendBinary(byte[] bytes);

        void close(int code, String reason);

        long bufferedAmount();
    }

    /** What a connection reports, in this order. */
    public interface WebSocketEvents {
        void onOpen(String protocol, String extensions);

        void onText(String text);

        void onBinary(byte[] bytes);

        void onError(String message);

        void onClose(int code, String reason, boolean wasClean);
    }

    /** Gives {@code realm} {@code fetch} over {@code fetch} and {@code WebSocket} over {@code webSocket}. */
    public static void install(Realm realm, FetchTransport fetch, WebSocketTransport webSocket) {
        if (WebGlobals.timersOf(realm) == null) {
            throw new IllegalStateException(
                    "fetch and WebSocket need the web globals: NeonEngine.builder().webGlobals(true)");
        }
        if (fetch != null) Fetch.installFetch(realm, fetch);
        if (webSocket != null) WebSockets.install(realm, webSocket);
    }
}
