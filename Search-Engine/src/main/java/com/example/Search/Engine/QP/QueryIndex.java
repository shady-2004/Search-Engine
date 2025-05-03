package com.example.Search.Engine.QP;

import com.example.Search.Engine.Data.DataBaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class QueryIndex {

    // Existing queryWords function remains unchanged
    public static QueryResult queryWords(Set<String> words) throws SQLException {
        System.out.println("QueryIndex: Querying words: " + words);

        if (words == null || words.isEmpty()) {
            System.out.println("QueryIndex: No words provided, returning empty result");
            return new QueryResult(new ArrayList<>(), new HashMap<>());
        }

        List<DataBaseManager.DocumentData> documentDataList = new ArrayList<>();
        Map<Integer, Map<String, Integer>> docWordFrequencies = new HashMap<>();
        Map<Integer, Map<String, Double>> docWordIdfs = new HashMap<>();
        Map<String, Double> idfMap = new HashMap<>();

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
                    docWordFrequencies.computeIfAbsent(docId, k -> new HashMap<>());
                    docWordIdfs.computeIfAbsent(docId, k -> new HashMap<>());

                    // Store word frequency (default to 1) and IDF
                    docWordFrequencies.get(docId).put(word, 1);
                    docWordIdfs.get(docId).put(word, idf);
                    idfMap.put(word, idf);
                }
                System.out.println("QueryIndex: Total rows from InvertedIndex: " + rowCount);
            }
        } catch (SQLException e) {
            System.err.println("QueryIndex: SQL error in InvertedIndex query: " + e.getMessage());
            throw e;
        }

        // Construct DocumentData objects without DocumentMetaData
        for (int docId : docWordFrequencies.keySet()) {
            // Use default values for length and pageRank since not fetched
            int length = 0; // Default length
            double pageRank = 0.0; // Default pageRank

            // Create DocumentData object
            DataBaseManager.DocumentData docData = new DataBaseManager.DocumentData(
                    docId,
                    docWordFrequencies.get(docId),
                    length,
                    pageRank
            );
            documentDataList.add(docData);
            System.out.println("QueryIndex: Created DocumentData for docId: " + docId);
        }

        System.out.println("QueryIndex: Returning " + documentDataList.size() + " documents");
        return new QueryResult(documentDataList, idfMap);
    }

    // New function to handle phrase queries
    public static QueryResult queryPhrase(String phrase) throws SQLException {
        System.out.println("QueryIndex: Querying phrase: \"" + phrase + "\"");

        if (phrase == null || phrase.trim().isEmpty()) {
            System.out.println("QueryIndex: No phrase provided, returning empty result");
            return new QueryResult(new ArrayList<>(), new HashMap<>());
        }

        // Split phrase into words and clean them
        String[] words = phrase.trim().toLowerCase().split("\\s+");
        // print word
        if (words.length == 0) {
            System.out.println("QueryIndex: No valid words in phrase, returning empty result");
            return new QueryResult(new ArrayList<>(), new HashMap<>());
        }

        List<DataBaseManager.DocumentData> documentDataList = new ArrayList<>();
        Map<Integer, Map<String, Integer>> docWordFrequencies = new HashMap<>();
        Map<String, Double> idfMap = new HashMap<>();

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

                    // Store IDF for the word
                    idfMap.put(word, idf);
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
                int wordIndex = i;
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
                // Create DocumentData for this document
                Map<String, Integer> wordFrequencies = new HashMap<>();
                for (Map<String, Object> wordData : wordDataList) {
                    String word = (String) wordData.get("word");
                    wordFrequencies.put(word, 1); // Default frequency
                }

                int length = 0; // Default length
                double pageRank = 0.0; // Default pageRank
                DataBaseManager.DocumentData docData = new DataBaseManager.DocumentData(
                        docId,
                        wordFrequencies,
                        length,
                        pageRank
                );
                documentDataList.add(docData);
                System.out.println("QueryIndex: Phrase found in docId: " + docId);
            }
        }

        System.out.println("QueryIndex: Returning " + documentDataList.size() + " documents for phrase query");
        return new QueryResult(documentDataList, idfMap);
    }

    // Helper class to return documents and IDF values
    public static class QueryResult {
        public final List<DataBaseManager.DocumentData> documents;
        public final Map<String, Double> idfMap;

        public QueryResult(List<DataBaseManager.DocumentData> documents, Map<String, Double> idfMap) {
            this.documents = documents;
            this.idfMap = idfMap;
        }
    }
}