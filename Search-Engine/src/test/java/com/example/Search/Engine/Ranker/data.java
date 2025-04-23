package com.example.Search.Engine.Ranker;

import com.example.Search.Engine.Data.DataBaseManager;
import javafx.util.Pair;
import java.sql.SQLException;
import java.util.List;

public class data {
    public static void main(String[] args) {
        try {
            // Call the method to get all queries
            List<Pair<String, Integer>> queries = DataBaseManager.GetALLQueries();

            // Print the results
            if (queries.isEmpty()) {
                System.out.println("No queries found.");
            } else {
                for (Pair<String, Integer> query : queries) {
                    System.out.println("Query: " + query.getKey() + ", Frequency: " + query.getValue());
                }
            }
        } catch (SQLException e) {
            System.out.println("Error occurred while querying the database.");
            e.printStackTrace();
        }
    }
}
