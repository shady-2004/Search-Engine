package com.example.Search.Engine.Indexer;

import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SQLiteSearcher implements AutoCloseable {
    private final Connection connection;
    private static final int BATCH_SIZE = 1000;
    private static final double TITLE_WEIGHT = 3.0;
    private static final double H1_WEIGHT = 2.0;
    private static final double H2_WEIGHT = 1.5;
    private static final double CONTENT_WEIGHT = 1.0;

    public static class SearchResult {
        private final String url;
        private final String title;
        private final double score;

        public SearchResult(String url, String title, double score) {
            this.url = url;
            this.title = title;
            this.score = score;
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

        @Override
        public String toString() {
            return "SearchResult{" +
                "url='" + url + '\'' +
                ", title='" + title + '\'' +
                ", score=" + score +
                '}';
        }
    }

    public SQLiteSearcher() throws SQLException {
        String projectRoot = System.getProperty("user.dir");
        String dbPath = projectRoot + "/search_index.db";
        
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        connection.setAutoCommit(false);
        initializeDatabase();
        System.out.println("Connected to database: " + dbPath);
    }

    private void initializeDatabase() {
        String createDocumentMetaDataTable = """
            CREATE TABLE IF NOT EXISTS DocumentMetaData (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                title TEXT,
                last_crawled_date TEXT DEFAULT CURRENT_TIMESTAMP,
                html TEXT
            )
        """;

        String createInvertedIndexTable = """
            CREATE TABLE IF NOT EXISTS InvertedIndex (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL,
                doc_id INTEGER NOT NULL,
                frequency REAL,
                importance REAL,
                IDF REAL,
                FOREIGN KEY (doc_id) REFERENCES DocumentMetaData(id)
            )
        """;

        String createWordPositionsTable = """
            CREATE TABLE IF NOT EXISTS WordPositions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                index_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                FOREIGN KEY (index_id) REFERENCES InvertedIndex(id)
            )
        """;

        String createWordIndex = "CREATE INDEX IF NOT EXISTS idx_inverted_word ON InvertedIndex(word)";
        String createDocIndex = "CREATE INDEX IF NOT EXISTS idx_inverted_doc ON InvertedIndex(doc_id)";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createDocumentMetaDataTable);
            stmt.execute(createInvertedIndexTable);
            stmt.execute(createWordPositionsTable);
            stmt.execute(createWordIndex);
            stmt.execute(createDocIndex);
            connection.commit();
            System.out.println("Database schema initialized successfully");
        } catch (SQLException e) {
            System.err.println("Failed to initialize database: " + e.getMessage());
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    public void addDocument(String url, String title, Map<String, Tokenizer.Token> tokens) {
        addDocuments(List.of(Map.entry(url, tokens)));
    }

    public void addDocuments(List<Map.Entry<String, Map<String, Tokenizer.Token>>> documents) {
        try {
            // First, get or create document IDs
            Map<String, Long> urlToDocId = new HashMap<>();
            String getDocId = "SELECT id FROM DocumentMetaData WHERE url = ?";
            String insertDoc = "INSERT INTO DocumentMetaData (url, title) VALUES (?, ?)";
            
            try (PreparedStatement getStmt = connection.prepareStatement(getDocId);
                 PreparedStatement insertStmt = connection.prepareStatement(insertDoc, Statement.RETURN_GENERATED_KEYS)) {
                
                for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : documents) {
                    String url = doc.getKey();
                    
                    // First try to get existing document ID
                    getStmt.setString(1, url);
                    try (ResultSet rs = getStmt.executeQuery()) {
                        if (rs.next()) {
                            // Document exists, get its ID
                            long docId = rs.getLong(1);
                            urlToDocId.put(url, docId);
                            System.out.println("Found existing document ID " + docId + " for URL: " + url);
                        } else {
                            // Document doesn't exist, insert it
                            insertStmt.setString(1, url);
                            insertStmt.setString(2, url); // Using URL as title for now
                            insertStmt.executeUpdate();
                            
                            try (ResultSet generatedKeys = insertStmt.getGeneratedKeys()) {
                                if (generatedKeys.next()) {
                                    long docId = generatedKeys.getLong(1);
                                    urlToDocId.put(url, docId);
                                    System.out.println("Created new document ID " + docId + " for URL: " + url);
                                }
                            }
                        }
                    }
                }
            }

            // Verify all documents have IDs
            for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : documents) {
                if (!urlToDocId.containsKey(doc.getKey())) {
                    System.err.println("Error: Failed to get or create document ID for URL: " + doc.getKey());
                    return; // Exit if any document failed
                }
            }

            // Count total tokens
            int totalTokens = 0;
            for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : documents) {
                Long docId = urlToDocId.get(doc.getKey());
                int docTokens = doc.getValue().size();
                totalTokens += docTokens;
                System.out.println("Document " + docId + " has " + docTokens + " unique tokens");
            }
            System.out.println("Total unique tokens across all documents: " + totalTokens);

            // Insert all tokens in bulk
            String insertToken = """
                INSERT INTO InvertedIndex (word, doc_id, frequency, importance)
                VALUES (?, ?, ?, ?)
            """;
            
            int insertedTokens = 0;
            try (PreparedStatement pstmt = connection.prepareStatement(insertToken)) {
                for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : documents) {
                    Long docId = urlToDocId.get(doc.getKey());
                    
                    for (Tokenizer.Token token : doc.getValue().values()) {
                        pstmt.setString(1, token.getWord());
                        pstmt.setLong(2, docId);
                        pstmt.setDouble(3, token.getCount());
                        pstmt.setDouble(4, getPositionWeight(token.getPosition()));
                        pstmt.addBatch();
                        insertedTokens++;
                    }
                }
                
                // Execute the batch and commit
                int[] results = pstmt.executeBatch();
                System.out.println("Executed batch of " + results.length + " tokens (expected: " + insertedTokens + ")");
                connection.commit();
            }

            // Insert word positions in bulk
            String insertPosition = """
                INSERT INTO WordPositions (index_id, position)
                VALUES (?, ?)
            """;
            
            int totalPositions = 0;
            try (PreparedStatement pstmt = connection.prepareStatement(insertPosition)) {
                for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : documents) {
                    Long docId = urlToDocId.get(doc.getKey());
                    
                    for (Tokenizer.Token token : doc.getValue().values()) {
                        for (Integer position : token.getPositions()) {
                            pstmt.setLong(1, docId); // Using docId as index_id for now
                            pstmt.setInt(2, position);
                            pstmt.addBatch();
                            totalPositions++;
                        }
                    }
                }
                
                // Execute the batch and commit
                int[] results = pstmt.executeBatch();
                System.out.println("Executed batch of " + results.length + " positions (expected: " + totalPositions + ")");
                connection.commit();
            }

            // Update IDF values once for all documents
            updateIDF();
            connection.commit();
            System.out.println("Successfully added " + documents.size() + " documents in bulk");
            
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                System.err.println("Error during rollback: " + rollbackError.getMessage());
            }
            System.err.println("Failed to add documents in bulk: " + e.getMessage());
            throw new RuntimeException("Failed to add documents in bulk", e);
        }
    }

    private void updateIDF() throws SQLException {
        System.out.println("Starting IDF update...");
        
        // First, get total number of documents
        String countDocs = "SELECT COUNT(*) FROM DocumentMetaData";
        int totalDocs;
        try (Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery(countDocs)) {
            rs.next();
            totalDocs = rs.getInt(1);
            System.out.println("Total documents: " + totalDocs);
        }

        // Create a temporary table to store word frequencies
        try (PreparedStatement pstmt = connection.prepareStatement("""
                CREATE TEMPORARY TABLE IF NOT EXISTS WordFrequencies AS
                SELECT word, COUNT(DISTINCT doc_id) as doc_count
                FROM InvertedIndex
                GROUP BY word
                HAVING doc_count < ?  -- Filter out words that appear in all documents
            """)) {
            pstmt.setInt(1, totalDocs);
            pstmt.execute();
            System.out.println("Created temporary table for word frequencies");
        }

        // Update IDF values in batches
        String updateIDF = """
            UPDATE InvertedIndex
            SET IDF = (
                SELECT -LOG(doc_count * 1.0 / ?)
                FROM WordFrequencies
                WHERE WordFrequencies.word = InvertedIndex.word
            )
            WHERE id BETWEEN ? AND ?
        """;

        // Get the range of IDs to process
        String getRange = "SELECT MIN(id), MAX(id) FROM InvertedIndex";
        long minId, maxId;
        try (Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery(getRange)) {
            rs.next();
            minId = rs.getLong(1);
            maxId = rs.getLong(2);
            System.out.println("Processing InvertedIndex IDs from " + minId + " to " + maxId);
        }

        // Process in larger batches
        final int BATCH_SIZE = 500000;
        try (PreparedStatement pstmt = connection.prepareStatement(updateIDF)) {
            pstmt.setInt(1, totalDocs);
            
            for (long startId = minId; startId <= maxId; startId += BATCH_SIZE) {
                long endId = Math.min(startId + BATCH_SIZE - 1, maxId);
                pstmt.setLong(2, startId);
                pstmt.setLong(3, endId);
                
                int updated = pstmt.executeUpdate();
                System.out.println("Updated IDF for " + updated + " records (IDs " + startId + " to " + endId + ")");
                connection.commit();
            }
        }

        // Set IDF to 0 for words that appear in all documents
        try (PreparedStatement pstmt = connection.prepareStatement("""
                UPDATE InvertedIndex
                SET IDF = 0
                WHERE word IN (
                    SELECT word
                    FROM InvertedIndex
                    GROUP BY word
                    HAVING COUNT(DISTINCT doc_id) = ?
                )
            """)) {
            pstmt.setInt(1, totalDocs);
            int removed = pstmt.executeUpdate();
            System.out.println("Set IDF to 0 for " + removed + " words that appear in all documents");
            connection.commit();
        }

        // Clean up temporary table
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS WordFrequencies");
        }
        
        System.out.println("IDF update completed");
    }

    public List<SearchResult> search(String query, Tokenizer tokenizer) {
        List<String> queryTokens = tokenizer.tokenizeString(query, true);
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        String sql = """
            WITH token_scores AS (
                SELECT 
                    d.url,
                    d.title,
                    i.word,
                    i.frequency * i.importance * i.IDF as weighted_score
                FROM DocumentMetaData d
                JOIN InvertedIndex i ON d.id = i.doc_id
                WHERE i.word IN (%s)
            )
            SELECT url, title, SUM(weighted_score) as score
            FROM token_scores
            GROUP BY url, title
            ORDER BY score DESC
            LIMIT 10
        """;

        String placeholders = String.join(",", Collections.nCopies(queryTokens.size(), "?"));
        sql = String.format(sql, placeholders);

        List<SearchResult> results = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            int paramIndex = 1;
            for (String token : queryTokens) {
                pstmt.setString(paramIndex++, token);
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new SearchResult(
                        rs.getString("url"),
                        rs.getString("title"),
                        rs.getDouble("score")
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("Search query failed: " + e.getMessage());
            throw new RuntimeException("Search failed", e);
        }

        return results;
    }

    private double getPositionWeight(String position) {
        return switch (position) {
            case "title" -> TITLE_WEIGHT;
            case "h1" -> H1_WEIGHT;
            case "h2" -> H2_WEIGHT;
            default -> CONTENT_WEIGHT;
        };
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                System.out.println("Database connection closed");
            } catch (SQLException e) {
                System.err.println("Error closing database connection: " + e.getMessage());
            }
        }
    }
} 