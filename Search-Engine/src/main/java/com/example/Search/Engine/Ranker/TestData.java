package com.example.Search.Engine.Ranker;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestData {
    // Sample query terms
    private static final List<String> queryTerms = Arrays.asList("banana", "recipe");

    // Sample candidate documents with their metadata (title term frequencies removed)
    private static final List<DocumentData> candidateDocuments = Arrays.asList(
            new DocumentData(
                    101,
                    new HashMap<String, Integer>() {{
                        put("banana", 4);
                        put("recipe", 3);
                    }},
                    180,
                    0.9
            ),
            new DocumentData(
                    102,
                    new HashMap<String, Integer>() {{
                        put("banana", 2);
                        put("recipe", 1);
                    }},
                    120,
                    0.5
            ),
            new DocumentData(
                    103,
                    new HashMap<String, Integer>() {{
                        put("banana", 1);
                        put("recipe", 0);
                    }},
                    90,
                    0.3
            ),
            new DocumentData(
                    104,
                    new HashMap<String, Integer>() {{
                        put("banana", 3);
                        put("recipe", 2);
                    }},
                    220,
                    0.7
            ),
            new DocumentData(
                    105,
                    new HashMap<String, Integer>() {{
                        put("banana", 0);
                        put("recipe", 2);
                    }},
                    70,
                    0.4
            ),
            new DocumentData(
                    106,
                    new HashMap<String, Integer>() {{
                        put("banana", 1);
                        put("recipe", 3);
                    }},
                    64,
                    0.45
            ),
            new DocumentData(
                    107,
                    new HashMap<String, Integer>() {{
                        put("banana", 2);
                        put("recipe", 1);
                    }},
                    52,
                    0.30
            ),
            new DocumentData(
                    108,
                    new HashMap<String, Integer>() {{
                        put("banana", 0);
                        put("recipe", 4);
                    }},
                    68,
                    0.50
            ),
            new DocumentData(
                    109,
                    new HashMap<String, Integer>() {{
                        put("banana", 3);
                        put("recipe", 2);
                    }},
                    70,
                    0.60
            ),
            new DocumentData(
                    110,
                    new HashMap<String, Integer>() {{
                        put("banana", 2);
                        put("recipe", 0);
                    }},
                    47,
                    0.28
            ),
            new DocumentData(
                    111,
                    new HashMap<String, Integer>() {{
                        put("banana", 4);
                        put("recipe", 1);
                    }},
                    75,
                    0.53
            ),
            new DocumentData(
                    112,
                    new HashMap<String, Integer>() {{
                        put("banana", 1);
                        put("recipe", 1);
                    }},
                    49,
                    0.33
            ),
            new DocumentData(
                    113,
                    new HashMap<String, Integer>() {{
                        put("banana", 0);
                        put("recipe", 2);
                    }},
                    56,
                    0.41
            ),
            new DocumentData(
                    114,
                    new HashMap<String, Integer>() {{
                        put("banana", 3);
                        put("recipe", 3);
                    }},
                    80,
                    0.66
            ),
            new DocumentData(
                    115,
                    new HashMap<String, Integer>() {{
                        put("banana", 2);
                        put("recipe", 2);
                    }},
                    62,
                    0.39
            )

    );

    // Sample document frequency (df(t)) for query terms
    private static final Map<String, Integer> documentFrequencies = new HashMap<String, Integer>() {{
        put("banana", 150);
        put("recipe", 80);
    }};

    private static final int totalDocuments = 10000;

    // Getter methods
    public static List<String> getQueryTerms() {
        return queryTerms;
    }

    public static List<DocumentData> getCandidateDocuments() {
        return candidateDocuments;
    }

    public static Map<String, Integer> getDocumentFrequencies() {
        return documentFrequencies;
    }

    public static int getTotalDocuments() {
        return totalDocuments;
    }

    // Inner class (title term frequency removed)
    public static class DocumentData {
        private final int docId;
        private final Map<String, Integer> termFrequencies;
        private final int documentLength;
        private final double pageRank;

        public DocumentData(int docId, Map<String, Integer> termFrequencies,
                            int documentLength, double pageRank) {
            this.docId = docId;
            this.termFrequencies = termFrequencies;
            this.documentLength = documentLength;
            this.pageRank = pageRank;
        }

        public int getDocId() {
            return docId;
        }

        public Map<String, Integer> getTermFrequencies() {
            return termFrequencies;
        }

        public int getDocumentLength() {
            return documentLength;
        }

        public double getPageRank() {
            return pageRank;
        }
    }
}
