package com.example.Search.Engine.Data;

import javafx.util.Pair;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;

public class DataBaseManager {
    private static final String URL = "jdbc:sqlite:./data/searchE.db";

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL);
    }
    public static List<Pair<String,Integer>> GetALLQueries() throws SQLException {
        List<Pair<String,Integer>>queries=new ArrayList<>();
        String sql="SELECT * FROM search_queries;\n";
        try(Connection conn=getConnection();
            Statement stmt= getConnection().createStatement();
        ResultSet rs = stmt.executeQuery(sql)){
                Instant currentTimestamp = Instant.now();
            while(rs.next()){
                Timestamp dbTimestamp = rs.getTimestamp("lastAdded");  // Replace with actual column name
                Duration duration = Duration.between(dbTimestamp.toInstant(), currentTimestamp);
                // Get the current timestamp
                if(duration.toHours()>12)continue;
                queries.add(new Pair<>(rs.getString("query"),rs.getInt("count")));
            }
        }
        return queries;
    }


    public static Map<Integer, List<Integer>> getGraphFromDB() throws SQLException {
        Map<Integer, List<Integer>> graph = new HashMap<>();

        String nodesSql = "SELECT id FROM nodes";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(nodesSql)) {

            while (rs.next()) {
                int nodeId = rs.getInt("id");
                graph.putIfAbsent(nodeId, new ArrayList<>());
            }
        }

        String linksSql = "SELECT from_id, to_id FROM links";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(linksSql)) {

            while (rs.next()) {
                int from = rs.getInt("from_id");
                int to = rs.getInt("to_id");


                if (graph.containsKey(from)) {
                    graph.get(from).add(to);
                } else {
                    graph.put(from, new ArrayList<>());
                    graph.get(from).add(to);
                }

            }
        }

        // Optionally, print the graph for debugging purposes
        System.out.println("Graph: " + graph);

        return graph;
    }




}
