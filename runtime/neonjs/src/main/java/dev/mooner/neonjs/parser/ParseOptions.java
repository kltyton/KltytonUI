/*
 * Copyright 2026 mooner1022
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from the NeonJS Kotlin parser sources to pure Java 17.
 */

package dev.mooner.neonjs.parser;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/** Options for parsing. */
public final class ParseOptions {
    public final boolean isModule;
    public final boolean strict;
    /**
     * Parsing code for a direct/indirect eval or Function constructor; enables context-dependent constructs.
     */
    public final boolean allowReturnOutsideFunction;
    public final boolean allowNewTarget;
    public final boolean allowSuperProperty;
    public final boolean allowSuperCall;
    public final boolean allowArguments;
    /** In a class field initializer context (eval inside field initializer). */
    public final boolean inClassFieldInit;
    /** Private names visible from the enclosing class(es) (direct eval inside class bodies). */
    public final Set<String> privateNames;
    public final String sourceName;
    /** Allow top-level await in scripts (REPL convenience). */
    public final boolean topLevelAwait;
    /** Allow html-like comments (Annex B). */
    public final boolean annexB;

    public ParseOptions() {
        this(false, false, false, false, false, false, true, false, Collections.emptySet(), null, false, true);
    }

    public ParseOptions(
            boolean isModule,
            boolean strict,
            boolean allowReturnOutsideFunction,
            boolean allowNewTarget,
            boolean allowSuperProperty,
            boolean allowSuperCall,
            boolean allowArguments,
            boolean inClassFieldInit,
            Set<String> privateNames,
            String sourceName,
            boolean topLevelAwait,
            boolean annexB
    ) {
        this.isModule = isModule;
        this.strict = strict;
        this.allowReturnOutsideFunction = allowReturnOutsideFunction;
        this.allowNewTarget = allowNewTarget;
        this.allowSuperProperty = allowSuperProperty;
        this.allowSuperCall = allowSuperCall;
        this.allowArguments = allowArguments;
        this.inClassFieldInit = inClassFieldInit;
        this.privateNames = Objects.requireNonNull(privateNames, "privateNames");
        this.sourceName = sourceName;
        this.topLevelAwait = topLevelAwait;
        this.annexB = annexB;
    }

    public boolean isModule() {
        return isModule;
    }

    public boolean getIsModule() {
        return isModule;
    }

    public boolean isStrict() {
        return strict;
    }

    public boolean getStrict() {
        return strict;
    }

    public boolean isAllowReturnOutsideFunction() {
        return allowReturnOutsideFunction;
    }

    public boolean getAllowReturnOutsideFunction() {
        return allowReturnOutsideFunction;
    }

    public boolean isAllowNewTarget() {
        return allowNewTarget;
    }

    public boolean getAllowNewTarget() {
        return allowNewTarget;
    }

    public boolean isAllowSuperProperty() {
        return allowSuperProperty;
    }

    public boolean getAllowSuperProperty() {
        return allowSuperProperty;
    }

    public boolean isAllowSuperCall() {
        return allowSuperCall;
    }

    public boolean getAllowSuperCall() {
        return allowSuperCall;
    }

    public boolean isAllowArguments() {
        return allowArguments;
    }

    public boolean getAllowArguments() {
        return allowArguments;
    }

    public boolean isInClassFieldInit() {
        return inClassFieldInit;
    }

    public boolean getInClassFieldInit() {
        return inClassFieldInit;
    }

    public Set<String> getPrivateNames() {
        return privateNames;
    }

    public String getSourceName() {
        return sourceName;
    }

    public boolean isTopLevelAwait() {
        return topLevelAwait;
    }

    public boolean getTopLevelAwait() {
        return topLevelAwait;
    }

    public boolean isAnnexB() {
        return annexB;
    }

    public boolean getAnnexB() {
        return annexB;
    }
}
