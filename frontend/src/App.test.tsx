import { render, screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import App from './App'

test('mostra o título e o status do backend', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ json: async () => ({ status: 'UP' }) }))
  render(<App />)
  expect(screen.getByRole('heading', { name: 'Plataforma Nexo' })).toBeTruthy()
  expect(await screen.findByText('UP')).toBeTruthy()
})
