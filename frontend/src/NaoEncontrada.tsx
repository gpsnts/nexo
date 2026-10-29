import Marca from './Marca'
import TemaToggle from './TemaToggle'
import { useTitulo } from './titulo'

/**
 * Tela 404.
 * @param props logado: se há sessão (muda o destino do botão); aoVoltar: chamado ao clicar no botão.
 * @returns a tela de página não encontrada.
 */
export default function NaoEncontrada({ logado, aoVoltar }: { logado: boolean; aoVoltar: () => void }) {
  useTitulo('Página não encontrada')
  return (
    <div className="centro">
      <main className="cartao" id="conteudo">
        <div className="topo"><Marca /><TemaToggle /></div>
        <h1>Página não encontrada</h1>
        <p className="sub">O endereço que você abriu não existe ou foi movido.</p>
        <button type="button" onClick={aoVoltar}>{logado ? 'Ir para o início' : 'Ir para o login'}</button>
      </main>
    </div>
  )
}
