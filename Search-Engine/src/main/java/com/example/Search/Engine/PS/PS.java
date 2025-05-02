package com.example.Search.Engine.PS;

import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.springframework.stereotype.Service;

@Service
public class PS {
    private static final TokenizerME tokenizer;

    static {
        // Load the tokenizer model from the classpath
        try {
            InputStream modelIn = PS.class.getClassLoader().getResourceAsStream("models/en-token.bin");
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

    public void main(String[] args) {
        String[] documents = {
                "traveling is fun is one of the most exciting activities for people who love adventure.",
                "The traveler was tired after a long day of sightseeing. Despite the exhaustion, the experience of traveling was worth every moment.",
                "attract tourists from all over the world. The beauty of nature is truly mesmerizing, and it leaves visitors in awe.",
                "Jumping into the pool on a hot summer day feels amazing. People who enjoy jumps and leaps often find joy in outdoor activities.",
                "Running is a great way to stay fit. Runners often participate in marathons to challenge themselves and improve their endurance."
        };

        String query1 = "\"traveling is fun\" OR \"Beautiful beaches\"";
        String query2 = "\"Traveling\" NOT \"tired\"";
        String query3 = "\"Beautiful beaches\" AND \"tourists\"";
        String query4 = "\"traveling is fun\"";

        System.out.println("Starting query processing...");
        processQuery(documents, query1);
        processQuery(documents, query2);
        processQuery(documents, query3);
        processQuery(documents, query4);
    }

    private void processQuery(String[] documents, String query) {
        System.out.println("\nProcessing Query: " + query);
        List<Integer> results = search(documents, query);
        printResults(documents, results);
    }

    public List<Integer> search(String[] documents, String query) {
        String[] parts = splitQuery(query);
        String operator = detectOperator(query);
        boolean isPhrase = isMultiWordPhrase(query);

        if (parts.length == 1) {
            return searchSinglePhrase(documents, query, isPhrase);
        }

        List<Integer> leftResults = searchSinglePhrase(documents, parts[0], isMultiWordPhrase(parts[0]));
        List<Integer> rightResults = searchSinglePhrase(documents, parts[1], isMultiWordPhrase(parts[1]));

        return switch (operator) {
            case "OR" -> {
                Set<Integer> combined = new HashSet<>(leftResults);
                combined.addAll(rightResults);
                yield new ArrayList<>(combined);
            }
            case "AND" -> {
                Set<Integer> leftSet = new HashSet<>(leftResults);
                leftSet.retainAll(rightResults);
                yield new ArrayList<>(leftSet);
            }
            case "NOT" -> {
                Set<Integer> leftSet = new HashSet<>(leftResults);
                leftSet.removeAll(rightResults);
                yield new ArrayList<>(leftSet);
            }
            default -> Collections.emptyList();
        };
    }

    private List<Integer> searchSinglePhrase(String[] documents, String query, boolean isPhrase) {
        List<Integer> matchingDocuments = new ArrayList<>();
        if (query == null || query.isEmpty()) {
            return matchingDocuments;
        }

        String[] queryTokens = preprocessQuery(query);
        String searchPhrase = String.join(" ", queryTokens).toLowerCase();

        for (int i = 0; i < documents.length; i++) {
            if (documents[i] == null) {
                continue;
            }
            String[] docTokens = tokenizer.tokenize(documents[i]);
            String docText = String.join(" ", docTokens).toLowerCase();

            if (isPhrase) {
                if (docText.contains(searchPhrase)) {
                    matchingDocuments.add(i);
                }
            } else {
                boolean allTokensPresent = Arrays.stream(queryTokens)
                        .allMatch(token -> docText.contains(token.toLowerCase()));
                if (allTokensPresent) {
                    matchingDocuments.add(i);
                }
            }
        }
        return matchingDocuments;
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

    private boolean isMultiWordPhrase(String query) {
        return query.startsWith("\"") && query.endsWith("\"") && query.contains(" ");
    }

    private String[] preprocessQuery(String query) {
        query = query.replaceAll("^\"|\"$", "").trim();
        return tokenizer.tokenize(query);
    }

    private void printResults(String[] documents, List<Integer> results) {
        if (results.isEmpty()) {
            System.out.println("No matching documents found.");
        } else {
            for (int index : results) {
                System.out.println("Document " + (index + 1) + ": " + documents[index]);
            }
        }
    }
}