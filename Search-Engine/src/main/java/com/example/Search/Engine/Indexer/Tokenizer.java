package com.example.Search.Engine.Indexer;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.example.Search.Engine.QP.Stemmer;
import java.util.stream.IntStream;

//Component responsible for tokenizing text and HTML documents.
//Handles stopword removal, word validation, and position-based weighting.
@Component
public class Tokenizer {
    private final Set<String> stopWords;
    private final Pattern wordPattern;
    private static final int MIN_WORD_LENGTH = 2;
    private static final int MAX_WORD_LENGTH = 45;
    
    // Position weights
    public static final double TITLE_WEIGHT = 5.0;    // Most important - page title
    public static final double H1_WEIGHT = 4.0;       // Main heading
    public static final double H2_WEIGHT = 3.0;       // Sub-heading
    public static final double H3_WEIGHT = 2.5;       // Sub-sub-heading
    public static final double H4_WEIGHT = 2.0;       // Minor heading
    public static final double H5_WEIGHT = 1.8;       // Minor heading
    public static final double H6_WEIGHT = 1.5;       // Minor heading
    public static final double CONTENT_WEIGHT = 1.0;  // Regular content

    public static class Token {
        private final String word;
        private double count;
        private String position;
        private final List<Integer> positions;

        public Token(String word, double count, String position) {
            this.word = word;
            this.count = count;
            this.position = position;
            this.positions = new ArrayList<>();
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

        public List<Integer> getPositions() {
            return positions;
        }

        public void addPosition(int position) {
            positions.add(position);
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
                Stemmer stemmer = new Stemmer();
                stemmer.add(word.toCharArray(), word.length());
                stemmer.stem();
                tokens.add(stemmer.toString());
            }
        }
        return tokens;
    }

    private boolean isValidWord(String word, boolean removeStopWords) {
        return word.length() >= MIN_WORD_LENGTH && 
            word.length() <= MAX_WORD_LENGTH &&
            word.matches("^[a-zA-Z]+$") && // Only allow letters, no apostrophes
            (!removeStopWords || !stopWords.contains(word));
    }

    //Tokenizes an HTML document, processing different elements with appropriate weights.
    public Map<String, Token> tokenizeDocument(Document doc) {
        ConcurrentHashMap<String, Token> tokens = new ConcurrentHashMap<>();
        AtomicInteger totalTokens = new AtomicInteger(0);

        CompletableFuture<Void> titleFuture = CompletableFuture.runAsync(() -> {
            String title = doc.title();
            if (title != null && !title.isEmpty()) {
                processText(title, tokens, "title");
                totalTokens.addAndGet(countTokens(title));
            }
        });

        CompletableFuture<Void> headersFuture = CompletableFuture.runAsync(() -> {
            // Process all header levels
            IntStream.rangeClosed(1, 6).forEach(i -> {
                Elements headers = doc.select("h" + i);
                if (!headers.isEmpty()) {
                    headers.parallelStream().forEach(header -> {
                        String text = header.text();
                        if (!text.isEmpty()) {
                            processText(text, tokens, "h" + i);
                            totalTokens.addAndGet(countTokens(text));
                        }
                    });
                }
            });
        });

        CompletableFuture<Void> contentFuture = CompletableFuture.runAsync(() -> {
            // Process paragraphs
            Elements paragraphs = doc.select("p");
            if (!paragraphs.isEmpty()) {
                paragraphs.parallelStream().forEach(p -> {
                    String text = p.text();
                    if (!text.isEmpty()) {
                        processText(text, tokens, "content");
                        totalTokens.addAndGet(countTokens(text));
                    }
                });
            }

            // Process divs with text content
            Elements divs = doc.select("div");
            if (!divs.isEmpty()) {
                divs.parallelStream().forEach(div -> {
                    String text = div.text();
                    if (!text.isEmpty()) {
                        processText(text, tokens, "content");
                        totalTokens.addAndGet(countTokens(text));
                    }
                });
            }

            // Process list items
            Elements listItems = doc.select("li");
            if (!listItems.isEmpty()) {
                listItems.parallelStream().forEach(li -> {
                    String text = li.text();
                    if (!text.isEmpty()) {
                        processText(text, tokens, "content");
                        totalTokens.addAndGet(countTokens(text));
                    }
                });
            }

            // Process spans
            Elements spans = doc.select("span");
            if (!spans.isEmpty()) {
                spans.parallelStream().forEach(span -> {
                    String text = span.text();
                    if (!text.isEmpty()) {
                        processText(text, tokens, "content");
                        totalTokens.addAndGet(countTokens(text));
                    }
                });
            }

            // Process articles and sections
            Elements articles = doc.select("article, section");
            if (!articles.isEmpty()) {
                articles.parallelStream().forEach(article -> {
                    String text = article.text();
                    if (!text.isEmpty()) {
                        processText(text, tokens, "content");
                        totalTokens.addAndGet(countTokens(text));
                    }
                });
            }
        });

        CompletableFuture.allOf(titleFuture, headersFuture, contentFuture).join();

        int finalTotalTokens = totalTokens.get();
        if (finalTotalTokens > 0) {
            tokens.values().parallelStream().forEach(token -> 
                token.setCount(token.getCount() / finalTotalTokens));
        }

        return new HashMap<>(tokens);
    }

    private void processText(String text, ConcurrentHashMap<String, Token> tokens, String position) {
        List<String> words = tokenizeString(text, true);
        if (words.isEmpty()) {
            return;
        }
        
        double positionWeight = getPositionWeight(position);
        
        for (int i = 0; i < words.size(); i++) {
            final int positionIndex = i;
            String word = words.get(i);
            tokens.compute(word, (key, existingToken) -> {
                if (existingToken == null) {
                    Token newToken = new Token(word, positionWeight, position);
                    newToken.addPosition(positionIndex);
                    return newToken;
                } else {
                    existingToken.setCount(existingToken.getCount() + positionWeight);
                    existingToken.addPosition(positionIndex);
                    if (getPositionWeight(position) > getPositionWeight(existingToken.getPosition())) {
                        existingToken.setPosition(position);
                    }
                    return existingToken;
                }
            });
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
            case "h3" -> H3_WEIGHT;
            case "h4" -> H4_WEIGHT;
            case "h5" -> H5_WEIGHT;
            case "h6" -> H6_WEIGHT;
            default -> CONTENT_WEIGHT;
        };
    }
} 