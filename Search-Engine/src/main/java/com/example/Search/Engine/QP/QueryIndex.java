package com.example.Search.Engine.QP;

import com.example.Search.Engine.Data.DataBaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class QueryIndex {

    // DocumentData class implemented as an inner static class
    public static class DocumentData {
        private final int docId; // Unique identifier for the document
        private final Map<String, List<Double>> wordInfo; // Map of words to [frequency, IDF]

        // Constructor
        public DocumentData(int docId, Map<String, List<Double>> wordInfo) {
            this.docId = docId;
            this.wordInfo = wordInfo;
        }

        // Getter for docId
        public int getDocId() {
            return docId;
        }

        // Getter for wordInfo
        public Map<String, List<Double>> getWordInfo() {
            return wordInfo;
        }

        // Override toString for debugging purposes
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("DocumentData{");
            sb.append("docId=").append(docId);
            sb.append(", wordInfo={");
            for (Map.Entry<String, List<Double>> entry : wordInfo.entrySet()) {
                sb.append(entry.getKey()).append("=[frequency=").append(entry.getValue().get(0))
                        .append(", IDF=").append(entry.getValue().get(1)).append("], ");
            }
            if (!wordInfo.isEmpty()) {
                sb.setLength(sb.length() - 2); // Remove trailing ", "
            }
            sb.append("}}");
            return sb.toString();
        }
    }

    // Existing queryWords function updated to use List<Double>
    public static QueryResult queryWords(Set<String> words) throws SQLException {
        System.out.println("QueryIndex: Querying words: " + words);
        if (words == null || words.isEmpty()) {
            System.out.println("QueryIndex: No words provided, returning empty result");
            return new QueryResult(new ArrayList<>(), new HashMap<>());
        }

        List<DocumentData> documentDataList = new ArrayList<>();
        Map<Integer, Map<String, List<Double>>> docWordInfo = new HashMap<>();

        // Dynamically construct the SQL query for InvertedIndex
        String baseSql = "SELECT word, doc_id, IDF FROM InvertedIndex WHERE word IN (";
        String placeholders = String.join(",", Collections.nCopies(words.size(), "?"));
        String indexSql = baseSql + placeholders + ")";
        try (Connection conn = DataBaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(indexSql)) {
            // Set the word parameters
            int index = 1;
            for (String word : words) {
                pstmt.setString(index++, word);
            }
            // Execute the query and process results
            try (ResultSet rs = pstmt.executeQuery()) {
                int rowCount = 0;
                while (rs.next()) {
                    rowCount++;
                    String word = rs.getString("word");
                    int docId = rs.getInt("doc_id");
                    double idf = rs.getDouble("IDF");

                    // Initialize maps for this doc_id if not present
                    docWordInfo.computeIfAbsent(docId, k -> new HashMap<>());

                    // Store word info (frequency default to 1)
                    docWordInfo.get(docId).put(word, Arrays.asList(1.0, idf));
                }
                System.out.println("QueryIndex: Total rows from InvertedIndex: " + rowCount);
            }
        } catch (SQLException e) {
            System.err.println("QueryIndex: SQL error in InvertedIndex query: " + e.getMessage());
            throw e;
        }

        // Construct DocumentData objects with the new structure
        for (int docId : docWordInfo.keySet()) {
            DocumentData docData = new DocumentData(
                    docId,
                    docWordInfo.get(docId)
            );
            documentDataList.add(docData);
            System.out.println("QueryIndex: Created DocumentData for docId: " + docId);
        }
        System.out.println("QueryIndex: Returning " + documentDataList.size() + " documents");
        return new QueryResult(documentDataList, new HashMap<>());
    }

    // New function to handle phrase queries updated to use List<Double>
    public static QueryResult queryPhrase(String phrase) throws SQLException {
        System.out.println("QueryIndex: Querying phrase: \"" + phrase + "\"");
        if (phrase == null || phrase.trim().isEmpty()) {
            System.out.println("QueryIndex: No phrase provided, returning empty result");
            return new QueryResult(new ArrayList<>(), new HashMap<>());
        }

        // Split phrase into words and clean them
        String[] words = phrase.trim().toLowerCase().split("\\s+");
        if (words.length == 0) {
            System.out.println("QueryIndex: No valid words in phrase, returning empty result");
            return new QueryResult(new ArrayList<>(), new HashMap<>());
        }

        List<DocumentData> documentDataList = new ArrayList<>();
        //Map<Integer, Map<String, List<Double>>> docWordInfo = new HashMap<>();

        // Step 1: Query InvertedIndex to get documents containing all words
        String baseSql = "SELECT word, doc_id, IDF, id AS index_id FROM InvertedIndex WHERE word IN (";
        String placeholders = String.join(",", Collections.nCopies(words.length, "?"));
        String indexSql = baseSql + placeholders + ")";
        Map<Integer, List<Map<String, Object>>> docWordData = new HashMap<>();

        try (Connection conn = DataBaseManager.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(indexSql)) {
            // Set the word parameters
            int index = 1;
            for (String word : words) {
                pstmt.setString(index++, word);
            }
            // Execute the query
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String word = rs.getString("word");
                    int docId = rs.getInt("doc_id");
                    double idf = rs.getDouble("IDF");
                    int indexId = rs.getInt("index_id");

                    // Store word data for this document
                    docWordData.computeIfAbsent(docId, k -> new ArrayList<>());
                    Map<String, Object> wordData = new HashMap<>();
                    wordData.put("word", word);
                    wordData.put("index_id", indexId);
                    wordData.put("idf", idf);
                    docWordData.get(docId).add(wordData);
                }
            }
        } catch (SQLException e) {
            System.err.println("QueryIndex: SQL error in InvertedIndex query for phrase: " + e.getMessage());
            throw e;
        }

        // Step 2: Filter documents that contain all words
        List<Integer> candidateDocIds = docWordData.entrySet().stream()
                .filter(entry -> {
                    Set<String> foundWords = entry.getValue().stream()
                            .map(data -> (String) data.get("word"))
                            .collect(Collectors.toSet());
                    return foundWords.containsAll(Arrays.asList(words));
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        System.out.println("QueryIndex: Found " + candidateDocIds.size() + " candidate documents with all words");

        // Step 3: Verify phrase using WordPositions
        for (int docId : candidateDocIds) {
            List<Map<String, Object>> wordDataList = docWordData.get(docId);
            List<Integer> indexIds = wordDataList.stream()
                    .map(data -> (Integer) data.get("index_id"))
                    .collect(Collectors.toList());

            // Query WordPositions for positions
            String posSql = "SELECT index_id, position FROM WordPositions WHERE index_id IN ("
                    + String.join(",", Collections.nCopies(indexIds.size(), "?")) + ")";
            Map<Integer, List<Integer>> indexIdToPositions = new HashMap<>();
            try (Connection conn = DataBaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(posSql)) {
                int index = 1;
                for (int indexId : indexIds) {
                    pstmt.setInt(index++, indexId);
                }
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        int indexId = rs.getInt("index_id");
                        int position = rs.getInt("position");
                        indexIdToPositions.computeIfAbsent(indexId, k -> new ArrayList<>()).add(position);
                    }
                }
            } catch (SQLException e) {
                System.err.println("QueryIndex: SQL error in WordPositions query for docId " + docId + ": " + e.getMessage());
                throw e;
            }

            // Step 4: Check if words form the phrase (sequential positions)
            boolean phraseFound = false;
            for (int i = 0; i < words.length; i++) {
                String word = words[i];
                Integer indexId = wordDataList.stream()
                        .filter(data -> data.get("word").equals(word))
                        .map(data -> (Integer) data.get("index_id"))
                        .findFirst()
                        .orElse(null);
                if (indexId == null || !indexIdToPositions.containsKey(indexId)) {
                    phraseFound = false;
                    break;
                }
                List<Integer> positions = indexIdToPositions.get(indexId);
                if (i == 0) {
                    // For the first word, any position is fine
                    if (!positions.isEmpty()) {
                        phraseFound = true;
                    }
                } else {
                    // For subsequent words, check if there exists a position that is exactly one more than the previous word's position
                    String prevWord = words[i - 1];
                    Integer prevIndexId = wordDataList.stream()
                            .filter(data -> data.get("word").equals(prevWord))
                            .map(data -> (Integer) data.get("index_id"))
                            .findFirst()
                            .orElse(null);
                    List<Integer> prevPositions = indexIdToPositions.get(prevIndexId);
                    boolean foundSequential = false;
                    for (int prevPos : prevPositions) {
                        if (positions.contains(prevPos + 1)) {
                            foundSequential = true;
                            break;
                        }
                    }
                    phraseFound = foundSequential;
                    if (!phraseFound) {
                        break;
                    }
                }
            }

            if (phraseFound) {
                // Create wordInfo map for this document
                Map<String, List<Double>> wordInfo = new HashMap<>();
                for (Map<String, Object> wordData : wordDataList) {
                    String word = (String) wordData.get("word");
                    double idf = (double) wordData.get("idf");
                    wordInfo.put(word, Arrays.asList(1.0, idf)); // Default frequency is 1
                }

                // Create DocumentData for this document
                DocumentData docData = new DocumentData(
                        docId,
                        wordInfo
                );
                documentDataList.add(docData);
                System.out.println("QueryIndex: Phrase found in docId: " + docId);
            }
        }
        System.out.println("QueryIndex: Returning " + documentDataList.size() + " documents for phrase query");
        return new QueryResult(documentDataList, new HashMap<>());
    }

    // Helper class to return documents and IDF values
    public static class QueryResult {
        public final List<DocumentData> documents;
        public final Map<String, Double> idfMap;

        public QueryResult(List<DocumentData> documents, Map<String, Double> idfMap) {
            this.documents = documents;
            this.idfMap = idfMap;
        }
    }
}