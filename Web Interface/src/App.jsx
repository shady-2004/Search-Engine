import { useState } from 'react'
import reactLogo from './assets/react.svg'
import viteLogo from '/vite.svg'
import './App.css'
import Spinner from './Spinner/spinner'

function App() {
  const [count, setCount] = useState(0)

  return (
    <>
      <header className='flex py-5 justify-center items-center'>
        <p className='font-bold text-[2rem]'>APT</p>
      </header>
      <main className='container pt-10 mx-auto'>
        <div className="input-container py-5 w-full flex flex-col justify-center">
            <label htmlFor="query" className='ml-5'>Damn you and your freaking APT project, Enter Your Damn Query:</label>
            <input type="text" id='query' className='w-full rounded-full py-3 bg-gradient-to-r from-slate-700 to-gray-600 border-gray-700
            px-5 text-[1.5rem] outline-none'/>
        </div>
        <div className="documents">
          <div className="document p-5 bg-gray-800">
            <div className='mb-5'>
              <a href="" >Link</a>
            </div>
            <p>Lorem, ipsum dolor sit amet consectetur adipisicing elit. Nisi assumenda consequuntur, libero voluptates incidunt accusantium? Eaque a, quod dolorem in, maxime unde saepe exercitationem, voluptates veritatis aut qui sed aliquam!</p>
          </div>
        </div>
      </main>
      <Spinner />
    </>
  )
}

export default App
