// Aplica o tema salvo ANTES da primeira pintura, para não piscar o tema errado (ADR 0013). Arquivo externo porque a CSP proíbe script inline.
try {
  var t = localStorage.getItem('nexo-tema')
  if (t === 'claro' || t === 'escuro') document.documentElement.setAttribute('data-theme', t === 'claro' ? 'light' : 'dark')
} catch { /* sem armazenamento: vale o tema do sistema */ }
