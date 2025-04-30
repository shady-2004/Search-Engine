package com.example.Search.Engine.Ranker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestRanker {

    @Test
    void testRanker() throws InterruptedException {
        // arrange
        Ranker ranker = new Ranker();

        // act
        List<Integer> ranked = ranker.Rank();

        // assert (use actual assertions for real tests)
        assertNotNull(ranked); // Basic check
        assertFalse(ranked.isEmpty(), "Ranked list should not be empty");

        // Debug print (optional)
        for (Integer rank : ranked)
            System.out.println(rank);
    }
}
