import { useState, useEffect } from 'react'
import './App.css'
import Spinner from './Spinner/spinner'
import SearchSuggestions from './components/SearchSuggestions'

function App() {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [page, setPage] = useState(0)
  const [totalResults, setTotalResults] = useState(0)
  const [showSuggestions, setShowSuggestions] = useState(false)
  const [searchTime, setSearchTime] = useState(null)
  const pageSize = 10

  const handleSearch = async (e) => {
    if (e) e.preventDefault()
    if (!query.trim()) return

    setLoading(true)
    setError(null)
    setPage(0)
    setShowSuggestions(false)
    setSearchTime(null)
    const startTime = performance.now()

    try {
      const response = await fetch(
        `http://localhost:8080/api/search?query=${encodeURIComponent(query)}&page=0&size=${pageSize}`
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
    if ((newPage + 1) * pageSize >= totalResults) return

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
      window.scrollTo({
        top: 0,
        behavior: 'smooth'
      })
    } catch (err) {
      setError('Failed to fetch results. Please try again.')
      console.error('Page change error:', err)
    } finally {
      setLoading(false)
    }
  }

  const handleSuggestionSelect = (suggestion) => {
    console.log('Suggestion selected:', suggestion)
    setQuery(suggestion)
    setShowSuggestions(false)
    const searchWithSuggestion = async () => {
      setLoading(true)
      setError(null)
      setPage(0)
      setSearchTime(null)
      const startTime = performance.now()

      try {
        const response = await fetch(
          `http://localhost:8080/api/search?query=${encodeURIComponent(suggestion)}&page=0&size=${pageSize}`
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
    searchWithSuggestion()
  }

  useEffect(() => {
    const handleClickOutside = (e) => {
      console.log('Click outside detected')
      if (!e.target.closest('.search-container')) {
        setShowSuggestions(false)
      }
    }

    document.addEventListener('click', handleClickOutside)
    return () => document.removeEventListener('click', handleClickOutside)
  }, [])

  const start = page * pageSize + 1
  const end = Math.min((page + 1) * pageSize, totalResults)

  return (
    <div className="min-h-screen bg-gradient-to-br from-gray-900 via-gray-800 to-gray-900 text-white">
      <header className="py-8 px-4">
        <div className="container mx-auto">
          <h1 className="text-5xl font-bold text-center bg-clip-text text-transparent bg-gradient-to-r from-blue-400 via-purple-500 to-pink-500 animate-gradient">
            Search Engine
          </h1>
          <p className="text-center text-gray-400 mt-4 text-lg">
            Find what you're looking for with our powerful search engine
          </p>
        </div>
      </header>

      <main className="container mx-auto px-4 py-8">
        <div className="max-w-3xl mx-auto mb-12">
          <form onSubmit={handleSearch} className="relative search-container">
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
                className="w-full px-8 py-5 text-xl rounded-full bg-gray-800/50 border-2 border-gray-700 focus:border-blue-500 focus:ring-4 focus:ring-blue-500/20 focus:outline-none transition-all duration-300 shadow-lg backdrop-blur-sm"
              />
              <button
                type="submit"
                disabled={loading}
                className="absolute right-2 top-1/2 -translate-y-1/2 px-8 py-3 bg-gradient-to-r from-blue-600 to-purple-600 hover:from-blue-700 hover:to-purple-700 rounded-full font-medium transition-all duration-300 shadow-lg hover:shadow-blue-500/20 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {loading ? 'Searching...' : 'Search'}
              </button>
            </div>
            {showSuggestions && (
              <SearchSuggestions query={query} onSelect={handleSuggestionSelect} />
            )}
          </form>
        </div>

        <div className="max-w-4xl mx-auto">
          {error && (
            <div className="p-6 mb-8 bg-red-900/30 border border-red-500/50 rounded-xl backdrop-blur-sm">
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

          <div className="space-y-6 results-container">
            {results.map((result, index) => (
              <div
                key={index}
                className="p-8 bg-gray-800/50 rounded-xl hover:bg-gray-700/50 transition-all duration-300 backdrop-blur-sm border border-gray-700/50 hover:border-gray-600/50"
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
                <p className="text-gray-300 text-lg mb-4">{result.snippet}</p>
                <div className="flex items-center text-sm text-gray-400">
                  <span className="mr-6 flex items-center">
                    <svg className="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
                    </svg>
                    Score: {result.score?.toFixed(2)}
                  </span>
                  <span className="flex items-center">
                    <svg className="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
                    </svg>
                    {result.url}
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
                className="px-8 py-3 bg-gradient-to-r from-blue-600 to-purple-600 hover:from-blue-700 hover:to-purple-700 rounded-lg font-medium transition-all duration-300 shadow-lg hover:shadow-blue-500/20 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                Previous
              </button>
              <button
                onClick={() => changePage(1)}
                disabled={loading || (page + 1) * pageSize >= totalResults}
                className="px-8 py-3 bg-gradient-to-r from-blue-600 to-purple-600 hover:from-blue-700 hover:to-purple-700 rounded-lg font-medium transition-all duration-300 shadow-lg hover:shadow-blue-500/20 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                Next
              </button>
            </div>
          )}
        </div>
      </main>

      <footer className="py-8 px-4 mt-auto">
        <div className="container mx-auto text-center text-gray-400">
          <p className="text-lg">© 2025 Search Engine. All rights reserved.</p>
        </div>
      </footer>
    </div>
  )
}

export default App
