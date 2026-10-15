package br.nexo.conta

import br.nexo.NexoProperties
import br.nexo.conta.TokenContaService.Companion.CONFIRMACAO
import br.nexo.conta.TokenContaService.Companion.REDEFINICAO
import br.nexo.seguranca.Auditoria
import br.nexo.seguranca.UsuarioAtual
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

data class PerfilDto(val nome: String, val email: String)
data class SessaoDto(val id: UUID, val dispositivo: String, val ip: String, val inicio: Instant, val ultimaAtividade: Instant, val atual: Boolean)

@Service
class ContaService(
    private val usuarios: UsuarioRepository,
    private val sessoes: SessaoRepository,
    private val tokens: TokenContaService,
    private val auth: AuthService,
    private val encoder: PasswordEncoder,
    private val email: EnviadorEmail,
    private val segundoPlano: SegundoPlano,
    private val tx: TransactionTemplate,
    private val props: NexoProperties,
    private val relogio: Clock,
    private val auditoria: Auditoria,
    private val arquivos: br.nexo.material.RemocaoArquivos,
) {
    // ---- recuperação de senha (sem login) ----

    /** Sempre "funciona" para quem chama: não revela se o e-mail existe nem se o limite por hora foi atingido. */
    fun esqueciSenha(emailInformado: String) {
        val u = usuarios.findByEmail(normalizarEmail(emailInformado)) ?: return
        // Em segundo plano para a resposta ter o mesmo custo com e sem conta (e um SMTP lento não travar a requisição).
        segundoPlano.executar {
            if (tokens.pedidosNaUltimaHora(u.id, REDEFINICAO) < props.conta.pedidosPorHora) {
                val bruto = tokens.criar(u.id, REDEFINICAO, null, props.conta.validadeRedefinicao)
                email.enviar(u.email, "Redefinir sua senha no Nexo",
                    "Olá, ${u.nome}.\n\nPara criar uma nova senha, abra o link (vale por ${props.conta.validadeRedefinicao.toMinutes()} minutos e só pode ser usado uma vez):\n" +
                        "${props.conta.urlFrontend}/redefinir-senha?token=$bruto\n\nSe não foi você, ignore este e-mail.")
            }
        }
    }

    fun redefinirSenha(bruto: String, novaSenha: String) {
        val hash = encoder.encode(novaSenha)!!
        val u = tx.execute {
            val t = tokens.validar(bruto, REDEFINICAO)
            val u = usuarios.findById(t.usuarioId).orElseThrow { TokenContaService.linkInvalido() }
            PoliticaSenha.exigir(novaSenha, u.email) // falhar aqui desfaz a transação: o link continua valendo
            u.senhaHash = hash
            u.falhasLogin = 0
            u.bloqueadoAte = null
            // Abrir o link enviado ao e-mail prova que a pessoa controla o endereço: confirma o e-mail (e libera quem foi vítima de cadastro alheio).
            if (u.emailVerificadoEm == null) u.emailVerificadoEm = relogio.instant()
            t.usadoEm = relogio.instant()
            auth.revogarTodas(u.id)
            u
        }
        auditoria.registrar("senha_redefinida", u.id)
        avisarSenhaAlterada(u)
    }

    fun confirmarEmail(bruto: String) {
        val (antigo, nome, novo) = try {
            tx.execute {
                val t = tokens.validar(bruto, CONFIRMACAO)
                val novo = t.novoEmail!!
                if (usuarios.findByEmail(novo) != null) throw emailEmUso()
                val u = usuarios.findById(t.usuarioId).orElseThrow { TokenContaService.linkInvalido() }
                val antigo = u.email
                u.email = novo
                u.emailVerificadoEm = relogio.instant()
                t.usadoEm = relogio.instant()
                auditoria.registrar("email_alterado", u.id)
                Triple(antigo, u.nome, novo)
            }
        } catch (e: DataIntegrityViolationException) {
            throw emailEmUso()
        }
        // Aviso ao endereço ANTIGO: se não foi o dono, é a chance de perceber.
        enviar(antigo, "O e-mail da sua conta Nexo foi alterado",
            "Olá, $nome.\n\nO e-mail de acesso da sua conta foi alterado para ${mascarar(novo)}.\n\n" +
                "Se não foi você, recupere o acesso agora em ${props.conta.urlFrontend} (\"Esqueci minha senha\") e troque a senha.")
    }

    // ---- perfil (autenticado; tudo sai do JWT, nenhuma rota recebe id de usuário) ----

    fun perfil() = atual().let { PerfilDto(it.nome, it.email) }

    fun alterarNome(nome: String) = tx.execute { usuarios.findById(atual().id).get().also { it.nome = nome.trim() } }.let { PerfilDto(it.nome, it.email) }

    fun solicitarTrocaEmail(senhaAtual: String, novoEmail: String) {
        val u = atual()
        exigirSenha(u, senhaAtual)
        val novo = normalizarEmail(novoEmail)
        if (novo == u.email) throw ErroNegocio(HttpStatus.BAD_REQUEST, "O novo e-mail é igual ao atual.", "email_igual")
        if (usuarios.findByEmail(novo) != null) throw emailEmUso()
        val bruto = tx.execute {
            tokens.invalidarPendentes(u.id, CONFIRMACAO)
            tokens.criar(u.id, CONFIRMACAO, novo, props.conta.validadeConfirmacaoEmail)
        }
        enviar(novo, "Confirme seu novo e-mail no Nexo",
            "Olá, ${u.nome}.\n\nPara confirmar que este é o seu novo e-mail, abra o link (vale por ${props.conta.validadeConfirmacaoEmail.toHours()} horas):\n" +
                "${props.conta.urlFrontend}/confirmar-email?token=$bruto\n\nO e-mail de acesso só muda depois da confirmação.")
    }

    /** Troca a senha, derruba as outras sessões e devolve uma sessão nova para quem trocou. */
    fun alterarSenha(senhaAtual: String, novaSenha: String, origem: Origem): Autenticacao {
        val u = atual()
        exigirSenha(u, senhaAtual)
        PoliticaSenha.exigir(novaSenha, u.email)
        val hash = encoder.encode(novaSenha)!!
        tx.executeWithoutResult {
            usuarios.findById(u.id).get().senhaHash = hash
            auth.revogarTodas(u.id)
        }
        auditoria.registrar("senha_alterada", u.id)
        avisarSenhaAlterada(u)
        return auth.abrirSessao(u, origem)
    }

    /** Regra 7: DELETE FROM usuario remove tudo em cascata. */
    fun excluirConta(senha: String, confirmacao: String) {
        if (confirmacao != "EXCLUIR") throw ErroNegocio(HttpStatus.BAD_REQUEST, "Digite EXCLUIR para confirmar.", "confirmacao_invalida")
        val u = atual()
        exigirSenha(u, senha)
        tx.executeWithoutResult {
            usuarios.deleteById(u.id)
            arquivos.depoisDoCommit(pastas = listOf(u.id.toString()))  // regra 7: os arquivos da conta saem só depois do commit
        }
        auditoria.registrar("conta_excluida", u.id)
    }

    // ---- sessões ativas (ADR 0015) ----

    fun sessoesAtivas(): List<SessaoDto> {
        val atualId = UsuarioAtual.sessaoId()
        return sessoes.ativas(atual().id, relogio.instant()).map {
            SessaoDto(it.familiaId, it.dispositivo ?: "Dispositivo desconhecido", it.ip ?: "", it.inicioEm, it.criadoEm ?: it.inicioEm, it.familiaId == atualId)
        }
    }

    /** Encerra uma sessão do próprio usuário (inclusive a atual). Id de outra pessoa ou inexistente: 404, sem distinguir. */
    fun encerrarSessao(familiaId: UUID) {
        if (sessoes.revogarFamiliaDoUsuario(atual().id, familiaId, relogio.instant()) == 0)
            throw ErroNegocio(HttpStatus.NOT_FOUND, "Sessão não encontrada.", "nao_encontrado")
        auditoria.registrar("sessao_encerrada", UsuarioAtual.id(), "familia=$familiaId")
    }

    /** "Sair de todos os outros dispositivos". */
    fun encerrarOutrasSessoes(): Int {
        val manter = UsuarioAtual.sessaoId() ?: throw ErroNegocio(HttpStatus.UNAUTHORIZED, "Sessão inválida.", "sessao_invalida")
        val n = sessoes.revogarOutras(atual().id, manter, relogio.instant())
        auditoria.registrar("sessoes_encerradas", UsuarioAtual.id(), "quantidade=$n")
        return n
    }

    // ---- apoio ----

    private fun atual(): Usuario =
        UsuarioAtual.id()?.let { usuarios.findById(it).orElse(null) } ?: throw ErroNegocio(HttpStatus.UNAUTHORIZED, "Sessão inválida.", "sessao_invalida")

    // Reautenticação passa pelo mesmo bloqueio do login: um token de acesso roubado não serve para adivinhar a senha.
    private fun exigirSenha(u: Usuario, senha: String) {
        if (!auth.conferirSenha(u, senha)) throw ErroNegocio(HttpStatus.FORBIDDEN, "Senha atual incorreta.", "senha_incorreta")
    }

    private fun enviar(para: String, assunto: String, texto: String) = segundoPlano.executar { email.enviar(para, assunto, texto) }

    private fun avisarSenhaAlterada(u: Usuario) {
        val quando = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm").withZone(props.fuso).format(relogio.instant())
        enviar(u.email, "Sua senha do Nexo foi alterada",
            "Olá, ${u.nome}.\n\nA senha da sua conta foi alterada em $quando.\n\n" +
                "Se não foi você, recupere o acesso agora em ${props.conta.urlFrontend} (\"Esqueci minha senha\").")
    }

    // m***@dominio.com: o aviso mostra para onde foi sem expor o endereço inteiro.
    private fun mascarar(email: String) = email.substringBefore("@").take(1) + "***@" + email.substringAfter("@")

    private fun emailEmUso() = ErroNegocio(HttpStatus.CONFLICT, "Este e-mail já está em uso por outra conta.", "email_em_uso")
}
