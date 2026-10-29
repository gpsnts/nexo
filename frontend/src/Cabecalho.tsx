import BarraUso from './BarraUso'
import { IconeDesempenho, IconeInicio, IconeMais, IconePerfil, IconeRevisar, IconeSair } from './Icones'
import Marca from './Marca'
import { irPara } from './rotas'
import TemaToggle from './TemaToggle'

/** Item de navegação que está ativo. */
type Atual = 'inicio' | 'perfil' | 'estudo' | 'revisar' | 'desempenho'

/**
 * Esqueleto de navegação: barra lateral no computador, barra inferior no celular (o CSS decide). Todos os itens têm rótulo de texto.
 * @param props atual: item ativo; aoIrInicio: vai ao início; aoIrPerfil: vai ao perfil; aoSair: encerra a sessão.
 * @returns o cabeçalho com navegação, uso de IA, tema e saída.
 */
export default function Cabecalho({ atual, aoIrInicio, aoIrPerfil, aoSair }: { atual: Atual; aoIrInicio: () => void; aoIrPerfil: () => void; aoSair: () => void }) {
  /**
   * Botão de navegação.
   * @param nome item que o botão representa.
   * @param rotulo texto do botão.
   * @param icone ícone decorativo.
   * @param aoClicar ação ao clicar.
   * @returns o botão, marcado como atual quando é o item ativo.
   */
  const item = (nome: Atual, rotulo: string, icone: React.ReactNode, aoClicar: () => void) => (
    <button type="button" className="nav-item" aria-current={atual === nome ? 'page' : undefined} onClick={aoClicar}>{icone}<span>{rotulo}</span></button>
  )
  return (
    <header className="cab">
      <a className="pular" href="#conteudo" onClick={(e) => { e.preventDefault(); document.getElementById('conteudo')?.focus() }}>Pular para o conteúdo</a>
      <Marca />
      <button type="button" className={`novo-estudo${atual === 'inicio' ? ' fab' : ''}`} onClick={() => irPara('/novo-estudo')}><IconeMais /><span>Novo estudo</span></button>
      <nav aria-label="Principal">
        {item('inicio', 'Início', <IconeInicio />, aoIrInicio)}
        {item('revisar', 'Revisar', <IconeRevisar />, () => irPara('/revisar'))}
        {item('desempenho', 'Desempenho', <IconeDesempenho />, () => irPara('/desempenho'))}
        {item('perfil', 'Perfil', <IconePerfil />, aoIrPerfil)}
      </nav>
      <div className="cab-fim">
        <BarraUso />
        <div className="cab-rodape">
          <TemaToggle />
          <button type="button" className="sair" onClick={aoSair}><IconeSair /><span>Sair</span></button>
        </div>
      </div>
    </header>
  )
}
