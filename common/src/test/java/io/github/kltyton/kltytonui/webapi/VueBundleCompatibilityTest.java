package io.github.kltyton.kltytonui.webapi;

import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.script.neon.NeonPageRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Execution(ExecutionMode.SAME_THREAD)
class VueBundleCompatibilityTest {
    private static final String ROOT = "assets/kltytonui/kltytonui/kltytonui/runtime/";
    private static final String COMPONENT_RUNTIME =
            "assets/kltytonui/kltytonui/kltytonui/runtime/mcui/";

    private Page page;

    @BeforeEach
    void createPage() {
        page = new Page();
    }

    @AfterEach
    void closePage() {
        page.close();
    }

    @Test
    void pinnedProductionBundlesCompileOnTheKuiScriptEngine() throws Exception {
        page.eval(readRuntime("vue.kui.js"), "vue.kui.js");
        page.eval(readRuntime("mcui-oreui.kui.js"), "mcui-oreui.kui.js");
        assertEquals("object|object", page.result("return typeof Vue + '|' + typeof McUIVue;", "bundle-globals"));
    }

    @Test
    void vueReactivityUsesTheGenericProxyAndReflectContract() throws Exception {
        page.eval(readRuntime("vue.kui.js"), "vue.kui.js");

        Object result = page.result(
                "var state = Vue.reactive({ count: 1 });"
                        + "var values = [];"
                        + "Vue.effect(function() { values.push(state.count); });"
                        + "state.count = 2;"
                        + "return values.join(',') + '|' + Vue.isReactive(state);",
                "vue-reactivity");

        assertEquals("1,2|true", result);
    }

    @Test
    void vueArrayMutationsUseStringProxyKeysAndTriggerLengthDependencies() throws Exception {
        page.eval(readRuntime("vue.kui.js"), "vue.kui.js");

        Object result = page.result(
                "var target=[];var keys=[];"
                        + "var probe=new Proxy(target,{"
                        + "get:function(t,k,r){return Reflect.get(t,k,r);},"
                        + "set:function(t,k,v,r){keys.push(typeof k+':'+String(k));return Reflect.set(t,k,v,r);}});"
                        + "probe.unshift(1);"
                        + "var spliceTarget=['a','b'];"
                        + "var spliceProbe=new Proxy(spliceTarget,{"
                        + "get:function(t,k,r){return Reflect.get(t,k,r);},"
                        + "set:function(t,k,v,r){return Reflect.set(t,k,v,r);},"
                        + "deleteProperty:function(t,k){return Reflect.deleteProperty(t,k);}});"
                        + "spliceProbe.splice(0,1);"
                        + "var items=Vue.ref([]);var lengths=[];"
                        + "Vue.effect(function(){lengths.push(items.value.length);});"
                        + "items.value.unshift('a');items.value.push('b');items.value.splice(0,1);"
                        + "return keys.join(',')+'|'+lengths.join(',')+'|'"
                        + "+String(spliceTarget.length)+':'+spliceTarget.join(',');",
                "vue-array-proxy-keys");

        assertEquals("string:0,string:length|0,1,2,1|1:b", result);
    }

    @Test
    void arrayIsArrayRecognizesReactiveAndNestedProxyArraysOnly() throws Exception {
        page.eval(readRuntime("vue.kui.js"), "vue.kui.js");

        Object result = page.result( """
                var array = ['survival'];
                var reactive = Vue.ref(array).value;
                var nested = new Proxy(reactive, {});
                var objectProxy = new Proxy({}, {});
                var fake = Object.create(Array.prototype);
                return [Array.isArray(array), Array.isArray(reactive), Array.isArray(nested),
                 Array.isArray(objectProxy), Array.isArray(fake), reactive.includes('survival')].join('|');
                """, "proxy-array-isarray");

        assertEquals("true|true|true|false|false|true", result);
    }

    @Test
    void proxySupportsTheReflectTrapsUsedByVue() throws Exception {

        Object result = page.result(
                "var target = { a: 1 };"
                        + "var proxy = new Proxy(target, {"
                        + " get: function(t, k, r) { return Reflect.get(t, k, r); },"
                        + " set: function(t, k, v, r) { return Reflect.set(t, k, v, r); },"
                        + " has: function(t, k) { return Reflect.has(t, k); },"
                        + " deleteProperty: function(t, k) { return Reflect.deleteProperty(t, k); },"
                        + " ownKeys: function(t) { return Reflect.ownKeys(t); },"
                        + " defineProperty: function(t, k, d) { return Reflect.defineProperty(t, k, d); }"
                        + "});"
                        + "var read = proxy.a; proxy.b = 3;"
                        + "var has = ('b' in proxy); var keys = Object.keys(proxy).sort().join(',');"
                        + "Object.defineProperty(proxy, 'c', { value: 4, enumerable: true, configurable: true });"
                        + "delete proxy.a;"
                        + "return read + '|' + target.b + '|' + has + '|' + keys + '|' + target.c + '|' + ('a' in target);",
                "proxy-reflect");

        assertEquals("1|3|true|a,b|4|false", result);
    }

    @Test
    void promiseCallbacksUseAnAsynchronousFifoMicrotaskQueue() throws Exception {
        page.eval("var order=[]; Promise.resolve(1).then(function(v){order.push('p'+v);return v+1;})"
                + ".then(function(v){order.push('p'+v);document.body.setAttribute('data-order',order.join(','));});"
                + "queueMicrotask(function(){order.push('q');}); order.push('sync');", "promise-order");
        assertEquals("sync,p1,q,p2", page.awaitAttribute("data-order", 2000));
    }

    @Test
    void vueMountsAndUpdatesAgainstTheGenericKuiDom() throws Exception {
        page.eval(readRuntime("vue.kui.js"), "vue.kui.js");
        page.eval("var root=document.createElement('div'); document.body.appendChild(root);"
                + "var value=Vue.ref('initial');"
                + "var app=Vue.createApp({render:function(){return Vue.h('span',{id:'vue-probe'},value.value);}});"
                + "app.mount(root); value.value='updated';"
                + "Vue.nextTick(function(){document.body.setAttribute('data-updated','yes');});", "vue-mount");
        assertEquals("yes", page.awaitAttribute("data-updated", 2000));
        assertEquals("updated", page.document.querySelector("#vue-probe").getTextContent());
    }

    private static String read(String name) throws Exception {
        try (InputStream stream = VueBundleCompatibilityTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(stream, name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void mcButtonsMountAndAddOneReactiveButtonWithoutLosingDisabledState() throws Exception {
        page.eval(readRuntime("vue.kui.js"), "vue.kui.js");
        page.eval(readRuntime("mcui-oreui.kui.js"), "mcui-oreui.kui.js");
        page.eval("""
                var buttonCount = Vue.ref(22);
                var root = document.createElement('div'); document.body.appendChild(root);
                var app = Vue.createApp({render:function() {
                  return Vue.h('div', {id:'buttons'}, Array.from({length:buttonCount.value}, function(_, i) {
                    return Vue.h(McUIVue.McButton, {key:i, disabled:i === 0}, function() {return 'Button ' + i;});
                  }));
                }});
                app.mount(root);
                document.body.setAttribute('data-first-count', String(root.querySelectorAll('button').length));
                buttonCount.value++;
                Vue.nextTick(function() { document.body.setAttribute('data-next-count', String(root.querySelectorAll('button').length)); });
                """, "mcui-buttons");
        assertEquals("22", page.document.body.getAttribute("data-first-count"));
        assertEquals("23", page.awaitAttribute("data-next-count", 2000));
        assertEquals(true, page.document.querySelector("button").isDisabled());
    }

    private static String readRuntime(String name) throws Exception {
        return read(("vue.kui.js".equals(name) ? ROOT : COMPONENT_RUNTIME) + name);
    }

    @Test
    void vueShallowReactiveMapValuesRemainIterable() throws Exception {
        page.eval(readRuntime("vue.kui.js"), "vue.kui.js");

        Object result = page.result( """
                var raw = new Map();
                raw.set('top', 1);
                function probe(fn) { try { return String(fn()); } catch (error) { return String(error); } }
                var plain = probe(function() { return Array.from(raw.values()).length; });
                var wrapped = Vue.shallowReactive(raw);
                var reactive = probe(function() { return Array.from(wrapped.values()).length; });
                return plain + '|' + reactive;
                """, "vue-map-iteration");
        assertEquals("1|1", result);
    }
    static final class Page implements AutoCloseable {
        private final TestDocumentFactory.NeonPage owner = new TestDocumentFactory.NeonPage();
        final Document document = owner.document();

        void eval(String code, String source) {
            try (Document.ContextScope ignored = Document.withContext(document)) {
                NeonPageRuntime.eval(code, null, source);
            }
        }

        String result(String body, String source) {
            eval("document.body.setAttribute('data-test-result', String((function(){" + body + "})()));", source);
            return document.body.getAttribute("data-test-result");
        }

        String awaitAttribute(String name, long timeoutMillis) throws InterruptedException {
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (System.nanoTime() < deadline) {
                eval("void 0;", "await-attribute");
                if (document.body.getAttribute(name) != null) break;
                Thread.sleep(10);
            }
            return document.body.getAttribute(name);
        }

        @Override public void close() {
            owner.close();
        }
    }

}
