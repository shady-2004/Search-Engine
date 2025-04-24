package com.example.Search.Engine.Data;

import javafx.util.Pair;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;

public class DataBaseManager {
    private static final String URL = "jdbc:sqlite:./data/searchE.db";

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL);
    }
    public static List<Pair<String,Integer>> GetALLQueries() throws SQLException {
        List<Pair<String,Integer>>queries=new ArrayList<>();
        String sql="SELECT * FROM search_queries;\n";
        try(Connection conn=getConnection();
            Statement stmt= getConnection().createStatement();
        ResultSet rs = stmt.executeQuery(sql)){
                Instant currentTimestamp = Instant.now();
            while(rs.next()){
                Timestamp dbTimestamp = rs.getTimestamp("lastAdded");  // Replace with actual column name
                Duration duration = Duration.between(dbTimestamp.toInstant(), currentTimestamp);
                // Get the current timestamp
                if(duration.toHours()>12)continue;
                queries.add(new Pair<>(rs.getString("query"),rs.getInt("count")));
            }
        }
        return queries;
    }
}
