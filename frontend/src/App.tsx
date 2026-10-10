import { useEffect, useState } from 'react'

export default function App() {
  const [status, setStatus] = useState('verificando...')
  useEffect(() => {
    fetch('/api/actuator/health')
      .then((r) => r.json())
      .then((d) => setStatus(d.status))
      .catch(() => setStatus('indisponível'))
  }, [])
  return (
    <main style={{ fontFamily: 'sans-serif', maxWidth: 640, margin: '2rem auto', padding: '0 16px' }}>
      <h1>Plataforma Nexo</h1>
      <p>Backend: <strong>{status}</strong></p>
    </main>
  )
}
