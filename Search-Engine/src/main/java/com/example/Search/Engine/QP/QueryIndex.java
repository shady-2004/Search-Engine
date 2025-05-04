package com.example.Search.Engine.QP;

import com.example.Search.Engine.Data.DataBaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class QueryIndex {

    public static class DocumentData {
        private final int docId;
        private final Map<String, List<Double>> wordInfo;
        public double pageRank;

        public DocumentData(int docId, Map<String, List<Double>> wordInfo) {
            this.docId = docId;
            this.wordInfo = wordInfo;
            this.pageRank = 0.0; // Default to 0.0, assuming set by Ranker
        }

        public int getDocId() {
            return docId;
        }

        public Map<String, List<Double>> getWordInfo() {
            return wordInfo;
        }

        public double getPageRank() {
            return pageRank;
        }

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
                sb.setLength(sb.length() - 2);
            }
            sb.append("}, pageRank=").append(pageRank);
            sb.append("}");
            return sb.toString();
        }
    }

    public static class QueryResult {
        public final List<DocumentData> documents;
        public final List<String> queryWords;
        public final Map<String, Double> idfMap;

        public QueryResult(List<DocumentData> documents, List<String> queryWords, Map<String, Double> idfMap) {
            this.documents = documents;
            this.queryWords = queryWords;
            this.idfMap = idfMap;
        }
    }

    private static class WordData {

        final String word; // Stemmed word
        final String originalWord; // Original query word
        final int indexId;
        final double idf;

        WordData(String word, String originalWord, int indexId, double idf) {
            this.word = word;
            this.originalWord = originalWord;

            this.indexId = indexId;
            this.idf = idf;
        }

        @Override
        public String toString() {

            return "WordData{word=" + word + ", originalWord=" + originalWord + ", indexId=" + indexId + ", idf=" + idf + "}";

        }
    }

    private static final Map<String, QueryResult> phraseQueryCache = new HashMap<>();

    public static QueryResult queryWords(Set<String> words, Map<String, String> stemToOriginal) throws SQLException {
        System.out.println("QueryIndex: Querying words: " + words);
        if (words == null || words.isEmpty()) {
            System.out.println("QueryIndex: No words provided, returning empty result");
            return new QueryResult(new ArrayList<>(), new ArrayList<>(), new HashMap<>());
        }

        List<DocumentData> documentDataList = new ArrayList<>();
        Map<Integer, Map<String, List<Double>>> docWordInfo = new HashMap<>();
        List<String> queryWords = new ArrayList<>(stemToOriginal.values());

        String baseSql = "SELECT word, doc_id, IDF FROM InvertedIndex WHERE word IN (";
        String placeholders = String.join(",", Collections.nCopies(words.size(), "?"));
        String indexSql = baseSql + placeholders + ")";
        try (Connection conn = DataBaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(indexSql)) {
            int index = 1;
            for (String word : words) {
                pstmt.setString(index++, word);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                int rowCount = 0;
                while (rs.next()) {
                    rowCount++;
                    String stemmedWord = rs.getString("word");
                    int docId = rs.getInt("doc_id");
                    double idf = rs.getDouble("IDF");


                    if (stemmedWord == null) {
                        System.err.println("QueryIndex: Null word in ResultSet for docId: " + docId);
                        continue;
                    }
                    String originalWord = stemToOriginal.getOrDefault(stemmedWord, stemmedWord);
                    if (originalWord == null) {
                        System.err.println("QueryIndex: No original word for stem: " + stemmedWord);
                        continue;
                    }

                    docWordInfo.computeIfAbsent(docId, k -> new HashMap<>());
                    docWordInfo.get(docId).put(originalWord, Arrays.asList(1.0, idf));
                    System.out.println("QueryIndex: Added wordInfo for docId: " + docId + ", word: " + originalWord);

                }
                System.out.println("QueryIndex: Total rows from InvertedIndex: " + rowCount);
            }
        } catch (SQLException e) {
            System.err.println("QueryIndex: SQL error in InvertedIndex query: " + e.getMessage());
            throw e;
        }

        for (int docId : docWordInfo.keySet()) {
            DocumentData docData = new DocumentData(
                    docId,
                    docWordInfo.get(docId)
            );
            documentDataList.add(docData);
            System.out.println("QueryIndex: Created DocumentData for docId: " + docId);
        }
        System.out.println("QueryIndex: Returning " + documentDataList.size() + " documents");
        return new QueryResult(documentDataList, queryWords, new HashMap<>());
    }

    public static QueryResult queryPhrase(Set<String> words, Set<String> originalWords) throws SQLException {
        long startTime = System.nanoTime();
        System.out.println("QueryIndex: Querying phrase with words: " + words);
        if (words == null || words.isEmpty()) {
            System.out.println("QueryIndex: No words provided, returning empty result");
            return new QueryResult(new ArrayList<>(), new ArrayList<>(originalWords), new HashMap<>());
        }

        // Check cache
        String cacheKey = words.toString();
        if (phraseQueryCache.containsKey(cacheKey)) {
            System.out.println("QueryIndex: Cache hit for phrase query: " + cacheKey);
            QueryResult cachedResult = phraseQueryCache.get(cacheKey);
            long endTime = System.nanoTime();
            System.out.println("QueryIndex: Phrase query took: " + (endTime - startTime) / 1_000_000.0 + " ms");
            return new QueryResult(
                    new ArrayList<>(cachedResult.documents),
                    new ArrayList<>(cachedResult.queryWords),
                    new HashMap<>(cachedResult.idfMap)
            );

        }

        List<String> wordList = new ArrayList<>(words);
        List<DocumentData> documentDataList = new ArrayList<>();
        List<String> queryWords = new ArrayList<>(originalWords);

        // Create stemToOriginal mapping based on originalWords order
        Map<String, String> stemToOriginal = new HashMap<>();
        List<String> originalWordList = new ArrayList<>(originalWords);
        for (int i = 0; i < wordList.size() && i < originalWordList.size(); i++) {
            stemToOriginal.put(wordList.get(i), originalWordList.get(i));
        }


        try (Connection conn = DataBaseManager.getConnection()) {
            // Optimized SQL query to fetch documents with all words
            String indexSql = "SELECT word, doc_id, IDF, id AS index_id FROM InvertedIndex WHERE word IN ("
                    + String.join(",", Collections.nCopies(words.size(), "?"))
                    + ") AND doc_id IN (SELECT doc_id FROM InvertedIndex WHERE word IN ("
                    + String.join(",", Collections.nCopies(words.size(), "?"))
                    + ") GROUP BY doc_id HAVING COUNT(DISTINCT word) = ?) ORDER BY doc_id";
            Map<Integer, Map<String, WordData>> docWordData = new HashMap<>();
            try (PreparedStatement pstmt = conn.prepareStatement(indexSql)) {
                int index = 1;
                for (String word : words) {
                    pstmt.setString(index++, word);
                }
                for (String word : words) {
                    pstmt.setString(index++, word);
                }
                pstmt.setInt(index, words.size());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {

                        String stemmedWord = rs.getString("word");

                        int docId = rs.getInt("doc_id");
                        double idf = rs.getDouble("IDF");
                        int indexId = rs.getInt("index_id");


                        if (stemmedWord == null) {
                            System.err.println("QueryIndex: Null word in ResultSet for docId: " + docId);
                            continue;
                        }
                        String originalWord = stemToOriginal.getOrDefault(stemmedWord, stemmedWord);
                        if (originalWord == null) {
                            System.err.println("QueryIndex: No original word for stem: " + stemmedWord);
                            continue;
                        }

                        WordData wordData = new WordData(stemmedWord, originalWord, indexId, idf);
                        System.out.println("QueryIndex: Created WordData: " + wordData);
                        docWordData.computeIfAbsent(docId, k -> new HashMap<>());
                        docWordData.get(docId).put(stemmedWord, wordData);

                    }
                }
            } catch (SQLException e) {
                System.err.println("QueryIndex: SQL error in InvertedIndex query: " + e.getMessage());
                throw e;
            }
            System.out.println("QueryIndex: Found " + docWordData.size() + " candidate documents");

            // Collect all index IDs for WordPositions query
            Set<Integer> allIndexIds = new HashSet<>();
            Map<Integer, Map<String, Integer>> docWordToIndexId = new HashMap<>();
            for (Map.Entry<Integer, Map<String, WordData>> entry : docWordData.entrySet()) {
                int docId = entry.getKey();
                Map<String, Integer> wordToIndexId = new HashMap<>();
                for (WordData wordData : entry.getValue().values()) {
                    wordToIndexId.put(wordData.word, wordData.indexId);
                    allIndexIds.add(wordData.indexId);
                }
                docWordToIndexId.put(docId, wordToIndexId);
            }

            // Query WordPositions
            Map<Integer, List<Integer>> indexIdToPositions = new HashMap<>();
            if (!allIndexIds.isEmpty()) {
                String posSql = "SELECT index_id, position FROM WordPositions WHERE index_id IN ("
                        + String.join(",", Collections.nCopies(allIndexIds.size(), "?"))
                        + ") ORDER BY index_id, position";
                try (PreparedStatement posPstmt = conn.prepareStatement(posSql)) {
                    int index = 1;
                    for (int indexId : allIndexIds) {
                        posPstmt.setInt(index++, indexId);
                    }
                    try (ResultSet rs = posPstmt.executeQuery()) {
                        while (rs.next()) {
                            int indexId = rs.getInt("index_id");
                            int position = rs.getInt("position");
                            indexIdToPositions.computeIfAbsent(indexId, k -> new ArrayList<>()).add(position);
                        }
                    }
                } catch (SQLException e) {
                    System.err.println("QueryIndex: SQL error in WordPositions query: " + e.getMessage());
                    throw e;
                }
            }

            // Check phrase for each candidate document
            for (Map.Entry<Integer, Map<String, WordData>> entry : docWordData.entrySet()) {
                int docId = entry.getKey();
                Map<String, WordData> wordDataMap = entry.getValue();
                Map<String, Integer> wordToIndexId = docWordToIndexId.get(docId);

                boolean phraseFound = checkSequentialPositions(indexIdToPositions, wordList, wordToIndexId);

                if (phraseFound) {
                    Map<String, List<Double>> wordInfo = new HashMap<>();
                    for (WordData wordData : wordDataMap.values()) {

                        wordInfo.put(wordData.originalWord, Arrays.asList(1.0, wordData.idf));
                        System.out.println("QueryIndex: Added wordInfo for docId: " + docId + ", word: " + wordData.originalWord);

                    }
                    DocumentData docData = new DocumentData(docId, wordInfo);
                    documentDataList.add(docData);
                    System.out.println("QueryIndex: Phrase found in docId: " + docId);
                }
            }
        } catch (SQLException e) {
            System.err.println("QueryIndex: SQL error in query for phrase: " + e.getMessage());
            throw e;
        }

        QueryResult result = new QueryResult(documentDataList, queryWords, new HashMap<>());
        phraseQueryCache.put(cacheKey, result); // Cache the result
        System.out.println("QueryIndex: Cached result for phrase query: " + cacheKey);
        System.out.println("QueryIndex: Returning " + documentDataList.size() + " documents for phrase query");

        long endTime = System.nanoTime();
        System.out.println("QueryIndex: Phrase query took: " + (endTime - startTime) / 1_000_000.0 + " ms");
        return result;
    }

    private static boolean checkSequentialPositions(Map<Integer, List<Integer>> indexIdToPositions, List<String> words, Map<String, Integer> wordToIndexId) {
        List<List<Integer>> positionLists = new ArrayList<>();
        for (String word : words) {
            Integer indexId = wordToIndexId.get(word);
            List<Integer> positions = indexIdToPositions.getOrDefault(indexId, Collections.emptyList());
            if (positions.isEmpty()) {
                System.out.println("QueryIndex: No positions for word: " + word);
                return false;
            }
            positionLists.add(positions); // Already sorted by SQL query
        }

        List<Integer> firstPositions = positionLists.get(0);
        for (int startPos : firstPositions) {
            boolean valid = true;
            for (int i = 1; i < words.size(); i++) {
                List<Integer> positions = positionLists.get(i);
                boolean found = false;
                for (int pos : positions) {
                    if (pos == startPos + i) {
                        found = true;
                        break;
                    }
                    if (pos > startPos + i) {
                        break; // Early termination
                    }
                }
                if (!found) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                return true;
            }
        }
        return false;
    }
}