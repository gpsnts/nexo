"""Sugestão de divisão em tópicos pelo modelo de linguagem (UC05). O backend valida a cobertura e faz o plano B (blocos iguais)."""
import json
import logging
import re
from dataclasses import dataclass

from nexo_processamento.ia.linguagem import ModeloLinguagem
from nexo_processamento.json_modelo import lista_de_objetos, primeiro_json


@dataclass(frozen=True)
class Proposta:
    topicos: list[dict]  # [{"nome", "inicio", "fim"}]; vazio quando o modelo não deu uma proposta válida
    tokens_entrada: int
    tokens_saida: int


log = logging.getLogger("nexo.processamento")


def motivo_invalido(lido: list[dict], total: int, atuais: list[dict] | None = None, lacunas: bool = False) -> str:
    """Por que a proposta do modelo foi recusada (só estrutura: nada do texto do material vai para o log)."""
    if not lido:
        return "JSON ilegível, vazio, sem a forma [{nome, inicio}] ou com inícios que voltam atrás"
    esperado = 1
    for i, t in enumerate(lido, 1):
        if not _faixa_ok(t, esperado, total, lacunas):
            return f"faixas fora de ordem ou com {'' if lacunas else 'lacuna/'}sobreposição no tópico {i} (esperava início {'a partir de ' if lacunas else ''}{esperado}, veio {t['inicio']}-{t['fim']}) de {len(lido)} tópicos"
        esperado = t["fim"] + 1
    if esperado != total + 1 and not lacunas:
        return f"as faixas terminam em {esperado - 1}, mas o material tem {total} trechos"
    if atuais is not None and not congelados_preservados(lido, atuais):
        return "a proposta altera um tópico congelado"
    return "motivo desconhecido"


def _faixa_ok(t: dict, esperado: int, total: int, lacunas: bool) -> bool:
    """`esperado` é o primeiro trecho livre; com `lacunas` a faixa pode começar depois dele (trechos de fora, ADR 0042)."""
    comeco = t["inicio"] >= esperado if lacunas else t["inicio"] == esperado
    return comeco and t["inicio"] <= t["fim"] <= total and bool(str(t["nome"]).strip())


def cobre(topicos: list[dict], total: int, lacunas: bool = False) -> bool:
    """Faixas consecutivas de 1 a total, sem lacuna nem sobreposição; com `lacunas`, em ordem e sem sobreposição, podendo deixar trechos de fora."""
    esperado = 1
    for t in topicos:
        if not _faixa_ok(t, esperado, total, lacunas):
            return False
        esperado = t["fim"] + 1
    return bool(topicos) and (lacunas or esperado == total + 1)


# O modelo informa só ONDE CADA TÓPICO COMEÇA; o fim sai do começo do próximo (e o último vai até o fim do material). Pedir também o fim fazia o modelo
# pequeno errar a conta (parar em 50 de 70, recomeçar do 1, sobrepor): medido com gpt-4.1-nano, só 3 a 4 de 8 respostas eram válidas.
_FORMATO_INICIOS = (
    'Responda só com JSON: [{{"nome": "...", "inicio": 1}}, {{"nome": "...", "inicio": 8}}, ...], onde "inicio" é o número do trecho em que o tópico COMEÇA. '
    "O primeiro tópico começa no trecho 1 e os inícios são crescentes. Cada tópico vai até o trecho anterior ao início do próximo, e o último vai até o trecho {total}."
)


def _prompt(trechos: list[dict]) -> str:
    linhas = "\n".join(f'{t["ordem"]}\t{t.get("nivel") or ""}\t{t.get("titulo") or ""}\t{(t.get("inicio") or "")[:120]}' for t in trechos)
    return (
        "Divida o material abaixo em tópicos de estudo (capítulos ou seções). Cada linha é um trecho: ordem, nível do título que abre o trecho "
        "(1 = capítulo ou seção principal, 2 e 3 = subseções; vazio se o trecho não abre com título), título e início do texto.\n"
        "Siga a estrutura do documento: prefira começar um tópico em cada título de nível 1 e junte subseções ao capítulo a que pertencem; "
        "só divida de outro modo se não houver títulos.\n"
        f"Os tópicos cobrem os trechos de 1 a {len(trechos)}, em ordem.\n"
        + _FORMATO_INICIOS.format(total=len(trechos)) + "\n\n" + linhas
    )


def _ler_json(texto: str, total: int | None = None, cobrir_inicio: bool = True) -> list[dict]:
    """
    Primeiro array JSON da resposta do modelo, no formato [{nome, inicio}] (o `fim` de cada um é o começo do próximo menos 1, e o do último é [total]) ou
    [{nome, inicio, fim}] (o `fim` informado vale). Lista vazia se não houver nada aproveitável ou se os inícios voltarem atrás.
    `cobrir_inicio`: o primeiro tópico passa a começar no trecho 1; sem isso o começo que o modelo pulou (capa, sumário) fica de fora.
    """
    try:
        itens = primeiro_json(texto, "[", lista_de_objetos) or []
        tem_fim = bool(itens) and all("fim" in t for t in itens)
        lidos = [{"nome": str(t["nome"]).strip(), "inicio": int(t["inicio"]), **({"fim": int(t["fim"])} if tem_fim else {})} for t in itens]
    except (ValueError, KeyError, TypeError):
        return []
    if not lidos or tem_fim:
        return lidos
    if total is None or any(b["inicio"] < a["inicio"] for a, b in zip(lidos, lidos[1:])):
        return []
    # início repetido = o modelo partiu o mesmo trecho em dois nomes (comum em modelo pequeno): fica o primeiro
    lidos = [t for i, t in enumerate(lidos) if i == 0 or t["inicio"] > lidos[i - 1]["inicio"]]
    if cobrir_inicio:
        lidos[0]["inicio"] = 1  # na sugestão inicial o material todo entra: o primeiro tópico absorve o começo que o modelo pulou
    for i, t in enumerate(lidos):
        t["fim"] = lidos[i + 1]["inicio"] - 1 if i + 1 < len(lidos) else total
    return lidos


def sugerir(modelo: ModeloLinguagem, trechos: list[dict]) -> Proposta:
    r = modelo.gerar(_prompt(trechos))
    topicos = _ler_json(r.texto, len(trechos))
    if not cobre(topicos, len(trechos)):
        log.warning("Sugestão de tópicos recusada (%s); resposta de %d caracteres", motivo_invalido(topicos, len(trechos)), len(r.texto))
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
        f"Os tópicos cobrem os trechos de 1 a {len(trechos)}, em ordem. "
        "Tópicos marcados com congelado=true devem ser mantidos EXATAMENTE: mesmo inicio e mesmo fim (o nome pode mudar), então o tópico seguinte começa no trecho depois do fim dele. "
        "Trechos que não servem para estudar (capa, folha de rosto, sumário, referências) podem ficar de fora: comece o primeiro tópico depois deles.\n"
        + _FORMATO_INICIOS.format(total=len(trechos)).replace("O primeiro tópico começa no trecho 1 e os", "Os") + "\n\n"
        f"ATUAIS_JSON: {json.dumps(atuais, ensure_ascii=False)}\n\n" + linhas
    )


def congelados_preservados(propostos: list[dict], atuais: list[dict]) -> bool:
    faixas = {(t["inicio"], t["fim"]) for t in propostos}
    return all((a["inicio"], a["fim"]) in faixas for a in atuais if a.get("congelado"))


def refinar(modelo: ModeloLinguagem, trechos: list[dict], atuais: list[dict]) -> Proposta:
    """Reorganização pedida pelo usuário (botão "Reorganizar com IA"). Proposta fora de ordem, com sobreposição ou que mexe num tópico congelado vira vazia; trechos de fora são aceitos (ADR 0042)."""
    r = modelo.gerar(_prompt_refinar(trechos, atuais))
    topicos = _ler_json(r.texto, len(trechos), cobrir_inicio=False)
    if not cobre(topicos, len(trechos), lacunas=True) or not congelados_preservados(topicos, atuais):
        log.warning("Reorganização recusada (%s); resposta de %d caracteres", motivo_invalido(topicos, len(trechos), atuais, lacunas=True), len(r.texto))
        topicos = []
    return Proposta(topicos, r.tokens_entrada, r.tokens_saida)
