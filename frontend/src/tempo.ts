const rtf = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'auto' })

/**
 * "há 5 minutos", "ontem". Abaixo de 1 minuto: "agora mesmo".
 * @param iso instante (ISO 8601).
 * @param agora instante de referência em milissegundos.
 * @returns o texto relativo em português.
 */
export function tempoRelativo(iso: string, agora: number = Date.now()): string {
  const s = Math.round((new Date(iso).getTime() - agora) / 1000)
  const a = Math.abs(s)
  if (a < 60) return 'agora mesmo'
  if (a < 3600) return rtf.format(Math.round(s / 60), 'minute')
  if (a < 86400) return rtf.format(Math.round(s / 3600), 'hour')
  return rtf.format(Math.round(s / 86400), 'day')
}

/**
 * Data e hora curtas no fuso do navegador.
 * @param iso instante (ISO 8601).
 * @returns texto como "09/03/2026 14:30".
 */
export const dataCompleta = (iso: string) => new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })

/**
 * "2026-03-09" -> "09/03/2026" (datas sem hora: nada de fuso do navegador).
 * @param d data no formato ISO, sem hora.
 * @returns a data em dia/mês/ano.
 */
export const dataIso = (d: string) => d.split('-').reverse().join('/')
