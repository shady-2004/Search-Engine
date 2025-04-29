package com.example.Search.Engine.Indexer;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

//Component responsible for tokenizing text and HTML documents.
//Handles stopword removal, word validation, and position-based weighting.
@Component
public class Tokenizer {
    private final Set<String> stopWords;
    private final Pattern wordPattern;
    private static final int MIN_WORD_LENGTH = 2;
    private static final int MAX_WORD_LENGTH = 45;
    
    // Position weights
    public static final double TITLE_WEIGHT = 3.0;
    public static final double H1_WEIGHT = 2.0;
    public static final double H2_WEIGHT = 1.5;
    public static final double CONTENT_WEIGHT = 1.0;

    public static class Token {
        private final String word;
        private double count;
        private String position;

        public Token(String word, double count, String position) {
            this.word = word;
            this.count = count;
            this.position = position;
        }

        public String getWord() {
            return word;
        }

        public double getCount() {
            return count;
        }

        public void setCount(double count) {
            this.count = count;
        }

        public String getPosition() {
            return position;
        }

        public void setPosition(String position) {
            this.position = position;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Token token = (Token) o;
            return Double.compare(token.count, count) == 0 &&
                Objects.equals(word, token.word) &&
                Objects.equals(position, token.position);
        }

        @Override
        public int hashCode() {
            return Objects.hash(word, count, position);
        }

        @Override
        public String toString() {
            return "Token{" +
                "word='" + word + '\'' +
                ", count=" + count +
                ", position='" + position + '\'' +
                '}';
        }
    }

    public Tokenizer() {
        this.stopWords = new HashSet<>();
        this.wordPattern = Pattern.compile("\\b[\\w']+\\b");
        loadStopWords();
    }

    private void loadStopWords() {
        try {
            ClassPathResource resource = new ClassPathResource("stopwords-en.txt");
            try (InputStream is = resource.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim().toLowerCase();
                    if (!line.isEmpty()) {
                        stopWords.add(line);
                    }
                }
            }
            System.out.println("Successfully loaded " + stopWords.size() + " stopwords");
        } catch (IOException e) {
            System.err.println("Warning: Could not load stopwords file. Proceeding without stopwords: " + e.getMessage());
        }
    }

    //Tokenizes a string into a list of words.
    public List<String> tokenizeString(String text, boolean removeStopWords) {
        List<String> tokens = new ArrayList<>();
        var matcher = wordPattern.matcher(text.toLowerCase());
        
        while (matcher.find()) {
            String word = matcher.group();
            if (isValidWord(word, removeStopWords)) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    private boolean isValidWord(String word, boolean removeStopWords) {
        return word.length() >= MIN_WORD_LENGTH && 
            word.length() <= MAX_WORD_LENGTH &&
            (!removeStopWords || !stopWords.contains(word));
    }

    //Tokenizes an HTML document, processing different elements with appropriate weights.
    public Map<String, Token> tokenizeDocument(Document doc) {
        ConcurrentHashMap<String, Token> tokens = new ConcurrentHashMap<>();
        AtomicInteger totalTokens = new AtomicInteger(0);

        // Process title with highest weight
        CompletableFuture<Void> titleFuture = CompletableFuture.runAsync(() -> {
            String title = doc.title();
            processText(title, tokens, "title");
            totalTokens.addAndGet(countTokens(title));
        });

        // Process headings in parallel
        CompletableFuture<Void> h1Future = CompletableFuture.runAsync(() -> {
            Elements h1s = doc.select("h1");
            h1s.parallelStream().forEach(h1 -> {
                processText(h1.text(), tokens, "h1");
                totalTokens.addAndGet(countTokens(h1.text()));
            });
        });

        CompletableFuture<Void> h2Future = CompletableFuture.runAsync(() -> {
            Elements h2s = doc.select("h2");
            h2s.parallelStream().forEach(h2 -> {
                processText(h2.text(), tokens, "h2");
                totalTokens.addAndGet(countTokens(h2.text()));
            });
        });

        // Process regular content in parallel
        CompletableFuture<Void> contentFuture = CompletableFuture.runAsync(() -> {
            Elements paragraphs = doc.select("p");
            paragraphs.parallelStream().forEach(p -> {
                processText(p.text(), tokens, "content");
                totalTokens.addAndGet(countTokens(p.text()));
            });
        });

        // Wait for all processing to complete
        CompletableFuture.allOf(titleFuture, h1Future, h2Future, contentFuture).join();

        // Normalize frequencies
        int finalTotalTokens = totalTokens.get();
        if (finalTotalTokens > 0) {
            tokens.values().parallelStream().forEach(token -> 
                token.setCount(token.getCount() / finalTotalTokens));
        }

        System.out.println("Processed document with " + finalTotalTokens + " total tokens");
        return new HashMap<>(tokens);
    }

    private void processText(String text, ConcurrentHashMap<String, Token> tokens, String position) {
        tokenizeString(text, true).parallelStream().forEach(word -> {
            tokens.compute(word, (key, existingToken) -> {
                if (existingToken == null) {
                    return new Token(word, 1.0, position);
                } else {
                    existingToken.setCount(existingToken.getCount() + 1.0);
                    if (getPositionWeight(position) > getPositionWeight(existingToken.getPosition())) {
                        existingToken.setPosition(position);
                    }
                    return existingToken;
                }
            });
        });
    }

    private int countTokens(String text) {
        return (int) tokenizeString(text, true).stream()
            .filter(word -> isValidWord(word, true))
            .count();
    }

    private double getPositionWeight(String position) {
        return switch (position) {
            case "title" -> TITLE_WEIGHT;
            case "h1" -> H1_WEIGHT;
            case "h2" -> H2_WEIGHT;
            default -> CONTENT_WEIGHT;
        };
    }
} 