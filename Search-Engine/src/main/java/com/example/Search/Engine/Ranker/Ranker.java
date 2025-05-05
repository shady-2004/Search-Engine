package com.example.Search.Engine.Ranker;

import com.example.Search.Engine.QP.QueryIndex;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import static com.example.Search.Engine.Data.DataBaseManager.getPageRank;
@Service
public class Ranker {
    static final int THREADS = 10;
    public static final double TFIDF_WEIGHT = 0.7;
    public static final double PAGERANK_WEIGHT = 0.3;
    static final int THREADING_THRESHOLD = 1000;

    private double docScore(QueryIndex.DocumentData docData, List<String> queryTerms) {
        double tfidfScore = 0.0;

        Map<String, List<Double>> wordInfo = docData.getWordInfo();
        for (String queryTerm : queryTerms) {
            // Use pre-stemmed query term directly
            List<Double> info = wordInfo.get(queryTerm);
            if (info != null && info.size() >= 2) {
                double tf = info.get(0); // Term frequency
                double idf = info.get(1); // IDF
                double importance = info.size() > 2 ? info.get(2) : 1.0; // Importance (default to 1.0 if not present)
                tfidfScore += tf * idf * importance;
            }
        }

        double pageRank = docData.getPageRank(); // Use getter for pageRank
        //System.out.println(TFIDF_WEIGHT +" " + tfidfScore+ " " + PAGERANK_WEIGHT + " " + pageRank);
        return TFIDF_WEIGHT * tfidfScore + PAGERANK_WEIGHT * pageRank;
    }

    // Overload rank method to accept List<String>
    public List<Map.Entry<Integer, Double>> rank(List<QueryIndex.DocumentData> documents, List<String> queryTerms) throws InterruptedException {
        return rankDocuments(documents, queryTerms);
    }

    // Overload rank method to accept Set<String>
    public List<Map.Entry<Integer, Double>> rank(List<QueryIndex.DocumentData> documents, Set<String> queryTerms) throws InterruptedException {
        return rankDocuments(documents, new ArrayList<>(queryTerms));
    }

    private List<Map.Entry<Integer, Double>> rankDocuments(List<QueryIndex.DocumentData> documents, List<String> queryTerms) throws InterruptedException {
        ConcurrentHashMap<Integer, Double> results = new ConcurrentHashMap<>();
        // Fetch PageRank values for all documents before scoring
        try {
            long startTime = System.nanoTime();
            getPageRank(documents);
            long endTime = System.nanoTime();
            System.out.println(documents);
            System.out.println("getPageRank calculation time: " + (endTime - startTime) / 1000000 + " milliseconds");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch PageRank values", e);
        }

        if (documents.size() < THREADING_THRESHOLD) {
            // Single-threaded processing
            for (QueryIndex.DocumentData doc : documents) {
                double score = docScore(doc, queryTerms);
                results.put(doc.getDocId(), score);
                //System.out.println("Document ID: " + doc.getDocId() + " Score: " + score);
            }
        } else {
            // Multi-threaded processing
            List<RankParallel> threadList = getRankParallels(documents, queryTerms, results);
            for (RankParallel t : threadList) {
                t.join();
            }
        }

        // Sort documents by score in descending order
        List<Map.Entry<Integer, Double>> sortedDocs = new ArrayList<>(results.entrySet());
        sortedDocs.sort((d1, d2) -> d2.getValue().compareTo(d1.getValue()));

        return sortedDocs;
    }

    private List<RankParallel> getRankParallels(List<QueryIndex.DocumentData> documents, List<String> queryTerms, ConcurrentHashMap<Integer, Double> results) {
        int N = documents.size();
        int docsPerThread;
        int maxThread;
        if (N > THREADS) {
            docsPerThread = N / THREADS;
            maxThread = THREADS;
        } else {
            maxThread = N;
            docsPerThread = 1;
        }

        List<RankParallel> threadList = new ArrayList<>();

        for (int i = 0; i < maxThread; i++) {
            RankParallel t;
            if (i != maxThread - 1) {
                t = new RankParallel(i * docsPerThread, (i + 1) * docsPerThread, documents, queryTerms, results);
            } else {
                t = new RankParallel(i * docsPerThread, N, documents, queryTerms, results);
            }
            threadList.add(t);
            t.start();
        }
        return threadList;
    }

    private class RankParallel extends Thread {
        int start;
        int end;
        List<QueryIndex.DocumentData> documents;
        List<String> queryTerms;
        ConcurrentHashMap<Integer, Double> results;

        RankParallel(int start, int end, List<QueryIndex.DocumentData> documents, List<String> queryTerms, ConcurrentHashMap<Integer, Double> results) {
            this.start = start;
            this.end = end;
            this.documents = documents;
            this.queryTerms = queryTerms;
            this.results = results;
        }

        public void run() {
            for (int i = start; i < end; i++) {
                double score = docScore(documents.get(i), queryTerms);
                results.put(documents.get(i).getDocId(), score);
            }
        }
    }
}