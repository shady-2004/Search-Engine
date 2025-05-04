package com.example.Search.Engine;

import com.example.Search.Engine.QP.QueryIndex;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.example.Search.Engine.Indexer.Tokenizer;
import com.example.Search.Engine.QP.QP;
import com.example.Search.Engine.Ranker.Ranker;
import com.example.Search.Engine.Ranker.PageRank;
import org.springframework.beans.factory.annotation.Autowired;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

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

    public static class SearchResult {
        private final String url;
        private final String title;
        private final double score;
        private final String snippet;

        public SearchResult(String url, String title, double score, String snippet) {
            this.url = url;
            this.title = title;
            this.score = score;
            this.snippet = snippet;
        }

        public String getUrl() {
            return url;
        }

        public String getTitle() {
            return title;
        }

        public double getScore() {
            return score;
        }

        public String getSnippet() {
            return snippet;
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

    private String generateSnippet(String title, String url, Map<String, List<Double>> wordInfo, Set<String> queryWords) {
        try {
            // Get the HTML content from the database
            String getContentSql = "SELECT html FROM DocumentMetaData WHERE url = ?";
            try (PreparedStatement pstmt = connection.prepareStatement(getContentSql)) {
                pstmt.setString(1, url);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        String html = rs.getString("html");
                        if (html != null && !html.isEmpty()) {
                            // Clean HTML and extract text content
                            String text = html.replaceAll("<script[^>]*>.*?</script>", " ") // Remove scripts
                                            .replaceAll("<style[^>]*>.*?</style>", " ") // Remove styles
                                            .replaceAll("<[^>]*>", " ") // Remove other HTML tags
                                            .replaceAll("&nbsp;", " ") // Replace HTML entities
                                            .replaceAll("&[^;]+;", " ")
                                            .replaceAll("\\s+", " ") // Normalize whitespace
                                            .replaceAll("[\\p{Cntrl}&&[^\n\t]]", "") // Remove control chars except newline/tab
                                            .replaceAll("\\s*[\\r\\n]+\\s*", " ") // Normalize line breaks
                                            .replaceAll("\\s*[.,!?]+\\s*", ". ") // Normalize punctuation
                                            .replaceAll("\\.+", ".") // Fix multiple periods
                                            .replaceAll("\\s+", " ") // Final whitespace normalization
                                            .trim();
                            
                            // Find the best position to start the snippet
                            int bestPosition = -1;
                            String bestWord = null;
                            
                            // First try to find exact word matches in the text
                            for (String word : queryWords) {
                                int pos = text.toLowerCase().indexOf(word.toLowerCase());
                                if (pos != -1) {
                                    bestPosition = pos;
                                    bestWord = word;
                                    break;
                                }
                            }
                            
                            // If no exact match found, try using wordInfo positions
                            if (bestPosition == -1) {
                                for (String word : queryWords) {
                                    if (wordInfo.containsKey(word)) {
                                        List<Double> positions = wordInfo.get(word);
                                        if (!positions.isEmpty()) {
                                            int position = positions.get(0).intValue();
                                            if (position < text.length()) {
                                                bestPosition = position;
                                                bestWord = word;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (bestPosition == -1) {
                                return "No preview available for this result.";
                            }
                            
                            // Find sentence boundaries
                            int startPos = bestPosition;
                            int endPos = bestPosition;
                            
                            // Look for sentence start (period + space or start of text)
                            while (startPos > 0 && startPos > bestPosition - 150) {
                                if (startPos >= 2 && text.substring(startPos - 2, startPos).matches("\\. ")) {
                                    startPos -= 2;
                                    break;
                                }
                                startPos--;
                            }
                            
                            // Look for sentence end (period + space or end of text)
                            while (endPos < text.length() && endPos < bestPosition + 150) {
                                if (endPos + 2 <= text.length() && text.substring(endPos, endPos + 2).matches("\\. ")) {
                                    endPos += 2;
                                    break;
                                }
                                endPos++;
                            }
                            
                            // Ensure we don't exceed text boundaries
                            startPos = Math.max(0, startPos);
                            endPos = Math.min(text.length(), endPos);
                            
                            StringBuilder snippet = new StringBuilder();
                            if (startPos > 0) {
                                snippet.append("...");
                            }
                            
                            String snippetText = text.substring(startPos, endPos).trim();
                            
                            // Verify the snippet contains at least one query word
                            boolean containsQueryWord = false;
                            for (String word : queryWords) {
                                if (snippetText.toLowerCase().contains(word.toLowerCase())) {
                                    containsQueryWord = true;
                                    break;
                                }
                            }
                            
                            if (!containsQueryWord) {
                                // If no query word in snippet, expand the snippet
                                startPos = Math.max(0, bestPosition - 100);
                                endPos = Math.min(text.length(), bestPosition + 100);
                                snippetText = text.substring(startPos, endPos).trim();
                            }
                            
                            // Clean up the snippet text
                            snippetText = snippetText.replaceAll("\\s+", " ")
                                                    .replaceAll("\\s*[.,!?]+\\s*", ". ")
                                                    .replaceAll("\\.+", ".")
                                                    .replaceAll("^[^a-zA-Z0-9]+", "") // Remove leading non-alphanumeric
                                                    .replaceAll("[^a-zA-Z0-9]+$", "") // Remove trailing non-alphanumeric
                                                    .trim();
                            
                            // Highlight query words in the snippet
                            for (String word : queryWords) {
                                snippetText = snippetText.replaceAll(
                                    "(?i)\\b" + word + "\\b",
                                    "<strong>$0</strong>"
                                );
                            }
                            
                            snippet.append(snippetText);
                            
                            if (endPos < text.length()) {
                                snippet.append("...");
                            }
                            
                            return snippet.toString();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error generating snippet: " + e.getMessage());
        }
        
        // Fallback to title-based snippet if content retrieval fails
        return "No preview available for this result.";
    }

    public SearchResponse search(String query, int page, int size) {
        if (connection == null) {
            System.err.println("Database connection is null. Attempting to reinitialize...");
            initialize();
            if (connection == null) {
                System.err.println("Failed to initialize database connection");
                return new SearchResponse(Collections.emptyList(), 0);
            }
        }

        // Process the query using QP
        QueryIndex.QueryResult queryResult;
        try {
            long startTime = System.nanoTime();
            queryResult = queryProcessor.search(query);
            long endTime = System.nanoTime();
            System.out.println("Query processing time: " + (endTime - startTime) / 1000000 + " milliseconds");
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

            // Rank documents
            long startTime = System.nanoTime();
            List<Map.Entry<Integer, Double>> rankedEntries;
            try {
                rankedEntries = ranker.rank(documents, queryResult.queryWords);
            } catch (InterruptedException e) {
                System.err.println("Ranking interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
                return new SearchResponse(Collections.emptyList(), 0);
            }
            System.out.println("Ranked entries: " + rankedEntries);
            long endTime = System.nanoTime();
            System.out.println("Ranking time: " + (endTime - startTime) / 1000000 + " milliseconds");
            
            List<Integer> rankedDocIds = rankedEntries.stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
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
            Map<Integer, Double> docScores = new HashMap<>();
            Map<Integer, QueryIndex.DocumentData> docDataMap = new HashMap<>();
            
            for (int i = startIndex; i < endIndex && i < rankedDocIds.size(); i++) {
                Map.Entry<Integer, Double> entry = rankedEntries.get(i);
                int docId = entry.getKey();
                pagedDocIds.add(docId);
                docScores.put(docId, entry.getValue());
                // Store document data for snippet generation
                docDataMap.put(docId, documents.stream()
                    .filter(doc -> doc.getDocId() == docId)
                    .findFirst()
                    .orElse(null));
            }

            // Get matching documents with their metadata
            List<SearchResult> results = new ArrayList<>();
            if (!pagedDocIds.isEmpty()) {
                StringBuilder orderByClause = new StringBuilder("ORDER BY CASE id ");
                for (int i = 0; i < pagedDocIds.size(); i++) {
                    orderByClause.append("WHEN ").append(pagedDocIds.get(i)).append(" THEN ").append(i).append(" ");
                }
                orderByClause.append("END");

                String getResultsSql = "SELECT id, url, title FROM DocumentMetaData WHERE id IN ("
                        + String.join(",", Collections.nCopies(pagedDocIds.size(), "?")) + ") "
                        + orderByClause.toString();
                try (PreparedStatement pstmt = connection.prepareStatement(getResultsSql)) {
                    int paramIndex = 1;
                    for (int docId : pagedDocIds) {
                        pstmt.setInt(paramIndex++, docId);
                    }
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            int docId = rs.getInt("id");
                            String title = rs.getString("title");
                            String url = rs.getString("url");
                            QueryIndex.DocumentData docData = docDataMap.get(docId);
                            
                            // Generate snippet
                            String snippet = docData != null ? 
                                generateSnippet(title, url, docData.getWordInfo(), new HashSet<>(queryResult.queryWords)) :
                                "No preview available for this result.";

                            results.add(new SearchResult(
                                url,
                                title,
                                docScores.getOrDefault(docId, 0.0),
                                snippet
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