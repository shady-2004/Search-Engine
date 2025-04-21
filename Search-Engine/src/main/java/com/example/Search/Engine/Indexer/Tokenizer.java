package com.example.Search.Engine.Indexer;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;

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
        Map<String, Token> tokens = new HashMap<>();
        int totalTokens = 0;

        // Process title with highest weight
        String title = doc.title();
        processText(title, tokens, "title");
        totalTokens += countTokens(title);

        // Process headings
        Elements h1s = doc.select("h1");
        for (Element h1 : h1s) {
            processText(h1.text(), tokens, "h1");
            totalTokens += countTokens(h1.text());
        }

        Elements h2s = doc.select("h2");
        for (Element h2 : h2s) {
            processText(h2.text(), tokens, "h2");
            totalTokens += countTokens(h2.text());
        }

        // Process regular content
        Elements paragraphs = doc.select("p");
        for (Element p : paragraphs) {
            processText(p.text(), tokens, "content");
            totalTokens += countTokens(p.text());
        }

        // Normalize frequencies
        if (totalTokens > 0) {
            for (Token token : tokens.values()) {
                token.count /= totalTokens;
            }
        }

        System.out.println("Processed document with " + totalTokens + " total tokens");
        return tokens;
    }

    private void processText(String text, Map<String, Token> tokens, String position) {
        List<String> words = tokenizeString(text, true);
        for (String word : words) {
            Token token = tokens.get(word);
            if (token == null) {
                token = new Token(word, 1.0, position);
                tokens.put(word, token);
            } else {
                token.setCount(token.getCount() + 1.0);
                // Keep the highest weighted position
                if (getPositionWeight(position) > getPositionWeight(token.getPosition())) {
                    token.setPosition(position);
                }
            }
        }
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