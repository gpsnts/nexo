import Marca from './Marca'
import TemaToggle from './TemaToggle'
import { useTitulo } from './titulo'

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
