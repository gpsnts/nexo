import Marca from './Marca'
import TemaToggle from './TemaToggle'

export default function Cabecalho({ atual, aoIrInicio, aoIrPerfil, aoSair }: {
  atual: 'inicio' | 'perfil'; aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void
}) {
  return (
    <header className="cab">
      <a className="pular" href="#conteudo" onClick={(e) => { e.preventDefault(); document.getElementById('conteudo')?.focus() }}>Pular para o conteúdo</a>
      <Marca />
      <nav aria-label="Principal">
        <button type="button" className="link" aria-current={atual === 'inicio' ? 'page' : undefined} onClick={aoIrInicio}>Início</button>
        <button type="button" className="link" aria-current={atual === 'perfil' ? 'page' : undefined} onClick={aoIrPerfil}>Perfil</button>
        <button type="button" className="link" onClick={aoSair}>Sair</button>
        <TemaToggle />
      </nav>
    </header>
  )
}
