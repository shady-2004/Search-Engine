package com.example.Search.Engine.Indexer;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class SQLiteSearcher implements AutoCloseable {
    private final Connection connection;
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
    }

    public SQLiteSearcher() throws SQLException {
        String dbPath = "jdbc:sqlite:data/search_index.db";
        connection = DriverManager.getConnection(dbPath);
        connection.setAutoCommit(false);
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
        } catch (SQLException e) {
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    public void addDocuments(List<Map.Entry<String, Map<String, Tokenizer.Token>>> documents) {
        try {
            Map<String, Long> urlToDocId = new ConcurrentHashMap<>();
            String getDocId = "SELECT id FROM DocumentMetaData WHERE url = ?";
            String insertDoc = "INSERT INTO DocumentMetaData (url, title) VALUES (?, ?)";
            
            int processors = Runtime.getRuntime().availableProcessors();
            ExecutorService executor = Executors.newFixedThreadPool(processors);
            List<CompletableFuture<Void>> idFutures = new ArrayList<>();
            
            for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : documents) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        String url = doc.getKey();
                        try (PreparedStatement getStmt = connection.prepareStatement(getDocId);
                             PreparedStatement insertStmt = connection.prepareStatement(insertDoc, Statement.RETURN_GENERATED_KEYS)) {
                            
                            getStmt.setString(1, url);
                            try (ResultSet rs = getStmt.executeQuery()) {
                                if (rs.next()) {
                                    urlToDocId.put(url, rs.getLong(1));
                                } else {
                                    insertStmt.setString(1, url);
                                    insertStmt.setString(2, url);
                                    insertStmt.executeUpdate();
                                    
                                    try (ResultSet generatedKeys = insertStmt.getGeneratedKeys()) {
                                        if (generatedKeys.next()) {
                                            urlToDocId.put(url, generatedKeys.getLong(1));
                                        }
                                    }
                                }
                            }
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException("Error processing document ID: " + e.getMessage(), e);
                    }
                }, executor);
                idFutures.add(future);
            }
            
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

            for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : documents) {
                if (!urlToDocId.containsKey(doc.getKey())) {
                    throw new RuntimeException("Failed to get or create document ID for URL: " + doc.getKey());
                }
            }

            String insertToken = """
                INSERT INTO InvertedIndex (word, doc_id, frequency, importance)
                VALUES (?, ?, ?, ?)
            """;
            
            try (PreparedStatement pstmt = connection.prepareStatement(insertToken)) {
                for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : documents) {
                    Long docId = urlToDocId.get(doc.getKey());
                    
                    for (Tokenizer.Token token : doc.getValue().values()) {
                        pstmt.setString(1, token.getWord());
                        pstmt.setLong(2, docId);
                        pstmt.setDouble(3, token.getCount());
                        pstmt.setDouble(4, getPositionWeight(token.getPosition()));
                        pstmt.addBatch();
                    }
                }
                
                pstmt.executeBatch();
            }

            String insertPosition = """
                INSERT INTO WordPositions (index_id, position)
                VALUES (?, ?)
            """;
            
            try (PreparedStatement pstmt = connection.prepareStatement(insertPosition)) {
                for (Map.Entry<String, Map<String, Tokenizer.Token>> doc : documents) {
                    Long docId = urlToDocId.get(doc.getKey());
                    
                    for (Tokenizer.Token token : doc.getValue().values()) {
                        for (Integer position : token.getPositions()) {
                            pstmt.setLong(1, docId);
                            pstmt.setInt(2, position);
                            pstmt.addBatch();
                        }
                    }
                }
                
                pstmt.executeBatch();
            }

            connection.commit();
            updateIDF();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                throw new RuntimeException("Error during rollback: " + rollbackError.getMessage(), rollbackError);
            }
            throw new RuntimeException("Error adding documents to database", e);
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

    private double getPositionWeight(String position) {
        return switch (position) {
            case "title" -> TITLE_WEIGHT;
            case "h1" -> H1_WEIGHT;
            case "h2" -> H2_WEIGHT;
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

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("Error closing connection: " + e.getMessage());
        }
    }
} 