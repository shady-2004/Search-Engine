package com.example.Search.Engine.Ranker;

import java.util.HashMap;
import java.util.Map;

public class PageRank {

    private static final double damping = 0.85;
    private static final double epsilon = 0.00001;

    public static Map<Integer, Double> pageRank() {
        Map<Integer, Map<Integer, Integer>> nodeAdjMap = TestDataPageRank.getGraph();
        int n = nodeAdjMap.size();

        // Initialize probability map with 1/N for each node
        Map<Integer, Double> probability = new HashMap<>();
        for (Integer node : nodeAdjMap.keySet()) {
            probability.put(node, 1.0 / n);
        }

        rank(nodeAdjMap, probability);
        double sum = 0;
        for (Integer key : probability.keySet()) {
            sum += probability.get(key);
        }
        System.out.println(sum);
        return probability;
    }

    private static void rank(Map<Integer, Map<Integer, Integer>> nodeAdjMap, Map<Integer, Double> probability) {
        boolean exit = false;
        while (!exit) {
            exit=true;

            Map<Integer, Double> newProbability = new HashMap<>();
            double dangling = 0.0;

            // Calculate dangling contribution
            for (Integer node : nodeAdjMap.keySet()) {
                Map<Integer, Integer> edges = nodeAdjMap.getOrDefault(node, new HashMap<>());
                if (edges.isEmpty()) {
                    dangling += probability.getOrDefault(node, 0.0);
                }
            }

            int totalNodes = nodeAdjMap.size();

            // Compute new rank for each node
            for (Integer i : nodeAdjMap.keySet()) {
                double rank = (1 - damping) / totalNodes;

                for (Integer j : nodeAdjMap.keySet()) {
                    Map<Integer, Integer> edges = nodeAdjMap.getOrDefault(j, new HashMap<>());
                    if (edges.containsKey(i)) {
                        rank += damping * probability.getOrDefault(j, 0.0) / edges.size();
                    }
                }

                // Add dangling node contribution
                rank += damping * dangling / totalNodes;

                newProbability.put(i, rank);
            }

            // Check for convergence
            for (Integer node : probability.keySet()) {
                if (Math.abs(probability.get(node) - newProbability.get(node)) > epsilon) {
                    exit = false;
                }
                probability.put(node, newProbability.get(node));
            }

            // Print for debugging
            System.out.println(newProbability);

        }
    }
}
