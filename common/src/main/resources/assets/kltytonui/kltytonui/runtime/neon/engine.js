(() => {
  'use strict';
  const hosts = new WeakMap();
  const identities = new WeakMap();
  let nextIdentity = 1;
  const packageCache = new Map();
  const api = {
    hosts,
    identity(value) {
      let id = identities.get(value);
      if (id === undefined) { id = nextIdentity++; identities.set(value, id); }
      return id;
    },
    bigInt: value => BigInt(value),
    type: value => typeof value,
    undefinedValue: () => undefined,
    isUndefined: value => value === undefined,
    isArray: value => Array.isArray(value) || ArrayBuffer.isView(value) && !(value instanceof DataView),
    isOpaque: value => !['[object Object]', '[object Array]'].includes(Object.prototype.toString.call(value))
      && !(ArrayBuffer.isView(value) && !(value instanceof DataView)),
    read: (object, key) => Reflect.get(object, key),
    write: (object, key, value) => Reflect.set(object, key, value),
    hasOwn: (object, key) => Object.prototype.hasOwnProperty.call(object, key),
    ownNames: object => Object.getOwnPropertyNames(object),
    remove: (object, key) => Reflect.deleteProperty(object, key),
    call: (object, name, ...args) => Reflect.apply(Reflect.get(object, name), object, args),
    apply: (callback, receiver, args) => Reflect.apply(callback, receiver, args),
    unwrap: value => hosts.has(value) ? hosts.get(value).raw : undefined,
    weak: value => new WeakRef(value),
    deref: reference => reference.deref(),
    javaType(name) {
      const type = api.loadClass(String(name));
      if (type === undefined) throw new TypeError('Java class not found: ' + name);
      return type;
    },
    package(prefix) {
      if (packageCache.has(prefix)) return packageCache.get(prefix);
      const namespace = new Proxy(Object.create(null), {
        get(target, name) {
          if (typeof name !== 'string') return undefined;
          const qualified = prefix ? prefix + '.' + name : name;
          const type = api.loadClass(qualified);
          return type === undefined ? api.package(qualified) : type;
        },
        has: (target, name) => typeof name === 'string' && name.length > 0,
        set() { throw new TypeError('Java package is read-only'); }
      });
      packageCache.set(prefix, namespace);
      return namespace;
    }
  };
  Object.defineProperty(globalThis, '__kuiEngine', { value: api });
})();

