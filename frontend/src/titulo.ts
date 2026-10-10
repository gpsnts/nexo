import { useEffect } from 'react'

/** Título da aba por tela (leitores de tela e histórico) e, em telas internas, foco no <h1> para quem navega por teclado. */
export function useTitulo(titulo: string, focarTitulo = false) {
  useEffect(() => {
    document.title = `${titulo} · Nexo`
    if (focarTitulo) document.querySelector<HTMLElement>('h1')?.focus()
  }, [titulo, focarTitulo])
}
