package com.example.Search.Engine.Data;

import javafx.util.Pair;
import java.sql.*;
import java.util.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.stream.Collectors;

public class DataBaseManager {
    private static final String URL = "jdbc:sqlite:./data/search_index.db";

    public static class DocumentData {
        private final int docId;
        private final Map<String, Integer> termFrequencies;
        private final int documentLength;
        private final double pageRank;

        public DocumentData(int docId, Map<String, Integer> termFrequencies,
                            int documentLength, double pageRank) {
            this.docId = docId;
            this.termFrequencies = termFrequencies;
            this.documentLength = documentLength;
            this.pageRank = pageRank;
        }

        public int getDocId() {
            return docId;
        }

        public Map<String, Integer> getTermFrequencies() {
            return termFrequencies;
        }

        public int getDocumentLength() {
            return documentLength;
        }

        public double getPageRank() {
            return pageRank;
        }
    }

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

        // Query to get links (edges) by joining extracted_links with DocumentMetaData
        String linksSql = "SELECT el.doc_id AS from_id, dm.id AS to_id " +
                "FROM extracted_links el " +
                "JOIN DocumentMetaData dm ON el.extracted_link = dm.url " +
                "WHERE dm.id IS NOT NULL";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(linksSql)) {

            while (rs.next()) {
                int from = rs.getInt("from_id");
                int to = rs.getInt("to_id");

                // Ensure both 'from' and 'to' nodes exist in the graph
                graph.putIfAbsent(from, new ArrayList<>());
                graph.putIfAbsent(to, new ArrayList<>());

                // Add the edge (to_id) to the 'from' node's adjacency list
                if (!graph.get(from).contains(to)) { // Avoid duplicate edges
                    graph.get(from).add(to);
                }
            }
        }

        return graph;
    }


    public static void setPageRank(Map<Integer, Double> pageRankMap) throws SQLException {
        String updateSql = "UPDATE DocumentMetaData SET page_rank = ? WHERE id = ?;";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(updateSql)) {


            for (Map.Entry<Integer, Double> entry : pageRankMap.entrySet()) {
                int id = entry.getKey();
                double pageRank = entry.getValue();

                pstmt.setDouble(1, pageRank);
                pstmt.setInt(2, id);

                // Execute the update
                pstmt.executeUpdate();
            }

        } catch (SQLException e) {
            e.printStackTrace();
            throw e;  // Re-throw exception after logging for further handling
        }
    }

}
