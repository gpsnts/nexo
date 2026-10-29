import { useEffect } from 'react'

/**
 * Título da aba por tela (leitores de tela e histórico) e, em telas internas, foco no <h1> para quem navega por teclado.
 * @param titulo nome da tela (a aba mostra "titulo · Nexo").
 * @param focarTitulo se o foco vai para o <h1>.
 */
export function useTitulo(titulo: string, focarTitulo = false) {
  useEffect(() => {
    document.title = `${titulo} · Nexo`
    if (focarTitulo) document.querySelector<HTMLElement>('h1')?.focus()
  }, [titulo, focarTitulo])
}
