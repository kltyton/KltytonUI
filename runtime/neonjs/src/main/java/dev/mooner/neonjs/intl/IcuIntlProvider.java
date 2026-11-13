/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.IntlProvider;
import dev.mooner.neonjs.runtime.Realm;

/**
 * ECMA-402 implementation backed by ICU4J. Registered through META-INF/services.
 *
 * <p>{@link #install} runs for every realm, so it only creates the JS function objects; no ICU class is touched (and no
 * locale data is loaded) until an Intl constructor or locale-sensitive method is first used.
 */
public final class IcuIntlProvider implements IntlProvider {
    @Override
    public void install(Realm realm) {
        IntlInstaller.install(realm);
    }
}
