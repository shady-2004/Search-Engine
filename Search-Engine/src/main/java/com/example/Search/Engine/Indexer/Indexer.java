package com.example.Search.Engine.Indexer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.io.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;

@Service
public class Indexer implements AutoCloseable {
    private final SQLiteSearcher searcher;
    private final Tokenizer tokenizer;

    @Autowired
    public Indexer(SQLiteSearcher searcher, Tokenizer tokenizer) {
        this.searcher = searcher;
        this.tokenizer = tokenizer;
    }

    public void indexDocument(String url, String htmlContent) throws IOException {
        System.out.println("\nIndexing document from URL: " + url);
        try {
            System.out.println("Parsing HTML content...");
            Document doc = Jsoup.parse(htmlContent);
            System.out.println("Title: " + doc.title());
            
            System.out.println("Tokenizing document...");
            Map<String, Tokenizer.Token> tokens = tokenizer.tokenizeDocument(doc);
            System.out.println("Found " + tokens.size() + " unique tokens");
            
            // Print some token statistics
            int titleTokens = 0;
            int h1Tokens = 0;
            int h2Tokens = 0;
            int contentTokens = 0;
            
            for (Tokenizer.Token token : tokens.values()) {
                switch (token.getPosition()) {
                    case "title" -> titleTokens++;
                    case "h1" -> h1Tokens++;
                    case "h2" -> h2Tokens++;
                    case "content" -> contentTokens++;
                }
            }
            
            System.out.println("Token distribution:");
            System.out.println("- Title tokens: " + titleTokens);
            System.out.println("- H1 tokens: " + h1Tokens);
            System.out.println("- H2 tokens: " + h2Tokens);
            System.out.println("- Content tokens: " + contentTokens);
            
            System.out.println("Adding document to search index...");
            searcher.addDocument(url, doc.title(), tokens);
            System.out.println("Document indexed successfully");
            
        } catch (RuntimeException e) {
            System.err.println("Failed to index document: " + e.getMessage());
            throw new IOException("Failed to index document: " + e.getMessage(), e);
        }
    }

    public void indexFile(File file) throws IOException {
        System.out.println("\nProcessing file: " + file.getAbsolutePath());
        if (!file.exists() || !file.isFile()) {
            System.err.println("Error: File does not exist or is not a regular file");
            throw new IOException("File does not exist or is not a regular file: " + file.getAbsolutePath());
        }

        String url = "file://" + file.getAbsolutePath();
        
        String content = readFileContent(file);
        
        indexDocument(url, content);
    }

    private String readFileContent(File file) throws IOException {
        System.out.println("Reading file: " + file.getName());
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        }
    }

    public void indexDirectory(File directory) throws IOException {
        long startTime = System.nanoTime();
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        System.out.println("\nIndexing directory: " + directory.getAbsolutePath());
        if (!directory.isDirectory()) {
            System.err.println("Error: Not a directory");
            throw new IOException("Not a directory: " + directory.getAbsolutePath());
        }

        File[] files = directory.listFiles((dir, name) -> name.toLowerCase().endsWith(".html"));
        if (files == null || files.length == 0) {
            System.err.println("Error: No HTML files found in directory");
            throw new IOException("No HTML files found in directory: " + directory.getAbsolutePath());
        }

        System.out.println("Found " + files.length + " HTML files to index");
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        // Create a thread pool for parallel processing
        int processors = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(processors);
        List<CompletableFuture<Map.Entry<String, Map<String, Tokenizer.Token>>>> futures = new ArrayList<>();

        // First pass: collect all documents and tokens in parallel
        long tokenizationStart = System.nanoTime();
        for (File file : files) {
            CompletableFuture<Map.Entry<String, Map<String, Tokenizer.Token>>> future = CompletableFuture.supplyAsync(() -> {
                long fileStart = System.nanoTime();
                try {
                    System.out.println("\n=== Processing file: " + file.getName() + " ===");
                    String url = "file://" + file.getAbsolutePath();
                    String content = readFileContent(file);
                    Document doc = Jsoup.parse(content);
                    Map<String, Tokenizer.Token> tokens = tokenizer.tokenizeDocument(doc);
                    long fileEnd = System.nanoTime();
                    System.out.printf("File %s processed in %.2f ms%n", 
                        file.getName(), (fileEnd - fileStart) / 1_000_000.0);
                    return Map.entry(url, tokens);
                } catch (IOException e) {
                    String error = String.format("Error processing %s: %s", file.getName(), e.getMessage());
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
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        
        System.out.println("\n=== Performance Metrics ===");
        System.out.printf("Total indexing time: %.2f seconds%n", (endTime - startTime) / 1_000_000_000.0);
        System.out.printf("Tokenization time: %.2f seconds%n", (tokenizationEnd - tokenizationStart) / 1_000_000_000.0);
        System.out.printf("Database time: %.2f seconds%n", (dbEnd - dbStart) / 1_000_000_000.0);
        System.out.printf("Average time per document: %.2f ms%n", 
            (endTime - startTime) / (results.size() * 1_000_000.0));
        System.out.printf("Memory used: %.2f MB%n", (finalMemory - initialMemory) / (1024.0 * 1024.0));
        System.out.println("=========================");

        if (!errors.isEmpty()) {
            System.err.println("\nErrors occurred while indexing:");
            errors.forEach(System.err::println);
            throw new IOException("Errors occurred while indexing:\n" + String.join("\n", errors));
        }
        
        System.out.println("\nSuccessfully indexed all files in directory");
    }

    public List<SQLiteSearcher.SearchResult> search(String query) throws SQLException {
        System.out.println("\nExecuting search for query: \"" + query + "\"");
        List<SQLiteSearcher.SearchResult> results = searcher.search(query, tokenizer);
        System.out.println("Found " + results.size() + " results");
        return results;
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

    public static void main(String[] args) {
        SQLiteSearcher searcher = null;
        try {
            searcher = new SQLiteSearcher();
            Tokenizer tokenizer = new Tokenizer();
            Indexer indexer = new Indexer(searcher, tokenizer);
            
            // Get the current working directory
            String currentDir = System.getProperty("user.dir");
            System.out.println("Current working directory: " + currentDir);
            
            // Create path to the filesToIndex directory
            File directory = new File(currentDir, "Search-Engine/src/main/resources/filesToIndex");
            System.out.println("Looking for directory at: " + directory.getAbsolutePath());
            
            if (!directory.exists()) {
                System.err.println("Error: Directory 'filesToIndex' not found. Please create it in: " + currentDir);
                return;
            }
            
            if (!directory.isDirectory()) {
                System.err.println("Error: 'filesToIndex' exists but is not a directory");
                return;
            }

            System.out.println("Starting to index directory: " + directory.getAbsolutePath());
            indexer.indexDirectory(directory);
            System.out.println("Successfully indexed all files in directory");
            
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