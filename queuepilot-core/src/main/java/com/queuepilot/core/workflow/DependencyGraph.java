package com.queuepilot.core.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Directed graph of "job depends on prerequisite" edges with cycle detection and topological
 * ordering. Node ids are plain strings (job ids or workflow step keys).
 *
 * <p>Cycle detection is a depth-first search with three colours: WHITE = not visited,
 * GREY = on the current DFS path, BLACK = finished. Reaching a GREY node again means the
 * path loops back on itself, i.e. a cycle.</p>
 */
public class DependencyGraph {

    private enum Colour { WHITE, GREY, BLACK }

    /** node -> its prerequisites; insertion order kept so results are deterministic. */
    private final Map<String, Set<String>> prerequisites = new LinkedHashMap<>();

    public void addNode(String node) {
        prerequisites.computeIfAbsent(node, key -> new LinkedHashSet<>());
    }

    public void addDependency(String node, String prerequisite) {
        addNode(node);
        addNode(prerequisite);
        prerequisites.get(node).add(prerequisite);
    }

    public Set<String> prerequisitesOf(String node) {
        return Collections.unmodifiableSet(prerequisites.getOrDefault(node, Set.of()));
    }

    public Set<String> nodes() {
        return Collections.unmodifiableSet(prerequisites.keySet());
    }

    /** Returns one cycle as a path that starts and ends with the same node, if any exists. */
    public Optional<List<String>> findCycle() {
        Map<String, Colour> colours = new HashMap<>();
        for (String node : prerequisites.keySet()) {
            colours.put(node, Colour.WHITE);
        }
        for (String node : prerequisites.keySet()) {
            if (colours.get(node) == Colour.WHITE) {
                List<String> path = new ArrayList<>();
                Optional<List<String>> cycle = visit(node, colours, path);
                if (cycle.isPresent()) {
                    return cycle;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<List<String>> visit(String node, Map<String, Colour> colours, List<String> path) {
        colours.put(node, Colour.GREY);
        path.add(node);
        for (String next : prerequisites.get(node)) {
            Colour colour = colours.get(next);
            if (colour == Colour.GREY) {
                List<String> cycle = new ArrayList<>(path.subList(path.indexOf(next), path.size()));
                cycle.add(next);
                return Optional.of(cycle);
            }
            if (colour == Colour.WHITE) {
                Optional<List<String>> cycle = visit(next, colours, path);
                if (cycle.isPresent()) {
                    return cycle;
                }
            }
        }
        path.remove(path.size() - 1);
        colours.put(node, Colour.BLACK);
        return Optional.empty();
    }

    /**
     * Orders nodes so every prerequisite comes before the nodes that depend on it.
     *
     * @throws CycleDetectedException if the graph has a cycle
     */
    public List<String> topologicalOrder() {
        findCycle().ifPresent(cycle -> {
            throw new CycleDetectedException(cycle);
        });
        List<String> order = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        for (String node : prerequisites.keySet()) {
            place(node, placed, order);
        }
        return order;
    }

    private void place(String node, Set<String> placed, List<String> order) {
        if (placed.contains(node)) {
            return;
        }
        for (String prerequisite : prerequisites.get(node)) {
            place(prerequisite, placed, order);
        }
        placed.add(node);
        order.add(node);
    }
}
