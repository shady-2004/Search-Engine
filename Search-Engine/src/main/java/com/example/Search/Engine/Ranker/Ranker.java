package com.example.Search.Engine.Ranker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.Math.log;

import org.springframework.stereotype.Service;

@Service
public class Ranker {
    static final int threads = 10;
    static final double TFIDF_WEIGHT = 0.7;
    static final double PAGERANK_WEIGHT = 0.3;
    static final int THREADING_THRESHOLD = 1000;

    private Double docScore(TestData.DocumentData docData) {
        double tfidfScore = 0.0;

        List<String> queryTerms = TestData.getQueryTerms();
        Map<String, Integer> termFrequencies = docData.getTermFrequencies();
        Map<String, Integer> documentFrequencies = TestData.getDocumentFrequencies();

        int N = TestData.getTotalDocuments();
        for (String queryTerm : queryTerms) {
            int tf = termFrequencies.getOrDefault(queryTerm, 0);
            int df = documentFrequencies.getOrDefault(queryTerm, 1); // avoid division by zero
            tfidfScore += tf * log((double) N / df);
        }

        double pageRank = docData.getPageRank(); // Assumes this method exists


        return TFIDF_WEIGHT * tfidfScore + PAGERANK_WEIGHT * pageRank;
    }

    public List<Integer> Rank() throws InterruptedException {
        List<TestData.DocumentData> documents = TestData.getCandidateDocuments();
        ConcurrentHashMap<Integer, Double> results = new ConcurrentHashMap<>();
        List<Integer> rankedDocs = new ArrayList<>();

        if (documents.size() < THREADING_THRESHOLD) {
            // Single-threaded processing
            for (TestData.DocumentData doc : documents) {
                double score = docScore(doc);
                results.put(doc.getDocId(), score);
            }
        } else {
            // Multi-threaded processing
            List<RankParallel> threadList = getRankParallels(documents, results);
            for (RankParallel t : threadList) {
                t.join();
            }
        }

        List<Map.Entry<Integer, Double>> sortedDocs = new ArrayList<>(results.entrySet());
        sortedDocs.sort((d1, d2) -> d2.getValue().compareTo(d1.getValue()));

        for (Map.Entry<Integer, Double> entry : sortedDocs) {
            rankedDocs.add(entry.getKey());
        }

        return rankedDocs;
    }

    private List<RankParallel> getRankParallels(List<TestData.DocumentData> Documents, ConcurrentHashMap<Integer, Double> results) {
        int N = Documents.size();
        int docsPerThread;
        int maxThread;
        if (N > threads) {
            docsPerThread = N / threads;
            maxThread = threads;
        } else {
            maxThread = N;
            docsPerThread = 1;
        }

        List<RankParallel>threadList=new ArrayList<>();

        for (int i = 0; i < maxThread; i++) {
            RankParallel t;
            if (i != maxThread - 1)
                t= new RankParallel(i * docsPerThread, (i + 1) * docsPerThread, Documents, results);

            else t= new RankParallel(i * docsPerThread, N, Documents, results);
            threadList.add(t);
            t.start();
        }
        return threadList;
    }


    private class RankParallel extends Thread {

        int start;
        int end;

        List<TestData.DocumentData> Documents;
        ConcurrentHashMap<Integer, Double> results;

        RankParallel(int start, int end, List<TestData.DocumentData> Documents, ConcurrentHashMap<Integer, Double> results) {
            this.start = start;
            this.end = end;
            this.Documents = Documents;
            this.results = results;
        }

        public void run() {

            Double score;

            for (int i = start; i < end; i++) {
                score = docScore(Documents.get(i));
                results.put(Documents.get(i).getDocId(), score);
            }
        }
    }
}

