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
        try {
            // Insert document and get its ID
            String insertDoc = "INSERT INTO DocumentMetaData (url, title) VALUES (?, ?)";
            long docId;
            try (PreparedStatement pstmt = connection.prepareStatement(insertDoc, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, url);
                pstmt.setString(2, title);
                pstmt.executeUpdate();
                
                try (ResultSet rs = pstmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        docId = rs.getLong(1);
                    } else {
                        throw new SQLException("Failed to get document ID");
                    }
                }
            }

            // Insert tokens and their positions
            String insertToken = """
                INSERT INTO InvertedIndex (word, doc_id, frequency, importance)
                VALUES (?, ?, ?, ?)
            """;
            
            try (PreparedStatement pstmt = connection.prepareStatement(insertToken, Statement.RETURN_GENERATED_KEYS)) {
                int count = 0;
                for (Tokenizer.Token token : tokens.values()) {
                    pstmt.setString(1, token.getWord());
                    pstmt.setLong(2, docId);
                    pstmt.setDouble(3, token.getCount());
                    pstmt.setDouble(4, getPositionWeight(token.getPosition()));
                    pstmt.addBatch();

                    if (++count % BATCH_SIZE == 0) {
                        pstmt.executeBatch();
                    }
                }
                if (count % BATCH_SIZE != 0) {
                    pstmt.executeBatch();
                }
            }

            // Update IDF values
            updateIDF();
            connection.commit();
            System.out.println("Document added successfully: " + url);
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                System.err.println("Error during rollback: " + rollbackError.getMessage());
            }
            System.err.println("Failed to add document: " + url + " - " + e.getMessage());
            throw new RuntimeException("Failed to add document", e);
        }
    }

    private void updateIDF() throws SQLException {
        String updateIDF = """
            UPDATE InvertedIndex
            SET IDF = (
                SELECT -LOG((COUNT(DISTINCT doc_id) * 1.0) / (
                    SELECT COUNT(*) FROM DocumentMetaData
                ))
                FROM InvertedIndex i2
                WHERE i2.word = InvertedIndex.word
            )
        """;
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(updateIDF);
        }
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