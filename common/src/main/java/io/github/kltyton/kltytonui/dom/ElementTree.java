package io.github.kltyton.kltytonui.dom;

import java.util.ArrayDeque;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.RandomAccess;
import java.util.UUID;
import io.github.kltyton.kltytonui.render.Drawer;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.init.Element;
import io.github.kltyton.kltytonui.init.Node;
import io.github.kltyton.kltytonui.spi.KuiServices;
import io.github.kltyton.kltytonui.parser.Selector;
import io.github.kltyton.kltytonui.script.neon.NeonPageRuntime;
import io.github.kltyton.kltytonui.style.Style;

public final class ElementTree {
    private final Document owner;
    private final ArrayList<Node> nodes = new ArrayList<>();
    private final TrackedElementList elements = new TrackedElementList();
    private final HashMap<String, Element> idMap = new HashMap<>();
    private final IdentityHashMap<Element, Boolean> registeredElements = new IdentityHashMap<>();
    private boolean elementIndexValid = true;

    private void rebuildElementIndex() {
        registeredElements.clear();
        for (Element element : elements) if (element != null) registeredElements.put(element, Boolean.TRUE);
        elementIndexValid = true;
    }

    private int findLastElementIndex(UUID uuid) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            Element element = elements.get(i);
            if (element != null && uuid.equals(element.uuid)) return i;
        }
        return -1;
    }

    public ElementTree(Document owner) {
        this.owner = owner;
    }

    public ArrayList<Element> getElements() {
        return elements;
    }

    public ArrayList<Node> getNodes() {
        return nodes;
    }

    public void clear() {
        if (!nodes.isEmpty()) NeonPageRuntime.treeChanged(nodes.get(0));
        nodes.clear();
        elements.clearInternally();
        idMap.clear();
        registeredElements.clear();
        elementIndexValid = true;
    }

    public void rebuildFromRoot(Element root) {
        Node oldRoot = nodes.isEmpty() ? null : nodes.get(0);
        clear();
        if (root == null) {
            if (oldRoot != null) NeonPageRuntime.treeChanged(oldRoot);
            return;
        }

        root.parentNode = null;
        root.parentElement = null;
        root.depth = 0;

        ArrayDeque<Node> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            Node current = stack.pop();
            nodes.add(current);
            if (current instanceof Element element) {
                syncElementChildView(element);
                elements.add(element);
                element.runInitFromDomOnce(element);
                if (element.id != null && !element.id.isBlank()) {
                    idMap.put(element.id, element);
                }
            }

            List<Node> children = current.childNodes;
            for (int i = children.size() - 1; i >= 0; i--) {
                Node child = children.get(i);
                if (child == null) continue;
                child.parentNode = current;
                child.depth = current.depth + 1;
                if (child instanceof Element childElement) {
                    childElement.parentElement = current instanceof Element parentElement ? parentElement : null;
                }
                stack.push(child);
            }
        }
        rebuildElementIndex();
        if (oldRoot != null && oldRoot != root) NeonPageRuntime.treeChanged(oldRoot);
        if (root != null) NeonPageRuntime.treeChanged(root);
    }

    public void updateElement(Element element) {
        if (element == null || element.uuid == null) return;
        int index = findLastElementIndex(element.uuid);
        if (index >= 0) elements.set(index, element);
    }

    public void updateInitializedElement(Element origin, Element replacement) {
        if (origin == null || replacement == null) return;
        ensureElementIndex();
        if (!registeredElements.containsKey(origin)) return;
        int index = elements.lastIndexOfIdentity(origin);
        if (index >= 0) elements.replaceInternally(index, replacement);
    }

    private void ensureElementIndex() { if (!elementIndexValid) rebuildElementIndex(); }

    public void createRelation(Node child, Node parent, boolean head) {
        if (child == null || parent == null) return;
        moveSubtree(child, parent, head ? 0 : parent.childNodes.size());
    }

    public void insertBefore(Node newChild, Node parent, Node referenceChild) {
        if (newChild == null || parent == null) return;
        int index = referenceChild == null ? parent.childNodes.size() : parent.childNodes.indexOf(referenceChild);
        if (index < 0) index = parent.childNodes.size();
        moveSubtree(newChild, parent, index);
    }

    public Node insertFragment(DocumentFragment fragment, Node parent, Node referenceChild) {
        if (fragment == null || parent == null || fragment.childNodes.isEmpty()) return null;
        int index = referenceChild == null ? parent.childNodes.size() : parent.childNodes.indexOf(referenceChild);
        if (index < 0) index = parent.childNodes.size();
        return moveFragmentChildren(fragment, parent, index);
    }

    public Node insertBeforeAndReturn(Node newChild, Node parent, Node referenceChild) {
        insertBefore(newChild, parent, referenceChild);
        return newChild;
    }

    public void replaceChild(Node parent, Node newChild, Node oldChild) {
        if (parent == null || newChild == null || oldChild == null) return;
        int index = parent.childNodes.indexOf(oldChild);
        if (index < 0) return;
        removeNode(oldChild);
        moveSubtree(newChild, parent, Math.min(index, parent.childNodes.size()));
    }

    public void removeNode(Node node) {
        if (node == null) return;
        detachSubtree(node, true);
    }

    public void removeNode(Node node, boolean notifyTreeChange) {
        if (node == null) return;
        detachSubtree(node, notifyTreeChange);
    }

    public void clearChildren(Node parent) {
        if (parent == null || parent.childNodes.isEmpty()) return;
        // 整批清空子节点改变单元的渲染子节点集合，选择缓存随之失效
        if (owner != null) owner.bumpSelectionCache();
        if (owner != null) owner.markHitTestDirtyAll();
        ArrayList<Node> removedRoots = new ArrayList<>(parent.childNodes);
        Node previousSibling = null;
        Node nextSibling = null;
        parent.childNodes.clear();
        syncElementChildView(parent);

        ArrayList<Node> removedNodes = new ArrayList<>();
        ArrayList<Element> removedElements = new ArrayList<>();
        for (Node child : removedRoots) {
            List<Node> subtree = flattenSubtree(child);
            removedNodes.addAll(subtree);
            removedElements.addAll(flattenElements(subtree));
            child.parentNode = null;
            if (child instanceof Element element) {
                element.parentElement = null;
            }
            NeonPageRuntime.treeChanged(child);
        }

        nodes.removeAll(removedNodes);
        elements.removeAllInternally(removedElements);
        for (Element element : removedElements) {
            element.onDisconnectedFromDocument();
            if (element.id != null && !element.id.isBlank()) {
                removeId(element.id, element);
            }
            element.getRenderer().route.clear();
        }
        // 离树后通知一次：运行时样式表元素（link/style）在这里卸载
        owner.onElementsDetached(removedElements);
        clearRemovedFocusState(removedElements);

        if (parent instanceof Element parentElement) {
            clearTextCaches(parentElement);
            parentElement.getRenderer().invalidateLayoutAncestors();
            owner.markDirty(parentElement, Drawer.RELAYOUT | Drawer.REORDER);
        }
        KuiServices.expander().restoreRequiredContent(owner, parent);
        owner.queueMutation(Document.MutationRecord.childList(parent, List.of(), removedRoots, previousSibling, nextSibling));
    }

    public void removeId(String id, Element element) {
        if (id == null || id.isBlank()) return;
        Element current = idMap.get(id);
        if (current == element) {
            idMap.remove(id);
        }
    }

    public void recordId(Element element) {
        if (element == null || !element.isConnected() || element.id == null || element.id.isBlank()) return;
        idMap.put(element.id, element);
    }

    public Element getElementById(String id) {
        return idMap.get(id);
    }

    private void moveSubtree(Node child, Node parent, int childIndex) {
        if (child == parent || child.contains(parent)) return;

        Document previousOwner = child.document;
        Node oldParent = child.parentNode;
        int oldIndex = oldParent == null ? -1 : oldParent.childNodes.indexOf(child);
        ElementTree sourceTree = oldParent != null && oldParent.document != null
                ? oldParent.document.getTree()
                : child.document != null ? child.document.getTree() : this;
        if (oldParent == parent && oldIndex >= 0 && oldIndex < childIndex) {
            childIndex--;
        }

        // 子树挂载改变单元的渲染子节点集合，选择缓存随之失效
        if (owner != null) owner.bumpSelectionCache();
        KuiServices.expander().validateRuntimeInsertion(owner, parent, child);
        if (oldParent != null || child.isConnected() || sourceTree != this) {
            sourceTree.detachSubtree(child, false);
            // Detaching required Slot/Ingredient content may restore a placeholder.
            KuiServices.expander().validateRuntimeInsertion(owner, parent, child);
        }

        int textSourceBefore = renderTextSourceMode(parent);
        int safeIndex = Math.max(0, Math.min(childIndex, parent.childNodes.size()));
        parent.childNodes.add(safeIndex, child);
        syncElementChildView(parent);
        Node previousSibling = safeIndex > 0 ? parent.childNodes.get(safeIndex - 1) : null;
        Node nextSibling = safeIndex + 1 < parent.childNodes.size() ? parent.childNodes.get(safeIndex + 1) : null;

        updateSubtree(child, parent, parent.depth + 1, owner);
        if (child instanceof Element childElement) {
            childElement.syncDomStateAfterAttach();
            childElement.invalidateSubtreeAfterAttach();
            childElement.invalidateStyle();
        }

        int insertIndex = safeIndex == 0 ? nodes.indexOf(parent) + 1 : findSubtreeEndExclusive(parent.childNodes.get(safeIndex - 1));
        List<Node> subtreeNodes = flattenSubtree(child);
        List<Element> subtreeElements = flattenElements(subtreeNodes);
        nodes.addAll(insertIndex, subtreeNodes);
        elements.addAllInternally(resolveInsertIndexForElements(insertIndex), subtreeElements);
        // 整棵子树都挂好了再通知一次：运行时样式表元素（link/style）从这里开始加载
        owner.onElementsAttached(subtreeElements);

        boolean geometryNeutral = isGeometryNeutralChange(child, parent, textSourceBefore);
        if (child instanceof Element childElement) {
            owner.markDirty(childElement, (geometryNeutral ? Drawer.COMMIT_LAYOUT : Drawer.RELAYOUT)
                    | Drawer.REPAINT | Drawer.REORDER | Drawer.HITTEST);
        }
        if (parent instanceof Element parentElement) {
            markParentTopologyChanged(parentElement, child, geometryNeutral);
        }
        notifyTreeChanged(child, previousOwner);
        owner.queueMutation(Document.MutationRecord.childList(parent, List.of(child), List.of(), previousSibling, nextSibling));
    }

    private Node moveFragmentChildren(DocumentFragment fragment, Node parent, int childIndex) {
        ArrayList<Node> roots = new ArrayList<>(fragment.childNodes);
        if (roots.isEmpty()) return null;
        ArrayList<Document> previousOwners = new ArrayList<>(roots.size());
        for (Node child : roots) previousOwners.add(child.document);
        // 片段挂载改变单元的渲染子节点集合，选择缓存随之失效
        if (owner != null) owner.bumpSelectionCache();
        for (Node child : roots) {
            KuiServices.expander().validateRuntimeInsertion(owner, parent, child);
        }

        int safeIndex = Math.max(0, Math.min(childIndex, parent.childNodes.size()));
        Node previousSibling = safeIndex > 0 ? parent.childNodes.get(safeIndex - 1) : null;
        Node nextSibling = safeIndex < parent.childNodes.size() ? parent.childNodes.get(safeIndex) : null;
        fragment.childNodes.clear();

        parent.childNodes.addAll(safeIndex, roots);
        syncElementChildView(parent);
        if (owner != null) owner.markHitTestDirtyAll();

        ArrayList<Node> insertedNodes = new ArrayList<>();
        ArrayList<Element> insertedElements = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            Node child = roots.get(i);
            updateSubtree(child, parent, parent.depth + 1, owner);
            if (child instanceof Element childElement) {
                childElement.syncDomStateAfterAttach();
                childElement.invalidateSubtreeAfterAttach();
                childElement.invalidateStyle();
                owner.markDirty(childElement, Drawer.RELAYOUT | Drawer.REPAINT | Drawer.REORDER);
            }
            List<Node> subtreeNodes = flattenSubtree(child);
            insertedNodes.addAll(subtreeNodes);
            insertedElements.addAll(flattenElements(subtreeNodes));
        }

        int insertIndex = safeIndex == 0 ? nodes.indexOf(parent) + 1 : findSubtreeEndExclusive(parent.childNodes.get(safeIndex - 1));
        nodes.addAll(insertIndex, insertedNodes);
        elements.addAllInternally(resolveInsertIndexForElements(insertIndex), insertedElements);
        // 整批片段都挂好了再通知一次：运行时样式表元素（link/style）从这里开始加载
        owner.onElementsAttached(insertedElements);

        if (parent instanceof Element parentElement) {
            clearTextCaches(parentElement);
            parentElement.getRenderer().invalidateLayoutAncestors();
            owner.markDirty(parentElement, Drawer.RELAYOUT | Drawer.REORDER);
        }
        KuiServices.expander().normalizeRuntimeChildren(owner, parent);
        for (int i = 0; i < roots.size(); i++) {
            notifyTreeChanged(roots.get(i), previousOwners.get(i));
        }
        owner.queueMutation(Document.MutationRecord.childList(parent, roots, List.of(), previousSibling, nextSibling));
        return roots.get(roots.size() - 1);
    }

    private static void notifyTreeChanged(Node node, Document previousOwner) {
        if (previousOwner == node.document) NeonPageRuntime.treeChanged(node);
        else NeonPageRuntime.treeChanged(node, previousOwner);
    }

    private void detachSubtree(Node node, boolean notifyTreeChange) {
        if (owner != null) owner.bumpSelectionCache();
        Node oldParent = node.parentNode;
        if (oldParent != null) {
            int textSourceBefore = renderTextSourceMode(oldParent);
            boolean neutralNode = isGeometryNeutralNode(node, oldParent)
                    && (!(node instanceof Element element) || element.getRenderer().wasOutOfFlowAtLayoutCommit());
            int index = oldParent.childNodes.indexOf(node);
            Node previousSibling = index > 0 ? oldParent.childNodes.get(index - 1) : null;
            Node nextSibling = index >= 0 && index + 1 < oldParent.childNodes.size() ? oldParent.childNodes.get(index + 1) : null;
            oldParent.childNodes.removeIf(candidate -> node.uuid.equals(candidate.uuid));
            syncElementChildView(oldParent);
            if (oldParent instanceof Element oldParentElement) {
                markParentTopologyChanged(oldParentElement, node,
                        neutralNode && textSourceBefore == renderTextSourceMode(oldParent));
            }
            owner.queueMutation(Document.MutationRecord.childList(oldParent, List.of(), List.of(node), previousSibling, nextSibling));
        }

        List<Node> subtree = flattenSubtree(node);
        nodes.removeAll(subtree);
        List<Element> subtreeElements = flattenElements(subtree);
        elements.removeAllInternally(subtreeElements);
        for (Element element : subtreeElements) {
            element.onDisconnectedFromDocument();
            if (element.id != null && !element.id.isBlank()) {
                removeId(element.id, element);
            }
            element.getRenderer().route.clear();
        }
        // 离树后通知一次：运行时样式表元素（link/style）在这里卸载。
        // 注意 moveSubtree 也会先走 detachSubtree（移动＝先摘再挂），所以这里对「刚创建、
        // 还没登记过的元素」会空跑一次——Document 只认登记过的元素，空跑无副作用。
        owner.onElementsDetached(subtreeElements);
        clearRemovedFocusState(subtreeElements);
        node.parentNode = null;
        if (node instanceof Element element) {
            element.parentElement = null;
        }
        if (oldParent != null) {
            KuiServices.expander().restoreRequiredContent(owner, oldParent);
        }
        if (notifyTreeChange) NeonPageRuntime.treeChanged(node);
    }

    private static void clearTextCaches(Element element) {
        if (element == null) return;
        element.getRenderer().text.clear();
        element.getRenderer().wrappedText.clear();
        element.getRenderer().invalidateTextVersion();
        element.getRenderer().size.clear();
    }

    private boolean isGeometryNeutralChange(Node node, Node parent, int textSourceBefore) {
        return textSourceBefore == renderTextSourceMode(parent) && isGeometryNeutralNode(node, parent);
    }

    private boolean isGeometryNeutralNode(Node node, Node parent) {
        if (node instanceof CommentNode) return true;
        if (node instanceof TextNode text) return text.getTextContent().isEmpty();
        if (!(node instanceof Element element)) return false;
        // Resolve in the current tree: reparenting can change positioning rules.
        return element.isOutOfFlowForTreeChange();
    }

    private static int renderTextSourceMode(Node node) {
        if (!(node instanceof Element element)) return 0;
        if (element.childNodes.isEmpty() && element.innerText != null && !element.innerText.isEmpty()) return 1;
        return element.childNodes.size() == 1 && element.childNodes.get(0) instanceof TextNode ? 2 : 0;
    }

    private void markParentTopologyChanged(Element parent, Node node, boolean geometryNeutral) {
        if (!geometryNeutral) {
            clearTextCaches(parent);
            clearLayoutChain(parent);
            owner.markDirty(parent, Drawer.RELAYOUT | Drawer.REORDER);
        } else if (node instanceof Element) {
            // Structural selectors may still change real layout. Their style
            // differences use the normal invalidation path after this mutation.
            var selectors = owner.getSelectorIndex();
            if (selectors.childListCanAffectStyles(parent)) {
                owner.requestStyleRecalc(selectors.hasRelationalSelectors() ? owner.documentElement : parent);
            }
            owner.markDirty(parent, Drawer.REORDER | Drawer.REPAINT | Drawer.HITTEST | Drawer.COMMIT_LAYOUT);
        } else {
            owner.markDirty(parent, Drawer.REPAINT);
        }
    }

    private static void clearLayoutChain(Element element) {
        Element current = element;
        while (current != null) {
            RenderElement renderer = current.getRenderer();
            renderer.invalidateLayoutVersion();
            renderer.size.clear();
            renderer.box.clear();
            renderer.position.clear();
            Style style = renderer.computedStyle.get();
            if (style != null && ("absolute".equals(style.position) || "fixed".equals(style.position))) break;
            current = current.parentElement;
        }
    }

    private void updateSubtree(Node root, Node parent, int depth, Document document) {
        root.parentNode = parent;
        root.depth = depth;
        root.document = document;
        if (root instanceof Element element) {
            element.parentElement = parent instanceof Element parentElement ? parentElement : null;
            syncElementChildView(element);
            element.getRenderer().route.clear();
            if (element.id != null && !element.id.isBlank()) {
                idMap.put(element.id, element);
            }
        }
        for (Node child : root.childNodes) {
            updateSubtree(child, root, depth + 1, document);
        }
    }

    private List<Node> flattenSubtree(Node root) {
        ArrayList<Node> subtree = new ArrayList<>();
        ArrayDeque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node current = stack.pop();
            subtree.add(current);
            List<Node> children = current.childNodes;
            for (int i = children.size() - 1; i >= 0; i--) {
                Node child = children.get(i);
                if (child != null) stack.push(child);
            }
        }
        return subtree;
    }

    private int findSubtreeEndExclusive(Node node) {
        int start = nodes.indexOf(node);
        if (start < 0) return nodes.size();
        int end = start + 1;
        while (end < nodes.size() && nodes.get(end).depth > node.depth) {
            end++;
        }
        return end;
    }

    private void clearRemovedFocusState(List<Element> subtree) {
        Element focused = owner.getFocusedElement();
        if (focused != null && subtree.contains(focused)) {
            owner.clearFocus();
        }
        Element active = owner.getPressedElement();
        if (active != null && subtree.contains(active)) {
            owner.setPressedElement(null);
        }
        Element previousCursor = owner.getPreviousCursorElement();
        if (previousCursor != null && subtree.contains(previousCursor)) {
            owner.setPreviousCursorElement(null);
        }
    }

    private void syncElementChildView(Node node) {
        if (!(node instanceof Element element)) return;
        ArrayList<Element> elementChildren = new ArrayList<>();
        for (Node child : element.childNodes) {
            if (child instanceof Element childElement) {
                childElement.parentElement = element;
                elementChildren.add(childElement);
            }
        }
        element.children = elementChildren;
        element.syncSelectStateAfterChildrenChanged();
    }

    private List<Element> flattenElements(List<Node> source) {
        ArrayList<Element> out = new ArrayList<>();
        for (Node node : source) {
            if (node instanceof Element element) out.add(element);
        }
        return out;
    }

    private int resolveInsertIndexForElements(int nodeInsertIndex) {
        int elementIndex = 0;
        for (int i = 0; i < Math.min(nodeInsertIndex, nodes.size()); i++) {
            if (nodes.get(i) instanceof Element) elementIndex++;
        }
        return elementIndex;
    }

    private final class TrackedElementList extends ArrayList<Element> {
        private void addAllInternally(int index, java.util.Collection<? extends Element> values) {
            if (values.isEmpty()) return;
            super.addAll(index, values);
            if (elementIndexValid) for (Element element : values) if (element != null) registeredElements.put(element, Boolean.TRUE);
        }
        private void removeAllInternally(java.util.Collection<?> values) {
            if (values.isEmpty()) return;
            super.removeAll(values);
            if (elementIndexValid) for (Object value : values) if (value instanceof Element element) registeredElements.remove(element);
        }
        private void replaceInternally(int index, Element value) {
            Element previous = super.set(index, value);
            if (elementIndexValid) {
                if (previous != value && !containsIdentity(previous)) registeredElements.remove(previous);
                if (value != null) registeredElements.put(value, Boolean.TRUE);
            }
        }
        private boolean containsIdentity(Element target) { for (Element element : this) if (element == target) return true; return false; }
        private int lastIndexOfIdentity(Element target) { for (int i = size() - 1; i >= 0; i--) if (get(i) == target) return i; return -1; }
        private void clearInternally() { super.clear(); registeredElements.clear(); elementIndexValid = true; }
        private void changed() { elementIndexValid = false; }
        @Override public boolean add(Element value) { boolean changed = super.add(value); if (changed) this.changed(); return changed; }
        @Override public void add(int index, Element value) { super.add(index, value); changed(); }
        @Override public boolean addAll(java.util.Collection<? extends Element> values) { boolean changed = super.addAll(values); if (changed) this.changed(); return changed; }
        @Override public boolean addAll(int index, java.util.Collection<? extends Element> values) { boolean changed = super.addAll(index, values); if (changed) this.changed(); return changed; }
        @Override public Element set(int index, Element value) { Element previous = super.set(index, value); changed(); return previous; }
        @Override public Element remove(int index) { Element previous = super.remove(index); changed(); return previous; }
        @Override public boolean remove(Object value) { boolean changed = super.remove(value); if (changed) this.changed(); return changed; }
        @Override public void clear() { super.clear(); changed(); }
        @Override public boolean removeAll(java.util.Collection<?> values) { try { return super.removeAll(values); } finally { changed(); } }
        @Override public boolean retainAll(java.util.Collection<?> values) { try { return super.retainAll(values); } finally { changed(); } }
        @Override public boolean removeIf(java.util.function.Predicate<? super Element> filter) { try { return super.removeIf(filter); } finally { changed(); } }
        @Override protected void removeRange(int from, int to) { super.removeRange(from, to); changed(); }
        @Override public void sort(java.util.Comparator<? super Element> comparator) { try { super.sort(comparator); } finally { changed(); } }
        @Override public void replaceAll(java.util.function.UnaryOperator<Element> operator) { try { super.replaceAll(operator); } finally { changed(); } }
        @Override public List<Element> subList(int from, int to) { return new TrackedSubList(super.subList(from, to)); }
    }

    private final class TrackedSubList extends AbstractList<Element> implements RandomAccess {
        private final List<Element> delegate;
        private TrackedSubList(List<Element> delegate) { this.delegate = delegate; }
        @Override public Element get(int index) { return delegate.get(index); }
        @Override public int size() { return delegate.size(); }
        @Override public Element set(int index, Element value) { try { return delegate.set(index, value); } finally { elementIndexValid = false; } }
        @Override public void add(int index, Element value) { delegate.add(index, value); modCount++; elementIndexValid = false; }
        @Override public Element remove(int index) { Element value = delegate.remove(index); modCount++; elementIndexValid = false; return value; }
        @Override public java.util.ListIterator<Element> listIterator(int index) {
            var iterator = delegate.listIterator(index);
            return new java.util.ListIterator<>() {
                public boolean hasNext() { return iterator.hasNext(); }
                public Element next() { return iterator.next(); }
                public boolean hasPrevious() { return iterator.hasPrevious(); }
                public Element previous() { return iterator.previous(); }
                public int nextIndex() { return iterator.nextIndex(); }
                public int previousIndex() { return iterator.previousIndex(); }
                public void remove() { iterator.remove(); elementIndexValid = false; }
                public void set(Element value) { iterator.set(value); elementIndexValid = false; }
                public void add(Element value) { iterator.add(value); elementIndexValid = false; }
            };
        }
        @Override public List<Element> subList(int from, int to) { return new TrackedSubList(delegate.subList(from, to)); }
    }
}
