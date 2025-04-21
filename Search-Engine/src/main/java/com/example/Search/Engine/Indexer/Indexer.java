package com.example.Search.Engine.Indexer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import java.io.*;
import java.sql.SQLException;
import java.util.*;

@Component
public class Indexer implements AutoCloseable {
    private final SQLiteSearcher searcher;
    private final Tokenizer tokenizer;

    public Indexer() throws SQLException {
        this.searcher = new SQLiteSearcher();
        this.tokenizer = new Tokenizer();
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
        List<String> errors = new ArrayList<>();

        for (File file : files) {
            try {
                System.out.println("\n=== Processing file: " + file.getName() + " ===");
                indexFile(file);
            } catch (IOException e) {
                String error = String.format("Error indexing %s: %s", file.getName(), e.getMessage());
                System.err.println(error);
                errors.add(error);
            }
        }

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
        try (Indexer indexer = new Indexer()) {
            String filename = "sample3.html";
            ClassPathResource resource = new ClassPathResource(filename);
            File sampleFile = resource.getFile();
            
            if (!sampleFile.exists()) {
                System.err.println(filename + " not found at: " + sampleFile.getAbsolutePath());
                return;
            }
            
            System.out.println("Found " + filename + " at: " + sampleFile.getAbsolutePath());
            
            try {
                System.out.println("Indexing " + filename);
                indexer.indexFile(sampleFile);
                System.out.println("Successfully indexed " + filename);
            } catch (IOException e) {
                System.err.println("Error indexing file: " + e.getMessage());
            }
        } catch (Exception e) {
            System.err.println("Error initializing indexer: " + e.getMessage());
            e.printStackTrace();
        }
    }
}