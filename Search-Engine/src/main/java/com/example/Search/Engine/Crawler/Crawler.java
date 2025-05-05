package com.example.Search.Engine.Crawler;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class Crawler {
    private static final int MAX_PAGES = 6000;
    private static final int MAX_PAGES_PER_DOMAIN = 20;
    private static final int MAX_DEPTH_PER_DOMAIN = 10;
    private static final int CHECKPOINT_INTERVAL = 50;

    private static final int MAX_QUEUE_SIZE = 10000;
    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0"
    };
    private static final String URLS_FILE_NAME = "src/main/resources/urls.txt";
    private final Map<String, BaseRobotRules> robotsCache = new HashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> domainPageCounts = new ConcurrentHashMap<>();

    private final Queue<String> urlQueue = new ConcurrentLinkedQueue<>();
    private final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
    private final Set<String> queuedUrls = ConcurrentHashMap.newKeySet();
    private final AtomicInteger totalCrawledPages = new AtomicInteger(0);
    private final AtomicInteger pendingPages = new AtomicInteger(0);
    private final AtomicInteger activeThreads = new AtomicInteger(0);
    private static final String DB_URL = "jdbc:sqlite:data/search_index.db";
    private static final ReentrantLock dbLock = new ReentrantLock();
    private static final ReentrantLock fileLock = new ReentrantLock();

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Number of threads not provided");
            System.exit(1);
        }
        int numThreads = 1;
        try {
            numThreads = Integer.parseInt(args[0]);
            if (numThreads < 1) {
                System.err.println("Number of threads must be at least 1");
                System.exit(1);
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid number of threads: " + args[0]);
            System.exit(1);
        }

        Crawler myCrawler = new Crawler();

        long startTime = System.currentTimeMillis();

        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(() -> myCrawler.crawl());
            threads[i].start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                System.err.println("Thread interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
        long endTime = System.currentTimeMillis();
        long duration = (endTime - startTime);
        System.out.println("Execution time: " + duration + " ms");
    }

    public Crawler() {
        initializeDatabase();
        totalCrawledPages.set(getRowCount());
        initializeVisitedUrls();
        initializeUrlQueue();
    }

    public void crawl() {
        activeThreads.incrementAndGet();
        Queue<String> tempQueuedUrls = new LinkedList<>();
        List<String> tempCrawledUrlsBuffer = new ArrayList<>();
        List<String> tempHtmlDocsBuffer = new ArrayList<>();
        List<String> tempHtmlTitlesBuffer = new ArrayList<>();
        List<String> tempTimeStampsBuffer = new ArrayList<>();
        List<HashSet<String>> tempListOfExtractedHyperLinksBuffer = new ArrayList<>();
        try {
            while (!urlQueue.isEmpty()) {
                if (pendingPages.incrementAndGet() + totalCrawledPages.get() > MAX_PAGES) {
                    pendingPages.decrementAndGet();
                    break;
                }
                String normalizedUrlStr = urlQueue.poll();
                if (normalizedUrlStr == null) {
                    if (urlQueue.isEmpty() && activeThreads.get() <= 1) {
                        break;
                    }
                    pendingPages.decrementAndGet();
                    Thread.sleep(10);
                    continue;
                }

                if (!visitedUrls.add(normalizedUrlStr)) {
//                    System.err.println(Thread.currentThread().getName() + " - Skipping " + normalizedUrlStr + ": Already visited");
                    pendingPages.decrementAndGet();
                    continue;
                }

                // Check domain limit after marking as visited
                String domain = getDomain(normalizedUrlStr);
                AtomicInteger domainCount = domainPageCounts.computeIfAbsent(domain, k -> new AtomicInteger(0));
                boolean canCrawl = false;
                int currentCount;
                do {
                    currentCount = domainCount.get();
                    if (currentCount >= MAX_PAGES_PER_DOMAIN) {
//                        System.err.println(Thread.currentThread().getName() + " - Skipping " + normalizedUrlStr + ": Domain " + domain + " reached limit of " + MAX_PAGES_PER_DOMAIN + " pages");
                        pendingPages.decrementAndGet();
                        break;
                    }
                    canCrawl = domainCount.compareAndSet(currentCount, currentCount + 1);
                } while (!canCrawl);

                if (!canCrawl) {
                    continue;
                }

                Document doc = fetchHtmlDocument(normalizedUrlStr);
                if (doc == null) {
                    visitedUrls.remove(normalizedUrlStr);
                    domainCount.decrementAndGet(); // Undo reservation
                    pendingPages.decrementAndGet();
                    continue;
                }

                // Increment domain page count
                queuedUrls.remove(normalizedUrlStr);
//                System.out.println(Thread.currentThread().getName() + " - Successfully downloaded " + normalizedUrlStr);

                tempCrawledUrlsBuffer.add(normalizedUrlStr);
                tempHtmlDocsBuffer.add(doc.html());
                tempHtmlTitlesBuffer.add(doc.title());
                tempTimeStampsBuffer.add(LocalDateTime.now().toString());
                HashSet<String> hyperLinks = extractLinks(doc);
                tempListOfExtractedHyperLinksBuffer.add(hyperLinks);

                for (String hyperLink : hyperLinks) {
                    String normalizedHyperLink = normalizeURL(hyperLink);
                    if (normalizedHyperLink == null) {
                        continue;
                    }
                    String domainLink = getDomain(normalizedHyperLink);
                    AtomicInteger domainLinkCount = domainPageCounts.computeIfAbsent(domainLink, k -> new AtomicInteger(0));
                    if (domainLinkCount.get() >= MAX_PAGES_PER_DOMAIN) {
//                        System.err.println(Thread.currentThread().getName() + " - Skipping extractedlink: " + normalizedHyperLink + ": Domain limit reached");
                        continue;
                    }
                    if (visitedUrls.contains(normalizedHyperLink) || queuedUrls.contains(normalizedHyperLink)) {
                        continue;
                    }
                    if (urlQueue.size() < MAX_QUEUE_SIZE) {
                        urlQueue.offer(normalizedHyperLink);
                        queuedUrls.add(normalizedHyperLink);
                        tempQueuedUrls.offer(normalizedHyperLink);
                    }
                }

                if (tempHtmlDocsBuffer.size() >= CHECKPOINT_INTERVAL || totalCrawledPages.get() + pendingPages.get() >= MAX_PAGES) {
//                    System.out.println(Thread.currentThread().getName() + " - Checkpoint: " + tempHtmlDocsBuffer.size() + " documents to save");
                    saveDataAtCheckpoint(tempQueuedUrls, tempCrawledUrlsBuffer, tempHtmlDocsBuffer, tempHtmlTitlesBuffer, tempTimeStampsBuffer, tempListOfExtractedHyperLinksBuffer);
                }
            }
        } catch (Exception e) {
            System.err.println(Thread.currentThread().getName() + " - Exception: " + e.getMessage());
        } finally {
            activeThreads.decrementAndGet();
            if (!tempCrawledUrlsBuffer.isEmpty()) {
//                System.out.println(Thread.currentThread().getName() + " - Final save: " + tempCrawledUrlsBuffer.size() + " documents to save");
                saveDataAtCheckpoint(tempQueuedUrls, tempCrawledUrlsBuffer, tempHtmlDocsBuffer, tempHtmlTitlesBuffer, tempTimeStampsBuffer, tempListOfExtractedHyperLinksBuffer);
            }
        }
    }

    private void writeUrlsToFile(Queue<String> tempUrls) {
        fileLock.lock();
        try (BufferedWriter urlsWriter = new BufferedWriter(new FileWriter(URLS_FILE_NAME, true))) {
            while (!tempUrls.isEmpty()) {
                String tempUrl = tempUrls.poll();
                urlsWriter.write(tempUrl);
                urlsWriter.newLine();
            }
        } catch (IOException e) {
            System.err.println(Thread.currentThread().getName() + " - Error writing to file: " + e.getMessage());
        } finally {
            fileLock.unlock();
        }
    }

    private int insertIntoDB(List<String> tempCrawledUrlsBuffer, List<String> tempHtmlDocsBuffer, List<String> tempHtmlTitlesBuffer,
                             List<String> tempTimeStampsBuffer, List<HashSet<String>> tempListOfExtractedHyperLinksBuffer) {
        if (tempHtmlDocsBuffer.size() != tempCrawledUrlsBuffer.size() ||
                tempHtmlDocsBuffer.size() != tempTimeStampsBuffer.size() ||
                tempHtmlDocsBuffer.size() != tempListOfExtractedHyperLinksBuffer.size()) {
            System.err.println(Thread.currentThread().getName() + " - Buffer sizes do not match");
            throw new IllegalArgumentException("Buffer sizes do not match");
        }
        dbLock.lock();
        java.sql.Connection conn = null;
        try {
            conn = DriverManager.getConnection(DB_URL);
            conn.setAutoCommit(false);
            List<Long> docIds = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO DocumentMetaData (url, title, html, last_crawled_date) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < tempHtmlDocsBuffer.size(); i++) {
                    stmt.setString(1, tempCrawledUrlsBuffer.get(i));
                    stmt.setString(2, tempHtmlTitlesBuffer.get(i));
                    stmt.setString(3, tempHtmlDocsBuffer.get(i));
                    stmt.setString(4, tempTimeStampsBuffer.get(i));
                    stmt.executeUpdate();
                    ResultSet rs = stmt.getGeneratedKeys();
                    if (rs.next()) {
                        docIds.add(rs.getLong(1));
                    } else {
                        throw new SQLException("No generated key returned for crawled_pages insertion at index " + i);
                    }
                    rs.close();
                }
            }
            if (docIds.size() != tempHtmlDocsBuffer.size()) {
                System.err.println(Thread.currentThread().getName() +
                        " - Error inserting into crawled_pages: expected " + tempHtmlDocsBuffer.size() +
                        ", got " + docIds.size());
                throw new SQLException("Inserted " + docIds.size() + " rows into crawled_pages, expected " +
                        tempHtmlDocsBuffer.size());
            }
//            System.out.println("Thread " + Thread.currentThread().getName() +
//                    " - Inserted " + docIds.size() + " rows into crawled_pages");
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO extracted_links (doc_id, extracted_link) VALUES (?, ?)")) {
                for (int i = 0; i < docIds.size(); i++) {
                    long docId = docIds.get(i);
                    HashSet<String> links = tempListOfExtractedHyperLinksBuffer.get(i);
                    for (String link : links) {
                        stmt.setLong(1, docId);
                        stmt.setString(2, link);
                        stmt.addBatch();
                    }
                }
                stmt.executeBatch();
            }
            conn.commit();
            return docIds.size();
        } catch (SQLException e) {
            System.err.println(Thread.currentThread().getName() +
                    " - SQLException in checkpoint: " + e.getMessage());
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    System.err.println(Thread.currentThread().getName() +
                            " - SQLException in rollback: " + rollbackEx.getMessage());
                }
            }
            throw new RuntimeException("Failed to insert into database", e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException e) {
                    System.err.println(Thread.currentThread().getName() +
                            " - Failed to restore auto-commit or close connection: " + e.getMessage());
                }
            }
            dbLock.unlock();
        }
    }

    void saveDataAtCheckpoint(Queue<String> tempUrls, List<String> tempCrawledUrlsBuffer,
                              List<String> tempHtmlDocsBuffer, List<String> tempHtmlTitles, List<String> tempTimeStampsBuffer, List<HashSet<String>> tempListOfExtractedHyperLinksBuffer) {
        if (!tempUrls.isEmpty()) {
            writeUrlsToFile(tempUrls);
        }
        if (!tempCrawledUrlsBuffer.isEmpty()) {
            int rowsInserted = insertIntoDB(tempCrawledUrlsBuffer, tempHtmlDocsBuffer, tempHtmlTitles, tempTimeStampsBuffer, tempListOfExtractedHyperLinksBuffer);
            totalCrawledPages.addAndGet(rowsInserted);
            pendingPages.addAndGet(-rowsInserted);
        }
        tempCrawledUrlsBuffer.clear();
        tempHtmlDocsBuffer.clear();
        tempTimeStampsBuffer.clear();
        tempListOfExtractedHyperLinksBuffer.clear();
    }

    private void initializeVisitedUrls() {
        try (java.sql.Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT url FROM DocumentMetaData")) {
            while (rs.next()) {
                visitedUrls.add(rs.getString("url"));
                String domain = getDomain(rs.getString("url"));
                domainPageCounts.computeIfAbsent(domain, k -> new AtomicInteger(0)).incrementAndGet();
            }
        } catch (SQLException e) {
            System.err.println("Failed to read urls: " + e.getMessage());
        }
    }

    private void initializeUrlQueue() {
        try (BufferedReader urlsReader = new BufferedReader(new FileReader(URLS_FILE_NAME))) {
            String urlStr;
            while ((urlStr = urlsReader.readLine()) != null) {
                if (urlStr.trim().isEmpty() || !urlStr.matches("^https?://.*")) {
                    continue;
                }
                String normalizedUrlStr = normalizeURL(urlStr);
                if (normalizedUrlStr == null) {
                    continue;
                }
                String domain = getDomain(normalizedUrlStr);
                AtomicInteger domainCount = domainPageCounts.computeIfAbsent(domain, k -> new AtomicInteger(0));
                if (domainCount.get() >= MAX_PAGES_PER_DOMAIN) {
//                    System.err.println(Thread.currentThread().getName() + " - Skipping " + normalizedUrlStr +  "from URL seed " + ": Domain limit reached");
                    continue;
                }
                if (visitedUrls.contains(normalizedUrlStr) || queuedUrls.contains(normalizedUrlStr)) {
                    continue;
                }
                urlQueue.offer(normalizedUrlStr);
                queuedUrls.add(normalizedUrlStr);
            }
        } catch (IOException e) {
            System.err.println("Error opening file: " + URLS_FILE_NAME + " " + e.getMessage());
        }
    }

    int getRowCount() {
        try (java.sql.Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS count FROM DocumentMetaData")) {
            if (rs.next()) {
                int count = rs.getInt("count");
//                System.out.println("Row count: " + count);
                return count;
            }
        } catch (SQLException e) {
            System.err.println("Failed to get row count: " + e.getMessage());
        }
        return 0;
    }

    private void initializeDatabase() {
        try (java.sql.Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS DocumentMetaData (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        url TEXT NOT NULL,
                        title TEXT,
                        html TEXT,
                        last_crawled_date TEXT DEFAULT CURRENT_TIMESTAMP,
                        page_rank REAL DEFAULT 0.0
                    )
                    """);
            stmt.execute("CREATE TABLE IF NOT EXISTS extracted_links (" +
                    "doc_id INTEGER NOT NULL, " +
                    "extracted_link TEXT NOT NULL, " +
                    "FOREIGN KEY (doc_id) REFERENCES DocumentMetaData(doc_id) ON DELETE CASCADE)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_doc_id ON extracted_links(doc_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_url ON DocumentMetaData(url)");
        } catch (SQLException e) {
            System.err.println("Failed to initialize database: " + e.getMessage());
        }
    }

    private Document fetchHtmlDocument(String url) {
        if (!isAllowedByRobots(url)) {
//            System.err.println(LocalDateTime.now() + ": Thread " + Thread.currentThread().getName() +
//                    " - Skipping " + url + ": Disallowed by robots.txt");
            return null;
        }

        try {
            Connection.Response response = Jsoup.connect(url)
                    .userAgent(USER_AGENTS[new Random().nextInt(USER_AGENTS.length)])
                    .timeout(5000)
                    .execute();

            int statusCode = response.statusCode();
            if (statusCode != 200) {
//                System.err.println(Thread.currentThread().getName() + " - Failed to fetch " + url + ": Status " + statusCode);
                return null;
            }

            String contentType = response.contentType();
            if (contentType == null || !contentType.toLowerCase().startsWith("text/html")) {
//                System.err.println(Thread.currentThread().getName() + " - Skipping " + url + ": Not HTML (" + contentType + ")");
                return null;
            }

            return response.parse();
        } catch (IOException e) {
//            System.err.println(Thread.currentThread().getName() + " - Failed to fetch " + url + ": " + e.getMessage());
            return null;
        }
    }

    private boolean isAllowedByRobots(String urlStr) {
        try {
            URL url = new URL(urlStr);
            String host = url.getProtocol() + "://" + url.getHost();

            if (robotsCache.containsKey(host)) {
                BaseRobotRules rules = robotsCache.get(host);
                return rules.isAllowed(urlStr);
            }

            URL robotsTxtUrl = new URL(host + "/robots.txt");
            URLConnection connection = robotsTxtUrl.openConnection();
            connection.setRequestProperty("User-Agent", USER_AGENTS[new Random().nextInt(USER_AGENTS.length)]);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);

            try (InputStream inputStream = connection.getInputStream()) {
                byte[] robotsTxtContent = inputStream.readAllBytes();
                SimpleRobotRulesParser parser = new SimpleRobotRulesParser();
                BaseRobotRules rules = parser.parseContent(robotsTxtUrl.toString(), robotsTxtContent, "text/plain", USER_AGENTS[new Random().nextInt(USER_AGENTS.length)]);
                robotsCache.put(host, rules);
                return rules.isAllowed(urlStr);
            }
        } catch (IOException e) {
            return true;
        }
    }

    public HashSet<String> extractLinks(Document doc) {
        HashSet<String> links = new HashSet<>();
        Elements anchorTags = doc.select("a[href]");
        for (Element anchor : anchorTags) {
            String href = anchor.absUrl("href");
            if (href.startsWith("http")) {
                links.add(href);
                if (links.size() > MAX_DEPTH_PER_DOMAIN){
//                    System.err.println(Thread.currentThread().getName() + " - Skipping " + href + ": Depth limit reached");
                    break;
                }
            }
        }
        return links;
    }

    public String normalizeURL(String urlString) {
        try {
            URI uri = new URI(urlString).normalize();
            String host = uri.getHost();
            if (host == null){
                return null;
            }
            host = host.toLowerCase();
            String scheme = uri.getScheme().toLowerCase();
            int port = uri.getPort();
            String path = uri.getPath().replaceAll("/+$", "");
            if (path.isEmpty()) path = "/";
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
                port = -1;
            }
            String query = uri.getQuery();
            if (query != null) {
                query = filterIrrelevantParams(query);
                if (query.isEmpty()) query = null;
            }
            return new URI(scheme, null, host, port, path, query, null).toString();
        } catch (URISyntaxException | NullPointerException e) {
//            System.err.println(Thread.currentThread().getName() + " - Error normalizing URL: " + urlString + " - " + e.getMessage());
            return null;
        }
    }

    private String filterIrrelevantParams(String query) {
        String[] params = query.split("&");
        Set<String> validParams = new LinkedHashSet<>();
        for (String param : params) {
            String[] keyValue = param.split("=", 2);
            String key = keyValue[0].toLowerCase();
            if (key.matches("^(q|search|id|page|sort|category)$")) {
                validParams.add(param);
            }
        }
        return String.join("&", validParams);
    }

    private String getDomain(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            return host != null ? host.toLowerCase() : "";
        } catch (URISyntaxException e) {
//            System.err.println(Thread.currentThread().getName() + " - Error extracting domain from URL: " + url);
            return "";
        }
    }
}