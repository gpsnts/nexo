import { afterEach, expect, test } from 'vitest'
import { aplicarTema } from './tema'

afterEach(() => { document.head.innerHTML = ''; document.documentElement.removeAttribute('data-theme'); localStorage.clear() })

test('a cor da barra do navegador acompanha o tema forçado e volta ao padrão no tema do sistema', () => {
  document.head.innerHTML =
    '<meta name="theme-color" content="#f6f7fb" media="(prefers-color-scheme: light)"><meta name="theme-color" content="#0f1020" media="(prefers-color-scheme: dark)">'
  const cores = () => [...document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')].map((m) => m.content)
  aplicarTema('escuro')
  expect(cores()).toEqual(['#0f1020', '#0f1020'])
  aplicarTema('claro')
  expect(cores()).toEqual(['#f6f7fb', '#f6f7fb'])
  aplicarTema('sistema')
  expect(cores()).toEqual(['#f6f7fb', '#0f1020'])
})
