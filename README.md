# 🔍 Seekr: A Smart Java-Powered Search Engine  
**Seekr** is a high-performance, scalable search engine built using Java and Spring Boot that simulates the core functionalities of modern search engines. From crawling and indexing web pages to ranking results and handling advanced user queries, Seekr delivers fast and accurate search experiences via a sleek, interactive React-based interface.

---

## 🌐 Core Components  
The system is composed of several tightly integrated modules, each with a distinct role in the search pipeline:

---

### 1. 🕷️ Web Crawler  
**Purpose:** Automatically traverses the web starting from seed URLs, fetching and parsing HTML documents.

**Features:**
- URL normalization to avoid duplicate visits.
- Recursively extracts links and restricts crawl to HTML-only content.
- Respects `robots.txt` directives to avoid unauthorized access.
- Designed with multithreading for performance scalability.
- Crawl state persistence enables stopping/resuming without data loss.

---

### 2. 📖 Indexer  
**Purpose:** Constructs an efficient, searchable index from crawled documents.

**Capabilities:**
- Extracts terms with contextual weighting (title, headers, body).
- Stores the index persistently in an **SQLite** database for quick lookups.
- Supports incremental updates for newly fetched documents.
- Optimized to handle large document volumes (6000+ documents in under 2 minutes).

---

### 3. 🧠 Query Processor  
**Purpose:** Processes user queries and retrieves relevant documents from the index.

**Highlights:**
- Stems query terms to support partial and morphological matches (e.g., travel, traveling, traveler).
- Filters out noise/stopwords to improve precision.
- Prepares tokens for downstream phrase and ranking modules.

---

### 4. ✏️ Phrase & Boolean Query Handler  
**Phrase Matching:**
- Seekr supports exact-phrase retrieval using quotation marks.  
  **Example:** `"machine learning"`

**Boolean Logic:**
- Handles `AND`, `OR`, and `NOT` operators with up to two operations per query.  
  **Example:** `"machine learning" AND python` or `climate NOT "climate change"`

---

### 5. 📈 Ranker  
**Purpose:** Sorts search results based on relevance and document authority.

**Ranking Factors:**
- **Relevance:** Utilizes TF-IDF and structural context (e.g., title matches).
- **Popularity:** Implements a variant of PageRank to prioritize highly-linked pages.

**Speed:**
- First result rendered within **20–50 ms**.
- Subsequent results in under **5 ms**.

---

### 6. 🖥️ Web-Based UI  
**Purpose:** Presents search results in a user-friendly, responsive format.

**User Experience:**
- Developed using **React** for a dynamic, single-page interface.
- Clean layout similar to popular search engines.
- Displays result snippets with highlighted query terms.
- Pagination support (e.g., 20 results per page across 10+ pages).
- Smart auto-suggestions as users type, based on popular queries.

---

## 📷 UI Snapshots 
**Homepage:**

<img src="https://github.com/shady-2004/Seekr/blob/main/readme-assets/home.png"  alt="home-page" />

**Search Results Page:**

<img src="https://github.com/shady-2004/Seekr/blob/main/readme-assets/results.png" alt="results-page" />
---

## 🚀 Tech Stack

Seekr is built using a modern and lightweight technology stack:

| 🔧 Layer       | 🛠️ Technology   | 📋 Description                            |
|---------------|------------------|--------------------------------------------|
| Backend       | **Java**         | Core logic for crawling, indexing, and ranking |
| Framework     | **Spring Boot**  | REST API development and modular backend structure |
| Database      | **SQLite**       | Lightweight, file-based storage for indexing and metadata |
| Frontend      | **React**        | Interactive and responsive web interface for user search experience |

---

