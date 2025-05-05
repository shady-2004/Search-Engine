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
    private static final int QUERY_THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static final int BOOL_THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static final ExecutorService queryExecutor = Executors.newFixedThreadPool(QUERY_THREAD_POOL_SIZE);
    private static final ExecutorService boolExecutor = Executors.newFixedThreadPool(BOOL_THREAD_POOL_SIZE);
    private static final Map<String, Set<String>> stemCache = new HashMap<>();
    private static final Map<String, Map<String, String>> stemToOriginalCache = new HashMap<>();
    private static final LinkedHashMap<String, QueryIndex.QueryResult> queryCache;
    private static final int MAX_CACHE_SIZE = 1000;
    private static final int BATCH_SIZE = 3; // Stems per query batch
    private static final boolean DEBUG = false; // Toggle for logging

    static {
        // Initialize LRU query cache
        queryCache = new LinkedHashMap<String, QueryIndex.QueryResult>(MAX_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, QueryIndex.QueryResult> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };

        // Ensure executors shutdown on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            queryExecutor.shutdown();
            boolExecutor.shutdown();
            try {
                if (!queryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    queryExecutor.shutdownNow();
                }
                if (!boolExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    boolExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                queryExecutor.shutdownNow();
                boolExecutor.shutdownNow();
            }
        }));
    }

    // public static void main(String[] args) {
    //     QP qp = new QP();
    //     String query = "\"stay\" OR \"career\"";
    //     try (Connection conn = DriverManager.getConnection(DB_URL)) {
    //         conn.setAutoCommit(false);
    //         QueryIndex.QueryResult result = qp.search(query);
    //         conn.commit();
    //         System.out.println("Query Words: " + result.queryWords);
    //         System.out.println("Relevant Documents for query \"" + query + "\":");
    //         if (result.documents.isEmpty()) {
    //             System.out.println("No documents found.");
    //         } else {
    //             System.out.println(result.documents);
    //             List<Integer> ranked = Ranker.rank(result.documents, result.queryWords);
    //             System.out.println("Ranked: " + ranked);
    //         }
    //     } catch (SQLException e) {
    //         System.err.println("Database error: " + e.getMessage());
    //         System.exit(1);
    //     } catch (InterruptedException e) {
    //         System.err.println("Interrupted error: " + e.getMessage());
    //         System.exit(1);
    //     } catch (Exception e) {
    //         System.err.println("Unexpected error: " + e.getMessage());
    //         System.exit(1);
    //     }
    //     System.exit(0);
    // }

    public QueryIndex.QueryResult search(String query) throws SQLException {
        if (query == null || query.trim().isEmpty()) {
            if (DEBUG) System.out.println("QP: Empty query, returning empty result");
            return new QueryIndex.QueryResult(Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        }

        // Check query cache
        String cacheKey = query.trim().toLowerCase();
        QueryIndex.QueryResult cachedResult = queryCache.get(cacheKey);
        if (cachedResult != null) {
            if (DEBUG) System.out.println("QP: Cache hit for query: " + query);
            return new QueryIndex.QueryResult(
                    new ArrayList<>(cachedResult.documents),
                    new ArrayList<>(cachedResult.queryWords),
                    new HashMap<>(cachedResult.idfMap)
            );
        }

        QueryIndex.QueryResult result = processQuery(query);

        // Cache only non-empty results
        if (!result.documents.isEmpty()) {
            queryCache.put(cacheKey, result);
            if (DEBUG) System.out.println("QP: Cached result for query: " + cacheKey);
        }
        return result;
    }

    private QueryIndex.QueryResult processQuery(String query) throws SQLException {
        String operator = detectOperator(query);
        if (!operator.isEmpty()) {
            String[] queryParts = splitQuery(query);
            if (queryParts.length != 2) {
                if (DEBUG) System.out.println("QP: Invalid query format, treating as single query");
                return processSingleQuery(query);
            }
            String leftQuery = queryParts[0].trim();
            String rightQuery = queryParts[1].trim();

            // Process query components in parallel
            List<String> subQueries = Arrays.asList(leftQuery, rightQuery);
            List<CompletableFuture<QueryIndex.QueryResult>> futures = subQueries.stream()
                    .map(subQuery -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return processQueryComponent(subQuery);
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    }, queryExecutor))
                    .collect(Collectors.toList());

            List<QueryIndex.QueryResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            QueryIndex.QueryResult leftResult = results.get(0);
            QueryIndex.QueryResult rightResult = results.get(1);

            List<String> combinedQueryWords = new ArrayList<>(leftResult.queryWords);
            combinedQueryWords.addAll(rightResult.queryWords);

            List<QueryIndex.DocumentData> documents;
            switch (operator) {
                case "AND":
                    documents = intersectDocumentsParallel(leftResult.documents, rightResult.documents);
                    if (DEBUG) System.out.println("QP: Applied AND, resulting documents: " + documents.size());
                    break;
                case "OR":
                    documents = unionDocumentsParallel(leftResult.documents, rightResult.documents);
                    if (DEBUG) System.out.println("QP: Applied OR, resulting documents: " + documents.size());
                    break;
                case "NOT":
                    documents = differenceDocumentsParallel(leftResult.documents, rightResult.documents);
                    if (DEBUG) System.out.println("QP: Applied NOT, resulting documents: " + documents.size());
                    break;
                default:
                    if (DEBUG) System.out.println("QP: Unknown operator, returning empty result");
                    return new QueryIndex.QueryResult(Collections.emptyList(), combinedQueryWords, Collections.emptyMap());
            }
            return new QueryIndex.QueryResult(documents, combinedQueryWords, Collections.emptyMap());
        } else {
            return processQueryComponent(query);
        }
    }

    private QueryIndex.QueryResult processQueryComponent(String query) throws SQLException {
        if (isQuoted(query)) {
            String cleanQuery = query.replaceAll("^\"|\"$", "");
            String[] originalWords = cleanQuery.split("\\s+");
            if (originalWords.length == 0) {
                originalWords = new String[]{cleanQuery};
            }
            Map<String, String> stemToOriginal = new HashMap<>();
            Set<String> queryStems = tokenizeAndStem(cleanQuery, stemToOriginal);
            List<String> queryWords = Arrays.asList(originalWords);
            List<QueryIndex.DocumentData> documents = queryStems.isEmpty() ?
                    Collections.emptyList() :
                    QueryIndex.queryPhrase(queryStems, new HashSet<>(queryWords)).documents;
            return new QueryIndex.QueryResult(documents, queryWords, Collections.emptyMap());
        } else {
            Map<String, String> stemToOriginal = new HashMap<>();
            Set<String> queryStems = tokenizeAndStem(query, stemToOriginal);
            List<String> queryWords = new ArrayList<>(stemToOriginal.values());
            List<QueryIndex.DocumentData> documents = queryStems.isEmpty() ?
                    Collections.emptyList() :
                    queryWordsParallel(queryStems, stemToOriginal);
            return new QueryIndex.QueryResult(documents, queryWords, Collections.emptyMap());
        }
    }

    private List<QueryIndex.DocumentData> queryWordsParallel(Set<String> queryStems, Map<String, String> stemToOriginal) throws SQLException {
        if (queryStems.isEmpty()) {
            return Collections.emptyList();
        }

        // Split stems into batches
        List<List<String>> batches = new ArrayList<>();
        List<String> stemList = new ArrayList<>(queryStems);
        for (int i = 0; i < stemList.size(); i += BATCH_SIZE) {
            batches.add(stemList.subList(i, Math.min(i + BATCH_SIZE, stemList.size())));
        }

        // Parallel query for each batch
        List<CompletableFuture<List<QueryIndex.DocumentData>>> futures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return QueryIndex.queryWords(new HashSet<>(batch), stemToOriginal).documents;
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }, queryExecutor))
                .collect(Collectors.toList());

        // Merge results
        Map<Integer, QueryIndex.DocumentData> resultMap = new HashMap<>();
        for (CompletableFuture<List<QueryIndex.DocumentData>> future : futures) {
            List<QueryIndex.DocumentData> docs = future.join();
            for (QueryIndex.DocumentData doc : docs) {
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
        }
        return new ArrayList<>(resultMap.values());
    }

    private QueryIndex.QueryResult processSingleQuery(String query) throws SQLException {
        return processQueryComponent(query);
    }

    private List<QueryIndex.DocumentData> intersectDocumentsParallel(
            List<QueryIndex.DocumentData> leftDocs, List<QueryIndex.DocumentData> rightDocs) {
        if (leftDocs.isEmpty() || rightDocs.isEmpty()) {
            return Collections.emptyList();
        }

        // Split documents into chunks
        int chunkSize = Math.max(1, leftDocs.size() / BOOL_THREAD_POOL_SIZE);
        List<List<QueryIndex.DocumentData>> leftChunks = new ArrayList<>();
        for (int i = 0; i < leftDocs.size(); i += chunkSize) {
            leftChunks.add(leftDocs.subList(i, Math.min(i + chunkSize, leftDocs.size())));
        }

        Set<Integer> rightDocIds = rightDocs.stream()
                .map(QueryIndex.DocumentData::getDocId)
                .collect(Collectors.toSet());

        // Parallel intersection
        List<CompletableFuture<List<QueryIndex.DocumentData>>> futures = leftChunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(() -> chunk.stream()
                        .filter(doc -> rightDocIds.contains(doc.getDocId()))
                        .collect(Collectors.toList()), boolExecutor))
                .collect(Collectors.toList());

        // Merge results
        return futures.stream()
                .flatMap(future -> future.join().stream())
                .collect(Collectors.toList());
    }

    private List<QueryIndex.DocumentData> unionDocumentsParallel(
            List<QueryIndex.DocumentData> leftDocs, List<QueryIndex.DocumentData> rightDocs) {
        if (leftDocs.isEmpty() && rightDocs.isEmpty()) {
            return Collections.emptyList();
        }

        // Combine both document lists
        List<List<QueryIndex.DocumentData>> allDocs = new ArrayList<>();
        allDocs.add(leftDocs);
        allDocs.add(rightDocs);

        // Split into chunks
        int chunkSize = Math.max(1, (leftDocs.size() + rightDocs.size()) / BOOL_THREAD_POOL_SIZE);
        List<List<QueryIndex.DocumentData>> chunks = new ArrayList<>();
        for (List<QueryIndex.DocumentData> docs : allDocs) {
            for (int i = 0; i < docs.size(); i += chunkSize) {
                chunks.add(docs.subList(i, Math.min(i + chunkSize, docs.size())));
            }
        }

        // Parallel union
        List<CompletableFuture<Map<Integer, QueryIndex.DocumentData>>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(() -> {
                    Map<Integer, QueryIndex.DocumentData> chunkMap = new HashMap<>();
                    for (QueryIndex.DocumentData doc : chunk) {
                        chunkMap.putIfAbsent(doc.getDocId(), doc);
                    }
                    return chunkMap;
                }, boolExecutor))
                .collect(Collectors.toList());

        // Merge results
        Map<Integer, QueryIndex.DocumentData> resultMap = new HashMap<>();
        for (CompletableFuture<Map<Integer, QueryIndex.DocumentData>> future : futures) {
            Map<Integer, QueryIndex.DocumentData> chunkMap = future.join();
            for (Map.Entry<Integer, QueryIndex.DocumentData> entry : chunkMap.entrySet()) {
                int docId = entry.getKey();
                QueryIndex.DocumentData doc = entry.getValue();
                if (!resultMap.containsKey(docId)) {
                    resultMap.put(docId, doc);
                } else {
                    Map<String, List<Double>> mergedWordInfo = new HashMap<>(resultMap.get(docId).getWordInfo());
                    mergedWordInfo.putAll(doc.getWordInfo());
                    QueryIndex.DocumentData mergedDoc = new QueryIndex.DocumentData(docId, mergedWordInfo);
                    mergedDoc.pageRank = Math.max(resultMap.get(docId).getPageRank(), doc.getPageRank());
                    resultMap.put(docId, mergedDoc);
                }
            }
        }
        return new ArrayList<>(resultMap.values());
    }

    private List<QueryIndex.DocumentData> differenceDocumentsParallel(
            List<QueryIndex.DocumentData> leftDocs, List<QueryIndex.DocumentData> rightDocs) {
        if (leftDocs.isEmpty()) {
            return Collections.emptyList();
        }
        if (rightDocs.isEmpty()) {
            return new ArrayList<>(leftDocs);
        }

        // Split left documents into chunks
        int chunkSize = Math.max(1, leftDocs.size() / BOOL_THREAD_POOL_SIZE);
        List<List<QueryIndex.DocumentData>> leftChunks = new ArrayList<>();
        for (int i = 0; i < leftDocs.size(); i += chunkSize) {
            leftChunks.add(leftDocs.subList(i, Math.min(i + chunkSize, leftDocs.size())));
        }

        Set<Integer> rightDocIds = rightDocs.stream()
                .map(QueryIndex.DocumentData::getDocId)
                .collect(Collectors.toSet());

        // Parallel difference
        List<CompletableFuture<List<QueryIndex.DocumentData>>> futures = leftChunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(() -> chunk.stream()
                        .filter(doc -> !rightDocIds.contains(doc.getDocId()))
                        .collect(Collectors.toList()), boolExecutor))
                .collect(Collectors.toList());

        // Merge results
        return futures.stream()
                .flatMap(future -> future.join().stream())
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
            return Collections.emptySet();
        }

        String cacheKey = text.toLowerCase();
        if (stemCache.containsKey(cacheKey)) {
            Set<String> cachedStems = stemCache.get(cacheKey);
            Map<String, String> cachedMapping = stemToOriginalCache.get(cacheKey);
            stemToOriginal.putAll(cachedMapping);
            return new HashSet<>(cachedStems);
        }

        String[] tokens = isQuoted(text) ? new String[]{text.replaceAll("^\"|\"$", "")} : text.split("\\s+");
        Set<String> stems = Collections.synchronizedSet(new HashSet<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String token : tokens) {
            if (token == null || token.trim().isEmpty()) {
                continue;
            }
            futures.add(CompletableFuture.runAsync(() -> {
                String lowerToken = token.toLowerCase();
                if (lowerToken.length() > 0) {
                    Stemmer stemmer = new Stemmer();
                    stemmer.add(lowerToken.toCharArray(), lowerToken.length());
                    stemmer.stem();
                    String stem = stemmer.toString();
                    if (!stem.isEmpty()) {
                        stems.add(stem);
                        synchronized (stemToOriginal) {
                            stemToOriginal.put(stem, token);
                        }
                    }
                }
            }, queryExecutor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        stemCache.put(cacheKey, new HashSet<>(stems));
        stemToOriginalCache.put(cacheKey, new HashMap<>(stemToOriginal));
        return stems;
    }
}