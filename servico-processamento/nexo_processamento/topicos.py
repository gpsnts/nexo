"""Sugestão de divisão em tópicos pelo modelo de linguagem (UC05). O backend valida a cobertura e faz o plano B (blocos iguais)."""
import json
import re
from dataclasses import dataclass

from nexo_processamento.ia.linguagem import ModeloLinguagem


@dataclass(frozen=True)
class Proposta:
    topicos: list[dict]  # [{"nome", "inicio", "fim"}]; vazio quando o modelo não deu uma proposta válida
    tokens_entrada: int
    tokens_saida: int


def cobre(topicos: list[dict], total: int) -> bool:
    """Faixas consecutivas de 1 a total, sem lacuna nem sobreposição."""
    esperado = 1
    for t in topicos:
        if t["inicio"] != esperado or t["fim"] < t["inicio"] or not str(t["nome"]).strip():
            return False
        esperado = t["fim"] + 1
    return esperado == total + 1 and bool(topicos)


def _prompt(trechos: list[dict]) -> str:
    linhas = "\n".join(f'{t["ordem"]}\t{t.get("nivel") or ""}\t{t.get("titulo") or ""}\t{(t.get("inicio") or "")[:120]}' for t in trechos)
    return (
        "Divida o material abaixo em tópicos de estudo (capítulos ou seções). Cada linha é um trecho: ordem, nível do título que abre o trecho "
        "(1 = capítulo ou seção principal, 2 e 3 = subseções; vazio se o trecho não abre com título), título e início do texto.\n"
        "Siga a estrutura do documento: prefira começar um tópico em cada título de nível 1 e junte subseções ao capítulo a que pertencem; "
        "só divida de outro modo se não houver títulos.\n"
        f"Os tópicos devem cobrir TODOS os trechos de 1 a {len(trechos)}, em ordem, sem lacunas nem sobreposição.\n"
        'Responda só com JSON: [{"nome": "...", "inicio": 1, "fim": 10}, ...]\n\n' + linhas
    )


def _ler_json(texto: str) -> list[dict]:
    """Primeiro array JSON da resposta do modelo, no formato [{nome, inicio, fim}]; lista vazia se não houver nada aproveitável."""
    m = re.search(r"\[.*\]", texto, re.S)
    if not m:
        return []
    try:
        return [{"nome": str(t["nome"]).strip(), "inicio": int(t["inicio"]), "fim": int(t["fim"])} for t in json.loads(m.group(0))]
    except (ValueError, KeyError, TypeError):
        return []


def sugerir(modelo: ModeloLinguagem, trechos: list[dict]) -> Proposta:
    r = modelo.gerar(_prompt(trechos))
    topicos = _ler_json(r.texto)
    if not cobre(topicos, len(trechos)):
        topicos = []
    return Proposta(topicos, r.tokens_entrada, r.tokens_saida)


def _prompt_refinar(trechos: list[dict], atuais: list[dict]) -> str:
    linhas = "\n".join(f'{t["ordem"]}\t{t.get("nivel") or ""}\t{t.get("titulo") or ""}\t{(t.get("inicio") or "")[:80]}' for t in trechos)
    return (
        "Você reorganiza a divisão de um material de estudo em tópicos. Abaixo estão a divisão ATUAL e os trechos do material "
        "(ordem, nível do título que abre o trecho, título e início do texto; nível 1 = capítulo, vazio = não abre com título).\n"
        "Melhore a divisão para estudar: agrupe capítulos ou seções que tratam do mesmo assunto, evite tópicos com um ou dois trechos "
        "e tópicos desproporcionais, divida os que misturam assuntos, e dê nomes claros (mantenha a numeração do capítulo quando houver). "
        "Não invente conteúdo e respeite a ordem do material.\n"
        f"Os tópicos devem cobrir TODOS os trechos de 1 a {len(trechos)}, em ordem, sem lacunas nem sobreposição. "
        "Tópicos marcados com congelado=true devem ser mantidos EXATAMENTE (mesmo inicio e fim; o nome pode mudar).\n"
        'Responda só com JSON: [{"nome": "...", "inicio": 1, "fim": 10}, ...]\n\n'
        f"ATUAIS_JSON: {json.dumps(atuais, ensure_ascii=False)}\n\n" + linhas
    )


def congelados_preservados(propostos: list[dict], atuais: list[dict]) -> bool:
    faixas = {(t["inicio"], t["fim"]) for t in propostos}
    return all((a["inicio"], a["fim"]) in faixas for a in atuais if a.get("congelado"))


def refinar(modelo: ModeloLinguagem, trechos: list[dict], atuais: list[dict]) -> Proposta:
    """Reorganização pedida pelo usuário (botão "Reorganizar com IA"). Proposta que não cobre o material ou mexe num tópico congelado vira vazia."""
    r = modelo.gerar(_prompt_refinar(trechos, atuais))
    topicos = _ler_json(r.texto)
    if not cobre(topicos, len(trechos)) or not congelados_preservados(topicos, atuais):
        topicos = []
    return Proposta(topicos, r.tokens_entrada, r.tokens_saida)
