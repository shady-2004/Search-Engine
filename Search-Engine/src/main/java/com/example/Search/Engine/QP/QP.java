package com.example.Search.Engine.QP;

import com.example.Search.Engine.PS.PS;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class QP {
    private static TokenizerME tokenizer;

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
                "Traveling is one of the most exciting activities for people who love adventure. Travelers often explore new places and cultures, making their journeys unforgettable.",
                "The traveler was tired after a long day of sightseeing. Despite the exhaustion, the experience of traveling was worth every moment.",
                "Beautiful beaches attract tourists from all over the world. The beauty of nature is truly mesmerizing, and it leaves visitors in awe.",
                "Jumping into the pool on a hot summer day feels amazing. People who enjoy jumps and leaps often find joy in outdoor activities.",
                "Running is a great way to stay fit. Runners often participate in marathons to challenge themselves and improve their endurance."
        };

        String query = "staying";
        Set<String> queryStems = tokenizeAndStem(query);
        List<Integer> relevantDocuments = searchInDocument(documents, queryStems);

        System.out.println("Relevant Documents:");
        for (int docIndex : relevantDocuments) {
            System.out.println("Document " + (docIndex + 1) + ": " + documents[docIndex]);
        }
    }

    public Set<String> tokenizeAndStem(String text) {
        String[] tokens = tokenizer.tokenize(text.toLowerCase());
        Set<String> stems = new HashSet<>();
        Stemmer stemmer = new Stemmer();

        for (String token : tokens) {
            if (!token.isEmpty()) {
                stemmer.add(token.toCharArray(), token.length());
                stemmer.stem();
                stems.add(stemmer.toString());
            }
        }

        return stems;
    }

    public List<Integer> search(String[] documents, String query) {
        Set<String> queryStems = tokenizeAndStem(query);
        return searchInDocument(documents, queryStems);
    }

    private List<Integer> searchInDocument(String[] documents, Set<String> queryStems) {
        List<Integer> relevantDocuments = new ArrayList<>();

        for (int i = 0; i < documents.length; i++) {
            Set<String> documentStems = tokenizeAndStem(documents[i]);

            for (String queryStem : queryStems) {
                if (documentStems.contains(queryStem)) {
                    relevantDocuments.add(i);
                    break;
                }
            }
        }

        return relevantDocuments;
    }
}