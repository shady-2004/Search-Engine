package com.example.Search.Engine.Ranker;

import com.example.Search.Engine.QP.QueryIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestRanker {

    @Test
    void testRanker() throws InterruptedException {
        // arrange
        List<QueryIndex.DocumentData> documents = new ArrayList<>();
        List<String> queryTerms = new ArrayList<>();

        // act
        List<Integer> ranked = Ranker.rank(documents, queryTerms);

        // assert (use actual assertions for real tests)
        assertNotNull(ranked); // Basic check
        assertFalse(ranked.isEmpty(), "Ranked list should not be empty");

        // Debug print (optional)
        for (Integer rank : ranked)
            System.out.println(rank);
    }
}
