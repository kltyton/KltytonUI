package io.github.kltyton.kltytonui.script.neon.host;

import io.github.kltyton.kltytonui.event.Event;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.init.Element;
import io.github.kltyton.kltytonui.init.Node;
import io.github.kltyton.kltytonui.init.Window;
import io.github.kltyton.kltytonui.registry.KltytonUIRegistry;
import io.github.kltyton.kltytonui.registry.annotation.ElementRegister;
import io.github.kltyton.kltytonui.script.neon.NeonDocumentRuntime;
import io.github.kltyton.kltytonui.script.host.KuiScriptHost;
import dev.mooner.neonjs.NeonValue;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/** A realm's host identities and JavaScript-visible DOM ownership edges. */
public final class NeonDocumentBindings {
    private final NeonDocumentRuntime owner;
    private final NeonValue bridge;
    private final NeonHostIdentity hosts;
    private final ConcurrentLinkedQueue<Node> changedNodes = new ConcurrentLinkedQueue<>();
    public NeonDocumentBindings(NeonDocumentRuntime owner, NeonValue bridge) {
        this.owner = owner;
        this.bridge = bridge;
        hosts = new NeonHostIdentity(owner.engine().api());
    }
    NeonValue cached(Object value) { return hosts.get(value); }
    NeonValue wrap(Object value, NeonValue raw) {
        NeonValue wrapped = bridge.callMember("wrap", raw, value instanceof KuiScriptHost,
                value instanceof java.util.Map<?, ?>, value instanceof Window,
                value instanceof NeonCallbackAdapter, value.getClass().isArray() || value instanceof List<?>,
                value instanceof Class<?>, value instanceof Set<?>, value instanceof Iterable<?>, value instanceof java.util.Iterator<?>,
                NeonHostProperties.of(value).genericGetter(), NeonHostProperties.of(value).genericSetter(),
                value instanceof Element, converter().classReflection(value));
        hosts.put(value, wrapped);
        if (value instanceof Node node) {
            connect(node, wrapped);
            if (node instanceof Element element && element.getClass().isAnnotationPresent(ElementRegister.class)
                    && KltytonUIRegistry.ELEMENTS.contains(element)) bridge.callMember("retainHost", wrapped);
        } else if (value instanceof Document document) {
            if (document.documentElement != null) connect(document.documentElement);
        } else if (value instanceof NeonCallbackAdapter adapter) {
            bridge.callMember("link", wrapped, "callback", adapter.function().toGuestValue());
        } else if (value instanceof Event event && event.detail != null) {
            bridge.callMember("link", wrapped, "detail", converter().toGuest(event.detail));
        }
        return wrapped;
    }
    public void treeChanged(Node node) { changedNodes.add(node); }
    public void synchronizeOwnership() {
        bridge.callMember("setRoots", converter().toGuest(owner.document()), converter().toGuest(owner.document().getScriptRoots()));
        Set<Node> changed = Collections.newSetFromMap(new IdentityHashMap<>());
        Node node;
        while ((node = changedNodes.poll()) != null) if (changed.add(node)) connect(node);
    }
    public NeonValue invoke(NeonValue callback, Object receiver, Object value, boolean argumentRequired) {
        return bridge.callMember("invoke", callback, converter().toGuest(receiver), converter().toGuest(value), argumentRequired);
    }
    public NeonValue invokeArgs(NeonValue callback, Object receiver, Object[] values) {
        return bridge.callMember("invokeArgs", callback, converter().toGuest(receiver), converter().toGuest(values));
    }
    public void release() { changedNodes.clear(); bridge.callMember("releaseRealm"); }
    private void connect(Node node) {
        NeonValue wrapped = hosts.get(node);
        if (wrapped != null) connect(node, wrapped);
    }
    private void connect(Node node, NeonValue wrapped) {
        Object parent = node.parentNode;
        if (parent == null && node.isConnected()) parent = node.document;
        bridge.callMember("setParent", wrapped, converter().toGuest(parent));
        bridge.callMember("link", wrapped, "ownerDocument", converter().toGuest(node.document));
    }
    private NeonHostConverter converter() { return owner.engine().converter(); }
}

