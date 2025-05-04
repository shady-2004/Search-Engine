package com.example.Search.Engine.Ranker;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.Search.Engine.Data.DataBaseManager.getGraphFromDB;
import static com.example.Search.Engine.Data.DataBaseManager.setPageRank;

public class PageRank {

    private static final double damping = 0.85;
    private static final double epsilon = 0.00001;

    public static void pageRank() throws SQLException {

        Map<Integer, List<Integer>> nodeAdjMap = getGraphFromDB();
        int n = nodeAdjMap.size();

        // Initialize probability map with 1/N for each node
        Map<Integer, Double> probability = new HashMap<>();
        for (Integer node : nodeAdjMap.keySet()) {
            probability.put(node, 1.0 / n);
        }

        long startTime = System.currentTimeMillis(); // Start time
        rank(nodeAdjMap, probability);

//        double sum = 0;
//        for (Integer key : probability.keySet()) {
//            sum += probability.get(key);
//        }
//        System.out.println(sum);

        long endTime = System.currentTimeMillis(); // End time

        long elapsedTime = endTime - startTime;
        // Calculate elapsed time in milliseconds
        System.out.println("Execution Time: " + elapsedTime + " milliseconds");

        setPageRank(probability);
    }


    private static void rank(Map<Integer, List<Integer>> nodeAdjMap, Map<Integer, Double> probability) {
        boolean exit = false;
        while (!exit) {
            exit = true;

            Map<Integer, Double> newProbability = new HashMap<>();
            double dangling = 0.0;

            // Calculate dangling contribution
            for (Integer node : nodeAdjMap.keySet()) {
                List<Integer> edges = nodeAdjMap.getOrDefault(node, new ArrayList<>());
                if (edges.isEmpty()) {
                    dangling += probability.getOrDefault(node, 0.0);
                }
            }

            int totalNodes = nodeAdjMap.size();

            // Compute new rank for each node
            for (Integer i : nodeAdjMap.keySet()) {
                double rank = (1 - damping) / totalNodes;

                // Loop through all other nodes to find which ones link to node 'i'
                for (Integer j : nodeAdjMap.keySet()) {
                    List<Integer> edges = nodeAdjMap.getOrDefault(j, new ArrayList<>());
                    if (edges.contains(i)) {
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
//            System.out.println(newProbability);
        }
    }

}
