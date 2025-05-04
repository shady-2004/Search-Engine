package com.example.Search.Engine;

import com.example.Search.Engine.QP.QueryIndex;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.example.Search.Engine.Indexer.Tokenizer;
import com.example.Search.Engine.QP.QP;
import com.example.Search.Engine.Ranker.Ranker;
import org.springframework.beans.factory.annotation.Autowired;
import java.sql.*;
import java.util.*;

@Component
public class BackendManager {
    private static final String DB_URL = "jdbc:sqlite:./data/search_index.db";
    private Connection connection;
    private Tokenizer tokenizer;
    private final QP queryProcessor;
    private final Ranker ranker;

    @Autowired
    public BackendManager(QP queryProcessor, Ranker ranker) {
        this.queryProcessor = queryProcessor;
        this.ranker = ranker;
        this.tokenizer = new Tokenizer();
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
        QueryIndex.QueryResult queryResult;
        try {
            queryResult = queryProcessor.search(query);
        } catch (SQLException e) {
            System.err.println("Query processing failed: " + e.getMessage());
            e.printStackTrace();
            return new SearchResponse(Collections.emptyList(), 0);
        }
        List<QueryIndex.DocumentData> documents = queryResult.documents;
        int totalCount = documents.size();
        System.out.println("Searching for query: " + query + ", found " + totalCount + " documents");

        if (documents.isEmpty()) {
            System.out.println("No documents found for query");
            return new SearchResponse(Collections.emptyList(), 0);
        }

        try {
            // Stem queryWords to match wordInfo keys
            Map<String, String> stemToOriginal = new HashMap<>();
            Set<String> stemmedQueryWords = queryProcessor.tokenizeAndStem(
                    String.join(" ", queryResult.queryWords), stemToOriginal);
            System.out.println("Searching for stems: " + stemmedQueryWords);

            if (stemmedQueryWords.isEmpty()) {
                System.out.println("No valid stems found in query");
                return new SearchResponse(Collections.emptyList(), 0);
            }

            // Rank documents
            List<Integer> rankedDocIds = Ranker.rank(documents, stemmedQueryWords);
            System.out.println("Ranked " + rankedDocIds.size() + " documents");

            // Apply pagination
            int startIndex = page * size;
            int endIndex = Math.min(startIndex + size, rankedDocIds.size());
            
            // If we're beyond the last page, return empty results with correct total
            if (startIndex >= rankedDocIds.size()) {
                System.out.println("Page " + page + " is out of range");
                return new SearchResponse(Collections.emptyList(), rankedDocIds.size());
            }

            // Get the documents for this page, even if it's a partial page
            List<Integer> pagedDocIds = new ArrayList<>();
            for (int i = startIndex; i < endIndex && i < rankedDocIds.size(); i++) {
                pagedDocIds.add(rankedDocIds.get(i));
            }

            // Compute scores for documents
            Map<Integer, Double> docScores = new HashMap<>();
            for (QueryIndex.DocumentData doc : documents) {
                double score = 0.0;
                Map<String, List<Double>> wordInfo = doc.getWordInfo();
                for (String queryTerm : stemmedQueryWords) {
                    List<Double> info = wordInfo.get(queryTerm);
                    if (info != null && info.size() >= 2) {
                        double tf = info.get(0); // Term frequency
                        double idf = info.get(1); // IDF
                        score += tf * idf;
                    }
                }
                score = Ranker.TFIDF_WEIGHT * score + Ranker.PAGERANK_WEIGHT * doc.getPageRank();
                docScores.put(doc.getDocId(), score);
            }

            // Get matching documents with their metadata
            List<SearchResult> results = new ArrayList<>();
            if (!pagedDocIds.isEmpty()) {
                String getResultsSql = "SELECT id, url, title FROM DocumentMetaData WHERE id IN ("
                        + String.join(",", Collections.nCopies(pagedDocIds.size(), "?")) + ")";
                try (PreparedStatement pstmt = connection.prepareStatement(getResultsSql)) {
                    int paramIndex = 1;
                    for (int docId : pagedDocIds) {
                        pstmt.setInt(paramIndex++, docId);
                    }
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            int docId = rs.getInt("id");
                            results.add(new SearchResult(
                                    rs.getString("url"),
                                    rs.getString("title"),
                                    docScores.getOrDefault(docId, 0.0)
                            ));
                        }
                    }
                }
            }

            System.out.println("Found " + results.size() + " results for page " + page + " out of " + rankedDocIds.size() + " total");
            return new SearchResponse(results, rankedDocIds.size());

        } catch (SQLException e) {
            System.err.println("Search query failed: " + e.getMessage());
            e.printStackTrace();
            return new SearchResponse(Collections.emptyList(), 0);
        } catch (InterruptedException e) {
            System.err.println("Ranking interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
            return new SearchResponse(Collections.emptyList(), 0);
        }
    }

    public List<String> getSearchSuggestions(String query) throws SQLException {
        if (connection == null) {
            System.err.println("Database connection is null. Attempting to reinitialize...");
            initialize();
        }

        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String searchTerm = query.trim().toLowerCase();
        String sql = """
            SELECT DISTINCT word
            FROM InvertedIndex
            WHERE word LIKE ? || '%'
            ORDER BY 
                CASE 
                    WHEN word = ? THEN 1
                    WHEN word LIKE ? || ' %' THEN 2
                    ELSE 3
                END,
                word
            LIMIT 5
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, searchTerm);
            pstmt.setString(2, searchTerm);
            pstmt.setString(3, searchTerm);

            List<String> suggestions = new ArrayList<>();
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    suggestions.add(rs.getString("word"));
                }
            }
            return suggestions;
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