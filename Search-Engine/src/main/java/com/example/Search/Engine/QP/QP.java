package com.example.Search.Engine.QP;

import com.example.Search.Engine.Ranker.Ranker;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class QP {

    private static final String DB_URL = "jdbc:sqlite:data/search_index.db";
    private static TokenizerME tokenizer;

    static {
        // Load the tokenizer model from the classpath
        try {
            InputStream modelIn = QP.class.getClassLoader().getResourceAsStream("models/en-token.bin");
            if (modelIn == null) {
                throw new RuntimeException("Tokenizer model file 'models/en-token.bin' not found in resources");
            }
            TokenizerModel model = new TokenizerModel(modelIn);
            tokenizer = new TokenizerME(model);
            modelIn.close();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load tokenizer model", e);
        }
    }

    public static void main(String[] args) {
        QP qp = new QP();
        String query = "naruto";
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false);
            QueryIndex.QueryResult result = qp.search(query); // Now returns QueryResult
            conn.commit();
            System.out.println("Query Words: " + result.queryWords);
            System.out.println("Relevant Documents for query \"" + query + "\":");
            if (result.documents.isEmpty()) {
                System.out.println("No documents found.");
            } else {
                List<Integer> ranked = Ranker.rank(result.documents, result.queryWords);
                System.out.println(ranked);
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public QueryIndex.QueryResult search(String query) throws SQLException {
        System.out.println("QP: Processing query: " + query);
        if (query == null || query.trim().isEmpty()) {
            System.out.println("QP: Empty query, returning empty result");
            return new QueryIndex.QueryResult(new ArrayList<>(), new ArrayList<>(), new HashMap<>());
        }

        // Detect operator in the query
        String operator = detectOperator(query);
        QueryIndex.QueryResult finalResult;

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

            // Combine query words
            List<String> combinedQueryWords = new ArrayList<>(leftResult.queryWords);
            combinedQueryWords.addAll(rightResult.queryWords);

            // Apply Boolean operator logic
            List<QueryIndex.DocumentData> documents;
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
                    return new QueryIndex.QueryResult(new ArrayList<>(), combinedQueryWords, new HashMap<>());
            }
            finalResult = new QueryIndex.QueryResult(documents, combinedQueryWords, new HashMap<>());
        } else {
            // No operator, process as single query
            finalResult = processQueryComponent(query);
        }

        System.out.println("QP: Retrieved " + finalResult.documents.size() + " documents from QueryIndex");
        System.out.println("QP: Returning query words: " + finalResult.queryWords);
        return finalResult;
    }

    private QueryIndex.QueryResult processQueryComponent(String query) throws SQLException {
        if (isQuoted(query)) {
            // Remove quotes from the phrase
            String cleanQuery = query.replaceAll("^\"|\"$", "");
            System.out.println("QP: Processing phrase query: " + cleanQuery);
            // Tokenize the phrase to get original words
            String[] originalWords = tokenizer.tokenize(cleanQuery);
            // Stem the tokens for querying
            Map<String, String> stemToOriginal = new HashMap<>();
            Set<String> queryStems = tokenizeAndStem(cleanQuery, stemToOriginal);
            return QueryIndex.queryPhrase(queryStems, new HashSet<>(Arrays.asList(originalWords)));
        } else {
            // Process as non-phrase query
            Map<String, String> stemToOriginal = new HashMap<>();
            Set<String> queryStems = tokenizeAndStem(query, stemToOriginal);
            System.out.println("QP: Processing word query with stems: " + queryStems);
            return QueryIndex.queryWords(queryStems, stemToOriginal);
        }
    }

    private QueryIndex.QueryResult processSingleQuery(String query) throws SQLException {
        return processQueryComponent(query);
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
        Map<Integer, QueryIndex.DocumentData> resultMap = new HashMap<>();

        for (QueryIndex.DocumentData doc : leftDocs) {
            resultMap.put(doc.getDocId(), doc);
        }
        for (QueryIndex.DocumentData doc : rightDocs) {
            if (!resultMap.containsKey(doc.getDocId())) {
                resultMap.put(doc.getDocId(), doc);
            } else {
                // Merge wordInfo for OR operation
                Map<String, List<Double>> mergedWordInfo = new HashMap<>(resultMap.get(doc.getDocId()).getWordInfo());
                mergedWordInfo.putAll(doc.getWordInfo());

                resultMap.put(doc.getDocId(), new QueryIndex.DocumentData(
                        doc.getDocId(),
                        mergedWordInfo
                ));
            }
        }
        return new ArrayList<>(resultMap.values());
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

    public Set<String> tokenizeAndStem(String text, Map<String, String> stemToOriginal) {
        if (text == null || text.trim().isEmpty()) {
            return new HashSet<>();
        }
        // Use OpenNLP tokenizer
        String[] tokens = tokenizer.tokenize(text);
        Set<String> stems = new HashSet<>();
        Stemmer stemmer = new Stemmer();
        for (String token : tokens) {
            if (!token.isEmpty()) {
                String lowerToken = token.toLowerCase();
                stemmer.add(lowerToken.toCharArray(), lowerToken.length());
                stemmer.stem();
                String stem = stemmer.toString();
                stems.add(stem);
                stemToOriginal.put(stem, token); // Map stem to original token (preserving case)
            }
        }
        return stems;
    }
}