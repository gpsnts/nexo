import { useId, useState } from 'react'

/** Campo de formulário com rótulo associado, erro por campo e, em tipo "password", botão de mostrar/ocultar. */
export default function Campo({ rotulo, valor, mudar, tipo = 'text', auto, dica, erro, focar }: {
  rotulo: string; valor: string; mudar: (v: string) => void; tipo?: 'text' | 'email' | 'password'; auto?: string; dica?: string; erro?: string; focar?: boolean
}) {
  const id = useId()
  const [visivel, setVisivel] = useState(false)
  const senha = tipo === 'password'
  const email = tipo === 'email'
  const descricao = [dica ? `${id}-dica` : '', erro ? `${id}-erro` : ''].filter(Boolean).join(' ') || undefined
  return (
    <div className="campo">
      <label htmlFor={id}>{rotulo}</label>
      <div className="entrada">
        <input id={id} type={senha && visivel ? 'text' : tipo} value={valor} onChange={(e) => mudar(e.target.value)} autoComplete={auto} autoFocus={focar}
          aria-invalid={erro ? true : undefined} aria-describedby={descricao}
          {...(email ? { autoCapitalize: 'none', spellCheck: false, inputMode: 'email' as const } : {})} />
        {senha && (
          <button type="button" className="olho" aria-pressed={visivel} aria-label={visivel ? 'Ocultar senha' : 'Mostrar senha'} onClick={() => setVisivel(!visivel)}>
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              {visivel
                ? <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19M1 1l22 22" />
                : <><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8Z" /><circle cx="12" cy="12" r="3" /></>}
            </svg>
          </button>
        )}
      </div>
      {dica && <small id={`${id}-dica`}>{dica}</small>}
      {erro && <small id={`${id}-erro`} role="alert" className="erro">{erro}</small>}
    </div>
  )
}

/** Campo de usuário invisível: sem ele o gerenciador de senhas do navegador não sabe a qual conta o campo de senha pertence. */
export const CampoUsuario = ({ email }: { email: string }) => (
  <input type="text" name="username" autoComplete="username" value={email} readOnly tabIndex={-1} aria-hidden="true" className="oculto" />
)
