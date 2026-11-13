/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

import dev.mooner.neonjs.parser.ArrayPattern;
import dev.mooner.neonjs.parser.AssignmentPattern;
import dev.mooner.neonjs.parser.AwaitExpression;
import dev.mooner.neonjs.parser.ClassDeclaration;
import dev.mooner.neonjs.parser.ClassNode;
import dev.mooner.neonjs.parser.ExportAllDeclaration;
import dev.mooner.neonjs.parser.ExportDefaultDeclaration;
import dev.mooner.neonjs.parser.ExportNamedDeclaration;
import dev.mooner.neonjs.parser.ForOfStatement;
import dev.mooner.neonjs.parser.FunctionDeclaration;
import dev.mooner.neonjs.parser.FunctionNode;
import dev.mooner.neonjs.parser.Identifier;
import dev.mooner.neonjs.parser.ImportAttribute;
import dev.mooner.neonjs.parser.ImportDeclaration;
import dev.mooner.neonjs.parser.ImportSpecifier;
import dev.mooner.neonjs.parser.MethodDefinition;
import dev.mooner.neonjs.parser.Node;
import dev.mooner.neonjs.parser.ObjectPattern;
import dev.mooner.neonjs.parser.Program;
import dev.mooner.neonjs.parser.Property;
import dev.mooner.neonjs.parser.PropertyDefinition;
import dev.mooner.neonjs.parser.RestElement;
import dev.mooner.neonjs.parser.VarKind;
import dev.mooner.neonjs.parser.VariableDeclaration;
import dev.mooner.neonjs.parser.VariableDeclarator;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds module request, import and export tables, and detects top-level await. */
public final class ModuleInfo {
    public static final ModuleInfo INSTANCE = new ModuleInfo();

    private ModuleInfo() {
    }

    /** The three tables produced by {@link #requestsAndEntries(Program)}, in their original order. */
    public record Tables(List<ModuleRequest> requests, List<ImportEntry> imports,
                         List<ExportEntry> exports) {
        public List<ModuleRequest> getRequests() {
            return requests;
        }

        public List<ImportEntry> getImports() {
            return imports;
        }

        public List<ExportEntry> getExports() {
            return exports;
        }
    }

    public static Tables requestsAndEntries(Program program) {
        List<ModuleRequest> requests = new ArrayList<>();
        List<ImportEntry> imports = new ArrayList<>();
        List<ExportEntry> exports = new ArrayList<>();
        for (Node statement : program.getBody()) {
            if (statement instanceof ImportDeclaration declaration) {
                int request = requestIndex(requests, declaration.getSource(),
                        declaration.getAttributes(), declaration.getPhase());
                for (ImportSpecifier specifier : declaration.getSpecifiers()) {
                    String name;
                    if (ModuleRequest.PHASE_DEFER.equals(declaration.getPhase())) {
                        name = ImportEntry.DEFERRED_NAMESPACE;
                    } else if (ModuleRequest.PHASE_SOURCE.equals(declaration.getPhase())) {
                        name = ImportEntry.MODULE_SOURCE;
                    } else {
                        name = specifier.getImported();
                    }
                    imports.add(new ImportEntry(request, name, specifier.getLocal().getName()));
                }
            } else if (statement instanceof ExportNamedDeclaration declaration) {
                Node node = declaration.getDeclaration();
                if (node != null) {
                    if (node instanceof VariableDeclaration variable) {
                        for (VariableDeclarator declarator : variable.getDeclarations()) {
                            List<Identifier> ids = new ArrayList<>();
                            collect(declarator.getId(), ids);
                            for (Identifier id : ids) {
                                exports.add(new ExportEntry(id.getName(), -1, null, id.getName()));
                            }
                        }
                    } else if (node instanceof FunctionDeclaration function) {
                        if (function.getFunction().getId() != null) {
                            Identifier id = function.getFunction().getId();
                            exports.add(new ExportEntry(id.getName(), -1, null, id.getName()));
                        }
                    } else if (node instanceof ClassDeclaration cls) {
                        if (cls.getCls().getId() != null) {
                            Identifier id = cls.getCls().getId();
                            exports.add(new ExportEntry(id.getName(), -1, null, id.getName()));
                        }
                    }
                } else if (declaration.getSource() != null) {
                    int request = requestIndex(requests, declaration.getSource(),
                            declaration.getAttributes(), null);
                    for (dev.mooner.neonjs.parser.ExportSpecifier specifier : declaration.getSpecifiers()) {
                        exports.add(new ExportEntry(specifier.getExported(), request,
                                specifier.getLocal(), null));
                    }
                } else {
                    for (dev.mooner.neonjs.parser.ExportSpecifier specifier : declaration.getSpecifiers()) {
                        exports.add(new ExportEntry(specifier.getExported(), -1, null,
                                specifier.getLocal()));
                    }
                }
            } else if (statement instanceof ExportDefaultDeclaration declaration) {
                String local;
                Node node = declaration.getDeclaration();
                if (node instanceof FunctionDeclaration function) {
                    local = function.getFunction().getId() == null
                            ? "*default*" : function.getFunction().getId().getName();
                } else if (node instanceof ClassDeclaration cls) {
                    local = cls.getCls().getId() == null ? "*default*" : cls.getCls().getId().getName();
                } else {
                    local = "*default*";
                }
                exports.add(new ExportEntry("default", -1, null, local));
            } else if (statement instanceof ExportAllDeclaration declaration) {
                int request = requestIndex(requests, declaration.getSource(),
                        declaration.getAttributes(), null);
                exports.add(new ExportEntry(declaration.getExported(), request,
                        ImportEntry.NAMESPACE, null));
            }
        }
        return new Tables(requests, imports, exports);
    }

    private static int requestIndex(List<ModuleRequest> requests, String specifier,
                                    List<ImportAttribute> attributes, String phase) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(attributes.size());
        for (ImportAttribute attribute : attributes) {
            entries.add(new AbstractMap.SimpleImmutableEntry<>(attribute.getKey(), attribute.getValue()));
        }
        ModuleRequest request = new ModuleRequest(specifier, entries, phase);
        int index = requests.indexOf(request);
        if (index >= 0) {
            return index;
        }
        requests.add(request);
        return requests.size() - 1;
    }

    private static void collect(Node node, List<Identifier> out) {
        if (node instanceof Identifier identifier) {
            out.add(identifier);
        } else if (node instanceof AssignmentPattern pattern) {
            collect(pattern.getLeft(), out);
        } else if (node instanceof RestElement element) {
            collect(element.getArgument(), out);
        } else if (node instanceof ArrayPattern pattern) {
            for (Node element : pattern.getElements()) {
                collect(element, out);
            }
        } else if (node instanceof ObjectPattern pattern) {
            for (Node property : pattern.getProperties()) {
                if (property instanceof Property value) {
                    collect(value.getValue(), out);
                } else {
                    collect(property, out);
                }
            }
        }
    }

    /** True if the module body (outside functions) contains {@code await} / {@code for await} / {@code await using}. */
    public static boolean hasTopLevelAwait(Program program) {
        boolean[] found = {false};
        for (Node statement : program.getBody()) {
            walkTopLevel(statement, found);
        }
        return found[0];
    }

    private static void walkTopLevel(Object value, boolean[] found) {
        if (found[0] || value == null) {
            return;
        }
        if (value instanceof FunctionNode) {
            return;
        }
        if (value instanceof ClassNode cls) {
            walkTopLevel(cls.getSuperClass(), found);
            for (Node element : cls.getBody()) {
                if (element instanceof MethodDefinition method && method.getComputed()) {
                    walkTopLevel(method.getKey(), found);
                }
                if (element instanceof PropertyDefinition property && property.getComputed()) {
                    walkTopLevel(property.getKey(), found);
                }
            }
            return;
        }
        if (value instanceof AwaitExpression) {
            found[0] = true;
            return;
        }
        if (value instanceof ForOfStatement loop && loop.isAwait()) {
            found[0] = true;
            return;
        }
        if (value instanceof VariableDeclaration declaration
                && declaration.getKind() == VarKind.AWAIT_USING) {
            found[0] = true;
            return;
        }
        if (value instanceof Node node) {
            AstWalk.forEachChild(node, child -> walkTopLevel(child, found));
        }
    }
}
