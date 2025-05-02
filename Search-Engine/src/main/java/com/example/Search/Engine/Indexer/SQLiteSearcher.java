package com.example.Search.Engine.Indexer;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class SQLiteSearcher implements AutoCloseable {
    private final Connection connection;
    private static final int BATCH_SIZE = 1000;
    private static final double TITLE_WEIGHT = 3.0;
    private static final double H1_WEIGHT = 2.0;
    private static final double H2_WEIGHT = 1.5;
    private static final double CONTENT_WEIGHT = 1.0;

    private final ThreadLocal<Connection> threadLocalConnection = new ThreadLocal<>();
    private final Object connectionLock = new Object();

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
        //String projectRoot = System.getProperty("user.dir");
        String dbPath = "jdbc:sqlite:data/search_index.db";
        
        connection = DriverManager.getConnection(dbPath);
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
            Map<String, Long> urlToDocId = new ConcurrentHashMap<>();
            String getDocId = "SELECT id FROM DocumentMetaData WHERE url = ?";
            String insertDoc = "INSERT INTO DocumentMetaData (url, title) VALUES (?, ?)";
            
            // Process documents in parallel to get/create IDs
            int processors = Runtime.getRuntime().availableProcessors();
            ExecutorService executor = Executors.newFixedThreadPool(processors);
            List<CompletableFuture<Void>> idFutures = new ArrayList<>();
            
            for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : documents) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        String url = doc.getKey();
                        try (PreparedStatement getStmt = getThreadConnection().prepareStatement(getDocId);
                             PreparedStatement insertStmt = getThreadConnection().prepareStatement(insertDoc, Statement.RETURN_GENERATED_KEYS)) {
                            
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
                            getThreadConnection().commit();
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException("Error processing document ID: " + e.getMessage(), e);
                    }
                }, executor);
                idFutures.add(future);
            }
            
            // Wait for all ID processing to complete
            CompletableFuture.allOf(idFutures.toArray(new CompletableFuture[0])).join();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for document ID processing", e);
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
            try (PreparedStatement pstmt = getThreadConnection().prepareStatement(insertToken)) {
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
                getThreadConnection().commit();
            }

            // Insert word positions in bulk
            String insertPosition = """
                INSERT INTO WordPositions (index_id, position)
                VALUES (?, ?)
            """;
            
            int totalPositions = 0;
            try (PreparedStatement pstmt = getThreadConnection().prepareStatement(insertPosition)) {
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
                getThreadConnection().commit();
            }

            // Update IDF values once for all documents
            updateIDF();
            getThreadConnection().commit();
            System.out.println("Successfully added " + documents.size() + " documents in bulk");
            
        } catch (SQLException e) {
            try {
                getThreadConnection().rollback();
            } catch (SQLException rollbackError) {
                System.err.println("Error during rollback: " + rollbackError.getMessage());
            }
            System.err.println("Failed to add documents in bulk: " + e.getMessage());
            throw new RuntimeException("Failed to add documents in bulk", e);
        }
    }

    public void updateIDF() throws SQLException {
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

        // Get all unique words and their document counts in a single query
        String getWordCounts = """
            SELECT word, COUNT(DISTINCT doc_id) as doc_count
            FROM InvertedIndex
            GROUP BY word
        """;

        // Update IDF values in batches
        String updateIDF = """
            UPDATE InvertedIndex
            SET IDF = -LOG(? * 1.0 / ?)
            WHERE word = ?
        """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(getWordCounts);
             PreparedStatement updateStmt = connection.prepareStatement(updateIDF)) {
            
            int batchSize = 1000; // Process 1000 words at a time
            int count = 0;
            int totalUpdated = 0;
            
            while (rs.next()) {
                String word = rs.getString("word");
                int docCount = rs.getInt("doc_count");
                
                updateStmt.setInt(1, docCount);
                updateStmt.setInt(2, totalDocs);
                updateStmt.setString(3, word);
                updateStmt.addBatch();
                count++;
                
                if (count % batchSize == 0) {
                    int[] results = updateStmt.executeBatch();
                    totalUpdated += results.length;
                    connection.commit();
                    System.out.println("Updated " + totalUpdated + " words so far...");
                }
            }
            
            // Process remaining batch
            if (count % batchSize != 0) {
                int[] results = updateStmt.executeBatch();
                totalUpdated += results.length;
                connection.commit();
            }
            
            System.out.println("Updated IDF for " + totalUpdated + " words in total");
        }

        System.out.println("IDF update completed successfully");
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

    public Connection getConnection() {
        return connection;
    }

    private Connection getThreadConnection() throws SQLException {
        Connection conn = threadLocalConnection.get();
        if (conn == null || conn.isClosed()) {
            synchronized (connectionLock) {
                conn = DriverManager.getConnection("jdbc:sqlite:data/search_index.db");
                conn.setAutoCommit(false);
                threadLocalConnection.set(conn);
            }
        }
        return conn;
    }

    public List<Map.Entry<String, String>> getAllDocuments() throws SQLException {
        String sql = "SELECT id, url, title, html FROM DocumentMetaData";
        List<Map.Entry<String, String>> results = new ArrayList<>();
        
        try (Statement stmt = getThreadConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                String url = rs.getString("url");
                String html = rs.getString("html");
                results.add(Map.entry(url, html));
            }
        }
        
        return results;
    }

    @Override
    public void close() {
        try {
            Connection conn = threadLocalConnection.get();
            if (conn != null && !conn.isClosed()) {
                conn.close();
                threadLocalConnection.remove();
            }
        } catch (SQLException e) {
            System.err.println("Error closing connection: " + e.getMessage());
        }
    }
} 