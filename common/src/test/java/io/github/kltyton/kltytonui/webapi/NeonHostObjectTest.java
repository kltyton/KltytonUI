package io.github.kltyton.kltytonui.webapi;

import io.github.kltyton.kltytonui.element.Input;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.init.Element;
import io.github.kltyton.kltytonui.script.neon.NeonPageRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NeonHostObjectTest {
    private static void eval(String code) {
        NeonPageRuntime.eval(code, null, "host-object-test");
    }

    @Test
    void browserCreateElementUsesSpecializedCanvasAndInputProperties() {
        Element.register(Input.TAG_NAME, (owner, tag) -> new Input(owner));
        TestDocumentFactory.withNeonDocument(document -> {
            eval("""
                    var canvas = document.createElement('canvas');
                    var firstInput = document.createElement('input'); firstInput.value = 'first';
                    var secondInput = document.createElement('input'); secondInput.value = 'second';
                    var firstValue = firstInput.value;
                    firstInput.value = 'changed';
                    document.body.setAttribute('data-result',
                      typeof document.createElement + '|' + canvas.tagName + '|' +
                      typeof canvas.getContext + '|' + (canvas.getContext('2d') != null) + '|' +
                      firstValue + '|' + firstInput.value + '|' + secondInput.value + '|' + firstInput.isConnected);
                    """);
            assertEquals("function|CANVAS|function|true|first|changed|second|false",
                    document.body.getAttribute("data-result"));
        });
    }

    @Test
    void stringAndSymbolExpandosPreserveJavaMembersAndNestedIdentity() {
        TestDocumentFactory.withNeonDocument(document -> {
            eval("""
                    var host = document.createElement('div');
                    var first = Symbol('same'), second = Symbol('same');
                    host.arbitrary = 41; host[''] = 7; host[first] = 'first'; host[second] = 'second';
                    Object.defineProperty(host, 'derived', {
                      get: function() { return host.arbitrary + 1; }, configurable: true
                    });
                    host.setAttribute('data-probe', 'ok');
                    var child = document.createElement('span'); host.appendChild(child);
                    child._vnode = 'cached';
                    document.body.setAttribute('data-result',
                      host.arbitrary + '|' + host[''] + '|' + host[first] + '|' + host[second] + '|' +
                      host.derived + '|' + host.getAttribute('data-probe') + '|' +
                      host.firstChild._vnode + '|' + (child === host.firstChild));
                    """);
            assertEquals("41|7|first|second|42|ok|cached|true", document.body.getAttribute("data-result"));
        });
    }

    @Test
    void stringPropertiesUseEcmaScriptMethods() {
        TestDocumentFactory.withNeonDocument(document -> {
            eval("""
                    var host = document.createElement('input'); host.value = '12a3';
                    document.body.setAttribute('data-result', typeof host.value + '|' + host.value.replace(/[^0-9]/g, ''));
                    """);
            assertEquals("string|123", document.body.getAttribute("data-result"));
        });
    }

    @Test
    void eventArgumentsRetainExpandosAndClonesAreIndependent() {
        TestDocumentFactory.withNeonDocument(document -> {
            eval("""
                    var target = document.createElement('div');
                    target.addEventListener('clone', function(event) {
                      event.testValue = 'original';
                      var clonedEvent = event.clone();
                      document.body.setAttribute('data-result',
                        (clonedEvent === event) + '|' + (typeof clonedEvent.testValue) + '|' + event.testValue);
                    });
                    target.dispatchEvent(window.createEvent('clone', false));
                    """);
            assertEquals("false|undefined|original", document.body.getAttribute("data-result"));
        });
    }

    @Test
    void eventCallbacksUseCurrentTargetAsThisAndCanBeRemovedByIdentity() {
        try (var page = new TestDocumentFactory.NeonPage();
             var ignored = Document.withContext(page.document())) {
            Document document = page.document();
            eval("""
                    var target = document.createElement('div'), calls = 0, matched = false;
                    var ordinaryValue, ordinaryThis;
                    function listener(event) { calls++; matched = this === event.currentTarget; }
                    target.addEventListener('click', listener);
                    target.dispatchEvent(window.createEvent('click', false));
                    target.removeEventListener('click', listener);
                    target.dispatchEvent(window.createEvent('click', false));
                    window.createCallback(function(value) { ordinaryValue = value; ordinaryThis = this; });
                    """);
            page.lastCallback().accept("payload");
            eval("""
                    document.body.setAttribute('data-result', calls + '|' + matched + '|' + ordinaryValue + '|' + (ordinaryThis !== target));
                    """);
            assertEquals("1|true|payload|true", document.body.getAttribute("data-result"));
        }
    }

    @Test
    void wrapperIdentityAndStaleCallbackAreScopedToDocumentGeneration() {
        try (var page = new TestDocumentFactory.NeonPage();
             var ignored = Document.withContext(page.document())) {
            Document document = page.document();
            Element target = document.createElement("div");
            target.setAttribute("id", "callback-target");
            document.body.appendChild(target);
            eval("""
                    var target = document.querySelector('#callback-target');
                    target.cached = 'present';
                    document.body.setAttribute('data-identity',
                      String(target === document.querySelector('#callback-target')) + '|' +
                      document.querySelector('#callback-target').cached);
                    window.createCallback(function() {
                      target.setAttribute('data-called', 'yes');
                    });
                    """);
            assertEquals("true|present", document.body.getAttribute("data-identity"));
            var listener = page.lastCallback();
            listener.accept(null);
            assertEquals("yes", target.getAttribute("data-called"));
            target.removeAttribute("data-called");
            document.refresh();
            listener.accept(null);
            assertEquals(null, target.getAttribute("data-called"));
        }
    }
}

