(() => {
  'use strict';
  const api = globalThis.__kuiEngine;
  const hosts = api.hosts;
  const microtask = Promise.resolve();
  const capture = options => typeof options === 'boolean' ? options : !!(options && options.capture);
  const createBridge = (nativeCall, globals) => {
    const roots = new Map();
    const hostRoots = new Set();
    let released = false;
    let nextRoot = 1;
    const inOwner = operation => nativeCall(operation);
    const callbackSource = value => hosts.has(value) && hosts.get(value).callbackHost
      ? hosts.get(value).owned.get('callback') : value;
    const wrap = (raw, browserHost, mapHost, globalHost, callbackHost, arrayHost, classHost, setHost, iterableHost, iteratorHost, genericGetter, genericSetter, elementHost, classReflection) => {
      const target = classHost || typeof raw === 'function' ? function() {}.bind(null) : arrayHost ? [] : {};
      if (typeof target === 'function') {
        delete target.name;
        delete target.length;
      }
      const state = { raw, callbackHost, parent: null, children: new Set(), roots: new Set(), listeners: [], owned: new Map(), methods: new Map() };
      let proxy;
      const iterator = (source, entryPairs, duplicate) => ({
        next() {
          return inOwner(() => {
            if (!source.hasNext()) return { value: undefined, done: true };
            const value = source.next();
            return { value: entryPairs ? [value.getKey(), value.getValue()] : duplicate ? [value, value] : value, done: false };
          });
        },
        [Symbol.iterator]() { return this; }
      });
      const collectionMethod = key => {
        if (mapHost) {
          switch (key) {
            case 'get': return name => raw.containsKey(name) ? raw.get(name) : undefined;
            case 'set': return (name, value) => { raw.put(name, value); return proxy; };
            case 'has': return name => raw.containsKey(name);
            case 'delete': return name => { const exists = raw.containsKey(name); if (exists) raw.remove(name); return exists; };
            case 'clear': return () => raw.clear();
            case 'keys': return () => iterator(raw.keySet().iterator(), false, false);
            case 'values': return () => iterator(raw.values().iterator(), false, false);
            case 'entries': return () => iterator(raw.entrySet().iterator(), true, false);
            case 'forEach': return (callback, receiver) => {
              for (const [name, value] of collectionMethod('entries')()) Reflect.apply(callback, receiver, [value, name, proxy]);
            };
          }
        } else if (setHost) {
          switch (key) {
            case 'add': return value => { raw.add(value); return proxy; };
            case 'has': return value => raw.contains(value);
            case 'delete': return value => raw.remove(value);
            case 'clear': return () => raw.clear();
            case 'keys':
            case 'values': return () => iterator(raw.iterator(), false, false);
            case 'entries': return () => iterator(raw.iterator(), false, true);
            case 'forEach': return (callback, receiver) => {
              for (const value of collectionMethod('values')()) Reflect.apply(callback, receiver, [value, value, proxy]);
            };
          }
        }
        return undefined;
      };
      const handler = {
        get(object, key, receiver) {
          return inOwner(() => {
            if (key === '__kuiHostObject') return browserHost;
            if (key === Symbol.toStringTag) return browserHost ? 'KuiHostObject' : 'JavaObject';
            if (key === Symbol.iterator && (mapHost || setHost || iterableHost || iteratorHost)) {
              if (arrayHost) return Reflect.get(object, key, receiver);
              if (mapHost) return collectionMethod('entries');
              return () => iterator(iteratorHost ? raw : raw.iterator(), false, false);
            }
            const own = Reflect.getOwnPropertyDescriptor(object, key);
            if (own && !(Array.isArray(object) && key === 'length' && own.writable)) {
              return Reflect.get(object, key, receiver);
            }
            if (Array.isArray(object) && key === 'length') return raw.length;
            if (!Array.isArray(object) && typeof object !== 'function' && Reflect.has(object, key)) return Reflect.get(object, key, receiver);
            if (state.methods.has(key)) return state.methods.get(key);
            if ((mapHost || setHost) && key === 'size') return raw.size();
            const collection = collectionMethod(key);
            if (collection) {
              const method = (...args) => inOwner(() => Reflect.apply(collection, proxy, args));
              state.methods.set(key, method);
              return method;
            }
            if (elementHost && key === 'scrollHeight') return raw.getScrollHeight();
            if (elementHost && key === 'scrollWidth') return raw.getScrollWidth();
            if (classHost && key === 'class') return classReflection;
            let nativeMember = Reflect.get(raw, key, raw);
            if (nativeMember === undefined && classHost) nativeMember = Reflect.get(classReflection, key, classReflection);
            if (nativeMember === undefined && genericGetter && typeof key === 'string') nativeMember = raw.get(key);
            if (nativeMember === undefined) {
              const inherited = Reflect.get(object, key, receiver);
              return inherited !== undefined ? inherited : globalHost ? Reflect.get(globals, key) : undefined;
            }
            const member = nativeMember;
            if (typeof member !== 'function' || hosts.has(member)) {
              if (hosts.has(member) && ['style', 'classList', 'dataset', 'childNodes', 'children', 'attributes'].includes(key)) {
                state.owned.set(key, member);
                hosts.get(member).owned.set('owner', proxy);
              }
              return member;
            }
            const method = function(...args) {
              return inOwner(() => {
                const invokeArgs = elementHost && key === 'setAttribute' && args.length >= 2
                  ? [`${args[0]}`, `${args[1]}`] : args;
                const result = Reflect.apply(member, raw, invokeArgs);
                const callback = callbackSource(args[1]);
                if (key === 'addEventListener' && typeof callback === 'function') {
                  const existing = state.listeners.some(item => item.type === args[0] && item.callback === callback && item.capture === capture(args[2]));
                  if (!existing) state.listeners.push({ type: args[0], callback, capture: capture(args[2]), once: !!(args[2] && args[2].once) });
                } else if (key === 'removeEventListener') {
                  state.listeners = state.listeners.filter(item => item.type !== args[0] || item.callback !== callback || item.capture !== capture(args[2]));
                }
                return result;
              });
            };
            state.methods.set(key, method);
            return method;
          });
        },
        set(object, key, value, receiver) {
          return inOwner(() => {
            const index = Array.isArray(object) && /^(0|[1-9][0-9]*)$/.test(String(key));
            if (Reflect.has(object, key) && !index) return Reflect.set(object, key, value, receiver);
            if (genericSetter && !mapHost && typeof key === 'string' && !Reflect.has(raw, key)) {
              raw.set(key, value);
              if (value !== null && (typeof value === 'object' || typeof value === 'function' || typeof value === 'symbol')) state.owned.set(key, value);
              else state.owned.delete(key);
              return true;
            }
            if ((mapHost || Reflect.has(raw, key) || index) && typeof Reflect.get(raw, key, raw) !== 'function') {
              const written = Reflect.set(raw, key, value, raw);
              if (written) {
                if (value !== null && (typeof value === 'object' || typeof value === 'function' || typeof value === 'symbol')) {
                  state.owned.set(key, value);
                } else state.owned.delete(key);
              }
              return written;
            }
            return Reflect.set(object, key, value, receiver);
          });
        },
        has(object, key) {
          return inOwner(() => Reflect.has(object, key) || Reflect.has(raw, key)
            || classHost && Reflect.has(classReflection, key)
            || genericGetter && typeof key === 'string' || globalHost && Reflect.has(globals, key));
        },
        getOwnPropertyDescriptor(object, key) {
          return inOwner(() => {
            const own = Reflect.getOwnPropertyDescriptor(object, key);
            if (own) return own;
            if (!Reflect.has(raw, key)) return undefined;
            return { value: handler.get(object, key, proxy), writable: true, enumerable: true, configurable: true };
          });
        },
        ownKeys(object) {
          return inOwner(() => [...new Set([...Reflect.ownKeys(object), ...Reflect.ownKeys(raw)])]);
        },
        deleteProperty(object, key) {
          return inOwner(() => Object.prototype.hasOwnProperty.call(object, key)
            ? Reflect.deleteProperty(object, key) : Reflect.deleteProperty(raw, key));
        },
        apply(object, receiver, args) {
          return inOwner(() => classHost ? Reflect.construct(raw, args) : Reflect.apply(raw, receiver, args));
        },
        construct(object, args) {
          return inOwner(() => Reflect.construct(raw, args));
        }
      };
      proxy = new Proxy(target, handler);
      hosts.set(proxy, state);
      return proxy;
    };
    const bridge = {
      wrap,
      setParent(child, parent) {
        const state = hosts.get(child);
        if (!state || state.parent === parent) return;
        if (state.parent) hosts.get(state.parent).children.delete(child);
        state.parent = parent;
        if (parent) hosts.get(parent).children.add(child);
      },
      setRoots: (owner, values) => { hosts.get(owner).roots = new Set(values); },
      link: (owner, key, value) => hosts.get(owner).owned.set(key, value),
      retainHost: value => hostRoots.add(value),
      retain(value) {
        if (released) return null;
        const token = nextRoot++;
        roots.set(token, value);
        return token;
      },
      release: token => roots.delete(token),
      releaseRealm() {
        released = true;
        roots.clear();
        hostRoots.clear();
      },
      invoke(callback, receiver, value, argumentRequired) {
        return bridge.invokeArgs(callback, receiver, argumentRequired ? [value] : []);
      },
      invokeArgs(callback, receiver, args) {
        return inOwner(() => {
          const callbackReceiver = receiver == null ? globals : receiver;
          const state = hosts.get(callbackReceiver);
          const value = args[0];
          if (state && value && value.type) {
            state.listeners = state.listeners.filter(item => !(item.once && item.callback === callback && item.type === value.type));
          }
          return Reflect.apply(callback, callbackReceiver, args);
        });
      },
      install() {
        Object.defineProperty(globals, '__kuiBridge', { value: bridge });
        Object.defineProperty(globals, 'queueMicrotask', {
          configurable: true,
          writable: true,
          value(callback) {
            if (typeof callback !== 'function') throw new TypeError('queueMicrotask callback must be a function');
            microtask.then(() => {
              if (!released) callback();
            });
          }
        });
        const packages = api.package('');
        Object.defineProperty(globals, 'Packages', { value: packages });
        Object.defineProperty(globals, 'Java', { value: {
          type(name) { return api.javaType(name); },
          loadClass(name) { return this.type(name); }
        } });
        for (const name of ['java', 'javax', 'org', 'com', 'net']) {
          Object.defineProperty(globals, name, { get: () => packages[name] });
        }
      }
    };
    return bridge;
  };
  api.createBridge = createBridge;
})();


