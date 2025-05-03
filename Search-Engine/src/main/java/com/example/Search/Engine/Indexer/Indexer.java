package com.example.Search.Engine.Indexer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class Indexer implements AutoCloseable {
    private final SQLiteSearcher searcher;
    private final Tokenizer tokenizer;

    @Autowired
    public Indexer(SQLiteSearcher searcher, Tokenizer tokenizer) {
        this.searcher = searcher;
        this.tokenizer = tokenizer;
    }

    @Override
    public void close() {
        System.out.println("Closing Indexer resources...");
        try {
            searcher.close();
            System.out.println("Resources closed successfully");
        } catch (Exception e) {
            System.err.println("Error closing resources: " + e.getMessage());
        }
    }

    public void index() {
        long startTime = System.nanoTime();

        System.out.println("\nStarting to index documents from database...");
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        try {
            // Get all documents from DocumentMetaData
            List<Map.Entry<String, String>> documents = searcher.getAllDocuments();
            System.out.println("Found " + documents.size() + " documents to index");

            // Create a thread pool for parallel processing
            int processors = Runtime.getRuntime().availableProcessors();
            ExecutorService executor = Executors.newFixedThreadPool(processors);
            List<CompletableFuture<Map.Entry<String, Map<String, Tokenizer.Token>>>> futures = new ArrayList<>();

            // First pass: collect all documents and tokens in parallel
            long tokenizationStart = System.nanoTime();
            for (Map.Entry<String, String> doc : documents) {
                CompletableFuture<Map.Entry<String, Map<String, Tokenizer.Token>>> future = CompletableFuture.supplyAsync(() -> {
                    long docStart = System.nanoTime();
                    try {
                        System.out.println("\n=== Processing document: " + doc.getKey() + " ===");
                        String url = doc.getKey();
                        String html = doc.getValue();
                        Document docHtml = Jsoup.parse(html);
                        Map<String, Tokenizer.Token> tokens = tokenizer.tokenizeDocument(docHtml);
                        long docEnd = System.nanoTime();
                        System.out.printf("Document %s processed in %.2f ms%n", url, (docEnd - docStart) / 1000000.0);
                        return Map.entry(url, tokens);
                    } catch (Exception e) {
                        String error = String.format("Error processing %s: %s", doc.getKey(), e.getMessage());
                        System.err.println(error);
                        errors.add(error);
                        return null;
                    }
                }, executor);
                futures.add(future);
            }

            // Wait for all processing to complete
            List<Map.Entry<String, Map<String, Tokenizer.Token>>> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();
            long tokenizationEnd = System.nanoTime();

            // Bulk write to database
            System.out.println("\nWriting " + results.size() + " documents to database in bulk...");
            long dbStart = System.nanoTime();
            try {
                searcher.addDocuments(results);
            } catch (Exception e) {
                String error = String.format("Error during bulk indexing: %s", e.getMessage());
                System.err.println(error);
                errors.add(error);
            }
            long dbEnd = System.nanoTime();

            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // Print performance metrics
            long endTime = System.nanoTime();
            
            System.out.println("\n=== Performance Metrics ===");
            System.out.printf("Total indexing time: %.2f seconds%n", (endTime - startTime) / 1000000000.0);
            System.out.printf("Tokenization time: %.2f seconds%n", (tokenizationEnd - tokenizationStart) / 1000000000.0);
            System.out.printf("Database time: %.2f seconds%n", (dbEnd - dbStart) / 1000000000.0);
            System.out.printf("Average time per document: %.2f ms%n", 
                (endTime - startTime) / (results.size() * 1000000.0));
            System.out.println("=========================");

            if (!errors.isEmpty()) {
                System.err.println("\nErrors occurred while indexing:");
                errors.forEach(System.err::println);
                throw new IOException("Errors occurred while indexing:\n" + String.join("\n", errors));
            }
            
            System.out.println("\nSuccessfully indexed all documents from database");

        } catch (Exception e) {
            System.err.println("Error during indexing: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        SQLiteSearcher searcher = null;
        try {
            searcher = new SQLiteSearcher();
            Tokenizer tokenizer = new Tokenizer();
            Indexer indexer = new Indexer(searcher, tokenizer);
            
            System.out.println("Starting to index documents from database...");
            indexer.index();
            System.out.println("Successfully indexed all documents from database");
            indexer.close();
        } catch (Exception e) {
            System.err.println("Error during indexing: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (searcher != null) {
                try {
                    searcher.close();
                } catch (Exception e) {
                    System.err.println("Error closing searcher: " + e.getMessage());
                }
            }
        }
    }
}