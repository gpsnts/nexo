import { useEffect, useRef } from 'react'

/**
 * Botão de envio desabilitado durante a requisição perde o foco (o navegador o joga no <body>) e quem usa teclado precisa voltar do topo da página. Quando o envio termina e o foco ficou perdido, devolve o foco ao botão; a mensagem de erro (role="alert") continua sendo anunciada.
 * @param ocupado se o envio está em curso.
 * @returns a ref a pôr no botão de envio.
 */
export function useDevolverFoco(ocupado: boolean) {
  const botao = useRef<HTMLButtonElement>(null)
  const estavaOcupado = useRef(false)
  useEffect(() => {
    if (ocupado) { estavaOcupado.current = true; return }
    if (estavaOcupado.current) {
      estavaOcupado.current = false
      if (document.activeElement === document.body) botao.current?.focus()
    }
  }, [ocupado])
  return botao
}
