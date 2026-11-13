/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class SwitchStatement extends Node {
    public final Node discriminant;
    public final List<SwitchCase> cases;

    public SwitchStatement(Node discriminant, List<SwitchCase> cases) {
        this.discriminant = discriminant;
        this.cases = cases;
    }

    public Node getDiscriminant() {
        return discriminant;
    }

    public List<SwitchCase> getCases() {
        return cases;
    }
}
