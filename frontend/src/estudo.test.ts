import { expect, test } from 'vitest'
import { rotuloPaginas, formatarTamanho, ajustar, criarNaLacuna, dividir, excluir, lacunas, erroArquivo, errosDeNome, faixasDe, igual, motivoDe, umSo, unir, type Faixa, type Topico } from './estudo'

const topicos: Topico[] = [
  { id: 'a', assuntoId: 'g', nome: 'A', ordem: 1, inicio: 1, fim: 4, previa: null, temConteudo: false },
  { id: 'b', assuntoId: 'g', nome: 'B', ordem: 2, inicio: 5, fim: 10, previa: null, temConteudo: false },
]
const f = () => faixasDe(topicos)
const cobre = (x: Faixa[], n: number) => x.every((t, i) => t.inicio === (i === 0 ? 1 : x[i - 1].fim + 1) && t.fim >= t.inicio) && x[x.length - 1].fim === n

test('unir mantém o primeiro tópico e cobre tudo', () => {
  const r = unir(f(), 0)
  expect(r).toHaveLength(1)
  expect(r[0]).toMatchObject({ id: 'a', nome: 'A', inicio: 1, fim: 10 })
  expect(unir(f(), 1)).toEqual(f()) // último não tem próximo
})

test('dividir cria um tópico novo sem id e continua cobrindo o material', () => {
  const r = dividir(f(), 1, 7)
  expect(r.map((x) => [x.inicio, x.fim])).toEqual([[1, 4], [5, 7], [8, 10]])
  expect(r[2].id).toBeNull()
  expect(cobre(r, 10)).toBe(true)
  expect(dividir(f(), 1, 10)).toEqual(f()) // dividir depois do último trecho não faz sentido
  expect(dividir(f(), 0, 0)).toEqual(f())
})

test('ajustar move a fronteira dos dois vizinhos juntos', () => {
  const r = ajustar(f(), 0, 6)
  expect(r.map((x) => [x.inicio, x.fim])).toEqual([[1, 6], [7, 10]])
  expect(ajustar(f(), 0, 10)).toEqual(f()) // esvaziaria o próximo
  expect(ajustar(f(), 0, 0)).toEqual(f())
  expect(ajustar(f(), 1, 5)).toEqual(f()) // o último não tem fronteira à direita
})

test('um tópico só ocupa todo o material', () => {
  expect(umSo(f(), 'material', 10)).toMatchObject([{ id: 'a', inicio: 1, fim: 10 }])
})

test('cortar: excluir deixa trechos de fora, lacunas os lista e criarNaLacuna os traz de volta', () => {
  const r = excluir(dividir(f(), 0, 1), 0)             // A divide em 1 | 2-4 e o primeiro sai: o trecho 1 fica de fora
  expect(r.map((x) => [x.inicio, x.fim])).toEqual([[2, 4], [5, 10]])
  expect(lacunas(r, 10)).toEqual([[1, 1]])
  expect(lacunas(f(), 10)).toEqual([])
  expect(lacunas([{ ...f()[0], fim: 3 }, { ...f()[1], inicio: 6, fim: 8 }], 10)).toEqual([[4, 5], [9, 10]])
  expect(excluir(excluir(f(), 0), 0)).toHaveLength(1)   // sempre sobra um tópico
  const volta = criarNaLacuna(r, 1, 1)
  expect(volta.map((x) => [x.inicio, x.fim])).toEqual([[1, 1], [2, 4], [5, 10]])
  expect(volta[0].id).toBeNull()
  expect(lacunas(volta, 10)).toEqual([])
})

test('com trechos de fora: não une, ajustar só mexe no próprio fim, e tópico com conteúdo não sai', () => {
  const g = excluir(dividir(f(), 1, 7), 1)             // A 1-4 | (5-7 fora) | 8-10
  expect(g.map((x) => [x.inicio, x.fim])).toEqual([[1, 4], [8, 10]])
  expect(unir(g, 0)).toEqual(g)                         // unir engoliria os trechos de fora
  expect(ajustar(g, 0, 6).map((x) => [x.inicio, x.fim])).toEqual([[1, 6], [8, 10]])
  expect(ajustar(g, 0, 8)).toEqual(g)                   // invadiria o próximo
  const t = faixasDe(topicos.map((x) => (x.id === 'a' ? { ...x, temConteudo: true } : x)))
  expect(excluir(t, 0)).toEqual(t)
  expect(umSo(g, 'm', 10).map((x) => [x.inicio, x.fim])).toEqual([[1, 10]])
})

test('nome vazio ou longo demais é erro; igual compara conteúdo', () => {
  const r = f(); r[1] = { ...r[1], nome: '   ' }; r[0] = { ...r[0], nome: 'x'.repeat(201) }
  expect(Object.keys(errosDeNome(r))).toEqual(['0', '1'])
  expect(igual(f(), f())).toBe(true)
  expect(igual(f(), unir(f(), 0))).toBe(false)
})

test('arquivo: formato, vazio e tamanho', () => {
  const arq = (nome: string, tamanho: number) => Object.defineProperty(new File(['x'], nome), 'size', { value: tamanho })
  expect(erroArquivo(null)).toContain('Escolha')
  expect(erroArquivo(arq('a.exe', 10))).toContain('Formato não aceito')
  expect(erroArquivo(arq('sem-extensao', 10))).toContain('Formato não aceito')
  expect(erroArquivo(arq('a.pdf', 0))).toContain('vazio')
  expect(erroArquivo(arq('a.PDF', 20 * 1024 * 1024 + 1))).toContain('20 MB')
  expect(erroArquivo(arq('a.docx', 20 * 1024 * 1024))).toBe('')
  expect(erroArquivo(arq('a.txt', 1))).toBe('')
})

test('tópico com conteúdo não une, não divide e não move fronteira, mas pode ser renomeado', () => {
  const t = faixasDe(topicos.map((x) => (x.id === 'a' ? { ...x, temConteudo: true } : x)))
  expect(t[0].bloqueado).toBe(true)
  expect(unir(t, 0)).toEqual(t)
  expect(dividir(t, 0, 2)).toEqual(t)
  expect(ajustar(t, 0, 6)).toEqual(t) // a fronteira também mudaria o tópico com conteúdo
  expect(umSo(t, 'm', 10)).toEqual(t)
  expect(dividir(t, 1, 7)).toHaveLength(3) // o tópico livre ao lado continua editável
  expect(dividir(t, 1, 7)[2].bloqueado).toBe(false)
})

test('motivo desconhecido tem mensagem genérica', () => {
  expect(motivoDe('sem_texto')).toContain('texto')
  expect(motivoDe('xyz')).toContain('Não foi possível')
  expect(motivoDe(null)).toContain('Não foi possível')
})

test('rótulo de páginas: faixa, página única e formatos sem páginas', () => {
  const pags = [1, 1, 2, 2, 3]
  expect(rotuloPaginas(pags, 1, 5)).toBe('págs. 1 a 3')
  expect(rotuloPaginas(pags, 1, 2)).toBe('pág. 1')
  expect(rotuloPaginas([null, null], 1, 2)).toBe('')
  expect(rotuloPaginas([], 1, 2)).toBe('')
})

test('tamanho legível em KB e MB', () => {
  expect(formatarTamanho(10)).toBe('1 KB')
  expect(formatarTamanho(2048)).toBe('2 KB')
  expect(formatarTamanho(5 * 1024 * 1024 + 300 * 1024)).toBe('5,3 MB')
})
