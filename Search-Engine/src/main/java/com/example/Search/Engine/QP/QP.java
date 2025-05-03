package com.example.Search.Engine.QP;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class QP {

    private static final String DB_URL = "jdbc:sqlite:data/search_index.db";

    public static void main(String[] args) {
        QP qp = new QP();
        String query = "stai"; // Example query with operator and phrases
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false);
            List<QueryIndex.DocumentData> results = qp.search(query);
            conn.commit();
            System.out.println("Relevant Documents for query \"" + query + "\":");
            if (results.isEmpty()) {
                System.out.println("No documents found.");
            } else {
                for (QueryIndex.DocumentData doc : results) {
                    System.out.println("Document - DocID: " + doc.getDocId() +
                            ", WordInfo: " + doc.getWordInfo());
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
        }
    }

    public List<QueryIndex.DocumentData> search(String query) throws SQLException {
        System.out.println("QP: Processing query: " + query);
        if (query == null || query.trim().isEmpty()) {
            System.out.println("QP: Empty query, returning empty result");
            return new ArrayList<>();
        }

        // Detect operator in the query
        String operator = detectOperator(query);
        List<QueryIndex.DocumentData> documents;

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
        }

        System.out.println("QP: Retrieved " + documents.size() + " documents from QueryIndex");
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

    private List<QueryIndex.DocumentData> processSingleQuery(String query) throws SQLException {
        QueryIndex.QueryResult result = processQueryComponent(query);
        return result.documents;
    }

    private List<QueryIndex.DocumentData> intersectDocuments(
            List<QueryIndex.DocumentData> leftDocs, List<QueryIndex.DocumentData> rightDocs) {
        Set<Integer> rightDocIds = rightDocs.stream()
                .map(QueryIndex.DocumentData::getDocId)
                .collect(Collectors.toSet());

        return leftDocs.stream()
                .filter(doc -> rightDocIds.contains(doc.getDocId()))
                .collect(Collectors.toList());
    }

    private List<QueryIndex.DocumentData> unionDocuments(
            List<QueryIndex.DocumentData> leftDocs, List<QueryIndex.DocumentData> rightDocs) {
        Set<Integer> docIds = new HashSet<>();
        List<QueryIndex.DocumentData> result = new ArrayList<>();

        for (QueryIndex.DocumentData doc : leftDocs) {
            if (docIds.add(doc.getDocId())) {
                result.add(doc);
            }
        }
        for (QueryIndex.DocumentData doc : rightDocs) {
            if (docIds.add(doc.getDocId())) {
                result.add(doc);
            }
        }
        return result;
    }

    private List<QueryIndex.DocumentData> differenceDocuments(
            List<QueryIndex.DocumentData> leftDocs, List<QueryIndex.DocumentData> rightDocs) {
        Set<Integer> rightDocIds = rightDocs.stream()
                .map(QueryIndex.DocumentData::getDocId)
                .collect(Collectors.toSet());

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
        return input != null && input.startsWith("\"") && input.endsWith("\"");
    }

    public Set<String> tokenizeAndStem(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new HashSet<>();
        }
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
}