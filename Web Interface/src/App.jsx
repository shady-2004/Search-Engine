import { useState, useEffect } from 'react'
import './App.css'
import Spinner from './Spinner/spinner'
import SearchSuggestions from './components/SearchSuggestions'

function SearchPage({ onSearch }) {
  const [query, setQuery] = useState('')
  const [showSuggestions, setShowSuggestions] = useState(false)

  const handleSubmit = (e) => {
    e.preventDefault()
    if (query.trim()) {
      onSearch(query)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-gray-900 via-gray-800 to-gray-900 text-white flex flex-col items-center justify-center">
      <div className="text-center mb-5">
        <h1 className="text-6xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-blue-400 via-purple-500 to-pink-500 animate-gradient pb-4">
          Seekr
        </h1>
      </div>

      <div className="w-1/3 -w-2xl">
        <form onSubmit={handleSubmit} className="relative search-container">
          <div className="relative">
            <input
              type="text"
              value={query}
              onChange={(e) => {
                setQuery(e.target.value)
                setShowSuggestions(true)
              }}
              onFocus={() => setShowSuggestions(true)}
              placeholder="Search anything..."
              className="w-full px-8 py-5 text-xl rounded-full bg-gray-800/50 border-2 border-gray-700 focus:border-blue-500 focus:ring-4 focus:ring-blue-500/20 focus:outline-none transition-all duration-300 shadow-lg backdrop-blur-sm"
            />
            <button
              type="submit"
              className="absolute right-2 top-1/2 -translate-y-1/2 px-8 py-3 bg-gradient-to-r from-blue-600 to-purple-600 hover:from-blue-700 hover:to-purple-700 rounded-full font-medium transition-all duration-300 shadow-lg hover:shadow-blue-500/20"
            >
              Search
            </button>
          </div>
          {showSuggestions && (
            <SearchSuggestions 
              query={query} 
              onSelect={(suggestion) => {
                setQuery(suggestion)
                setShowSuggestions(false)
                onSearch(suggestion)
              }} 
            />
          )}
        </form>
      </div>
    </div>
  )
}

function ResultsPage({ initialQuery, onNewSearch }) {
  const [query, setQuery] = useState(initialQuery)
  const [results, setResults] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [page, setPage] = useState(0)
  const [totalResults, setTotalResults] = useState(0)
  const [showSuggestions, setShowSuggestions] = useState(false)
  const [searchTime, setSearchTime] = useState(null)
  const pageSize = 10

  const handleSearch = async (searchQuery) => {
    if (!searchQuery.trim()) return
    setQuery(searchQuery)
    setLoading(true)
    setError(null)
    setPage(0)
    setShowSuggestions(false)
    setSearchTime(null)
    const startTime = performance.now()

    try {
      const response = await fetch(
        `http://localhost:8080/api/search?query=${encodeURIComponent(searchQuery)}&page=0&size=${pageSize}`
      )
      if (!response.ok) {
        throw new Error('Search failed')
      }
      const data = await response.json()
      setResults(data.results || [])
      setTotalResults(data.totalCount || 0)
      setSearchTime(performance.now() - startTime)
    } catch (err) {
      setError('Failed to fetch search results. Please try again.')
      console.error('Search error:', err)
    } finally {
      setLoading(false)
    }
  }

  const changePage = async (delta) => {
    const newPage = page + delta
    if (newPage < 0) return

    setLoading(true)
    try {
      const response = await fetch(
        `http://localhost:8080/api/search?query=${encodeURIComponent(query)}&page=${newPage}&size=${pageSize}`
      )
      if (!response.ok) {
        throw new Error('Failed to fetch results')
      }
      const data = await response.json()
      setResults(data.results || [])
      setPage(newPage)
      setTotalResults(data.totalCount || 0)
      window.scrollTo({ top: 0, behavior: 'smooth' })
    } catch (err) {
      setError('Failed to fetch results. Please try again.')
      console.error('Page change error:', err)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    handleSearch(initialQuery)
  }, [initialQuery])

  const start = page * pageSize + 1
  const end = Math.min((page + 1) * pageSize, totalResults)

  return (
    <div className="min-h-screen bg-gradient-to-br from-gray-900 via-gray-800 to-gray-900 text-white">
      <header className="py-4 px-4 border-b border-gray-700">
        <div className="container mx-auto">
          <div className="flex items-center justify-between">
            <a href="/" className="text-4xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-blue-400 via-purple-500 to-pink-500">
              Seekr
            </a>
            <div className="w-full max-w-2xl mx-4">
              <form onSubmit={(e) => { e.preventDefault(); handleSearch(query) }} className="relative search-container">
                <div className="relative">
                  <input
                    type="text"
                    value={query}
                    onChange={(e) => {
                      setQuery(e.target.value)
                      setShowSuggestions(true)
                    }}
                    onFocus={() => setShowSuggestions(true)}
                    placeholder="Enter your search query..."
                    className="w-full px-6 py-3 text-lg rounded-full bg-gray-800/50 border-2 border-gray-700 focus:border-blue-500 focus:ring-4 focus:ring-blue-500/20 focus:outline-none transition-all duration-300"
                  />
                  <button
                    type="submit"
                    disabled={loading}
                    className="absolute right-2 top-1/2 -translate-y-1/2 px-6 py-2 bg-gradient-to-r from-blue-600 to-purple-600 hover:from-blue-700 hover:to-purple-700 rounded-full font-medium transition-all duration-300 disabled:opacity-50"
                  >
                    {loading ? 'Searching...' : 'Search'}
                  </button>
                </div>
                {showSuggestions && (
                  <SearchSuggestions 
                    query={query} 
                    onSelect={(suggestion) => {
                      setQuery(suggestion)
                      setShowSuggestions(false)
                      handleSearch(suggestion)
                    }} 
                  />
                )}
              </form>
            </div>
          </div>
        </div>
      </header>

      <main className="container mx-auto px-4 py-8">
        {error && (
          <div className="p-6 mb-8 bg-red-900/30 border border-red-500/50 rounded-xl">
            <div className="flex items-center">
              <svg className="w-6 h-6 mr-3 text-red-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span className="text-red-200">{error}</span>
            </div>
          </div>
        )}

        {loading && results.length === 0 && (
          <div className="flex justify-center my-12">
            <Spinner />
          </div>
        )}

        {results.length > 0 && (
          <div className="text-gray-400 mb-6 text-lg">
            Showing results {start}-{end} of {totalResults}
            {searchTime !== null && (
              <span className="ml-4 text-blue-400">
                (Search took {(searchTime / 1000).toFixed(2)} seconds)
              </span>
            )}
          </div>
        )}

        <div className="space-y-6">
          {results.map((result, index) => (
            <div
              key={index}
              className="p-8 bg-gray-800/50 rounded-xl hover:bg-gray-700/50 transition-all duration-300 border border-gray-700/50"
            >
              <h2 className="text-2xl font-semibold mb-3">
                <a
                  href={result.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-blue-400 hover:text-blue-300 transition-colors duration-200"
                >
                  {result.title}
                </a>
              </h2>
              <div className="mb-4">
                <p 
                  className="text-gray-300 text-lg mb-2"
                  dangerouslySetInnerHTML={{ __html: result.snippet }}
                />
                <a
                  href={result.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-green-400 hover:text-green-300 text-sm"
                >
                  {result.url}
                </a>
              </div>
              <div className="flex items-center text-sm text-gray-400">
                <span className="mr-6 flex items-center">
                  <svg className="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
                  </svg>
                  Score: {result.score?.toFixed(2)}
                </span>
              </div>
            </div>
          ))}
        </div>

        {results.length > 0 && (
          <div className="flex justify-center gap-6 mt-12">
            <button
              onClick={() => changePage(-1)}
              disabled={loading || page === 0}
              className="px-8 py-3 bg-gradient-to-r from-blue-600 to-purple-600 hover:from-blue-700 hover:to-purple-700 rounded-lg font-medium transition-all duration-300 disabled:opacity-50"
            >
              Previous
            </button>
            <button
              onClick={() => changePage(1)}
              disabled={loading || (page + 1) * pageSize >= totalResults}
              className="px-8 py-3 bg-gradient-to-r from-blue-600 to-purple-600 hover:from-blue-700 hover:to-purple-700 rounded-lg font-medium transition-all duration-300 disabled:opacity-50"
            >
              Next
            </button>
          </div>
        )}
      </main>
    </div>
  )
}

function App() {
  const [currentPage, setCurrentPage] = useState('search')
  const [searchQuery, setSearchQuery] = useState('')

  const handleSearch = (query) => {
    setSearchQuery(query)
    setCurrentPage('results')
  }

  return (
    <>
      {currentPage === 'search' ? (
        <SearchPage onSearch={handleSearch} />
      ) : (
        <ResultsPage initialQuery={searchQuery} onNewSearch={handleSearch} />
      )}
    </>
  )
}

export default App
