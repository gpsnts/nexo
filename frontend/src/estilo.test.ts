import { expect, test } from 'vitest'
import css from './estilo.css?raw'

// O tema escuro vive em dois blocos (atributo e prefers-color-scheme) porque CSS não os compartilha (ADR 0013). Este teste impede que divirjam.
test('os dois blocos do tema escuro têm as mesmas variáveis', () => {
  const declaracoes = (bloco: RegExpMatchArray | null) =>
    (bloco?.[1] ?? '').split(';').map((d) => d.replace(/\s+/g, ' ').trim()).filter(Boolean).sort()
  const porAtributo = declaracoes(css.match(/:root\[data-theme="dark"\]\s*\{([^}]*)\}/))
  const porMedia = declaracoes(css.match(/:root:not\(\[data-theme="light"\]\)\s*\{([^}]*)\}/))
  expect(porAtributo.length).toBeGreaterThan(5)
  expect(porMedia).toEqual(porAtributo)
})
