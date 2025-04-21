package com.example.Search.Engine.Ranker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.Math.log;

public class Ranker {
    static final int threads = 10;

    private Integer docScore(TestData.DocumentData docData) {
        int score = 0;

        List<String> queryTerms = TestData.getQueryTerms();
        Map<String, Integer> TermFrequencies = docData.getTermFrequencies();
        Map<String, Integer> DocumentFrequencies = TestData.getDocumentFrequencies();

        int N = TestData.getTotalDocuments();
        for (String queryTerm : queryTerms) {
            score += (int) (TermFrequencies.get(queryTerm) * log((double) N / DocumentFrequencies.get(queryTerm)));
        }
        return score;
    }

    public List<Integer> Rank() throws InterruptedException {
        List<TestData.DocumentData> Documents = TestData.getCandidateDocuments();
        ConcurrentHashMap<Integer, Integer> results = new ConcurrentHashMap<>();

        List<Integer> rankedDocs = new ArrayList<>();

        List<RankParallel> threadList = getRankParallels(Documents, results);

        for(RankParallel t:threadList)
            t.join();

        List<Map.Entry<Integer, Integer>> sortedDocs = new ArrayList<>(results.entrySet());
        sortedDocs.sort((d1, d2) -> d2.getValue().compareTo(d1.getValue()));

        for (Map.Entry<Integer, Integer> entry : sortedDocs) {
            rankedDocs.add(entry.getKey());
        }

        return rankedDocs;
    }

    private List<RankParallel> getRankParallels(List<TestData.DocumentData> Documents, ConcurrentHashMap<Integer, Integer> results) {
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
        ConcurrentHashMap<Integer, Integer> results;

        RankParallel(int start, int end, List<TestData.DocumentData> Documents, ConcurrentHashMap<Integer, Integer> results) {
            this.start = start;
            this.end = end;
            this.Documents = Documents;
            this.results = results;
        }

        public void run() {

            Integer score;

            for (int i = start; i < end; i++) {
                score = docScore(Documents.get(i));
                results.put(Documents.get(i).getDocId(), score);
            }
        }
    }
}
