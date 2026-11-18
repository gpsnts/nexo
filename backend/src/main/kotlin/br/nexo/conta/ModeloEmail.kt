package br.nexo.conta

import org.springframework.web.util.HtmlUtils

/**
 * Mensagem pronta para envio, nas duas versões que o cliente de e-mail escolhe entre si.
 *
 * @property texto versão em texto simples (a que os clientes sem HTML e os testes leem).
 * @property html versão com a identidade visual do Nexo.
 */
class MensagemEmail(val texto: String, val html: String)

/**
 * Botão de chamada para ação de um e-mail.
 *
 * @property rotulo texto do botão.
 * @property url endereço aberto ao clicar (também aparece por extenso, para quem não vê o botão).
 */
class BotaoEmail(val rotulo: String, val url: String)

/** Monta os e-mails da conta com um único modelo visual: faixa com a marca, cartão branco, botão e rodapé. */
object ModeloEmail {
    private const val INDIGO = "#4338ca"

    /**
     * Monta a mensagem. Todo texto recebido é escapado no HTML, pois o nome da pessoa vem do cadastro.
     *
     * @param titulo título em destaque no cartão.
     * @param nome nome de quem recebe, usado na saudação.
     * @param paragrafos parágrafos do corpo, em ordem.
     * @param botao chamada para ação; nulo se a mensagem for só informativa.
     * @param nota aviso final em letra menor (por exemplo, "se não foi você, ignore"); nulo para omitir.
     * @return a mesma mensagem em texto simples e em HTML.
     */
    fun montar(titulo: String, nome: String, paragrafos: List<String>, botao: BotaoEmail?, nota: String?): MensagemEmail {
        val texto = buildString {
            append("Olá, $nome.\n\n").append(paragrafos.joinToString("\n\n"))
            if (botao != null) append("\n\n${botao.rotulo}:\n${botao.url}")
            if (nota != null) append("\n\n$nota")
            append("\n\n— Plataforma Nexo (mensagem automática, não responda)")
        }
        val corpo = paragrafos.joinToString("") { "<p style=\"margin:0 0 16px;font-size:16px;line-height:1.6;color:#1f2937\">${esc(it)}</p>" }
        val chamada = botao?.let {
            """<table role="presentation" cellspacing="0" cellpadding="0" border="0" style="margin:24px 0"><tr><td style="border-radius:10px;background:$INDIGO">
              <a href="${esc(it.url)}" style="display:inline-block;padding:14px 28px;font-size:16px;font-weight:700;color:#ffffff;text-decoration:none;border-radius:10px">${esc(it.rotulo)}</a>
              </td></tr></table>
              <p style="margin:0 0 16px;font-size:13px;line-height:1.5;color:#6b7280">Se o botão não funcionar, copie e cole este endereço no navegador:<br>
              <a href="${esc(it.url)}" style="color:$INDIGO;word-break:break-all">${esc(it.url)}</a></p>"""
        } ?: ""
        val aviso = nota?.let { "<p style=\"margin:8px 0 0;padding-top:16px;border-top:1px solid #e5e7eb;font-size:14px;line-height:1.6;color:#4b5563\">${esc(it)}</p>" } ?: ""
        val html = """<!DOCTYPE html>
<html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><title>${esc(titulo)}</title></head>
<body style="margin:0;padding:0;background:#f4f5fb;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif">
<div style="display:none;max-height:0;overflow:hidden;opacity:0">${esc(paragrafos.firstOrNull() ?: titulo)}</div>
<table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background:#f4f5fb"><tr><td align="center" style="padding:32px 16px">
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="max-width:560px">
    <tr><td style="background:$INDIGO;background-image:linear-gradient(135deg,#4338ca,#0d9488);border-radius:16px 16px 0 0;padding:24px 32px">
      <span style="font-size:28px;font-weight:800;letter-spacing:-0.5px;color:#ffffff">nexo</span>
      <span style="display:block;margin-top:2px;font-size:13px;color:#e0e7ff">Estude com o que é seu</span>
    </td></tr>
    <tr><td style="background:#ffffff;border-radius:0 0 16px 16px;padding:32px">
      <h1 style="margin:0 0 20px;font-size:22px;line-height:1.3;color:#111827">${esc(titulo)}</h1>
      <p style="margin:0 0 16px;font-size:16px;line-height:1.6;color:#1f2937">Olá, ${esc(nome)}.</p>
      $corpo
      $chamada
      $aviso
    </td></tr>
    <tr><td align="center" style="padding:20px 16px;font-size:12px;line-height:1.5;color:#9ca3af">Plataforma Nexo · Mensagem automática, não responda este e-mail.</td></tr>
  </table>
</td></tr></table>
</body></html>"""
        return MensagemEmail(texto, html)
    }

    /**
     * Escapa texto para HTML (corpo e atributos).
     * @param s texto original.
     * @return texto seguro para inserir no HTML.
     */
    private fun esc(s: String): String = HtmlUtils.htmlEscape(s)
}
