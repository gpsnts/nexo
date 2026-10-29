/** Mensagens de erro por campo. */
export type Erros = Record<string, string>

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/ // o mesmo critério do backend: exige domínio com ponto

/**
 * Valida um e-mail.
 * @param v texto digitado.
 * @returns a mensagem de erro, ou texto vazio se estiver válido.
 */
export const erroEmail = (v: string) =>
  !v.trim() ? 'Informe o e-mail.' : !EMAIL.test(v.trim()) ? 'Informe um e-mail válido, como nome@exemplo.com.' : ''

/**
 * Valida um campo obrigatório.
 * @param v texto digitado.
 * @param mensagem erro a mostrar se estiver vazio.
 * @returns a mensagem, ou texto vazio se preenchido.
 */
export const erroObrigatorio = (v: string, mensagem: string) => (v.trim() ? '' : mensagem)

/**
 * Valida o nome (obrigatório, até 120 caracteres).
 * @param v texto digitado.
 * @returns a mensagem de erro, ou texto vazio.
 */
export const erroNome = (v: string) => (!v.trim() ? 'Informe o nome.' : v.trim().length > 120 ? 'O nome pode ter até 120 caracteres.' : '')

/**
 * Valida a senha nova (8 a 128 caracteres).
 * @param v texto digitado.
 * @returns a mensagem de erro, ou texto vazio.
 */
export const erroSenhaNova = (v: string) =>
  v.length < 8 ? 'A senha precisa ter pelo menos 8 caracteres.' : v.length > 128 ? 'A senha pode ter até 128 caracteres.' : ''

/**
 * Diz se há algum erro.
 * @param e erros por campo.
 * @returns verdadeiro se algum campo tem mensagem.
 */
export const temErro = (e: Erros) => Object.values(e).some(Boolean)

/**
 * Leva o foco ao primeiro campo marcado como inválido (depois que o React pinta os erros).
 * @param formulario elemento que contém os campos.
 */
export function focarPrimeiroInvalido(formulario: ParentNode | null) {
  requestAnimationFrame(() => formulario?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus())
}
