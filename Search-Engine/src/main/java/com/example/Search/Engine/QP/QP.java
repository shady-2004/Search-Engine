package com.example.Search.Engine.QP;

import com.example.Search.Engine.Data.DataBaseManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class QP {
    private static final String DB_URL = "jdbc:sqlite:data/search_index.db";

    public static void main(String[] args) {
        QP qp = new QP();
        String query = "\"stai\" NOT \"me\""; // Example query with operator and phrases

        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false);
            List<DataBaseManager.DocumentData> results = qp.search(query);
            conn.commit();

            System.out.println("Relevant Documents for query \"" + query + "\":");
            if (results.isEmpty()) {
                System.out.println("No documents found.");
            } else {
                for (DataBaseManager.DocumentData result : results) {
                    System.out.println("DocID: " + result.getDocId() +
                            ", TermFrequencies: " + result.getTermFrequencies() +
                            ", Length: " + result.getDocumentLength() +
                            ", PageRank: " + result.getPageRank());
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
        }
    }

    public List<DataBaseManager.DocumentData> search(String query) throws SQLException {
        System.out.println("QP: Processing query: " + query);

        if (query == null || query.trim().isEmpty()) {
            System.out.println("QP: Empty query, returning empty result");
            return new ArrayList<>();
        }

        // Detect operator in the query
        String operator = detectOperator(query);
        List<DataBaseManager.DocumentData> documents;
        Map<String, Double> idfMap = new HashMap<>();

        if (!operator.isEmpty()) {
            // Split query into two parts based on operator
            String[] queryParts = splitQuery(query);
            if (queryParts.length != 2) {
                System.out.println("QP: Invalid query format, treating as single query");
                return processSingleQuery(query);
            }

            String leftQuery = queryParts[0].trim();
            String rightQuery = queryParts[1].trim();

            // Process left and right queries
            QueryIndex.QueryResult leftResult = processQueryComponent(leftQuery);
            QueryIndex.QueryResult rightResult = processQueryComponent(rightQuery);

            // Combine IDF maps
            idfMap.putAll(leftResult.idfMap);
            idfMap.putAll(rightResult.idfMap);

            // Apply Boolean operator logic
            switch (operator) {
                case "AND":
                    documents = intersectDocuments(leftResult.documents, rightResult.documents);
                    System.out.println("QP: Applied AND, resulting documents: " + documents.size());
                    break;
                case "OR":
                    documents = unionDocuments(leftResult.documents, rightResult.documents);
                    System.out.println("QP: Applied OR, resulting documents: " + documents.size());
                    break;
                case "NOT":
                    documents = differenceDocuments(leftResult.documents, rightResult.documents);
                    System.out.println("QP: Applied NOT, resulting documents: " + documents.size());
                    break;
                default:
                    System.out.println("QP: Unknown operator, returning empty result");
                    return new ArrayList<>();
            }
        } else {
            // No operator, process as single query
            QueryIndex.QueryResult result = processQueryComponent(query);
            documents = result.documents;
            idfMap = result.idfMap;
        }

        System.out.println("QP: Retrieved " + documents.size() + " documents from QueryIndex");
        System.out.println("QP: IDF Map: " + idfMap);

        // Print detailed document data for debugging purposes
        for (DataBaseManager.DocumentData doc : documents) {
            System.out.println("QP: Document - DocID: " + doc.getDocId() +
                    ", TermFrequencies: " + doc.getTermFrequencies() +
                    ", Length: " + doc.getDocumentLength() +
                    ", PageRank: " + doc.getPageRank());
        }

        // Return the documents as-is, without any ranking logic
        System.out.println("QP: Returning " + documents.size() + " documents");
        return documents;
    }

    private QueryIndex.QueryResult processQueryComponent(String query) throws SQLException {
        if (isQuoted(query)) {
            // Remove quotes from the phrase
            String cleanQuery = query.replaceAll("^\"|\"$", "");
            System.out.println("QP: Processing phrase query: " + cleanQuery);
            return QueryIndex.queryPhrase(cleanQuery);
        } else {
            // Process as non-phrase query
            Set<String> queryStems = tokenizeAndStem(query);
            System.out.println("QP: Processing word query with stems: " + queryStems);
            return QueryIndex.queryWords(queryStems);
        }
    }

    private List<DataBaseManager.DocumentData> processSingleQuery(String query) throws SQLException {
        QueryIndex.QueryResult result = processQueryComponent(query);
        return result.documents;
    }

    private List<DataBaseManager.DocumentData> intersectDocuments(List<DataBaseManager.DocumentData> leftDocs, List<DataBaseManager.DocumentData> rightDocs) {
        Map<Integer, DataBaseManager.DocumentData> resultMap = new HashMap<>();

        // Create a set of docIds from the right documents for efficient lookup
        Set<Integer> rightDocIds = rightDocs.stream()
                .map(DataBaseManager.DocumentData::getDocId)
                .collect(Collectors.toSet());

        // Keep only left documents that are also in rightDocs
        for (DataBaseManager.DocumentData leftDoc : leftDocs) {
            if (rightDocIds.contains(leftDoc.getDocId())) {
                // Merge term frequencies
                DataBaseManager.DocumentData rightDoc = rightDocs.stream()
                        .filter(d -> d.getDocId() == leftDoc.getDocId())
                        .findFirst()
                        .orElse(null);
                if (rightDoc != null) {
                    Map<String, Integer> mergedFrequencies = new HashMap<>(leftDoc.getTermFrequencies());
                    mergedFrequencies.putAll(rightDoc.getTermFrequencies());
                    resultMap.put(leftDoc.getDocId(), new DataBaseManager.DocumentData(
                            leftDoc.getDocId(),
                            mergedFrequencies,
                            leftDoc.getDocumentLength(),
                            leftDoc.getPageRank()
                    ));
                }
            }
        }

        return new ArrayList<>(resultMap.values());
    }

    private List<DataBaseManager.DocumentData> unionDocuments(List<DataBaseManager.DocumentData> leftDocs, List<DataBaseManager.DocumentData> rightDocs) {
        Map<Integer, DataBaseManager.DocumentData> resultMap = new HashMap<>();

        // Add all left documents
        for (DataBaseManager.DocumentData doc : leftDocs) {
            resultMap.put(doc.getDocId(), doc);
        }

        // Add right documents, merging term frequencies if docId exists
        for (DataBaseManager.DocumentData rightDoc : rightDocs) {
            if (resultMap.containsKey(rightDoc.getDocId())) {
                Map<String, Integer> mergedFrequencies = new HashMap<>(resultMap.get(rightDoc.getDocId()).getTermFrequencies());
                mergedFrequencies.putAll(rightDoc.getTermFrequencies());
                resultMap.put(rightDoc.getDocId(), new DataBaseManager.DocumentData(
                        rightDoc.getDocId(),
                        mergedFrequencies,
                        rightDoc.getDocumentLength(),
                        rightDoc.getPageRank()
                ));
            } else {
                resultMap.put(rightDoc.getDocId(), rightDoc);
            }
        }

        return new ArrayList<>(resultMap.values());
    }

    private List<DataBaseManager.DocumentData> differenceDocuments(List<DataBaseManager.DocumentData> leftDocs, List<DataBaseManager.DocumentData> rightDocs) {
        Set<Integer> rightDocIds = rightDocs.stream()
                .map(DataBaseManager.DocumentData::getDocId)
                .collect(Collectors.toSet());

        // Return left documents not in rightDocs
        return leftDocs.stream()
                .filter(doc -> !rightDocIds.contains(doc.getDocId()))
                .collect(Collectors.toList());
    }

    private String[] splitQuery(String query) {
        query = query.trim();
        if (query.contains(" OR ")) return query.split(" OR ", 2);
        if (query.contains(" AND ")) return query.split(" AND ", 2);
        if (query.contains(" NOT ")) return query.split(" NOT ", 2);
        return new String[]{query};
    }

    private String detectOperator(String query) {
        if (query.contains(" OR ")) return "OR";
        if (query.contains(" AND ")) return "AND";
        if (query.contains(" NOT ")) return "NOT";
        return "";
    }

    public static boolean isQuoted(String input) {
        // Check if the string is non-null, starts with " and ends with "
        return input != null && input.startsWith("\"") && input.endsWith("\"");
    }

    public Set<String> tokenizeAndStem(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new HashSet<>();
        }

        // Split the text by spaces to get tokens
        String[] tokens = text.toLowerCase().split("\\s+");
        Set<String> stems = new HashSet<>();
        Stemmer stemmer = new Stemmer();

        for (String token : tokens) {
            if (!token.isEmpty()) {
                stemmer.add(token.toCharArray(), token.length());
                stemmer.stem();
                stems.add(stemmer.toString());
            }
        }
        return stems;
    }

    // Helper class to store document ID and score
    public static class DocumentScore {
        private final int docId;
        private final double score;

        public DocumentScore(int docId, double score) {
            this.docId = docId;
            this.score = score;
        }

        public int getDocId() { return docId; }
        public double getScore() { return score; }

        @Override
        public String toString() {
            return "DocID: " + docId + ", Score: " + score;
        }
    }
}