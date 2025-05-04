package com.example.Search.Engine.Indexer;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class SQLiteSearcher implements AutoCloseable {
    private final Connection connection;
    private final ThreadLocal<Connection> threadLocalConnection = new ThreadLocal<>();
    private final Object connectionLock = new Object();
    private static final double TITLE_WEIGHT = 5.0;    // Most important - page title
    private static final double H1_WEIGHT = 4.0;       // Main heading
    private static final double H2_WEIGHT = 3.0;       // Sub-heading
    private static final double H3_WEIGHT = 2.5;       // Sub-sub-heading
    private static final double H4_WEIGHT = 2.0;       // Minor heading
    private static final double H5_WEIGHT = 1.8;       // Minor heading
    private static final double H6_WEIGHT = 1.5;       // Minor heading
    private static final double CONTENT_WEIGHT = 1.0;  // Regular content

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
    }

    public SQLiteSearcher() throws SQLException {
        connection = getThreadConnection();
        initializeDatabase();
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
                FOREIGN KEY (index_id) REFERENCES InvertedIndex(id),
                UNIQUE(index_id, position)
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
        } catch (SQLException e) {
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    public void addDocuments(List<Map.Entry<String, Map<String, Tokenizer.Token>>> documents) {
        int batchSize = 1000;
        int totalBatches = (documents.size() + batchSize - 1) / batchSize;
        
        for (int batchNum = 0; batchNum < totalBatches; batchNum++) {
            int start = batchNum * batchSize;
            int end = Math.min(start + batchSize, documents.size());
            List<Map.Entry<String, Map<String, Tokenizer.Token>>> currentBatch = documents.subList(start, end);
            
            System.out.println("Processing batch " + (batchNum + 1) + " of " + totalBatches + " (" + currentBatch.size() + " documents)");
            
            try {
                // Process this batch
                processBatch(currentBatch);
                
                // Clear references to allow garbage collection
                currentBatch = null;
                System.gc(); // Suggest garbage collection
                
            } catch (SQLException e) {
                System.err.println("SQL Error in batch " + (batchNum + 1) + ": " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Error processing batch " + (batchNum + 1) + ": " + e.getMessage(), e);
            } catch (Exception e) {
                System.err.println("Unexpected error in batch " + (batchNum + 1) + ": " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Unexpected error in batch " + (batchNum + 1) + ": " + e.getMessage(), e);
            }
        }
        
        // Update IDF once after all batches are processed
        try {
            System.out.println("Updating IDF for all words...");
            updateIDF();
        } catch (SQLException e) {
            System.err.println("Error updating IDF: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Error updating IDF: " + e.getMessage(), e);
        }
    }
    
    private void processBatch(List<Map.Entry<String, Map<String, Tokenizer.Token>>> batch) throws SQLException {
        if (batch == null || batch.isEmpty()) {
            throw new SQLException("Batch is null or empty");
        }

        Map<String, Long> urlToDocId = new ConcurrentHashMap<>();
        int processors = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(processors);
        List<CompletableFuture<Void>> idFutures = new ArrayList<>();
        
        // Process document IDs
        String getDocId = "SELECT id FROM DocumentMetaData WHERE url = ?";
        String insertDoc = "INSERT INTO DocumentMetaData (url, title) VALUES (?, ?)";
        
        for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : batch) {
            if (doc == null || doc.getKey() == null) {
                throw new SQLException("Invalid document entry in batch");
            }

            final String url = doc.getKey();
            System.out.println("Processing URL: " + url);

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                Connection conn = null;
                try {
                    conn = getThreadConnection();
                    conn.setAutoCommit(false); // Ensure we're in transaction mode
                    
                    try (PreparedStatement getStmt = conn.prepareStatement(getDocId);
                         PreparedStatement insertStmt = conn.prepareStatement(insertDoc, Statement.RETURN_GENERATED_KEYS)) {
                        
                        // Try to get existing document ID
                        getStmt.setString(1, url);
                        try (ResultSet rs = getStmt.executeQuery()) {
                            if (rs.next()) {
                                long existingId = rs.getLong(1);
                                System.out.println("Found existing document ID " + existingId + " for URL: " + url);
                                urlToDocId.put(url, existingId);
                            } else {
                                // Insert new document
                                System.out.println("Inserting new document for URL: " + url);
                                insertStmt.setString(1, url);
                                insertStmt.setString(2, url);
                                int affectedRows = insertStmt.executeUpdate();
                                
                                if (affectedRows == 0) {
                                    throw new SQLException("Failed to insert document for URL: " + url);
                                }
                                
                                try (ResultSet generatedKeys = insertStmt.getGeneratedKeys()) {
                                    if (generatedKeys.next()) {
                                        long newId = generatedKeys.getLong(1);
                                        System.out.println("Created new document ID " + newId + " for URL: " + url);
                                        urlToDocId.put(url, newId);
                                    } else {
                                        throw new SQLException("Failed to get generated key for URL: " + url);
                                    }
                                }
                            }
                        }
                        conn.commit();
                    } catch (SQLException e) {
                        if (conn != null) {
                            conn.rollback();
                        }
                        throw e;
                    }
                } catch (SQLException e) {
                    System.err.println("SQL Error processing URL " + url + ": " + e.getMessage());
                    if (conn != null) {
                        try {
                            conn.rollback();
                        } catch (SQLException rollbackError) {
                            System.err.println("Rollback failed for URL " + url + ": " + rollbackError.getMessage());
                        }
                    }
                    throw new RuntimeException("Error processing document ID for URL " + url + ": " + e.getMessage(), e);
                } finally {
                    if (conn != null) {
                        try {
                            conn.close();
                        } catch (SQLException e) {
                            System.err.println("Error closing connection for URL " + url + ": " + e.getMessage());
                        }
                    }
                }
            }, executor);
            idFutures.add(future);
        }
        
        // Wait for all document IDs to be processed
        try {
            CompletableFuture.allOf(idFutures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            executor.shutdownNow();
            throw new SQLException("Error waiting for document ID processing: " + e.getMessage(), e);
        }
        
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for document ID processing", e);
        }

        // Verify all documents have IDs
        for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : batch) {
            String url = doc.getKey();
            if (!urlToDocId.containsKey(url)) {
                System.err.println("Failed to get or create document ID for URL: " + url);
                System.err.println("Current URL to ID mapping: " + urlToDocId);
                throw new SQLException("Failed to get or create document ID for URL: " + url);
            }
        }
        
        // Process tokens and positions in smaller sub-batches
        int subBatchSize = 100; // Process 100 documents at a time for tokens and positions
        for (int i = 0; i < batch.size(); i += subBatchSize) {
            int end = Math.min(i + subBatchSize, batch.size());
            List<Map.Entry<String, Map<String, Tokenizer.Token>>> subBatch = batch.subList(i, end);
            
            // Process tokens
            String insertToken = """
                INSERT INTO InvertedIndex (word, doc_id, frequency, importance)
                VALUES (?, ?, ?, ?)
            """;
            
            Map<String, Long> wordToIndexId = new HashMap<>();
            try (PreparedStatement pstmt = getThreadConnection().prepareStatement(insertToken, Statement.RETURN_GENERATED_KEYS)) {
                for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : subBatch) {
                    Long docId = urlToDocId.get(doc.getKey());
                    if (docId == null) {
                        throw new SQLException("Document ID is null for URL: " + doc.getKey());
                    }
                    
                    for (Tokenizer.Token token : doc.getValue().values()) {
                        pstmt.setString(1, token.getWord());
                        pstmt.setLong(2, docId);
                        pstmt.setDouble(3, token.getCount());
                        pstmt.setDouble(4, getPositionWeight(token.getPosition()));
                        pstmt.executeUpdate();
                        
                        try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                            if (generatedKeys.next()) {
                                wordToIndexId.put(token.getWord() + "_" + docId, generatedKeys.getLong(1));
                            } else {
                                throw new SQLException("Failed to get generated key for word: " + token.getWord());
                            }
                        }
                    }
                }
            }

            // Process positions in even smaller batches
            String insertPosition = """
                INSERT OR IGNORE INTO WordPositions (index_id, position)
                VALUES (?, ?)
            """;
            
            try (PreparedStatement pstmt = getThreadConnection().prepareStatement(insertPosition)) {
                int positionBatchSize = 0;
                for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : subBatch) {
                    Long docId = urlToDocId.get(doc.getKey());
                    if (docId == null) {
                        throw new SQLException("Document ID is null for URL: " + doc.getKey());
                    }
                    
                    for (Tokenizer.Token token : doc.getValue().values()) {
                        Long indexId = wordToIndexId.get(token.getWord() + "_" + docId);
                        if (indexId == null) {
                            throw new SQLException("Index ID is null for word: " + token.getWord());
                        }
                        
                        for (Integer position : token.getPositions()) {
                            pstmt.setLong(1, indexId);
                            pstmt.setInt(2, position);
                            pstmt.addBatch();
                            positionBatchSize++;
                            
                            if (positionBatchSize >= 1000) {
                                pstmt.executeBatch();
                                positionBatchSize = 0;
                            }
                        }
                    }
                }
                if (positionBatchSize > 0) {
                    pstmt.executeBatch();
                }
            }

            // Clear references for this sub-batch
            wordToIndexId.clear();
            subBatch = null;
            System.gc();
        }

        getThreadConnection().commit();
        
        // Clear all references
        urlToDocId.clear();
        batch = null;
        System.gc();
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
            
            int batchSize = 10000; // Increased batch size for IDF updates
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

    private double getPositionWeight(String position) {
        return switch (position) {
            case "title" -> TITLE_WEIGHT;
            case "h1" -> H1_WEIGHT;
            case "h2" -> H2_WEIGHT;
            case "h3" -> H3_WEIGHT;
            case "h4" -> H4_WEIGHT;
            case "h5" -> H5_WEIGHT;
            case "h6" -> H6_WEIGHT;
            default -> CONTENT_WEIGHT;
        };
    }

    public List<Map.Entry<String, String>> getAllDocuments() throws SQLException {
        String sql = "SELECT url, html FROM DocumentMetaData";
        List<Map.Entry<String, String>> results = new ArrayList<>();
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                String url = rs.getString("url");
                String html = rs.getString("html");
                results.add(Map.entry(url, html));
            }
        }
        
        return results;
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

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("Error closing connection: " + e.getMessage());
        }
    }
} 