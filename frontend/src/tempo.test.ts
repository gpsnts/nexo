import { expect, test } from 'vitest'
import { tempoRelativo } from './tempo'

const agora = Date.parse('2026-10-10T12:00:00Z')
const antes = (s: number) => new Date(agora - s * 1000).toISOString()

test('tempo relativo em português', () => {
  expect(tempoRelativo(antes(10), agora)).toBe('agora mesmo')
  expect(tempoRelativo(antes(5 * 60), agora)).toBe('há 5 minutos')
  expect(tempoRelativo(antes(3 * 3600), agora)).toBe('há 3 horas')
  expect(tempoRelativo(antes(86400), agora)).toBe('ontem')
  expect(tempoRelativo(antes(5 * 86400), agora)).toBe('há 5 dias')
})
