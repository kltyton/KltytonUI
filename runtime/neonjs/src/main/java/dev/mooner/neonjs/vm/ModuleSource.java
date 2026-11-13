/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.Realm;
import java.util.Map;

/** Source of a module provided by the host loader. */
public final class ModuleSource {
    public final String key;
    public final String text;
    public final String type;

    /** For host-defined modules: export name -&gt; JS value (the source text is ignored). */
    public Map<String, Object> hostExports;

    /**
     * The realm {@link #hostExports} were created in, if they are objects of a particular realm. Such a module cannot
     * be loaded into another realm (e.g. a ShadowRealm sharing the loader): its objects would cross the realm boundary.
     */
    public Realm hostRealm;

    /**
     * For host modules that have a source-phase representation ({@code import source}): the class name reported by the
     * module source object's @@toStringTag (e.g. "WebAssembly.Module"). JS modules have none.
     */
    public String sourceClassName;

    /** Raw contents for {@code with { type: "bytes" }} imports (loaders should set it for such requests). */
    public byte[] bytes;

    public ModuleSource(String key, String text) {
        this(key, text, "javascript");
    }

    public ModuleSource(String key, String text, String type) {
        this.key = key;
        this.text = text;
        this.type = type;
    }

    public String getKey() {
        return key;
    }

    public String getText() {
        return text;
    }

    public String getType() {
        return type;
    }

    public Map<String, Object> getHostExports() {
        return hostExports;
    }

    public void setHostExports(Map<String, Object> hostExports) {
        this.hostExports = hostExports;
    }

    public Realm getHostRealm() {
        return hostRealm;
    }

    public void setHostRealm(Realm hostRealm) {
        this.hostRealm = hostRealm;
    }

    public String getSourceClassName() {
        return sourceClassName;
    }

    public void setSourceClassName(String sourceClassName) {
        this.sourceClassName = sourceClassName;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public void setBytes(byte[] bytes) {
        this.bytes = bytes;
    }
}
