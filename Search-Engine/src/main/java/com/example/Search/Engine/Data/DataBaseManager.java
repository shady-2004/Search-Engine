package com.example.Search.Engine.Data;

import com.example.Search.Engine.QP.QueryIndex;
import javafx.util.Pair;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class DataBaseManager {
    private static final String URL = "jdbc:sqlite:./data/search_index.db";
    private static final int BATCH_SIZE = 100; // Number of documents per batch

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL);
    }

    public static List<Pair<String, Integer>> GetALLQueries() throws SQLException {
        List<Pair<String, Integer>> queries = new ArrayList<>();
        String sql = "SELECT * FROM search_queries;\n";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            Instant currentTimestamp = Instant.now();
            while (rs.next()) {
                Timestamp dbTimestamp = rs.getTimestamp("lastAdded");
                Duration duration = Duration.between(dbTimestamp.toInstant(), currentTimestamp);
                if (duration.toHours() > 12) continue;
                queries.add(new Pair<>(rs.getString("query"), rs.getInt("count")));
            }
        }
        return queries;
    }

    public static Map<Integer, List<Integer>> getGraphFromDB() throws SQLException {
        Map<Integer, List<Integer>> graph = new HashMap<>();
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
                graph.putIfAbsent(from, new ArrayList<>());
                graph.putIfAbsent(to, new ArrayList<>());
                if (!graph.get(from).contains(to)) {
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
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw e;
        }
    }

    public static void getPageRank(List<QueryIndex.DocumentData> documents) throws SQLException {
        if (documents == null || documents.isEmpty()) {
            return; // No documents to process
        }

        // Create a thread pool with a constant number of 4 threads
        ExecutorService executor = Executors.newFixedThreadPool(12);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Split documents into batches
        List<List<QueryIndex.DocumentData>> batches = new ArrayList<>();
        for (int i = 0; i < documents.size(); i += BATCH_SIZE) {
            batches.add(documents.subList(i, Math.min(i + BATCH_SIZE, documents.size())));
        }

        // Process each batch in a separate thread
        for (List<QueryIndex.DocumentData> batch : batches) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    processBatch(batch);
                } catch (SQLException e) {
                    throw new CompletionException(e); // Wrap SQLException for CompletableFuture
                }
            }, executor);
            futures.add(future);
        }

        // Wait for all tasks to complete and handle exceptions
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            // Unwrap and rethrow SQLException
            Throwable cause = e.getCause();
            if (cause instanceof SQLException) {
                throw (SQLException) cause;
            } else {
                throw new SQLException("Error processing batches", cause);
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void processBatch(List<QueryIndex.DocumentData> batch) throws SQLException {
        // Build SQL query with placeholders
        String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
        String sql = "SELECT id, page_rank FROM DocumentMetaData WHERE id IN (" + placeholders + ")";

        try (Connection conn = getConnection(); // Each thread gets its own connection
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // Set parameters for the query
            for (int i = 0; i < batch.size(); i++) {
                pstmt.setInt(i + 1, batch.get(i).getDocId());
            }

            // Execute query and process results
            try (ResultSet rs = pstmt.executeQuery()) {
                // Create a map for quick lookup of documents by docId
                Map<Integer, QueryIndex.DocumentData> docMap = batch.stream()
                        .collect(Collectors.toMap(QueryIndex.DocumentData::getDocId, doc -> doc));

                while (rs.next()) {
                    int id = rs.getInt("id");
                    double pageRank = rs.getDouble("page_rank");
                    QueryIndex.DocumentData doc = docMap.get(id);
                    if (doc != null) {
                        doc.pageRank = pageRank; // Update pageRank field
                    }
                }
            }
        }
    }
}