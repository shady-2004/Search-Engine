import React, { useState, useEffect } from 'react'

const SearchSuggestions = ({ query, onSelect }) => {
  const [suggestions, setSuggestions] = useState([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    const fetchSuggestions = async () => {
      if (query.length < 2) {
        setSuggestions([])
        return
      }

      setLoading(true)
      try {
        const response = await fetch(`http://localhost:8080/api/suggestions?q=${encodeURIComponent(query)}`)
        if (response.ok) {
          const data = await response.json()
          setSuggestions(data)
        }
      } catch (error) {
        console.error('Error fetching suggestions:', error)
      } finally {
        setLoading(false)
      }
    }

    const debounceTimer = setTimeout(fetchSuggestions, 300)
    return () => clearTimeout(debounceTimer)
  }, [query])

  if (!suggestions.length || !query) return null

  return (
    <div className="absolute w-full mt-2 bg-gray-800/90 backdrop-blur-sm rounded-xl shadow-xl z-50 border border-gray-700/50">
      {loading ? (
        <div className="p-4 text-center text-gray-400 flex items-center justify-center">
          <svg className="animate-spin h-5 w-5 mr-3 text-blue-400" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
          </svg>
          Loading suggestions...
        </div>
      ) : (
        <ul className="divide-y divide-gray-700/50">
          {suggestions.map((suggestion, index) => (
            <li
              key={index}
              className="p-4 hover:bg-gray-700/50 cursor-pointer transition-all duration-200 text-gray-200 flex items-center"
              onClick={() => onSelect(suggestion)}
            >
              <svg className="w-5 h-5 mr-3 text-blue-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
              </svg>
              {suggestion}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default SearchSuggestions 