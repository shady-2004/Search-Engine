package com.example.Search.Engine;

import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.example.Search.Engine.QP.QP;
import org.springframework.beans.factory.annotation.Autowired;
import java.sql.*;
import java.util.*;

@Component
public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:./data/search_index.db";
    private Connection connection;
    private final QP queryProcessor;
    @Autowired
    public DatabaseManager(QP queryProcessor) {
        this.queryProcessor = queryProcessor;
        initialize();
    }

    @EventListener(ContextRefreshedEvent.class)
    public void initialize() {
        try {
            if (connection == null || connection.isClosed()) {
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection(DB_URL);
                connection.setAutoCommit(false);
                System.out.println("Database connection initialized successfully");
            }
        } catch (Exception e) {
            System.err.println("Error initializing database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static class SearchResponse {
        private final List<SearchResult> results;
        private final int totalCount;

        public SearchResponse(List<SearchResult> results, int totalCount) {
            this.results = results;
            this.totalCount = totalCount;
        }

        public List<SearchResult> getResults() {
            return results;
        }

        public int getTotalCount() {
            return totalCount;
        }
    }

    private int getTotalCount(Set<String> queryStems) throws SQLException {
        String countSql = """
            SELECT COUNT(DISTINCT i.doc_id) as total
            FROM InvertedIndex i
            WHERE i.word IN (%s)
        """;

        String placeholders = String.join(",", Collections.nCopies(queryStems.size(), "?"));
        countSql = String.format(countSql, placeholders);

        try (PreparedStatement pstmt = connection.prepareStatement(countSql)) {
            int paramIndex = 1;
            for (String stem : queryStems) {
                pstmt.setString(paramIndex++, stem);
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("total");
                }
            }
        }
        return 0;
    }

    public SearchResponse search(String query, int page, int size) {
        if (connection == null) {
            System.err.println("Database connection is null. Attempting to reinitialize...");
            initialize();
        }

        // Process the query using QP
        Set<String> queryStems = queryProcessor.tokenizeAndStem(query);
        System.out.println("Searching for stems: " + queryStems);

        if (queryStems.isEmpty()) {
            System.out.println("No valid stems found in query");
            return new SearchResponse(Collections.emptyList(), 0);
        }

        try {
            // Get total count first
            int totalCount = getTotalCount(queryStems);

            // Get matching documents with their scores
            String getResultsSql = """
                WITH matching_docs AS (
                    SELECT 
                        i.doc_id,
                        SUM(i.frequency * i.importance * i.IDF) as score
                    FROM InvertedIndex i
                    WHERE i.word IN (%s)
                    GROUP BY i.doc_id
                    ORDER BY score DESC
                )
                SELECT 
                    d.url,
                    d.title,
                    m.score
                FROM matching_docs m
                JOIN DocumentMetaData d ON m.doc_id = d.id
                ORDER BY m.score DESC
                LIMIT ? OFFSET ?
            """;

            String placeholders = String.join(",", Collections.nCopies(queryStems.size(), "?"));
            getResultsSql = String.format(getResultsSql, placeholders);

            List<SearchResult> results = new ArrayList<>();
            try (PreparedStatement pstmt = connection.prepareStatement(getResultsSql)) {
                int paramIndex = 1;
                for (String stem : queryStems) {
                    pstmt.setString(paramIndex++, stem);
                }
                pstmt.setInt(paramIndex++, size);
                pstmt.setInt(paramIndex, page * size);

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(new SearchResult(
                            rs.getString("url"),
                            rs.getString("title"),
                            rs.getDouble("score")
                        ));
                    }
                }
            }

            System.out.println("Found " + results.size() + " results out of " + totalCount + " total");
            return new SearchResponse(results, totalCount);

        } catch (SQLException e) {
            System.err.println("Search query failed: " + e.getMessage());
            e.printStackTrace();
            return new SearchResponse(Collections.emptyList(), 0);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("Database connection closed successfully");
            }
        } catch (SQLException e) {
            System.err.println("Error closing connection: " + e.getMessage());
            e.printStackTrace();
        }
    }
} 