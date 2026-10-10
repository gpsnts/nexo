export type Erros = Record<string, string>

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/ // o mesmo critério do backend: exige domínio com ponto

export const erroEmail = (v: string) =>
  !v.trim() ? 'Informe o e-mail.' : !EMAIL.test(v.trim()) ? 'Informe um e-mail válido, como nome@exemplo.com.' : ''

export const erroObrigatorio = (v: string, mensagem: string) => (v.trim() ? '' : mensagem)

export const erroNome = (v: string) => (!v.trim() ? 'Informe o nome.' : v.trim().length > 120 ? 'O nome pode ter até 120 caracteres.' : '')

export const erroSenhaNova = (v: string) =>
  v.length < 8 ? 'A senha precisa ter pelo menos 8 caracteres.' : v.length > 128 ? 'A senha pode ter até 128 caracteres.' : ''

export const temErro = (e: Erros) => Object.values(e).some(Boolean)

/** Leva o foco ao primeiro campo marcado como inválido (depois que o React pinta os erros). */
export function focarPrimeiroInvalido(formulario: HTMLFormElement | null) {
  requestAnimationFrame(() => formulario?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus())
}
