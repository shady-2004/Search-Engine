package com.example.Search.Engine.Ranker;

import java.util.*;

public class TestDataPageRank {

    public static Map<Integer, Map<Integer, Integer>> getGraph() {
        Map<Integer, Map<Integer, Integer>> graph = new HashMap<>();

        graph.put(0, new HashMap<>() {{ put(1, 1); put(2, 1); put(3, 1); put(4, 1); }});
        graph.put(1, new HashMap<>() {{ put(0, 1); put(2, 1); put(5, 1); put(6, 1); }});
        graph.put(2, new HashMap<>() {{ put(0, 1); put(1, 1); put(3, 1); put(6, 1); put(7, 1); }});
        graph.put(3, new HashMap<>() {{ put(0, 1); put(2, 1); put(4, 1); put(7, 1); put(8, 1); }});
        graph.put(4, new HashMap<>() {{ put(0, 1); put(3, 1); put(5, 1); put(8, 1); put(9, 1); }});
        graph.put(5, new HashMap<>() {{ put(1, 1); put(4, 1); put(6, 1); put(9, 1); put(10, 1); }});
        graph.put(6, new HashMap<>() {{ put(1, 1); put(2, 1); put(5, 1); put(7, 1); put(10, 1); put(11, 1); }});
        graph.put(7, new HashMap<>() {{ put(2, 1); put(3, 1); put(6, 1); put(8, 1); put(11, 1); put(12, 1); }});
        graph.put(8, new HashMap<>() {{ put(3, 1); put(4, 1); put(7, 1); put(9, 1); put(12, 1); put(13, 1); }});
        graph.put(9, new HashMap<>() {{ put(4, 1); put(5, 1); put(8, 1); put(10, 1); put(13, 1); put(14, 1); }});
        graph.put(10, new HashMap<>() {{ put(5, 1); put(6, 1); put(9, 1); put(11, 1); put(14, 1); put(15, 1); }});
        graph.put(11, new HashMap<>() {{ put(6, 1); put(7, 1); put(10, 1); put(12, 1); put(15, 1); put(16, 1); }});
        graph.put(12, new HashMap<>() {{ put(7, 1); put(8, 1); put(11, 1); put(13, 1); put(16, 1); put(17, 1); }});
        graph.put(13, new HashMap<>() {{ put(8, 1); put(9, 1); put(12, 1); put(14, 1); put(17, 1); put(18, 1); }});
        graph.put(14, new HashMap<>() {{ put(9, 1); put(10, 1); put(13, 1); put(15, 1); put(18, 1); put(19, 1); }});
        graph.put(15, new HashMap<>() {{ put(10, 1); put(11, 1); put(14, 1); put(16, 1); put(19, 1); put(20, 1); }});
        graph.put(16, new HashMap<>() {{ put(11, 1); put(12, 1); put(15, 1); put(17, 1); put(20, 1); put(21, 1); }});
        graph.put(17, new HashMap<>() {{ put(12, 1); put(13, 1); put(16, 1); put(18, 1); put(21, 1); put(22, 1); }});
        graph.put(18, new HashMap<>() {{ put(13, 1); put(14, 1); put(17, 1); put(19, 1); put(22, 1); put(23, 1); }});
        graph.put(19, new HashMap<>() {{ put(14, 1); put(15, 1); put(18, 1); put(20, 1); put(23, 1); put(24, 1); }});
        graph.put(20, new HashMap<>() {{ put(15, 1); put(16, 1); put(19, 1); put(21, 1); put(24, 1); put(25, 1); }});
        graph.put(21, new HashMap<>() {{ put(16, 1); put(17, 1); put(20, 1); put(22, 1); put(25, 1); put(26, 1); }});
        graph.put(22, new HashMap<>() {{ put(17, 1); put(18, 1); put(21, 1); put(23, 1); put(26, 1); put(27, 1); }});
        graph.put(23, new HashMap<>() {{ put(18, 1); put(19, 1); put(22, 1); put(24, 1); put(27, 1); put(28, 1); }});
        graph.put(24, new HashMap<>() {{ put(19, 1); put(20, 1); put(23, 1); put(25, 1); put(28, 1); put(29, 1); }});
        graph.put(25, new HashMap<>() {{ put(20, 1); put(21, 1); put(24, 1); put(26, 1); put(29, 1); put(30, 1); }});
        graph.put(26, new HashMap<>() {{ put(21, 1); put(22, 1); put(25, 1); put(27, 1); put(30, 1); put(31, 1); }});
        graph.put(27, new HashMap<>() {{ put(22, 1); put(23, 1); put(26, 1); put(28, 1); put(31, 1); put(32, 1); }});
        graph.put(28, new HashMap<>() {{ put(23, 1); put(24, 1); put(27, 1); put(29, 1); put(32, 1); put(33, 1); }});
        graph.put(29, new HashMap<>() {{ put(24, 1); put(25, 1); put(28, 1); put(30, 1); put(33, 1); put(34, 1); }});
        graph.put(30, new HashMap<>() {{ put(25, 1); put(26, 1); put(29, 1); put(31, 1); put(34, 1); put(35, 1); }});
        graph.put(31, new HashMap<>() {{ put(26, 1); put(27, 1); put(30, 1); put(32, 1); put(35, 1); put(36, 1); }});
        graph.put(32, new HashMap<>() {{ put(27, 1); put(28, 1); put(31, 1); put(33, 1); put(36, 1); put(37, 1); }});
        graph.put(33, new HashMap<>() {{ put(28, 1); put(29, 1); put(32, 1); put(34, 1); put(37, 1); put(38, 1); }});
        graph.put(34, new HashMap<>() {{ put(29, 1); put(30, 1); put(33, 1); put(35, 1); put(38, 1); put(39, 1); }});
        graph.put(35, new HashMap<>() {{ put(30, 1); put(31, 1); put(34, 1); put(36, 1); put(39, 1); put(40, 1); }});
        graph.put(36, new HashMap<>() {{ put(31, 1); put(32, 1); put(35, 1); put(37, 1); put(40, 1); put(41, 1); }});
        graph.put(37, new HashMap<>() {{ put(32, 1); put(33, 1); put(36, 1); put(38, 1); put(41, 1); put(42, 1); }});
        graph.put(38, new HashMap<>() {{ put(33, 1); put(34, 1); put(37, 1); put(39, 1); put(42, 1); put(43, 1); }});
        graph.put(39, new HashMap<>() {{ put(34, 1); put(35, 1); put(38, 1); put(40, 1); put(43, 1); put(44, 1); }});
        graph.put(40, new HashMap<>() {{ put(35, 1); put(36, 1); put(39, 1); put(41, 1); put(44, 1); put(45, 1); }});
        graph.put(41, new HashMap<>() {{ put(36, 1); put(37, 1); put(40, 1); put(42, 1); put(45, 1); put(46, 1); }});
        graph.put(42, new HashMap<>() {{ put(37, 1); put(38, 1); put(41, 1); put(43, 1); put(46, 1); put(47, 1); }});
        graph.put(43, new HashMap<>() {{ put(38, 1); put(39, 1); put(42, 1); put(44, 1); put(47, 1); put(48, 1); }});
        graph.put(44, new HashMap<>() {{ put(39, 1); put(40, 1); put(43, 1); put(45, 1); put(48, 1); put(49, 1); }});
        graph.put(45, new HashMap<>() {{ put(40, 1); put(41, 1); put(44, 1); put(46, 1); put(49, 1); put(0, 1); }});
        graph.put(46, new HashMap<>() {{ put(41, 1); put(42, 1); put(45, 1); put(47, 1); put(0, 1); put(1, 1); }});
        graph.put(47, new HashMap<>() {{ put(42, 1); put(43, 1); put(46, 1); put(48, 1); put(1, 1); put(2, 1); }});
        graph.put(48, new HashMap<>() {{ put(43, 1); put(44, 1); put(47, 1); put(49, 1); put(2, 1); put(3, 1); }});
        graph.put(49, new HashMap<>() {{ put(44, 1); put(45, 1); put(48, 1); put(0, 1); put(3, 1); put(4, 1); }});

        return graph;
    }
}