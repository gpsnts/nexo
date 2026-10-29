import { useState } from 'react'
import { aplicarTema, lerTema, type Tema } from './tema'

const icones: Record<Tema, React.ReactNode> = {
  claro: <path d="M12 4V2m0 20v-2m8-8h2M2 12h2m13.66-5.66 1.41-1.41M4.93 19.07l1.41-1.41m0-11.32L4.93 4.93m14.14 14.14-1.41-1.41M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10Z" />,
  escuro: <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />,
  sistema: <path d="M3 5h18v11H3zM8 20h8m-4-4v4" />,
}
const nomes: Record<Tema, string> = { claro: 'Tema claro', escuro: 'Tema escuro', sistema: 'Tema do sistema' }

/**
 * Seletor de tema (claro, sistema, escuro) com botões de ícone.
 * @returns o grupo de botões.
 */
export default function TemaToggle() {
  const [tema, setTema] = useState(lerTema)
  /**
   * Aplica e guarda o tema escolhido.
   * @param t tema escolhido.
   */
  const escolher = (t: Tema) => { aplicarTema(t); setTema(t) }
  return (
    <div className="tema" role="group" aria-label="Tema">
      {(['claro', 'sistema', 'escuro'] as Tema[]).map((t) => (
        <button key={t} type="button" aria-pressed={tema === t} aria-label={nomes[t]} title={nomes[t]} onClick={() => escolher(t)}>
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{icones[t]}</svg>
        </button>
      ))}
    </div>
  )
}
