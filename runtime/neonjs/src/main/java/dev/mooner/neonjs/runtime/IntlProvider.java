package dev.mooner.neonjs.runtime;

/**
 * Installs ECMA-402 and locale-sensitive built-ins into a realm.
 * Derived from NeonJS and adapted to Java.
 */
public interface IntlProvider {
    void install(Realm realm);
}
