package com.queuepilot.core.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DependencyGraphTest {

    @Test
    void acyclicGraphHasNoCycleAndPrerequisitesComeFirst() {
        DependencyGraph graph = new DependencyGraph();
        // upload -> validate -> metadata -> {transcode720, transcode1080} -> combine -> verify
        graph.addDependency("validate", "upload");
        graph.addDependency("metadata", "validate");
        graph.addDependency("t720", "metadata");
        graph.addDependency("t1080", "metadata");
        graph.addDependency("combine", "t720");
        graph.addDependency("combine", "t1080");
        graph.addDependency("verify", "combine");

        assertThat(graph.findCycle()).isEmpty();
        List<String> order = graph.topologicalOrder();
        assertThat(order).hasSize(7);
        assertThat(order.indexOf("upload")).isLessThan(order.indexOf("validate"));
        assertThat(order.indexOf("t720")).isLessThan(order.indexOf("combine"));
        assertThat(order.indexOf("t1080")).isLessThan(order.indexOf("combine"));
        assertThat(order.get(6)).isEqualTo("verify");
    }

    @Test
    void detectsTwoNodeCycleAndReportsThePath() {
        DependencyGraph graph = new DependencyGraph();
        graph.addDependency("a", "b");
        graph.addDependency("b", "a");

        assertThat(graph.findCycle()).hasValueSatisfying(cycle -> {
            assertThat(cycle.get(0)).isEqualTo(cycle.get(cycle.size() - 1));
            assertThat(cycle).contains("a", "b");
        });
        assertThatThrownBy(graph::topologicalOrder).isInstanceOf(CycleDetectedException.class)
                .hasMessageContaining("->");
    }

    @Test
    void detectsSelfDependencyAndLongCycle() {
        DependencyGraph self = new DependencyGraph();
        self.addDependency("x", "x");
        assertThat(self.findCycle()).isPresent();

        DependencyGraph longCycle = new DependencyGraph();
        longCycle.addDependency("a", "b");
        longCycle.addDependency("b", "c");
        longCycle.addDependency("c", "d");
        longCycle.addDependency("d", "b");
        longCycle.addNode("unrelated");
        assertThat(longCycle.findCycle()).hasValue(List.of("b", "c", "d", "b"));
    }

    @Test
    void diamondIsNotACycle() {
        DependencyGraph graph = new DependencyGraph();
        graph.addDependency("d", "b");
        graph.addDependency("d", "c");
        graph.addDependency("b", "a");
        graph.addDependency("c", "a");
        assertThat(graph.findCycle()).isEmpty();
    }
}
