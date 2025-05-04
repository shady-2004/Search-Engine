package com.example.Search.Engine.QP;

import com.example.Search.Engine.Ranker.Ranker;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class QP {

    private static final String DB_URL = "jdbc:sqlite:data/search_index.db";
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Map<String, Set<String>> stemCache = new HashMap<>();
    private static final Map<String, Map<String, String>> stemToOriginalCache = new HashMap<>();

    static {
        // Ensure executor shutdown on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }));
    }

    public static void main(String[] args) {
        QP qp = new QP();
        String query = "\"stay\"";
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false);
            QueryIndex.QueryResult result = qp.search(query);
            conn.commit();
            System.out.println("Query Words: " + result.queryWords);
            System.out.println("Relevant Documents for query \"" + query + "\":");
            if (result.documents.isEmpty()) {
                System.out.println("No documents found.");
            } else {
                System.out.println(result.documents);
                List<Integer> ranked = Ranker.rank(result.documents, result.queryWords);
                System.out.println(ranked);
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            System.err.println("Interrupted error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            System.exit(1);
        }
        System.exit(0);
    }

    public QueryIndex.QueryResult search(String query) throws SQLException {
        System.out.println("QP: Processing query: " + query);
        if (query == null || query.trim().isEmpty()) {
            System.out.println("QP: Empty query, returning empty result");
            return new QueryIndex.QueryResult(new ArrayList<>(), new ArrayList<>(), new HashMap<>());
        }

        String operator = detectOperator(query);
        QueryIndex.QueryResult finalResult;

        if (!operator.isEmpty()) {
            String[] queryParts = splitQuery(query);
            if (queryParts.length != 2) {
                System.out.println("QP: Invalid query format, treating as single query");
                return processSingleQuery(query);
            }
            String leftQuery = queryParts[0].trim();
            String rightQuery = queryParts[1].trim();

            CompletableFuture<QueryIndex.QueryResult> leftFuture = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return processQueryComponent(leftQuery);
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    }, executor);
            CompletableFuture<QueryIndex.QueryResult> rightFuture = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return processQueryComponent(rightQuery);
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    }, executor);

            QueryIndex.QueryResult leftResult = leftFuture.join();
            QueryIndex.QueryResult rightResult = rightFuture.join();

            List<String> combinedQueryWords = new ArrayList<>(leftResult.queryWords);
            combinedQueryWords.addAll(rightResult.queryWords);

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
            finalResult = processQueryComponent(query);
        }

        System.out.println("QP: Retrieved " + finalResult.documents.size() + " documents from QueryIndex");
        System.out.println("QP: Returning query words: " + finalResult.queryWords);
        return finalResult;
    }

    private QueryIndex.QueryResult processQueryComponent(String query) throws SQLException {
        if (isQuoted(query)) {
            String cleanQuery = query.replaceAll("^\"|\"$", "");
            System.out.println("QP: Processing phrase query: " + cleanQuery);
            String[] originalWords = cleanQuery.split("\\s+");
            if (originalWords.length == 0) {
                originalWords = new String[]{cleanQuery};
            }
            Map<String, String> stemToOriginal = new HashMap<>();
            Set<String> queryStems = tokenizeAndStem(cleanQuery, stemToOriginal);
            List<String> queryWords = Arrays.asList(originalWords);
            return new QueryIndex.QueryResult(
                    QueryIndex.queryPhrase(queryStems, new HashSet<>(queryWords)).documents,
                    queryWords,
                    new HashMap<>()
            );
        } else {
            Map<String, String> stemToOriginal = new HashMap<>();
            Set<String> queryStems = tokenizeAndStem(query, stemToOriginal);
            System.out.println("QP: Processing word query with stems: " + queryStems);
            List<String> queryWords = queryStems.stream()
                    .map(stem -> stemToOriginal.getOrDefault(stem, stem))
                    .collect(Collectors.toList());
            return new QueryIndex.QueryResult(
                    QueryIndex.queryWords(queryStems, stemToOriginal).documents,
                    queryWords,
                    new HashMap<>()
            );
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
                Map<String, List<Double>> mergedWordInfo = new HashMap<>(resultMap.get(doc.getDocId()).getWordInfo());
                mergedWordInfo.putAll(doc.getWordInfo());

                QueryIndex.DocumentData mergedDoc = new QueryIndex.DocumentData(
                        doc.getDocId(),
                        mergedWordInfo
                );
                mergedDoc.pageRank = Math.max(resultMap.get(doc.getDocId()).getPageRank(), doc.getPageRank());
                resultMap.put(doc.getDocId(), mergedDoc);
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

        if (stemCache.containsKey(text)) {
            Set<String> cachedStems = stemCache.get(text);
            Map<String, String> cachedMapping = stemToOriginalCache.get(text);
            stemToOriginal.putAll(cachedMapping);
            return new HashSet<>(cachedStems);
        }

        String[] tokens = isQuoted(text) ? new String[]{text.replaceAll("^\"|\"$", "")} : text.split("\\s+");
        Set<String> stems = new HashSet<>();
        Stemmer stemmer = new Stemmer();
        for (String token : tokens) {
            if (token != null && !token.trim().isEmpty()) {
                String lowerToken = token.toLowerCase();
                if (lowerToken.length() > 0) {
                    stemmer.add(lowerToken.toCharArray(), lowerToken.length());
                    stemmer.stem();
                    String stem = stemmer.toString();
                    if (!stem.isEmpty()) {
                        stems.add(stem);
                        stemToOriginal.put(stem, token);
                    }
                }
            }
        }

        stemCache.put(text, new HashSet<>(stems));
        stemToOriginalCache.put(text, new HashMap<>(stemToOriginal));
        return stems;
    }
}