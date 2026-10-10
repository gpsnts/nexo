const rtf = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'auto' })

/** "há 5 minutos", "ontem". Abaixo de 1 minuto: "agora mesmo". */
export function tempoRelativo(iso: string, agora: number = Date.now()): string {
  const s = Math.round((new Date(iso).getTime() - agora) / 1000)
  const a = Math.abs(s)
  if (a < 60) return 'agora mesmo'
  if (a < 3600) return rtf.format(Math.round(s / 60), 'minute')
  if (a < 86400) return rtf.format(Math.round(s / 3600), 'hour')
  return rtf.format(Math.round(s / 86400), 'day')
}

export const dataCompleta = (iso: string) => new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
